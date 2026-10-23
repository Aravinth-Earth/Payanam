//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.TaskOccurrence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/**
 * Tests for [TaskOccurrenceRepositoryJvm].
 *
 * The behaviours pinned here are the habit grid's contract: one row per (task, day), found by CALENDAR day
 * rather than string equality, with skip/missed reporting `skippedAt` and never `completedAt`. The
 * two-placeholder and IN-list statements are covered because a positional bind is where this layer has
 * already produced one silent bug.
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class TaskOccurrenceRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "task-occurrence-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    private val today: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("occurrences.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE task_occurrences (
                    id TEXT PRIMARY KEY NOT NULL,
                    taskId TEXT NOT NULL,
                    dueDate TEXT NOT NULL,
                    completedAt TEXT,
                    actualCompletedAt TEXT,
                    actualDurationMinutes INTEGER,
                    status TEXT NOT NULL,
                    statusReason TEXT,
                    createdAt TEXT NOT NULL,
                    completionRate REAL,
                    note TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE daily_insights (
                    id TEXT PRIMARY KEY NOT NULL,
                    day_key TEXT NOT NULL,
                    module TEXT NOT NULL,
                    dimension_id TEXT,
                    planned_minutes INTEGER,
                    actual_minutes INTEGER,
                    focused_minutes INTEGER,
                    completed_count INTEGER,
                    total_count INTEGER,
                    summary_json TEXT,
                    generated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun repository() = TaskOccurrenceRepositoryJvm(session, pollIntervalMs = 10L)

    private fun rowCount(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM task_occurrences").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun dirtyDays(): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT day_key FROM daily_insights WHERE module = 'lens_dirty_day'").use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getString(1))
                }
            }
        }

    private fun rawStatus(taskId: String): String? =
        connection.prepareStatement("SELECT status FROM task_occurrences WHERE taskId = ?").use { statement ->
            statement.setString(1, taskId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    @Test
    fun `toggling a new occurrence inserts one row and marks the day dirty`(): Unit =
        runBlocking {
            val created =
                repository().toggleOccurrence(
                    taskId = "task-1",
                    date = today,
                    newStatus = "completed",
                    note = "done",
                    reason = null,
                    actualCompletedAt = null,
                    actualDurationMinutes = 30,
                )

            assertThat(rowCount()).isEqualTo(1)
            assertThat(created.status).isEqualTo("completed")
            assertThat(created.occurrenceDate).isEqualTo(today.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            assertThat(created.completedAt).isNotNull()
            assertThat(created.actualDurationMinutes).isEqualTo(30)
            assertThat(dirtyDays()).containsExactly(today.toString())
        }

    @Test
    fun `toggling the same day again updates instead of duplicating`(): Unit =
        runBlocking {
            repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)
            repository().toggleOccurrence("task-1", today, "pending", null, null, null, null)

            assertThat(rowCount()).isEqualTo(1)
            assertThat(rawStatus("task-1")).isEqualTo("pending")
        }

    @Test
    fun `skipped and missed rows carry no timestamp at all on the toggle path`(): Unit =
        runBlocking {
            val skipped = repository().toggleOccurrence("task-1", today, "skipped", null, "no time", null, null)
            val missed =
                repository().toggleOccurrence("task-2", today, "missed", null, "forgot", null, null)

            // The Android toggle path computes completedAt only for `completed` and then derives skippedAt
            // FROM it, so a skipped or missed row records when it was toggled: nowhere. Pinned deliberately --
            // the reason string is the only evidence, and a desktop that "improved" this would write rows
            // Android does not.
            assertThat(skipped.completedAt).isNull()
            assertThat(skipped.skippedAt).isNull()
            assertThat(missed.completedAt).isNull()
            assertThat(missed.skippedAt).isNull()
        }

    @Test
    fun `the parameterised recordOccurrence returns a skippedAt for a skipped status`(): Unit =
        runBlocking {
            repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)

            val recorded =
                repository().recordOccurrence(
                    taskId = "task-1",
                    dueDate = today.atTime(8, 0),
                    status = "skipped",
                    note = "rescheduled",
                    completionRate = null,
                )

            // The UPDATE branch of the Android implementation populates skippedAt from `now` while the row it
            // just wrote stores a NULL completedAt. Asserted so the asymmetry is a known port, not a surprise.
            assertThat(recorded.skippedAt).isNotNull()
            assertThat(repository().getOccurrenceForDate("task-1", today)?.skippedAt).isNull()
        }

    fun `a completed toggle clears the previous skip reason by design`(): Unit =
        runBlocking {
            repository().toggleOccurrence("task-1", today, "skipped", null, "no time", null, null)

            // `completed` takes the incoming reason as-is (null here), unlike every other status, which keeps
            // the stored one. Asserted so the asymmetry is deliberate rather than accidental.
            val completed = repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)

            assertThat(completed.statusReason).isNull()
        }

    @Test
    fun `an occurrence stored with a time component is found by its calendar day`(): Unit =
        runBlocking {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO task_occurrences
                    (id, taskId, dueDate, status, createdAt)
                    VALUES ('row-1', 'task-1', '${today}T09:30:00', 'completed', '${today}T09:30:00')
                    """.trimIndent(),
                )
            }

            val found = repository().getOccurrenceForDate("task-1", today)

            assertThat(found?.id).isEqualTo("row-1")
            assertThat(repository().getOccurrenceForDate("task-1", today.minusDays(1))).isNull()
        }

    @Test
    fun `getOccurrencesForLastNDays honours the inclusive window`(): Unit =
        runBlocking {
            repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)
            repository().toggleOccurrence("task-1", today.minusDays(3), "skipped", null, null, null, null)
            repository().toggleOccurrence("task-1", today.minusDays(10), "completed", null, null, null, null)

            val lastFive = repository().getOccurrencesForLastNDays("task-1", days = 5)

            assertThat(lastFive.map { it.occurrenceDate.take(10) })
                .containsExactly(today.toString(), today.minusDays(3).toString())
        }

    @Test
    fun `bulk range lookup groups by task and expands the id list`(): Unit =
        runBlocking {
            repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)
            repository().toggleOccurrence("task-2", today, "skipped", null, null, null, null)
            repository().toggleOccurrence("task-2", today.minusDays(1), "missed", null, null, null, null)

            val grouped = repository().getOccurrencesForTasksInLastNDays(listOf("task-1", "task-2"), days = 5)

            assertThat(grouped.keys).containsExactly("task-1", "task-2")
            assertThat(grouped["task-1"]).hasSize(1)
            assertThat(grouped["task-2"]).hasSize(2)
            assertThat(repository().getOccurrencesForTasksInLastNDays(emptyList(), days = 5)).isEmpty()
        }

    @Test
    fun `the parameterised recordOccurrence upserts and preserves actuals`(): Unit =
        runBlocking {
            repository().toggleOccurrence(
                taskId = "task-1",
                date = today,
                newStatus = "completed",
                note = null,
                reason = null,
                actualCompletedAt = today.atTime(9, 30),
                actualDurationMinutes = 45,
            )

            val recorded =
                repository().recordOccurrence(
                    taskId = "task-1",
                    dueDate = today.atTime(8, 0),
                    status = "skipped",
                    note = "rescheduled",
                    completionRate = 0.5,
                )

            assertThat(rowCount()).isEqualTo(1)
            assertThat(recorded.status).isEqualTo("skipped")
            assertThat(recorded.actualDurationMinutes).isEqualTo(45)
            assertThat(recorded.actualCompletedAt).isEqualTo(today.atTime(9, 30))
        }

    @Test
    fun `recordOccurrence with a domain object is insert-only and keys on the caller's id`(): Unit =
        runBlocking {
            val occurrence =
                TaskOccurrence(
                    id = "caller-owned",
                    taskId = "task-9",
                    occurrenceDate = today.toString(),
                    status = "completed",
                    statusNote = "from the caller",
                )

            repository().recordOccurrence(occurrence)

            assertThat(rowCount()).isEqualTo(1)
            val readBack = repository().getOccurrenceForDate("task-9", today)
            assertThat(readBack?.id).isEqualTo("caller-owned")
            assertThat(readBack?.statusNote).isEqualTo("from the caller")
        }

    @Test
    fun `deleting by task and date removes the row and is a no-op when absent`(): Unit =
        runBlocking {
            repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)

            repository().deleteOccurrence("task-1", today)
            repository().deleteOccurrence("task-1", today)

            assertThat(rowCount()).isEqualTo(0)
        }

    @Test
    fun `deleting by id removes exactly that row`(): Unit =
        runBlocking {
            val first = repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)
            repository().toggleOccurrence("task-2", today, "completed", null, null, null, null)

            repository().deleteOccurrence(first.id)

            assertThat(rowCount()).isEqualTo(1)
        }

    @Test
    fun `occurrences for a task come back newest first`(): Unit =
        runBlocking {
            repository().toggleOccurrence("task-1", today.minusDays(2), "completed", null, null, null, null)
            repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)

            val dates = repository().getOccurrencesByTaskId("task-1").map { it.occurrenceDate.take(10) }

            assertThat(dates).containsExactly(today.toString(), today.minusDays(2).toString()).inOrder()
        }

    @Test
    fun `a subscribed read re-emits after an occurrence changes`(): Unit =
        runBlocking {
            val flow = repository().getOccurrencesForDate(today)
            assertThat(flow.first()).isEmpty()

            repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)

            val after = kotlinx.coroutines.withTimeout(5_000) { flow.first { it.isNotEmpty() } }
            assertThat(after.map { it.taskId }).containsExactly("task-1")
        }

    @Test
    fun `occurrences for a date ignore other days`(): Unit =
        runBlocking {
            repository().toggleOccurrence("task-1", today, "completed", null, null, null, null)
            repository().toggleOccurrence("task-1", today.minusDays(1), "completed", null, null, null, null)

            val forToday = repository().getOccurrencesForDate(today).first()

            assertThat(forToday.map { it.taskId }).containsExactly("task-1")
            assertThat(repository().getOccurrenceForDate("task-1", today.minusDays(1))).isNotNull()
        }

    @Test
    fun `toggleOccurrence accepts an explicit actual completion time`(): Unit =
        runBlocking {
            val completedAt = today.atTime(7, 15)

            val result =
                repository().toggleOccurrence(
                    taskId = "task-1",
                    date = today,
                    newStatus = "completed",
                    note = null,
                    reason = null,
                    actualCompletedAt = completedAt,
                    actualDurationMinutes = 15,
                )

            assertThat(result.actualCompletedAt).isEqualTo(completedAt)
        }

    @Test
    fun `recording an occurrence with a malformed date still persists the row`(): Unit =
        runBlocking {
            val occurrence =
                TaskOccurrence(
                    id = "odd-date",
                    taskId = "task-1",
                    occurrenceDate = LocalDateTime.now().toString(),
                    status = "completed",
                )

            repository().recordOccurrence(occurrence)

            assertThat(rowCount()).isEqualTo(1)
        }
}
