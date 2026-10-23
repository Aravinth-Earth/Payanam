//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.payanam.database.jvm.schema.PayanamDatabaseSchemaDdl
import io.payanam.domain.model.NoteInput
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TimeEntryInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * MEASUREMENT: how many orphaned rows ordinary deletes leave behind in a database written on Linux.
 *
 * **The question.** Android's Room delegate issues `PRAGMA foreign_keys = ON` on every open; the JVM
 * opener issues nothing, so SQLite's default of OFF stands. A delete that the schema declares as
 * `ON DELETE CASCADE` or `ON DELETE SET NULL` therefore has to write the declared action out by
 * hand, or the child row survives the parent. This class MEASURES what is left, through
 * [DatabaseForeignKeyCheck], on a database built by the app's own create path and driven through the
 * repositories' own public delete methods.
 *
 * **All three delete paths are CLEAN.** [TaskRepositoryJvm.deleteTask] writes all five of its declared
 * child actions itself, in one transaction; [NoteRepositoryJvm.deleteNote] and
 * [TimeEntryRepositoryJvm.deleteTimeEntry] each write the one their schema declares, in the same shape —
 * children first, parent last, all inside a single `JdbcQueries.transaction`. Deleting a task removes its
 * occurrences, reschedules and notifications, clears its tag links and NULLs the `taskId` on the time
 * entries that tracked it; deleting a note removes its `note_tags` links along with both of its rows;
 * deleting a time entry removes its `time_entry_tags` links. None of the three leaves a violation behind,
 * so the whole measured count is now zero; see [ordinary deletes across every path leave no divergence].
 *
 * **What makes this a measurement and not a restatement.**
 *
 * - The schema is the app's own. [PayanamDatabaseOpener.create] runs the generated Room DDL
 *   ([PayanamDatabaseSchemaDdl]), so a divergence reported here is one the shipped schema declares —
 *   not one a hand-copied fixture table in this test invented.
 * - The rows are written by the repositories. Every child row here is produced by calling
 *   `createTask`/`recordOccurrence`/`replaceTaskTags`/`deleteTask` (and the note and time-entry
 *   equivalents), never by a raw `INSERT` into a child table. The delete under test is the delete a
 *   user reaches from a screen, which is the only one whose child rows could be stranded.
 * - Nothing enables enforcement. `PRAGMA foreign_keys` is READ on the app's own session and asserted
 *   to be `0`, so the cause is established by measurement rather than assumed, and the fixture does
 *   not perturb the very flag under study.
 * - The file is generated per test in a temp directory. A real export is never opened; it holds the
 *   user's personal data.
 *
 * **The counts are pinned deliberately.** A test that merely asserted "orphans exist" would keep
 * passing after someone added child cleanup to a delete path — which is precisely the change this
 * file has already been through twice. Each test asserts the exact relationships found, and
 * [ordinary deletes across every path leave no divergence] asserts the whole set as an exact empty
 * set, so a divergence that is fixed makes this file red and says which one — and so does one that
 * appears.
 *
 * **What this does not claim.** `PRAGMA foreign_key_check` reports what is STORED. It says nothing
 * about a table that a given user never populated, and it does not repair anything. The zero asserted
 * here means "this fixture wrote nothing that violates its own schema", not "the desktop never strands
 * a row" — a path this file does not exercise, or a file imported from a platform that enforced its
 * keys, is outside what a count can speak to.
 */
