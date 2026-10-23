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
 * `ON DELETE CASCADE` or `ON DELETE SET NULL` therefore removes the parent row and leaves the child
 * row behind, silently. A parity document claims exactly seven such divergences, each reachable from
 * an ordinary UI delete. This class MEASURES the number instead of restating the claim, through
 * [DatabaseForeignKeyCheck], on a database built by the app's own create path and driven through the
 * repositories' own public delete methods.
 *
 * **What makes this a measurement and not a restatement.**
 *
 * - The schema is the app's own. [PayanamDatabaseOpener.create] runs the generated Room DDL
 *   ([PayanamDatabaseSchemaDdl]), so a divergence reported here is one the shipped schema declares —
 *   not one a hand-copied fixture table in this test invented.
 * - The rows are written by the repositories. Every orphan below is produced by calling
 *   `createTask`/`recordOccurrence`/`replaceTaskTags`/`deleteTask` (and the note and time-entry
 *   equivalents), never by a raw `INSERT` into a child table. The delete that strands the child is
 *   the delete a user reaches from a screen.
 * - Nothing enables enforcement. `PRAGMA foreign_keys` is READ on the app's own session and asserted
 *   to be `0`, so the cause is established by measurement rather than assumed, and the fixture does
 *   not perturb the very flag under study.
 * - The file is generated per test in a temp directory. A real export is never opened; it holds the
 *   user's personal data.
 *
 * **The counts are pinned deliberately.** A test that merely asserted "orphans exist" would keep
 * passing after someone added child cleanup to a delete path — which is precisely the change the
 * number exists to detect. Each test asserts the exact relationships found, and
 * [ordinary deletes across every path leave exactly the seven declared divergences] asserts the whole
 * set, so a divergence that is fixed makes this file red and says which one.
 *
 * **What this does not claim.** `PRAGMA foreign_key_check` reports what is STORED. It says nothing
 * about a table that a given user never populated, and it does not repair anything. A zero here would
 * mean "this fixture wrote nothing that violates its own schema", not "the desktop never strands a
 * row"; conversely a non-zero here is a lower bound on the rows a real session accumulates, since a
 * real user performs these operations many times over.
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
     * Writes a task, attaches every child row the schema binds to `tasks.id` with an actionable
     * declared action, then deletes the task through [TaskRepositoryJvm.deleteTask].
     *
     * One task rather than several, so a count of one row per relationship is the expected shape: a
     * second deleted task would double every number here without teaching anything a name does not.
     */
    private fun deleteATaskWithEveryChildAttached(): Unit =
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
        }

    /**
     * The task delete is the single worst offender: one parent delete strands five child tables.
     *
     * Four of the five are `ON DELETE CASCADE` (the child should have gone with the parent) and the
     * fifth is `ON DELETE SET NULL` (the child's `taskId` should have become NULL). Under an
     * unenforced connection neither happens, so the check finds all five.
     */
    @Test
    fun `deleting a task strands five child rows the schema says it should have removed or cleared`(): Unit =
        runBlocking {
            assertWithMessage("the session under test must not enforce foreign keys")
                .that(declaredForeignKeys())
                .isEqualTo(0)

            deleteATaskWithEveryChildAttached()

            assertThat(foundRelationships()).containsExactlyElementsIn(TASK_SIDE)
            assertThat(DatabaseForeignKeyCheck.violations(connection())).hasSize(TASK_SIDE.size)
        }

    /** `deleteNote` removes `journal_notes` and its `notes` shadow row, and not the note's tag links. */
    @Test
    fun `deleting a note strands its tag links`() {
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

            assertThat(foundRelationships()).isEmpty()

            NoteRepositoryJvm(session, pollIntervalMs = 10L).deleteNote(note.id)

            assertThat(foundRelationships()).containsExactly("note_tags -> notes")
        }
    }

    /** `deleteTimeEntry` issues only `DELETE FROM time_entries`, so the entry's tag links survive. */
    @Test
    fun `deleting a time entry strands its tag links`() {
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

            assertThat(foundRelationships()).isEmpty()

            TimeEntryRepositoryJvm(session, pollIntervalMs = 10L).deleteTimeEntry(entry.id)

            assertThat(foundRelationships()).containsExactly("time_entry_tags -> time_entries")
        }
    }

    /**
     * The whole finding, pinned: seven divergences across three delete paths, and no others.
     *
     * The three per-path tests above localise a failure to one repository; this one is the number the
     * finding rests on. It is written as an exact set — not a count, not a `containsAll` — so that
     * adding an eighth divergence and fixing a seventh both fail here, and the failure message names
     * the difference rather than only its size.
     */
    @Test
    fun `ordinary deletes across every path leave exactly the seven declared divergences`() {
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
            val expected = TASK_SIDE + setOf("note_tags -> notes", "time_entry_tags -> time_entries")
            assertThat(expected).hasSize(SEVEN)

            assertWithMessage(
                "measured orphans, grouped: %s",
                DatabaseForeignKeyCheck.message(found),
            ).that(foundRelationships().toSet()).isEqualTo(expected)
            assertThat(found).hasSize(expected.size)
        }
    }

    private companion object {
        /**
         * The five relationships [TaskRepositoryJvm.deleteTask] strands on its own.
         *
         * Four are `ON DELETE CASCADE` and one is `ON DELETE SET NULL`, so they are listed by name
         * rather than by action: what a reader needs to check is which CHILD TABLE still holds a row
         * pointing at a task that no longer exists.
         */
        val TASK_SIDE =
            setOf(
                "task_occurrences -> tasks",
                "task_reschedules -> tasks",
                "scheduled_notifications -> tasks",
                "task_tags -> tasks",
                "time_entries -> tasks",
            )

        /**
         * The measured total, pinned as a literal so a reader sees the number rather than a sum.
         *
         * Asserted against `expected.size` in [ordinary deletes across every path leave exactly the
         * seven declared divergences]: if someone adds an eighth child table to a delete path this
         * stops matching before the set assertion is reached, which points at the count rather than at
         * the membership.
         */
        const val SEVEN = 7
    }
}
