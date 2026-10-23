//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.TimeEntryEntity
import io.payanam.database.mapper.TimeEntryMapper.toDomain
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.domain.time.TimeEntryDimensionRule
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * JDBC implementation of [TimeEntryRepository] over the encrypted database — the desktop's half of tracked
 * time.
 *
 * Statements are copied from `TimeEntryDao` and the behaviour mirrors `TimeEntryRepositoryImpl`, including
 * the parts that are easy to lose in a port:
 *
 *  - **Starting an entry stops the running one first.** There is a single running entry by design; the
 *    `endedAt IS NULL LIMIT 1` reads depend on that invariant holding.
 *  - **Journaling the day is not optional.** Every mutation marks the affected day lens-dirty for module
 *    `time_entry`, and an UPDATE marks **both** the day the entry used to sit on and the day it now sits
 *    on. Marking only the new day leaves the previous day's lens serving a snapshot that no longer matches
 *    the table.
 *  - **`stopActiveTimeEntry()` records a focus rating of 0.0, not null.** The plain stop path has always
 *    written `0.0`, while the rated path clamps to `0.0..1.0` and trims a blank note away. Both are
 *    preserved: the stored `0.0` is what the focus statistics have always seen.
 *  - **An unparseable day key is skipped, not fatal.** Stopping reads the entry's own `dayKey`; a malformed
 *    one must not fail the stop that already happened.
 *  - **The insert statement is NOT copied from `TimeEntryDao` — deliberately.** Room's
 *    `@Insert(onConflict = REPLACE)` is a DELETE plus an INSERT, and `time_entries` is the parent of
 *    `time_entry_tags` (`ON DELETE CASCADE`), so porting it verbatim would port a silent data-loss bug.
 *    `INSERT_SQL` uses `ON CONFLICT(id) DO UPDATE` instead, which reaches the same end state without
 *    deleting the row. See [INSERT_SQL].
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
@Suppress("TooManyFunctions")
class TimeEntryRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : TimeEntryRepository {
    private val logger = AppLogger.current

    /** Returns the single running entry (null `endedAt`), or null. */
    override suspend fun getActiveTimeEntry(): TimeEntry? =
        JdbcFlows.onIo {
            val entry = readActive()?.toDomain()
            logger.d(
                "TimeEntryRepositoryJvm.getActiveTimeEntry",
                "Active time entry fetched",
                mapOf("found" to (entry != null), "id" to (entry?.id ?: "none")),
            )
            entry
        }

    /** Emits the running entry (null `endedAt`), or null. */
    override fun observeActiveTimeEntry(): Flow<TimeEntry?> = JdbcFlows.observe(pollIntervalMs) { readActive()?.toDomain() }

    /** Emits entries whose `startedAt` falls inside the inclusive [start]..[end] window, oldest first. */
    override fun getTimeEntriesForRange(
        start: LocalDateTime,
        end: LocalDateTime,
    ): Flow<List<TimeEntry>> {
        logger.d(
            "TimeEntryRepositoryJvm.getTimeEntriesForRange",
            "Subscribing to time entries for range",
            mapOf("start" to start.toString(), "end" to end.toString()),
        )
        val parameters =
            arrayOf(PersistedDateTime.format(start), PersistedDateTime.format(end))
        return JdbcFlows
            .observe(pollIntervalMs) { readEntries(RANGE_SQL, parameters) }
            .map { entities ->
                logger.d(
                    "TimeEntryRepositoryJvm.getTimeEntriesForRange",
                    "Time entries emitted for range",
                    mapOf("count" to entities.size),
                )
                entities.map { it.toDomain() }
            }
    }

