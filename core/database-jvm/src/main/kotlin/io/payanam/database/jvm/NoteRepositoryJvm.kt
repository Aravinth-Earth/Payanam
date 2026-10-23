//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.JournalNoteEntity
import io.payanam.database.entity.NoteEntity
import io.payanam.database.mapper.NoteMapper.toDomain
import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import io.payanam.domain.note.NoteDimensionResolution
import io.payanam.domain.repository.NoteRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * JDBC implementation of [NoteRepository] over the encrypted database — the desktop's half of the notes
 * feature, on the same interface the Android Room implementation satisfies.
 *
 * Four things here are deliberate and each is a place the two platforms could have drifted:
 *
 *  - **The SQL is the Android `@Query` text, verbatim.** If the desktop selected different columns or a
 *    different order, the same database would produce two different screens.
 *  - **Writes touch BOTH tables, in one transaction**, exactly as `NoteRepositoryImpl` does: the note
 *    itself in `journal_notes`, and its shadow row in `notes`. The shadow is not decorative — the tag
 *    junction table's foreign key points at `notes`, so a note written without it can never carry a tag.
 *  - **The delete writes one child statement the Room DAO does not.** Room turns `PRAGMA foreign_keys`
 *    on and the declared `ON DELETE CASCADE` on `note_tags` fires on its own; this connection enforces
 *    nothing, so [deleteNote] clears the note's tag links itself. It is the one method here whose SQL
 *    is deliberately longer than the DAO it was ported from. See [deleteNote].
 *  - **Dimension resolution is the shared [NoteDimensionResolution]**, not a copy of it. The Android
 *    implementation used to hold these rules privately; a second copy would let the two platforms persist
 *    different dimension ids for the same user action.
 *
 * Reads are exposed as [Flow] through [JdbcFlows]; see that file for why a JDBC read needs an interval to
 * behave like Room's invalidation.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
class NoteRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : NoteRepository {
    private val logger = AppLogger.current
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** Emits every note, newest first. */
    override fun getAllNotes(): Flow<List<Note>> {
        logger.d(SOURCE, "Subscribing to all notes")
        return JdbcFlows.observe(pollIntervalMs) { readNotes(ALL_NOTES_SQL, emptyArray()) }
    }

    /** Emits notes tied to [dimension] by id or life-intention category, newest first. */
    override fun getNotesByDimension(dimension: String): Flow<List<Note>> {
        logger.d(SOURCE, "Subscribing to notes by dimension", mapOf("dimension" to dimension))
        // The query binds :dimension twice - the caller checks an id OR a label, so both placeholders get
        // the same value. Binding one parameter for two placeholders leaves the second NULL, which matches
        // only a NULL column: the label branch would silently never match.
        return JdbcFlows.observe(pollIntervalMs) {
            readNotes(BY_DIMENSION_SQL, arrayOf(dimension, dimension))
        }
    }

    /** Emits notes dated [date], newest first. */
    override fun getNotesForDate(date: LocalDate): Flow<List<Note>> {
        logger.d(SOURCE, "Subscribing to notes for date", mapOf("date" to date.toString()))
        return JdbcFlows.observe(pollIntervalMs) { readNotes(FOR_DAY_SQL, arrayOf(date.toString())) }
    }

    /** Returns the note with [id], or null. */
    override suspend fun getNoteById(id: String): Note? =
        JdbcFlows.onIo {
            val note =
                JdbcQueries.read(session, BY_ID_SQL, arrayOf(id)) { resultSet ->
                    if (resultSet.next()) resultSet.readJournalNote() else null
                }
            logger.d(SOURCE, "Fetched note by id", mapOf("id" to id, "found" to (note != null)))
            note?.toDomain()
        }

    /** Creates a note from [input], generating id and timestamps. */
    override suspend fun createNote(input: NoteInput): Note =
        JdbcFlows.onIo {
            logger.i(SOURCE, "Creating new note")
            val now = LocalDateTime.now()
            val id = UUID.randomUUID().toString()
            val resolvedDimensionId =
                NoteDimensionResolution.resolveDimensionId(
                    explicitDimensionId = input.dimensionId,
                    categoryLabel = input.lifeIntentionCategory,
                )
            val note =
                JournalNoteEntity(
                    id = id,
                    title = input.title,
                    details = input.details,
                    lifeIntentionCategory =
                        NoteDimensionResolution.resolveDimensionLabel(
                            explicitLabel = input.lifeIntentionCategory,
                            resolvedDimensionId = resolvedDimensionId,
                        ),
                    dimensionId = resolvedDimensionId,
                    dayKey = now.toLocalDate().toString(),
                    createdAt = now.format(formatter),
                    updatedAt = now.format(formatter),
                )
            JdbcQueries.transaction(session) {
                insertJournalNote(note)
                insertLegacyShadow(note)
            }
            logger.i(SOURCE, "Note created successfully", mapOf("id" to id))
            note.toDomain()
        }

