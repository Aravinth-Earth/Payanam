//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.DayJournalEntryEntity
import io.payanam.database.entity.DayJournalResponseEntity
import io.payanam.domain.model.DayJournalEntry
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DayJournalResponseInput
import io.payanam.domain.model.JournalPromptScope
import io.payanam.domain.repository.JournalRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * JDBC implementation of [JournalRepository] over the encrypted database — the day's journal entry and the
 * answers to its prompts.
 *
 * Statements are copied from `JournalDao` and the behaviour mirrors `JournalRepositoryImpl`, including the
 * parts that are easy to lose:
 *
 *  - **The dimension match is null-safe AND binds its value twice.** The statement reads
 *    `dimensionKey = ? OR (dimensionKey IS NULL AND ? IS NULL)`, so a response with no dimension matches only
 *    when the lookup's dimension is also null. Room binds one named parameter to both occurrences; a
 *    positional bind must pass the value twice or the second placeholder is SQL NULL and the "no dimension"
 *    branch silently stops matching.
 *  - **`dimension_id` is a column the domain model does not carry.** It survives an edit because the update
 *    copies the row it read; a port that rebuilt the entity from the input would erase it.
 *  - **A journal write does NOT mark the day lens-dirty.** The Android implementation marks dirty in every
 *    other content repository and does not here, so the port does not either: a desktop that started marking
 *    would invalidate lens snapshots that Android leaves alone, and the two platforms would recompute on
 *    different schedules. Whether Android *should* mark is a separate question, recorded as a finding.
 *  - **`getOrCreateEntry` persists a blank entry.** Reading a day's journal is what creates its row, on both
 *    platforms, so an entry appearing is not evidence that anything was written to it.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
class JournalRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : JournalRepository {
    private val logger = AppLogger.current
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** Returns the entry for [date], creating and persisting a blank one when none exists. */
    override suspend fun getOrCreateEntry(date: LocalDate): DayJournalEntry =
        JdbcFlows.onIo {
            val dateStr = date.format(dateFormatter)
            val existing = readEntryForDate(dateStr)
            if (existing != null) return@onIo existing.toDomain()
            val now = LocalDateTime.now().format(dateTimeFormatter)
            val newEntry =
                DayJournalEntryEntity(
                    id = UUID.randomUUID().toString(),
                    entryDate = dateStr,
                    createdAt = now,
                    updatedAt = now,
                )
            JdbcQueries.write(session, INSERT_ENTRY_SQL, newEntry.toBindings())
            logger.i(
                "JournalRepositoryJvm.getOrCreateEntry",
                "Created new journal entry",
                mapOf("date" to dateStr),
            )
            newEntry.toDomain()
        }

    /** Emits the entry for [date], or null. */
    override fun observeEntry(date: LocalDate): Flow<DayJournalEntry?> {
        val dateStr = date.format(dateFormatter)
        logger.d(
            "JournalRepositoryJvm.observeEntry",
            "Subscribing to journal entry",
            mapOf("date" to dateStr),
        )
        return JdbcFlows
            .observe(pollIntervalMs) { readEntryForDate(dateStr) }
            .map { entity -> entity?.toDomain() }
    }

    /** Emits every prompt response for [entryId]. */
    override fun getResponses(entryId: String): Flow<List<DayJournalResponse>> {
        logger.d(
            "JournalRepositoryJvm.getResponses",
            "Subscribing to journal responses",
            mapOf("entryId" to entryId),
        )
        return JdbcFlows
            .observe(pollIntervalMs) { readResponses(RESPONSES_FOR_ENTRY_SQL, arrayOf(entryId)) }
            .map { entities -> entities.map { it.toDomain() } }
    }