class OrdinaryDeletesLeaveOrphanedRowsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "ordinary-deletes-orphan-fixture-passphrase"
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        // The app's own create path: generated DDL, then re-opened through the real guards, so the
        // session below is the same shape of session the desktop holds.
        val path = tempFolder.newFolder().toPath().resolve("orphans.db")
        val result = PayanamDatabaseOpener.create(path, passphrase)
        assertWithMessage("create refused the fixture path")
            .that(result)
            .isInstanceOf(DatabaseOpenResult.Opened::class.java)
        session = (result as DatabaseOpenResult.Opened).session
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun connection(): Connection = session.requireConnection()

    /**
     * The declared enforcement state of the session under test.
     *
     * Read, never written: this is the fact that makes the orphans the schema's doing rather than the
     * fixture's, and the whole measurement would be meaningless if this ever read `1` — the
     * cascades would fire and every assertion below would be vacuously empty rather than green.
     */
    private fun declaredForeignKeys(): Int =
        connection().createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_keys").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /** The child/parent relationships the check found, as `child -> parent`. */
    private fun foundRelationships(): List<String> = DatabaseForeignKeyCheck.violations(connection()).map { "${it.table} -> ${it.parent}" }

    /**
     * Counts the rows a tag junction table holds for one owner id.
     *
     * The FK check cannot tell "this delete removed its links" from "this owner never had any": a surviving
     * link on a live parent raises no violation, and neither does an owner that never had a link. Every
     * per-path test therefore pairs its empty [foundRelationships] with a `1` here BEFORE the delete and a
     * `0` after it, which is the pair that fails if a delete stops issuing its child statement.
     *
     * [ownerColumn] is passed rather than inferred because the junction columns are snake_case while the
     * columns of the tables they point at are camelCase — the whole reason this cannot hardcode a spelling
     * the way [timeEntryRowsFor] does for `time_entries`.
     */
    private fun tagLinkRows(
        linkTable: String,
        ownerColumn: String,
        ownerId: String,
    ): Int = countRows(linkTable, "$ownerColumn = '$ownerId'")

    /**
     * Counts the rows a parent table holds for one id.
     *
     * **The FK check cannot see a dropped parent delete at all.** Deleting a parent row raises no violation
     * when it succeeds, and leaving it in place raises none either — nothing else in these fixtures
     * references a `notes` or a `time_entries` row. So a `deleteNote` that dropped its
     * `DELETE FROM journal_notes` would leave the whole file GREEN, which is why each per-path test reads
     * its own parent rows back before and after.
     *
     * Both halves of that pair are asserted rather than just the after, because an after of `0` is also
     * what a row that was never created looks like: the `1` before is what makes the `0` mean "this delete
     * removed it".
     */
    private fun parentRows(
        table: String,
        id: String,
    ): Int = countRows(table, "id = '$id'")

    /** One `SELECT COUNT(*)`. The table and predicate are the caller's, so no column is spelled here. */
    private fun countRows(
        table: String,
        where: String,
    ): Int =
        connection().createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table WHERE $where").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /**
     * Reads a `time_entries` row back as `(exists, taskId IS NULL)`.
     *
     * The FK check cannot see the difference between the `SET NULL` the schema declares and deleting
     * the row outright: a deleted entry produces no violation either. Both outcomes look clean to
     * [DatabaseForeignKeyCheck], and only one of them is the declared behaviour — the entry is time the
     * user actually spent. Reading the row is therefore the only assertion that can tell them apart.
     */
    private fun timeEntryRowsFor(id: String): Pair<Int, Boolean> =
        connection().createStatement().use { statement ->
            statement.executeQuery("SELECT taskId IS NULL FROM time_entries WHERE id = '$id'").use { rows ->
                if (rows.next()) 1 to (rows.getInt(1) != 0) else 0 to true
            }
        }

    /**
     * Writes a task, attaches every child row the schema binds to `tasks.id` with an actionable
     * declared action, then deletes the task through [TaskRepositoryJvm.deleteTask].
     *
     * One task rather than several, so a count of one row per relationship is the expected shape: a
     * second deleted task would double every number here without teaching anything a name does not.
     *
     * @return the id of the `time_entries` row attached to the task, which the caller must be able to
     *   look up afterwards: `time_entries` is the `SET NULL` child, so "no violation" alone cannot
     *   distinguish the correct action from DELETING the row (see [timeEntryRowsFor]).
     */
    private fun deleteATaskWithEveryChildAttached(): String =
        runBlocking {
            val today: LocalDate = LocalDate.of(2026, 10, 1)
            val dueAt: LocalDateTime = today.atTime(9, 0)
            // dimensionId is left null on purpose: it is not one of the divergences, and seeding a
            // dimension row the app does not seed in `create` would add an unrelated NO ACTION
            // violation to a count about cascades.
            val task =
                TaskRepositoryJvm(session, pollIntervalMs = 10L)
                    .createTask(TaskInput(title = "Task with a full history", dueDate = dueAt))
            val taskId = task.id

            TaskOccurrenceRepositoryJvm(session, pollIntervalMs = 10L)
                .recordOccurrence(
                    taskId = taskId,
                    dueDate = dueAt,
                    status = "completed",
                    note = "done",
                    completionRate = 1.0,
                )
            TaskRescheduleRepositoryJvm(session, pollIntervalMs = 10L)
                .recordReschedule(
                    taskId = taskId,
                    previousDueDate = dueAt,
                    newDueDate = today.plusDays(1).atTime(9, 0),
                    wasOverdue = true,
                )
            NotificationRepositoryJvm(session)
                .scheduleNotification(
                    taskId = taskId,
                    scheduledAt = today.atTime(8, 0),
                    notificationType = "REMINDER",
                    title = "Stand up",
                    body = "Time to move",
                )
            TagRepositoryJvm(session, pollIntervalMs = 10L).replaceTaskTags(taskId, listOf("Deep Work"))
            val timeEntry =
                TimeEntryRepositoryJvm(session, pollIntervalMs = 10L)
                    .createTimeEntry(
                        TimeEntryInput(
                            lifeIntentionCategory = "Work",
                            taskId = taskId,
                            startedAt = today.atTime(10, 0),
                            endedAt = today.atTime(11, 0),
                        ),
                    )

            assertWithMessage("the fixture must be clean before the delete under test")
                .that(foundRelationships())
                .isEmpty()

            TaskRepositoryJvm(session, pollIntervalMs = 10L).deleteTask(taskId)
            timeEntry.id
        }

    /**
     * The task delete leaves nothing behind, and says so for each of the five children separately.
     *
     * [TaskRepositoryJvm.deleteTask] writes the declared actions out by hand because this connection
     * enforces nothing: four `ON DELETE CASCADE` children are deleted and the one `ON DELETE SET
     * NULL` child has its `taskId` cleared. The session pragma is still asserted to be `0`, which is
     * what makes this a real result rather than a vacuous one — with enforcement on, the cascades would
     * fire on their own and an empty assertion here would prove nothing about the repository.
     *
     * Each child is named in the failure message when one survives, so a dropped statement is
     * localised by child table rather than reported as a bare "not empty".
     */
    @Test
    fun `deleting a task removes its four cascade children and clears its set-null child`(): Unit =
        runBlocking {
            assertWithMessage("the session under test must not enforce foreign keys")
                .that(declaredForeignKeys())
                .isEqualTo(0)

            val timeEntryId = deleteATaskWithEveryChildAttached()

            assertWithMessage(
                "no child row may survive a task delete, but these relationships still point at a " +
                    "deleted task: %s",
                DatabaseForeignKeyCheck.message(DatabaseForeignKeyCheck.violations(connection())),
            ).that(foundRelationships()).isEmpty()
            assertThat(DatabaseForeignKeyCheck.violations(connection())).isEmpty()

            // `time_entries` separately, because the FK check cannot tell SET NULL from DELETE: a
            // deleted entry raises no violation either. The row must be present AND unlinked.
            val (entryExists, entryTaskIdIsNull) = timeEntryRowsFor(timeEntryId)
            assertWithMessage("the SET NULL child must survive the delete, not be removed with it")
                .that(entryExists)
                .isEqualTo(1)
            assertWithMessage("the surviving time entry must no longer point at the deleted task")
                .that(entryTaskIdIsNull)
                .isTrue()
        }

    /**
     * `deleteNote` removes `journal_notes`, its `notes` shadow row AND the note's `note_tags` links.
     *
     * **Three statements, three assertions, and no two of them cover for each other.** The FK check catches
     * only a dropped CHILD statement: a surviving link raises a violation, while a dropped parent `DELETE`
     * raises nothing at all, because nothing else in this fixture references a `notes` or a `journal_notes`
     * row. So the parent rows are read back directly — `1` before, `0` after — which is what makes each of
     * `DELETE_JOURNAL_NOTE_SQL` and `DELETE_SHADOW_NOTE_SQL` a statement this file would notice losing, and
     * [tagLinkRows] is what makes `DELETE_NOTE_TAGS_SQL` one.
     */
    @Test
    fun `deleting a note removes both its rows and its tag links`(): Unit =
        runBlocking {
            val today: LocalDate = LocalDate.of(2026, 10, 1)
            val note =
                NoteRepositoryJvm(session, pollIntervalMs = 10L)
                    .createNote(
                        NoteInput(
                            title = "Journal idea",
                            details = "Details",
                            lifeIntentionCategory = "Learning & Growth",
                            dimensionId = null,
                        ),
                    )
            TagRepositoryJvm(session, pollIntervalMs = 10L).replaceNoteTags(note.id, listOf("Focus"))

            // The fixture must be clean AND must actually have a link and a row to lose, or the assertions
            // below would pass on a note that was never written.
            assertWithMessage("the fixture must be clean before the delete under test")
                .that(foundRelationships())
                .isEmpty()
            assertWithMessage("the fixture must attach a tag link for the delete to have to remove")
                .that(tagLinkRows("note_tags", "note_id", note.id))
                .isEqualTo(1)
            assertWithMessage("the fixture must write both note rows for the delete to have to remove")
                .that(listOf(parentRows("journal_notes", note.id), parentRows("notes", note.id)))
                .containsExactly(1, 1)
                .inOrder()

            NoteRepositoryJvm(session, pollIntervalMs = 10L).deleteNote(note.id)

            assertWithMessage(
                "no child row may survive a note delete, but these relationships still point at a " +
                    "deleted note: %s",
                DatabaseForeignKeyCheck.message(DatabaseForeignKeyCheck.violations(connection())),
            ).that(foundRelationships()).isEmpty()
            assertThat(DatabaseForeignKeyCheck.violations(connection())).isEmpty()

            assertWithMessage("deleteNote must remove the note's own note_tags links")
                .that(tagLinkRows("note_tags", "note_id", note.id))
                .isEqualTo(0)
            assertWithMessage("deleteNote must remove the journal_notes row, not only the notes shadow")
                .that(parentRows("journal_notes", note.id))
                .isEqualTo(0)
            assertWithMessage("deleteNote must remove the legacy notes shadow row too")
                .that(parentRows("notes", note.id))
                .isEqualTo(0)
        }

    /**
     * `deleteTimeEntry` removes the `time_entries` row AND the entry's `time_entry_tags` links.
     *
     * Asserted on both statements for the reason the note path gives: the FK check proves the links are gone,
     * and reading `time_entries` back is the only thing that notices a dropped parent `DELETE` — which
     * raises no violation whether or not it happens.
     */
    @Test
    fun `deleting a time entry removes its row and its tag links`(): Unit =
        runBlocking {
            val today: LocalDate = LocalDate.of(2026, 10, 1)
            val entry =
                TimeEntryRepositoryJvm(session, pollIntervalMs = 10L)
                    .createTimeEntry(
                        TimeEntryInput(
                            lifeIntentionCategory = "Work",
                            startedAt = today.atTime(9, 0),
                            endedAt = today.atTime(10, 0),
                        ),
                    )
            TagRepositoryJvm(session, pollIntervalMs = 10L)
                .replaceTimeEntryTags(entry.id, listOf("Deep Work"))

            assertWithMessage("the fixture must be clean before the delete under test")
                .that(foundRelationships())
                .isEmpty()
            assertWithMessage("the fixture must attach a tag link for the delete to have to remove")
                .that(tagLinkRows("time_entry_tags", "time_entry_id", entry.id))
                .isEqualTo(1)
            assertWithMessage("the fixture must write the entry row for the delete to have to remove")
                .that(parentRows("time_entries", entry.id))
                .isEqualTo(1)

            TimeEntryRepositoryJvm(session, pollIntervalMs = 10L).deleteTimeEntry(entry.id)

            assertWithMessage(
                "no child row may survive a time-entry delete, but these relationships still point " +
                    "at a deleted entry: %s",
                DatabaseForeignKeyCheck.message(DatabaseForeignKeyCheck.violations(connection())),
            ).that(foundRelationships()).isEmpty()
            assertThat(DatabaseForeignKeyCheck.violations(connection())).isEmpty()

            assertWithMessage("deleteTimeEntry must remove the entry's own time_entry_tags links")
                .that(tagLinkRows("time_entry_tags", "time_entry_id", entry.id))
                .isEqualTo(0)
            assertWithMessage("deleteTimeEntry must remove the time_entries row itself")
                .that(parentRows("time_entries", entry.id))
                .isEqualTo(0)
        }

    /**
     * The whole finding, pinned: ordinary deletes across all three paths leave NO divergence.
     *
     * The three per-path tests above localise a failure to one repository; this one is the number the
     * finding rests on. It is written as an exact SET against [NO_DIVERGENCE], not as a count and not as
     * a `containsAll`, so a divergence that reappears fails here with the relationship named in the
     * message rather than only as a number that moved.
     *
     * **All three paths run, and all three must contribute nothing.** The task path runs first and in full
     * ([deleteATaskWithEveryChildAttached] attaches all five of its children), so its five relationships
     * would reappear in this set the moment any one of its cleanup statements was dropped — this test still
     * detects a regression there even though none of its names are listed anywhere now. The note and
     * time-entry paths follow, each carrying one tag link into its delete.
     *
     * **A zero is a lower bound, not a proof.** It says these three deletes wrote nothing that violates the
     * schema the app's own create path declares. It says nothing about a delete path this fixture does not
     * exercise, nor about a file imported from a platform that enforced its keys — see the class KDoc.
     */
    @Test
    fun `ordinary deletes across every path leave no divergence`(): Unit =
        runBlocking {
            deleteATaskWithEveryChildAttached()

            val today: LocalDate = LocalDate.of(2026, 10, 1)
            val note =
                NoteRepositoryJvm(session, pollIntervalMs = 10L)
                    .createNote(
                        NoteInput(
                            title = "Journal idea",
                            details = null,
                            lifeIntentionCategory = "Learning & Growth",
                            dimensionId = null,
                        ),
                    )
            TagRepositoryJvm(session, pollIntervalMs = 10L).replaceNoteTags(note.id, listOf("Focus"))
            NoteRepositoryJvm(session, pollIntervalMs = 10L).deleteNote(note.id)

            val entry =
                TimeEntryRepositoryJvm(session, pollIntervalMs = 10L)
                    .createTimeEntry(
                        TimeEntryInput(
                            lifeIntentionCategory = "Work",
                            startedAt = today.atTime(9, 0),
                            endedAt = today.atTime(10, 0),
                        ),
                    )
            TagRepositoryJvm(session, pollIntervalMs = 10L)
                .replaceTimeEntryTags(entry.id, listOf("Deep Work"))
            TimeEntryRepositoryJvm(session, pollIntervalMs = 10L).deleteTimeEntry(entry.id)

            val found = DatabaseForeignKeyCheck.violations(connection())
            val expected = NO_DIVERGENCE
            assertThat(expected).hasSize(ZERO)

            assertWithMessage(
                "measured orphans, grouped: %s",
                DatabaseForeignKeyCheck.message(found),
            ).that(foundRelationships().toSet()).isEqualTo(expected)
            assertThat(found).hasSize(expected.size)
        }

    private companion object {
        /**
         * Every relationship an ordinary delete may leave behind: none.
         *
         * Named as a constant rather than asserted with `isEmpty()` because the set assertion below is what
         * produces a failure message naming WHICH relationship reappeared. `isEmpty()` on a list would report
         * only that something is there.
         */
        val NO_DIVERGENCE = emptySet<String>()

        /**
         * The measured total, pinned as a literal so a reader sees the number rather than a sum.
         *
         * Asserted against `expected.size` in [ordinary deletes across every path leave no divergence]: if
         * someone adds a relationship to [NO_DIVERGENCE] this stops matching before the set assertion is
         * reached, which points at the count rather than at the membership.
         *
         * It was `7` when this file first measured the desktop, fell to `2` when
         * [TaskRepositoryJvm.deleteTask] began writing its own `CASCADE` and `SET NULL` actions, and is `0`
         * now that [NoteRepositoryJvm.deleteNote] and [TimeEntryRepositoryJvm.deleteTimeEntry] write theirs.
         * The five the task fix removed were `task_occurrences`, `task_reschedules`,
         * `scheduled_notifications`, `task_tags` and `time_entries` — all still `-> tasks`. The two the
         * note and time-entry fixes removed were `note_tags -> notes` and
         * `time_entry_tags -> time_entries`.
         */
        const val ZERO = 0
    }
}