    /**
     * Applies a partial update from [input] to note [id], rewriting both tables in one transaction.
     *
     * @throws IllegalArgumentException when [id] does not exist — the Android implementation throws too,
     *   and a silent no-op here would look like a successful save.
     */
    override suspend fun updateNote(
        id: String,
        input: NoteInput,
    ): Note =
        JdbcFlows.onIo {
            logger.i(SOURCE, "Updating note", mapOf("id" to id))
            val existing =
                JdbcQueries.read(session, BY_ID_SQL, arrayOf(id)) { resultSet ->
                    if (resultSet.next()) resultSet.readJournalNote() else null
                } ?: run {
                    logger.w(SOURCE, "Note not found for update", mapOf("id" to id))
                    throw IllegalArgumentException("Note not found: $id")
                }
            val resolvedDimensionId =
                NoteDimensionResolution.resolveDimensionId(
                    explicitDimensionId = input.dimensionId,
                    categoryLabel = input.lifeIntentionCategory,
                )
            val resolvedDimensionLabel =
                resolvedDimensionId
                    ?.let {
                        NoteDimensionResolution.resolveDimensionLabel(
                            explicitLabel = null,
                            resolvedDimensionId = it,
                        )
                    }
                    ?: existing.lifeIntentionCategory
            val updated =
                existing.copy(
                    title = input.title,
                    details = input.details,
                    lifeIntentionCategory = resolvedDimensionLabel,
                    dimensionId = resolvedDimensionId ?: existing.dimensionId,
                    dayKey = existing.dayKey.ifEmpty { existing.createdAt.take(10) },
                    updatedAt = LocalDateTime.now().format(formatter),
                )
            JdbcQueries.transaction(session) {
                insertJournalNote(updated)
                insertLegacyShadow(updated)
            }
            logger.i(SOURCE, "Note updated successfully", mapOf("id" to id))
            updated.toDomain()
        }

    /**
     * Hard-deletes note [id] from both the journal table and its legacy shadow, and clears the note's tag
     * links, in one transaction.
     *
     * **The child statement is the point of this method, and Room's DAO does not write it either** —
     * Room issues `PRAGMA foreign_keys = ON` on every open, so the declared `ON DELETE CASCADE` from
     * `note_tags` to `notes` fires on its own and `deleteById` is honestly one statement per table. The JVM
     * opener enables nothing, SQLite defaults `PRAGMA foreign_keys` to OFF, and the same delete therefore
     * left every tag link pointing at a note that no longer existed, in silence.
     *
     * **`note_tags` is the child, and `notes` is the parent — not `journal_notes`.** The junction table's
     * foreign key names `notes`, which is why the shadow table exists at all and why this delete clears the
     * links before BOTH parent rows go. Putting the child statement after the shadow delete would make it
     * delete links belonging to a note that is already gone, which is the state this method exists to
     * avoid.
     *
     * **One transaction, children before parents.** A failure after the first parent delete is rolled back
     * rather than leaving a note whose links were dropped but whose row survived — or, without the
     * transaction, the reverse.
     *
     * **`note_tags.note_id` is snake_case** (see [DELETE_NOTE_TAGS_SQL]). Spelling it `noteId` is a
     * `no such column` at prepare time, so the mistake surfaces as a failed delete rather than as a
     * quietly skipped one.
     *
     * Measured by `OrdinaryDeletesLeaveOrphanedRowsTest`.
     */
    override suspend fun deleteNote(id: String) {
        JdbcFlows.onIo {
            logger.i(SOURCE, "Deleting note", mapOf("id" to id))
            JdbcQueries.transaction(session) {
                // The tag links first: both parent rows are removed after this one, so this is the last
                // statement that can still delete a link belonging to a note that exists. See the KDoc above.
                JdbcQueries.write(session, DELETE_NOTE_TAGS_SQL, arrayOf(id))
                JdbcQueries.write(session, DELETE_JOURNAL_NOTE_SQL, arrayOf(id))
                JdbcQueries.write(session, DELETE_SHADOW_NOTE_SQL, arrayOf(id))
            }
            logger.i(SOURCE, "Note deleted successfully", mapOf("id" to id))
        }
    }