    /**
     * Upserts one prompt response from [input] for [entryId], matching on scope + dimension + prompt key.
     *
     * The match is what makes re-answering a prompt an edit rather than a duplicate row, so the lookup and
     * the write are one transaction.
     */
    override suspend fun saveResponse(
        entryId: String,
        input: DayJournalResponseInput,
    ): DayJournalResponse =
        JdbcFlows.onIo {
            val now = LocalDateTime.now().format(dateTimeFormatter)
            val scopeStr = input.scope.name
            val entity =
                JdbcQueries.transaction(session) {
                    val existing =
                        readResponse(entryId, scopeStr, input.dimensionKey, input.promptKey)
                    if (existing != null) {
                        val updated =
                            existing.copy(responseText = input.responseText, updatedAt = now)
                        JdbcQueries.write(session, UPDATE_RESPONSE_SQL, updated.toBindings() + updated.id)
                        updated
                    } else {
                        val created =
                            DayJournalResponseEntity(
                                id = UUID.randomUUID().toString(),
                                entryId = entryId,
                                scope = scopeStr,
                                dimensionKey = input.dimensionKey,
                                promptKey = input.promptKey,
                                responseText = input.responseText,
                                createdAt = now,
                                updatedAt = now,
                            )
                        JdbcQueries.write(session, INSERT_RESPONSE_SQL, created.toBindings())
                        created
                    }
                }
            logger.i(
                "JournalRepositoryJvm.saveResponse",
                "Saved journal response",
                mapOf(
                    "entryId" to entryId,
                    "scope" to scopeStr,
                    "promptKey" to input.promptKey,
                ),
            )
            entity.toDomain()
        }

    /** Returns the single response matching [entryId], [scope], [dimensionKey] and [promptKey], or null. */
    override suspend fun getResponse(
        entryId: String,
        scope: JournalPromptScope,
        dimensionKey: String?,
        promptKey: String,
    ): DayJournalResponse? =
        JdbcFlows.onIo {
            val response = readResponse(entryId, scope.name, dimensionKey, promptKey)?.toDomain()
            logger.d(
                "JournalRepositoryJvm.getResponse",
                "Fetched journal response",
                mapOf(
                    "entryId" to entryId,
                    "scope" to scope.name,
                    "promptKey" to promptKey,
                    "found" to (response != null),
                ),
            )
            response
        }

    /** Returns the entry for the ISO date string [dateString], or null. */
    override suspend fun getEntryByDate(dateString: String): DayJournalEntry? =
        JdbcFlows.onIo {
            val entry = readEntryForDate(dateString)?.toDomain()
            logger.d(
                "JournalRepositoryJvm.getEntryByDate",
                "Fetched journal entry by date",
                mapOf("date" to dateString, "found" to (entry != null)),
            )
            entry
        }

    /** Persists a domain [DayJournalEntry] (insert-only; the caller owns the id and timestamps). */
    override suspend fun insertEntry(entry: DayJournalEntry) {
        JdbcFlows.onIo {
            val entity =
                DayJournalEntryEntity(
                    id = entry.id,
                    entryDate = entry.entryDate,
                    createdAt = entry.createdAt,
                    updatedAt = entry.updatedAt,
                )
            JdbcQueries.write(session, INSERT_ENTRY_SQL, entity.toBindings())
            logger.d("JournalRepositoryJvm.insertEntry", "Entry inserted", mapOf("id" to entry.id))
        }
    }

    /** Returns every response for [entryId] as a one-shot list. */
    override suspend fun getResponsesByEntryId(entryId: String): List<DayJournalResponse> =
        JdbcFlows.onIo {
            val responses = readResponses(RESPONSES_FOR_ENTRY_SQL, arrayOf(entryId)).map { it.toDomain() }
            logger.d(
                "JournalRepositoryJvm.getResponsesByEntryId",
                "Fetched responses for entry",
                mapOf("entryId" to entryId, "count" to responses.size),
            )
            responses
        }

