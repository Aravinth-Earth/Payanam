//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.TaskInput
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
import java.util.Properties

/**
 * Tests for [TaskRepositoryJvm].
 *
 * The behaviours pinned here are the ones a from-intent rewrite would get wrong: the create defaults, the
 * partial-update merge, the two-placeholder due-on and today queries, and the lens dirty-day mark that
 * follows every mutation. The three status writes are covered separately because skip and miss must NOT
 * stamp `completedAt` — a skipped task that looks completed is a scoring bug, not a cosmetic one.
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class TaskRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "task-repository-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    // The repository's "today" is the SYSTEM clock (`LocalDate.now()`), so the fixture must be the same clock.
    // It was hardcoded to 2026-09-20 - the day it was written - which passed only until midnight: every test
    // keyed to `today` then looked for a day the repository no longer considered today.
    private val today: LocalDate = LocalDate.now()
    private val tomorrow: LocalDate = today.plusDays(1)

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("tasks.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE tasks (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    description TEXT,
                    status TEXT NOT NULL DEFAULT 'pending',
                    dueDate TEXT,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    completedAt TEXT,
                    archivedAt TEXT,
                    recurrenceEnabled INTEGER NOT NULL DEFAULT 0,
                    recurrenceRule TEXT,
                    durationMinutes INTEGER NOT NULL DEFAULT 60,
                    impactLevel TEXT NOT NULL DEFAULT 'Moderate Impact',
                    goalAlignment TEXT NOT NULL DEFAULT 'Moderate Alignment',
                    energyLevel TEXT NOT NULL DEFAULT 'Moderate',
                    controlLevel TEXT NOT NULL DEFAULT 'Office/Colleagues Dependent',
                    lifeIntentionCategory TEXT NOT NULL DEFAULT 'Career & Work',
                    dimension_id TEXT,
                    day_key TEXT,
                    explicitUrgency REAL,
                    focusRequired REAL,
                    recurrenceStrategy TEXT,
                    blockedReason TEXT,
                    completionRate REAL,
                    externalDependency TEXT,
                    notificationMode TEXT,
                    customNotificationMinutes INTEGER,
                    taskScore REAL,
                    lastOccurrenceDate TEXT,
                    dayBoundaryHour INTEGER NOT NULL DEFAULT 0,
                    import_source TEXT,
                    import_id TEXT,
                    imported_at TEXT,
                    import_batch_id TEXT
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
            // The five child tables a task delete has to reach: the four the schema cascades and the one it
            // clears. The four cascade tables carry their real column set and constraint text, copied from the
            // Room export - a hand-invented column would hide the very mismatch this guards. `time_entries`
            // carries only the two columns these statements touch, because nothing in this fixture reads a
            // time entry; the delete's full-fidelity assertions live with the orphan-row test.
            statement.execute(
                """
                CREATE TABLE task_occurrences (
                    id TEXT PRIMARY KEY NOT NULL,
                    taskId TEXT NOT NULL,
                    dueDate TEXT NOT NULL,
                    status TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE task_reschedules (
                    id TEXT PRIMARY KEY NOT NULL,
                    taskId TEXT NOT NULL,
                    rescheduledTo TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE scheduled_notifications (
                    id TEXT PRIMARY KEY NOT NULL,
                    taskId TEXT NOT NULL,
                    fireAt TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE task_tags (
                    task_id TEXT NOT NULL,
                    tag_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY(task_id, tag_id),
                    FOREIGN KEY(task_id) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE time_entries (
                    id TEXT PRIMARY KEY NOT NULL,
                    taskId TEXT,
                    FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE SET NULL
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

    private fun repository() = TaskRepositoryJvm(session, pollIntervalMs = 10L)

    private fun insertRaw(
        id: String,
        title: String,
        status: String = "pending",
        dueDate: LocalDateTime? = null,
        dayKey: String? = null,
        taskScore: Double? = null,
        recurrenceEnabled: Int = 0,
        createdAt: String = "2026-09-20T08:00:00",
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO tasks (id, title, status, dueDate, day_key, taskScore, recurrenceEnabled,
                    createdAt, updatedAt, durationMinutes, lifeIntentionCategory, dayBoundaryHour)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 60, 'Career & Work', 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, title)
                statement.setString(3, status)
                // Serialized with the same ISO form the repository writes, so the calendar-date branch of the
                // due-on query (date(dueDate) = date(?)) sees a real date rather than an unparseable string.
                statement.setString(4, dueDate?.toString())
                statement.setString(5, dayKey)
                if (taskScore == null) statement.setNull(6, java.sql.Types.REAL) else statement.setDouble(6, taskScore)
                statement.setInt(7, recurrenceEnabled)
                statement.setString(8, createdAt)
                statement.setString(9, createdAt)
                statement.executeUpdate()
            }
    }

    private fun taskIds(): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM tasks").use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getString(1))
                }
            }
        }

    private fun dirtyDays(): List<String> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT day_key FROM daily_insights WHERE module = 'lens_dirty_day'",
                ).use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString(1))
                    }
                }
        }

    @Test
    fun `createTask applies the Android defaults and marks the due day dirty`(): Unit =
        runBlocking {
            val created = repository().createTask(TaskInput(title = "Write the plan", dueDate = today.atTime(9, 0)))

            assertThat(created.status).isEqualTo("pending")
            assertThat(created.durationMinutes).isEqualTo(60)
            assertThat(created.recurrenceEnabled).isFalse()
            assertThat(created.taskScore).isNull()
            assertThat(created.lifeIntentionCategory)
                .isEqualTo(DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel)
            assertThat(repository().getTaskById(created.id)?.title).isEqualTo("Write the plan")
            assertThat(dirtyDays()).containsExactly(today.toString())
        }

    @Test
    fun `createTask keeps a canonical dimension id and ignores a non-canonical label`(): Unit =
        runBlocking {
            val canonical = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id
            val withId = repository().createTask(TaskInput(title = "Has id", dimensionId = canonical))
            val withLabel = repository().createTask(TaskInput(title = "Has label", lifeIntentionCategory = "Focus"))

            assertThat(withId.dimensionId).isEqualTo(canonical)
            // A label is locale-dependent display text; guessing an id from it would write a dimension the
            // user never chose, so the Android implementation logs and ignores it. This asserts the port does.
            assertThat(withLabel.dimensionId).isNull()
        }

    @Test
    fun `getAllTasks orders by score descending then newest`(): Unit =
        runBlocking {
            insertRaw("a", "low", taskScore = 10.0, createdAt = "2026-09-20T08:00:00")
            insertRaw("b", "high", taskScore = 90.0, createdAt = "2026-09-20T07:00:00")
            insertRaw("c", "also-high-newer", taskScore = 90.0, createdAt = "2026-09-20T09:00:00")

            val ids = repository().getAllTasks().first().map { it.id }

            assertThat(ids).containsExactly("c", "b", "a").inOrder()
        }

    @Test
    fun `updateTask merges only the fields it was given`(): Unit =
        runBlocking {
            val created =
                repository().createTask(
                    TaskInput(title = "Original", description = "keep me", dueDate = today.atTime(9, 0)),
                )

            val updated = repository().updateTask(created.id, TaskInput(title = "Renamed"))

            assertThat(updated.title).isEqualTo("Renamed")
            assertThat(updated.description).isEqualTo("keep me")
            assertThat(updated.dueDate).isEqualTo(today.atTime(9, 0))
            assertThat(updated.status).isEqualTo("pending")
        }

    @Test
    fun `updateTask moves day_key with a new due date`(): Unit =
        runBlocking {
            val created = repository().createTask(TaskInput(title = "Move me", dueDate = today.atTime(9, 0)))

            val updated = repository().updateTask(created.id, TaskInput(title = "Move me", dueDate = tomorrow.atTime(9, 0)))

            assertThat(dayKeyOf(created.id)).isEqualTo(tomorrow.toString())
            assertThat(updated.dueDate).isEqualTo(tomorrow.atTime(9, 0))
        }

    @Test
    fun `updateTask on a missing id throws rather than inserting`(): Unit =
        runBlocking {
            val failure = runCatching { repository().updateTask("absent", TaskInput(title = "x")) }

            assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(taskIds()).isEmpty()
        }

    @Test
    fun `completeTask stamps completedAt, skip and miss do not`(): Unit =
        runBlocking {
            val a = repository().createTask(TaskInput(title = "done me"))
            val b = repository().createTask(TaskInput(title = "skip me"))
            val c = repository().createTask(TaskInput(title = "miss me"))

            assertThat(repository().completeTask(a.id).status).isEqualTo("completed")
            assertThat(repository().completeTask(a.id).completedAt).isNotNull()
            assertThat(repository().skipTask(b.id).status).isEqualTo("skipped")
            assertThat(repository().skipTask(b.id).completedAt).isNull()
            assertThat(repository().missTask(c.id).status).isEqualTo("missed")
            assertThat(repository().missTask(c.id).completedAt).isNull()
        }

    @Test
    fun `archiveTask records archivedAt and removes the task from the pending set`(): Unit =
        runBlocking {
            val created = repository().createTask(TaskInput(title = "archive me"))

            val archived = repository().archiveTask(created.id)

            assertThat(archived.status).isEqualTo("archived")
            assertThat(archived.archivedAt).isNotNull()
            assertThat(repository().getTasksByStatus("pending").first()).isEmpty()
        }

    @Test
    fun `deleteTask removes the row`(): Unit =
        runBlocking {
            val created = repository().createTask(TaskInput(title = "delete me"))

            repository().deleteTask(created.id)

            assertThat(repository().getTaskById(created.id)).isNull()
            assertThat(taskIds()).isEmpty()
        }

    @Test
    fun `getTasksDueOn matches day_key or, when null, the calendar due date`(): Unit =
        runBlocking {
            // day_key set: the first branch.
            insertRaw("with-key", "by day key", dayKey = today.toString())
            // day_key null, dueDate today: the second branch — this is the one a single-value bind would break.
            insertRaw("by-due-date", "by due date", dueDate = today.atTime(9, 0))
            insertRaw("other-day", "elsewhere", dayKey = tomorrow.toString())

            val ids = repository().getTasksDueOn(today).first().map { it.id }

            assertThat(ids).containsExactly("with-key", "by-due-date")
        }

    @Test
    fun `getOverdueTasks returns only pending tasks already past due`(): Unit =
        runBlocking {
            insertRaw("past", "past", dueDate = today.minusDays(2).atTime(9, 0))
            insertRaw("future", "future", dueDate = tomorrow.atTime(9, 0))
            insertRaw("done-past", "done", status = "completed", dueDate = today.minusDays(2).atTime(9, 0))

            val ids = repository().getOverdueTasks().first().map { it.id }

            assertThat(ids).containsExactly("past")
        }

    @Test
    fun `getTodaysTasks includes today and every pending recurring task`(): Unit =
        runBlocking {
            insertRaw("today", "today", dayKey = today.toString())
            insertRaw("recurring", "recurring", recurrenceEnabled = 1, dayKey = tomorrow.toString())
            insertRaw("elsewhere", "elsewhere", dayKey = tomorrow.toString())

            val ids = repository().getTodaysTasks().first().map { it.id }

            assertThat(ids).containsExactly("today", "recurring")
        }

    @Test
    fun `getRecurringTasks lists only recurring tasks`(): Unit =
        runBlocking {
            insertRaw("one", "one", recurrenceEnabled = 1)
            insertRaw("two", "two")

            assertThat(repository().getRecurringTasks().map { it.id }).containsExactly("one")
        }

    @Test
    fun `updateRecurrenceState advances the due date and resets the status`(): Unit =
        runBlocking {
            val created = repository().createTask(TaskInput(title = "weekly"))
            repository().completeTask(created.id)
            val next = tomorrow.atTime(9, 0)

            repository().updateRecurrenceState(created.id, newDueDate = next, lastOccurrenceDate = today.atTime(9, 0))

            val reloaded = repository().getTaskById(created.id)!!
            assertThat(reloaded.status).isEqualTo("pending")
            assertThat(reloaded.dueDate).isEqualTo(next)
            assertThat(reloaded.lastOccurrenceDate).isNotNull()
            assertThat(dayKeyOf(created.id)).isEqualTo(tomorrow.toString())
        }

    @Test
    fun `updateTaskScore writes the recomputed score`(): Unit =
        runBlocking {
            val created = repository().createTask(TaskInput(title = "score me"))

            repository().updateTaskScore(created.id, 42.5)

            assertThat(repository().getTaskById(created.id)?.taskScore).isEqualTo(42.5)
        }

    @Test
    fun `a subscribed read re-emits after a task is added`(): Unit =
        runBlocking {
            val tasks = repository().getAllTasks()
            assertThat(tasks.first()).isEmpty()

            repository().createTask(TaskInput(title = "appears"))

            val after = kotlinx.coroutines.withTimeout(5_000) { tasks.first { it.isNotEmpty() } }
            assertThat(after.map { it.title }).containsExactly("appears")
        }

    @Test
    fun `a status change marks the task's day dirty`(): Unit =
        runBlocking {
            val created = repository().createTask(TaskInput(title = "dirty me", dueDate = today.atTime(9, 0)))
            clearDirtyDays()

            repository().completeTask(created.id)

            assertThat(dirtyDays()).containsExactly(today.toString())
        }

    private fun clearDirtyDays() {
        connection.createStatement().use { it.executeUpdate("DELETE FROM daily_insights") }
    }

    private fun dayKeyOf(id: String): String? =
        connection.prepareStatement("SELECT day_key FROM tasks WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }
}