    /**
     * Emits entries overlapping the day of [date], oldest first.
     *
     * `currentTime` is fixed when the flow is created, not per emission: it exists so an entry still running
     * counts towards today, and re-reading the clock on every poll would move that boundary under the UI.
     */
    override fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>> {
        val dayStart = date.atStartOfDay()
        // Bound in the ORDER THE PLACEHOLDERS APPEAR in DATE_SQL, which is not the order the Android call
        // site names its arguments: Room binds by name, JDBC binds by position. The first `?` is the day
        // END, the second is the current time inside COALESCE, the third is the day START.
        val parameters =
            arrayOf(
                PersistedDateTime.format(dayStart.plusDays(1)),
                PersistedDateTime.format(LocalDateTime.now()),
                PersistedDateTime.format(dayStart),
            )
        return JdbcFlows
            .observe(pollIntervalMs) { readEntries(DATE_SQL, parameters) }
            .map { entities ->
                logger.d(
                    "TimeEntryRepositoryJvm.getTimeEntriesForDate",
                    "Time entries emitted for day",
                    mapOf("date" to date.toString(), "count" to entities.size),
                )
                entities.map { it.toDomain() }
            }
    }

    /** Starts a running entry from [input], stopping any entry already running. `endedAt` stays null. */
    override suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry =
        JdbcFlows.onIo {
            logger.i(
                "TimeEntryRepositoryJvm.startTimeEntry",
                "Starting time entry",
                mapOf(
                    "dimensionId" to (input.dimensionId ?: "unknown"),
                    "taskId" to (input.taskId ?: "none"),
                    "startedAt" to input.startedAt.toString(),
                ),
            )
            // Stop any existing active entry first, then re-read: the stop writes its own day key.
            stopActiveEntryInternal(focusRating = 0.0, focusNote = null)

            val now = LocalDateTime.now()
            val entity =
                TimeEntryEntity(
                    id = UUID.randomUUID().toString(),
                    lifeIntentionCategory = input.lifeIntentionCategory,
                    dimensionId =
                        TimeEntryDimensionRule.resolveDimensionId(
                            dimensionId = input.dimensionId,
                            lifeIntentionCategory = input.lifeIntentionCategory,
                        ),
                    dayKey = PersistedDateTime.dayKey(input.startedAt),
                    taskId = TimeEntryDimensionRule.normalizeIdentifier(input.taskId),
                    startedAt = PersistedDateTime.format(input.startedAt),
                    endedAt = null,
                    focusRating = input.focusRating,
                    focusNote = input.focusNote,
                    focusRatedAt = input.focusRatedAt?.let(PersistedDateTime::format),
                    createdAt = PersistedDateTime.format(now),
                    updatedAt = PersistedDateTime.format(now),
                )
            JdbcQueries.write(session, INSERT_SQL, entity.toBindings())
            markDirtyForDay(input.startedAt.toLocalDate(), "time_entry_started")
            logger.i(
                "TimeEntryRepositoryJvm.startTimeEntry",
                "Time entry started",
                mapOf("id" to entity.id, "dayKey" to entity.dayKey),
            )
            entity.toDomain()
        }

    /** Stops the running entry, recording a focus rating of 0.0. Null when nothing was running. */
    override suspend fun stopActiveTimeEntry(): TimeEntry? = JdbcFlows.onIo { stopActiveEntryInternal(focusRating = 0.0, focusNote = null) }