    /**
     * Upserts a whole domain [DayJournalResponse], matching on entry + scope + dimension + prompt key.
     *
     * An insert uses the CALLER's id, unlike [saveResponse] which generates one: this is the import path,
     * and an imported response has to keep the id it was exported with.
     */
    override suspend fun upsertResponse(response: DayJournalResponse) {
        JdbcFlows.onIo {
            val now = LocalDateTime.now().format(dateTimeFormatter)
            JdbcQueries.transaction(session) {
                val existing =
                    readResponse(response.entryId, response.scope, response.dimensionKey, response.promptKey)
                if (existing != null) {
                    val updated = existing.copy(responseText = response.responseText, updatedAt = now)
                    JdbcQueries.write(session, UPDATE_RESPONSE_SQL, updated.toBindings() + updated.id)
                    logger.d(
                        "JournalRepositoryJvm.upsertResponse",
                        "Response updated",
                        mapOf("id" to response.id),
                    )
                } else {
                    val entity =
                        DayJournalResponseEntity(
                            id = response.id,
                            entryId = response.entryId,
                            scope = response.scope,
                            dimensionKey = response.dimensionKey,
                            promptKey = response.promptKey,
                            responseText = response.responseText,
                            createdAt = now,
                            updatedAt = now,
                        )
                    JdbcQueries.write(session, INSERT_RESPONSE_SQL, entity.toBindings())
                    logger.d(
                        "JournalRepositoryJvm.upsertResponse",
                        "Response inserted",
                        mapOf("id" to response.id),
                    )
                }
            }
        }
    }

    /** Emits every journal entry. */
    override fun getAllJournalEntries(): Flow<List<DayJournalEntry>> {
        logger.d("JournalRepositoryJvm.getAllJournalEntries", "Subscribing to all journal entries")
        return JdbcFlows
            .observe(pollIntervalMs) { readEntries() }
            .map { entities -> entities.map { it.toDomain() } }
    }

    /**
     * Emits the number of stored prompt responses.
     *
     * The subscription observes the ROWS and counts downstream rather than observing a count. Room re-emits
     * whenever the table it read changes; observing `SELECT COUNT(*)` would compare two equal integers and
     * suppress the emission, so deleting one answer while writing another would tell the UI nothing.
     */
    override fun getTotalResponseCount(): Flow<Int> =
        JdbcFlows
            .observe(pollIntervalMs) { readResponses(ALL_RESPONSES_SQL) }
            .map { responses -> responses.size }

    private fun readEntryForDate(dateStr: String): DayJournalEntryEntity? =
        JdbcQueries.read(session, ENTRY_FOR_DATE_SQL, arrayOf(dateStr)) { resultSet ->
            if (resultSet.next()) resultSet.readEntry() else null
        }

    private fun readEntries(): List<DayJournalEntryEntity> =
        JdbcQueries.read(session, ALL_ENTRIES_SQL, emptyArray<String?>()) { resultSet ->
            buildList {
                while (resultSet.next()) add(resultSet.readEntry())
            }
        }

    private fun readResponses(
        sql: String,
        parameters: Array<out String?> = emptyArray<String?>(),
    ): List<DayJournalResponseEntity> =
        JdbcQueries.read(session, sql, parameters) { resultSet ->
            buildList {
                while (resultSet.next()) add(resultSet.readResponse())
            }
        }

    /**
     * One response by its natural key. The dimension is bound TWICE because the statement names the
     * placeholder twice.
     */
    private fun readResponse(
        entryId: String,
        scope: String,
        dimensionKey: String?,
        promptKey: String,
    ): DayJournalResponseEntity? =
        JdbcQueries.read(
            session,
            RESPONSE_BY_KEY_SQL,
            arrayOf(entryId, scope, dimensionKey, dimensionKey, promptKey),
        ) { resultSet ->
            if (resultSet.next()) resultSet.readResponse() else null
        }

    private fun ResultSet.readEntry(): DayJournalEntryEntity =
        DayJournalEntryEntity(
            id = getString("id"),
            entryDate = getString("entryDate"),
            createdAt = getString("createdAt"),
            updatedAt = getString("updatedAt"),
        )

    private fun ResultSet.readResponse(): DayJournalResponseEntity =
        DayJournalResponseEntity(
            id = getString("id"),
            entryId = getString("entryId"),
            scope = getString("scope"),
            dimensionKey = getString("dimensionKey"),
            // Read even though no domain model exposes it: the update copies what it read, and dropping it
            // here would erase the column on the next edit.
            dimensionId = getString("dimension_id"),
            promptKey = getString("promptKey"),
            responseText = getString("responseText"),
            createdAt = getString("createdAt"),
            updatedAt = getString("updatedAt"),
        )