    private fun readNotes(
        sql: String,
        parameters: Array<out String>,
    ): List<Note> =
        JdbcQueries
            .read(session, sql, parameters) { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.readJournalNote())
                    }
                }
            }.map { it.toDomain() }

    private fun ResultSet.readJournalNote(): JournalNoteEntity =
        JournalNoteEntity(
            id = getString("id"),
            title = getString("title"),
            details = getString("details"),
            lifeIntentionCategory = getString("lifeIntentionCategory"),
            dimensionId = getString("dimension_id"),
            dayKey = getString("day_key"),
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
        )

    private fun insertJournalNote(note: JournalNoteEntity) {
        JdbcQueries.write(
            session,
            INSERT_JOURNAL_NOTE_SQL,
            arrayOf(
                note.id,
                note.title,
                note.details,
                note.lifeIntentionCategory,
                note.dimensionId,
                note.dayKey,
                note.createdAt,
                note.updatedAt,
            ),
        )
    }

    private fun insertLegacyShadow(note: JournalNoteEntity) {
        // Keeps the `notes` table in step for tag foreign keys and read paths that still use it. The
        // Android implementation names this syncLegacyNoteShadow and does exactly the same copy.
        JdbcQueries.write(
            session,
            INSERT_SHADOW_NOTE_SQL,
            arrayOf(
                note.id,
                note.title,
                note.details,
                note.lifeIntentionCategory,
                note.dimensionId,
                note.dayKey,
                note.createdAt,
                note.updatedAt,
            ),
        )
    }

    private fun JournalNoteEntity.toDomain(): Note =
        NoteEntity(
            id = id,
            title = title,
            details = details,
            lifeIntentionCategory = lifeIntentionCategory,
            dimensionId = dimensionId,
            dayKey = dayKey,
            createdAt = createdAt,
            updatedAt = updatedAt,
        ).toDomain()

    private companion object {
        const val SOURCE = "NoteRepositoryJvm"

        /** Verbatim from `JournalDao.getAllNotes`. */
        const val ALL_NOTES_SQL = "SELECT * FROM journal_notes ORDER BY updated_at DESC"

        /** Verbatim from `JournalDao.getNotesForDay`. */
        const val FOR_DAY_SQL =
            "SELECT * FROM journal_notes WHERE day_key = ? ORDER BY updated_at DESC"

        /** Verbatim from `JournalDao.getNotesByDimension`, with its `:dimension` bound twice. */
        const val BY_DIMENSION_SQL = """
            SELECT * FROM journal_notes
            WHERE dimension_id = ?
               OR lifeIntentionCategory = ?
            ORDER BY updated_at DESC
        """

        /** Verbatim from `JournalDao.getNoteById`. */
        const val BY_ID_SQL = "SELECT * FROM journal_notes WHERE id = ?"

        /** The `REPLACE` insert `JournalDao.insertNote` performs. */
        const val INSERT_JOURNAL_NOTE_SQL = """
            INSERT OR REPLACE INTO journal_notes
            (id, title, details, lifeIntentionCategory, dimension_id, day_key, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """

        /**
         * The `REPLACE` insert `NoteDao.insert` performs, against the legacy shadow table — WITHOUT
         * the REPLACE.
         *
         * `notes` is the parent of `note_tags` (`ON DELETE CASCADE`), and `updateNote` rewrites this
         * same id on every edit, so the REPLACE form deleted the note's tag links on each save —
         * silently, since SQLite defaults `PRAGMA foreign_keys` to OFF. `ON CONFLICT(id) DO UPDATE`
         * writes the same row without deleting it. See `TaskRepositoryJvm` for the same fix.
         */
        const val INSERT_SHADOW_NOTE_SQL = """
            INSERT INTO notes
            (id, title, details, lifeIntentionCategory, dimension_id, day_key, createdAt, updatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                details = excluded.details,
                lifeIntentionCategory = excluded.lifeIntentionCategory,
                dimension_id = excluded.dimension_id,
                day_key = excluded.day_key,
                createdAt = excluded.createdAt,
                updatedAt = excluded.updatedAt
        """

        /** Verbatim from `JournalDao.deleteNoteById`. */
        const val DELETE_JOURNAL_NOTE_SQL = "DELETE FROM journal_notes WHERE id = ?"

        /** Verbatim from `NoteDao.deleteById`. */
        const val DELETE_SHADOW_NOTE_SQL = "DELETE FROM notes WHERE id = ?"

        // ── The child statement `deleteNote` issues before those two ────────────────────────────────
        //
        // Re-declared here rather than read from `TagRepositoryJvm`, which holds the identical text as a
        // `private` companion member a different file cannot see. See [deleteNote].

        /**
         * `note_tags.note_id`, `ON DELETE CASCADE` onto `notes`.
         *
         * Same text as `TagRepositoryJvm.CLEAR_NOTE_TAGS_SQL`. The owner column is `note_id` — snake_case
         * like every other junction column — while the note tables themselves bind `id`, so a `noteId` here
         * raises `no such column` at prepare time rather than deleting nothing quietly.
         */
        const val DELETE_NOTE_TAGS_SQL = "DELETE FROM note_tags WHERE note_id = ?"
    }
}