    /** Stops the running entry, recording [focusRating] (clamped to 0..1) and a trimmed [focusNote]. */
    override suspend fun stopActiveTimeEntryWithFocus(
        focusRating: Double,
        focusNote: String?,
    ): TimeEntry? =
        JdbcFlows.onIo {
            stopActiveEntryInternal(
                focusRating = focusRating.coerceIn(0.0, 1.0),
                focusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

    /**
     * Rewrites entry [id] from [input]: re-resolves the dimension, recomputes the day key from the new
     * start time, and marks the previous and the new day lens-dirty.
     *
     * @throws IllegalArgumentException when [id] does not exist.
     */
    override suspend fun updateTimeEntry(
        id: String,
        input: TimeEntryInput,
    ): TimeEntry =
        JdbcFlows.onIo {
            logger.i(
                "TimeEntryRepositoryJvm.updateTimeEntry",
                "Updating time entry",
                mapOf(
                    "id" to id,
                    "dimensionId" to (input.dimensionId ?: "resolved_from_category"),
                ),
            )
            val existing = readById(id) ?: throw IllegalArgumentException("TimeEntry not found: $id")
            val now = LocalDateTime.now()
            val updated =
                existing.copy(
                    lifeIntentionCategory = input.lifeIntentionCategory,
                    dimensionId =
                        TimeEntryDimensionRule.resolveDimensionId(
                            dimensionId = input.dimensionId,
                            lifeIntentionCategory = input.lifeIntentionCategory,
                            fallbackDimensionId = existing.dimensionId,
                        ),
                    taskId = TimeEntryDimensionRule.normalizeIdentifier(input.taskId),
                    dayKey = PersistedDateTime.dayKey(input.startedAt),
                    startedAt = PersistedDateTime.format(input.startedAt),
                    endedAt = input.endedAt?.let(PersistedDateTime::format),
                    focusRating = input.focusRating,
                    focusNote = input.focusNote,
                    focusRatedAt = input.focusRatedAt?.let(PersistedDateTime::format),
                    updatedAt = PersistedDateTime.format(now),
                )
            // The trailing id satisfies the statement's `WHERE id = ?`; without it nothing matches.
            JdbcQueries.write(session, UPDATE_FULL_ROW_SQL, updated.toBindings() + updated.id)
            markDirtyForDayKey(existing.dayKey, "time_entry_updated_previous_day")
            markDirtyForDay(input.startedAt.toLocalDate(), "time_entry_updated_target_day")
            logger.i(
                "TimeEntryRepositoryJvm.updateTimeEntry",
                "Time entry updated",
                mapOf("id" to id, "dayKey" to updated.dayKey),
            )
            updated.toDomain()
        }

    /** Rewrites [entry] wholesale: same rules as [updateTimeEntry], from a domain object. */
    @Suppress("LongMethod")
    override suspend fun updateTimeEntry(entry: TimeEntry) {
        JdbcFlows.onIo {
            logger.i(
                "TimeEntryRepositoryJvm.updateTimeEntryDirect",
                "Updating time entry via domain model",
                mapOf("id" to entry.id, "taskId" to (entry.taskId ?: "none")),
            )
            val existing =
                readById(entry.id) ?: throw IllegalArgumentException("TimeEntry not found: ${entry.id}")
            val now = LocalDateTime.now()
            val updated =
                existing.copy(
                    lifeIntentionCategory = entry.lifeIntentionCategory,
                    dimensionId =
                        TimeEntryDimensionRule.resolveDimensionId(
                            dimensionId = entry.dimensionId,
                            lifeIntentionCategory = entry.lifeIntentionCategory,
                            fallbackDimensionId = existing.dimensionId,
                        ),
                    taskId = TimeEntryDimensionRule.normalizeIdentifier(entry.taskId),
                    dayKey = PersistedDateTime.dayKey(entry.startedAt),
                    startedAt = PersistedDateTime.format(entry.startedAt),
                    endedAt = entry.endedAt?.let(PersistedDateTime::format),
                    focusRating = entry.focusRating,
                    focusNote = entry.focusNote,
                    focusRatedAt = entry.focusRatedAt?.let(PersistedDateTime::format),
                    updatedAt = PersistedDateTime.format(now),
                )
            // The trailing id satisfies the statement's `WHERE id = ?`; without it nothing matches.
            JdbcQueries.write(session, UPDATE_FULL_ROW_SQL, updated.toBindings() + updated.id)
            markDirtyForDayKey(existing.dayKey, "time_entry_direct_updated_previous_day")
            markDirtyForDay(entry.startedAt.toLocalDate(), "time_entry_direct_updated_target_day")
            logger.i(
                "TimeEntryRepositoryJvm.updateTimeEntryDirect",
                "Time entry updated",
                mapOf("id" to entry.id, "dayKey" to updated.dayKey),
            )
        }
    }

    /** Hard-deletes [id] and marks its day lens-dirty. */
    override suspend fun deleteTimeEntry(id: String) {
        JdbcFlows.onIo {
            logger.w("TimeEntryRepositoryJvm.deleteTimeEntry", "Deleting time entry", mapOf("id" to id))
            val existing = readById(id)
            JdbcQueries.write(session, DELETE_BY_ID_SQL, arrayOf(id))
            markDirtyForDayKey(existing?.dayKey, "time_entry_deleted")
            logger.i("TimeEntryRepositoryJvm.deleteTimeEntry", "Time entry deleted", mapOf("id" to id))
        }
    }

    /** Persists a completed (or explicit) entry from [input] with `endedAt` already supplied. */
    override suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry =
        JdbcFlows.onIo {
            logger.i(
                "TimeEntryRepositoryJvm.createTimeEntry",
                "Creating time entry",
                mapOf(
                    "dimensionId" to (input.dimensionId ?: "unknown"),
                    "hasEndedAt" to (input.endedAt != null),
                ),
            )
            val now = LocalDateTime.now()
            val entity =
                TimeEntryEntity(
                    id = UUID.randomUUID().toString(),
                    lifeIntentionCategory = input.lifeIntentionCategory,
                    dimensionId =
                        TimeEntryDimensionRule.resolveDimensionId(
                            dimensionId = input.dimensionId,
                            lifeIntentionCategory = input.lifeIntentionCategory,
                        ),
                    dayKey = PersistedDateTime.dayKey(input.startedAt),
                    taskId = TimeEntryDimensionRule.normalizeIdentifier(input.taskId),
                    startedAt = PersistedDateTime.format(input.startedAt),
                    endedAt = input.endedAt?.let(PersistedDateTime::format),
                    focusRating = input.focusRating,
                    focusNote = input.focusNote,
                    focusRatedAt = input.focusRatedAt?.let(PersistedDateTime::format),
                    createdAt = PersistedDateTime.format(now),
                    updatedAt = PersistedDateTime.format(now),
                )
            JdbcQueries.write(session, INSERT_SQL, entity.toBindings())
            markDirtyForDay(input.startedAt.toLocalDate(), "time_entry_created")
            logger.i(
                "TimeEntryRepositoryJvm.createTimeEntry",
                "Time entry created",
                mapOf("id" to entity.id, "dayKey" to entity.dayKey),
            )
            entity.toDomain()
        }

    /** Emits every entry, newest-started first. */
    override fun getAllTimeEntries(): Flow<List<TimeEntry>> =
        JdbcFlows
            .observe(pollIntervalMs) { readEntries(ALL_SQL) }
            .map { entities -> entities.map { it.toDomain() } }

    /** Emits every running entry, newest-started first. */
    override fun getActiveTimeEntries(): Flow<List<TimeEntry>> =
        JdbcFlows
            .observe(pollIntervalMs) { readEntries(ALL_ACTIVE_SQL) }
            .map { entities -> entities.map { it.toDomain() } }

    /**
     * Stops the running entry and records post-session focus feedback.
     *
     * Timestamps `focusRatedAt` even when [focusRating] is the `0.0` of a plain stop, matching the Android
     * path: the rated-at column records *when the entry was stopped*, and the statistics filter on the
     * rating, not on its presence.
     */
    private fun stopActiveEntryInternal(
        focusRating: Double?,
        focusNote: String?,
    ): TimeEntry? {
        val active = readActive()
        if (active == null) {
            logger.d("TimeEntryRepositoryJvm.stopActiveTimeEntry", "No active entry to stop", emptyMap())
            return null
        }
        logger.i(
            "TimeEntryRepositoryJvm.stopActiveTimeEntry",
            "Stopping active entry",
            mapOf("id" to active.id, "dimensionId" to (active.dimensionId ?: "unknown")),
        )
        val now = LocalDateTime.now()
        JdbcQueries.write(
            session,
            STOP_ENTRY_SQL,
            arrayOf(
                PersistedDateTime.format(now),
                focusRating?.toString(),
                focusNote,
                PersistedDateTime.format(now),
                PersistedDateTime.format(now),
                active.id,
            ),
        )
        markDirtyForDayKey(active.dayKey, "time_entry_stopped")
        logger.i("TimeEntryRepositoryJvm.stopActiveTimeEntry", "Entry stopped", mapOf("id" to active.id))
        return readById(active.id)?.toDomain()
    }

    private fun readActive(): TimeEntryEntity? =
        JdbcQueries.read(session, ACTIVE_SQL, emptyArray<String?>()) { resultSet ->
            if (resultSet.next()) resultSet.readEntry() else null
        }

    private fun readById(id: String): TimeEntryEntity? =
        JdbcQueries.read(session, BY_ID_SQL, arrayOf(id)) { resultSet ->
            if (resultSet.next()) resultSet.readEntry() else null
        }

    private fun readEntries(
        sql: String,
        parameters: Array<out String?> = emptyArray<String?>(),
    ): List<TimeEntryEntity> =
        JdbcQueries.read(session, sql, parameters) { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.readEntry())
                }
            }
        }

    private fun ResultSet.readEntry(): TimeEntryEntity =
        TimeEntryEntity(
            id = getString("id"),
            lifeIntentionCategory = getString("lifeIntentionCategory"),
            dimensionId = getString("dimension_id"),
            dayKey = getString("day_key"),
            taskId = getString("taskId"),
            startedAt = getString("startedAt"),
            endedAt = getString("endedAt"),
            focusRating = getNullableDouble("focusRating"),
            focusNote = getString("focusNote"),
            focusRatedAt = getString("focusRatedAt"),
            importSource = getString("import_source"),
            importId = getString("import_id"),
            importedAt = getString("imported_at"),
            importBatchId = getString("import_batch_id"),
            createdAt = getString("createdAt"),
            updatedAt = getString("updatedAt"),
        )

    /** `getDouble` returns 0 for SQL NULL, which would invent a focus rating nobody gave. */
    private fun ResultSet.getNullableDouble(column: String): Double? {
        val value = getDouble(column)
        return if (wasNull()) null else value
    }

    /** The 16 time-entry columns, in the order the write statements bind them. */
    private fun TimeEntryEntity.toBindings(): Array<String?> =
        arrayOf(
            id,
            lifeIntentionCategory,
            dimensionId,
            dayKey,
            taskId,
            startedAt,
            endedAt,
            focusRating?.toString(),
            focusNote,
            focusRatedAt,
            importSource,
            importId,
            importedAt,
            importBatchId,
            createdAt,
            updatedAt,
        )

    /** Marks the day [dayKey] names. A malformed key is logged and skipped: the write already happened. */
    private fun markDirtyForDayKey(
        dayKey: String?,
        reason: String,
    ) {
        if (dayKey == null) return
        runCatching { LocalDate.parse(dayKey) }
            .onSuccess { day -> markDirtyForDay(day, reason) }
            .onFailure { error ->
                logger.d(
                    "TimeEntryRepositoryJvm.markDirtyForDayKey",
                    "Skipped dirty-mark: stored day key is not a plain day",
                    mapOf("dayKey" to dayKey, "reason" to reason, "failure" to error::class.simpleName),
                )
            }
    }

    private fun markDirtyForDay(
        day: LocalDate,
        reason: String,
    ) {
        logger.d(
            "TimeEntryRepositoryJvm.markDirtyForDay",
            "Marking daily insight dirty due to time entry mutation",
            mapOf("day" to day.toString(), "reason" to reason),
        )
        // The sync form: this runs inside the caller's onIo block.
        markLensDayDirtySync(
            session = session,
            dayKey = day.toString(),
            changedModules = setOf("time_entry"),
            reason = reason,
            logger = logger,
        )
    }

    private companion object {
        /** Verbatim from `TimeEntryDao.getActiveTimeEntry` / `observeActiveTimeEntry`. */
        const val ACTIVE_SQL = "SELECT * FROM time_entries WHERE endedAt IS NULL LIMIT 1"

        /** Verbatim from `TimeEntryDao.getById`. */
        const val BY_ID_SQL = "SELECT * FROM time_entries WHERE id = ?"

        /** Verbatim from `TimeEntryDao.getAll`. */
        const val ALL_SQL = "SELECT * FROM time_entries ORDER BY startedAt DESC"

        /** Verbatim from `TimeEntryDao.getAllActiveTimeEntries`. */
        const val ALL_ACTIVE_SQL =
            "SELECT * FROM time_entries WHERE endedAt IS NULL ORDER BY startedAt DESC"

        /** Verbatim from `TimeEntryDao.getTimeEntriesForRange`. */
        const val RANGE_SQL = """
            SELECT * FROM time_entries
            WHERE datetime(startedAt) >= datetime(?)
            AND datetime(startedAt) <= datetime(?)
            ORDER BY startedAt ASC
        """

        /** Verbatim from `TimeEntryDao.getTimeEntriesForDate`. */
        const val DATE_SQL = """
            SELECT * FROM time_entries
            WHERE datetime(startedAt) < datetime(?)
            AND datetime(COALESCE(endedAt, ?)) > datetime(?)
            ORDER BY startedAt ASC
        """

        /** Verbatim from `TimeEntryDao.stopEntry`; a null rating or note writes SQL NULL. */
        const val STOP_ENTRY_SQL = """
            UPDATE time_entries
            SET endedAt = ?, focusRating = ?, focusNote = ?, focusRatedAt = ?, updatedAt = ?
            WHERE id = ?
        """

        /** Verbatim from `TimeEntryDao.deleteById`. */
        const val DELETE_BY_ID_SQL = "DELETE FROM time_entries WHERE id = ?"

        /**
         * The 16 time-entry columns, in the order [INSERT_SQL] and [UPDATE_FULL_ROW_SQL] bind them —
         * the ONE place a time-entry column name is spelled, so the two statements cannot drift.
         */
        private val TIME_ENTRY_COLUMNS =
            listOf(
                "id",
                "lifeIntentionCategory",
                "dimension_id",
                "day_key",
                "taskId",
                "startedAt",
                "endedAt",
                "focusRating",
                "focusNote",
                "focusRatedAt",
                "import_source",
                "import_id",
                "imported_at",
                "import_batch_id",
                "createdAt",
                "updatedAt",
            )

        private const val KEY_COLUMN = "id"

        val PLACEHOLDERS = "(" + List(TIME_ENTRY_COLUMNS.size) { "?" }.joinToString(", ") + ")"

        /**
         * The insert path, matching `TimeEntryDao.insert` (REPLACE) WITHOUT the REPLACE.
         *
         * `time_entries` is the parent of `time_entry_tags` (`ON DELETE CASCADE`), so a REPLACE that
         * collided on id deleted the entry's tag links — silently, since SQLite defaults
         * `PRAGMA foreign_keys` to OFF. `ON CONFLICT(id) DO UPDATE` writes the same row without deleting
         * it, so the links survive. See `TaskRepositoryJvm` for the same fix and the same reasoning.
         */
        val INSERT_SQL =
            "INSERT INTO time_entries " +
                TIME_ENTRY_COLUMNS.joinToString(", ", prefix = "(", postfix = ")") +
                " VALUES $PLACEHOLDERS " +
                "ON CONFLICT($KEY_COLUMN) DO UPDATE SET " +
                TIME_ENTRY_COLUMNS
                    .filterNot { it == KEY_COLUMN }
                    .joinToString(", ") { "$it = excluded.$it" }

        /** Room's `@Update` writes every column of the row; the same [TIME_ENTRY_COLUMNS], same order. */
        val UPDATE_FULL_ROW_SQL =
            "UPDATE OR ABORT time_entries SET " +
                TIME_ENTRY_COLUMNS.joinToString(", ") { "$it = ?" } +
                " WHERE $KEY_COLUMN = ?"
    }
}