    private fun DayJournalEntryEntity.toBindings(): Array<String?> = arrayOf(id, entryDate, createdAt, updatedAt)

    /** The 9 response columns, in the order both write statements bind them. */
    private fun DayJournalResponseEntity.toBindings(): Array<String?> =
        arrayOf(
            id,
            entryId,
            scope,
            dimensionKey,
            dimensionId,
            promptKey,
            responseText,
            createdAt,
            updatedAt,
        )

    private fun DayJournalEntryEntity.toDomain(): DayJournalEntry =
        DayJournalEntry(
            id = id,
            entryDate = entryDate,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun DayJournalResponseEntity.toDomain(): DayJournalResponse =
        DayJournalResponse(
            id = id,
            entryId = entryId,
            scope = scope,
            dimensionKey = dimensionKey,
            promptKey = promptKey,
            // A null response reads back as the empty string: the UI edits and compares text, and null
            // would need a second "unanswered" state at every call site.
            responseText = responseText ?: "",
        )

    private companion object {
        /** Verbatim from `JournalDao.getEntryForDate` / `observeEntryForDate`. */
        const val ENTRY_FOR_DATE_SQL = "SELECT * FROM day_journal_entries WHERE entryDate = ?"

        /** Verbatim from `JournalDao.getAllEntries`. */
        const val ALL_ENTRIES_SQL = "SELECT * FROM day_journal_entries"

        /** Verbatim from `JournalDao.getAllResponses`. */
        const val ALL_RESPONSES_SQL = "SELECT * FROM day_journal_responses"

        /** Verbatim from `JournalDao.getResponsesForEntry` / `getResponsesForEntryOnce`. */
        const val RESPONSES_FOR_ENTRY_SQL =
            "SELECT * FROM day_journal_responses WHERE entryId = ?"

        /**
         * Verbatim from `JournalDao.getResponse`. The null-safe dimension match names its placeholder twice,
         * and the caller passes the value twice.
         */
        const val RESPONSE_BY_KEY_SQL = """
            SELECT * FROM day_journal_responses
            WHERE entryId = ? AND scope = ?
            AND (dimensionKey = ? OR (dimensionKey IS NULL AND ? IS NULL))
            AND promptKey = ?
        """

        /**
         * `JournalDao.insertEntry` is `@Insert(REPLACE)` — WITHOUT the REPLACE.
         *
         * `day_journal_entries` is the parent of `day_journal_responses` (`ON DELETE CASCADE`), so a
         * REPLACE that collided on id deleted the entry's responses — silently, since SQLite defaults
         * `PRAGMA foreign_keys` to OFF. `ON CONFLICT(id) DO UPDATE` writes the same row without
         * deleting it, so the responses survive. See `TaskRepositoryJvm` for the same fix.
         */
        const val INSERT_ENTRY_SQL = """
            INSERT INTO day_journal_entries (id, entryDate, createdAt, updatedAt)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                entryDate = excluded.entryDate,
                createdAt = excluded.createdAt,
                updatedAt = excluded.updatedAt
        """

        /** `JournalDao.insertResponse` is `@Insert(REPLACE)`. */
        const val INSERT_RESPONSE_SQL = """
            INSERT OR REPLACE INTO day_journal_responses
            (id, entryId, scope, dimensionKey, dimension_id, promptKey, responseText, createdAt, updatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        /** Room's `@Update` writes every column of the row; this is that statement made explicit. */
        const val UPDATE_RESPONSE_SQL = """
            UPDATE OR ABORT day_journal_responses SET
                id = ?, entryId = ?, scope = ?, dimensionKey = ?, dimension_id = ?, promptKey = ?,
                responseText = ?, createdAt = ?, updatedAt = ?
            WHERE id = ?
        """
    }
}
