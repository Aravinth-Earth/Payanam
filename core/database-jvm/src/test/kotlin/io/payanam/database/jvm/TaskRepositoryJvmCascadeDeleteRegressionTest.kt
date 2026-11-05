//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.payanam.database.entity.TaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties

/**
 * Regression test for the hazard: the task insert path must not cascade-delete a task's children.
 *
 * `TaskRepositoryJvm.writeEntity` used to bind `INSERT OR REPLACE`. In SQLite a REPLACE is a DELETE
 * followed by an INSERT, and `tasks` is the parent of four `ON DELETE CASCADE` child tables
 * (`task_occurrences`, `task_reschedules`, `scheduled_notifications`, `task_tags`). Writing an id that
 * already existed therefore destroyed that task's habit history, notification queue and tag set — with
 * no exception, no log, and a successful statement, which is the worst combination there is.
 *
 * Two properties are pinned, because the fix can be undone in two ways:
 *
 *  1. **A colliding id leaves the children alone.** This is the hazard itself, asserted against a real
 *     SQLCipher connection with `PRAGMA foreign_keys = ON` — the setting the desktop does NOT use today.
 *     SQLite defaults that pragma OFF, which is the only reason this has been silent; the flip is the
 *     change this test is written to survive.
 *  2. **The upsert still writes every column.** A `DO UPDATE SET` clause that quietly omits a column
 *     looks correct and leaves the omitted field holding the previous value — replacing silent data loss
 *     with silent data non-write. Every one of the 34 columns is given a distinct second value and read
 *     back, so a clause that stops covering them fails here rather than in the app.
 *
 * The colliding write is driven by reflecting into the private `writeEntity`, because no public method
 * can produce one: `createTask` mints a fresh UUID and `updateTask` deliberately uses a real `UPDATE`.
 * That unreachability is itself the finding — reachability from a desktop path (an import replaying a
 * preserved id, say) is exactly what the parity doc lists as unverified.
 *
 * Fixture and session follow [TaskRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class TaskRepositoryJvmCascadeDeleteRegressionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "task-cascade-delete-regression-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("cascade.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        // The pragma is per-connection, and it is the whole point of this fixture: set here so the
        // cascade is live, where the desktop today runs with it off and the bug is therefore invisible.
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        assertThat(pragmaForeignKeys()).isEqualTo(1)
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
            // The four child tables the schema binds to `tasks.id` with ON DELETE CASCADE. Column sets
            // and constraint text are copied from the Room export (v22), not from memory.
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
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun pragmaForeignKeys(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_keys").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    /**
     * The repository's own insert path, reached reflectively.
     *
     * [TaskRepositoryJvm.createTask] generates its id, so it cannot collide, and
     * [TaskRepositoryJvm.updateTask] writes through `updateEntity` (a real `UPDATE`) rather than this
     * method. The private method is therefore the only way to write a duplicate id, which is precisely
     * why the hazard sat unnoticed behind an unreachable branch.
     */
    private fun writeThroughRepositoryInsertPath(entity: TaskEntity) {
        val writeEntity =
            TaskRepositoryJvm::class.java
                .getDeclaredMethod("writeEntity", TaskEntity::class.java)
                .apply { isAccessible = true }
        writeEntity.invoke(TaskRepositoryJvm(session, pollIntervalMs = 10L), entity)
    }

    private fun childCount(table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun attachChildren(taskId: String) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "INSERT INTO task_occurrences (id, taskId, dueDate, status, createdAt) " +
                    "VALUES ('o1', '$taskId', '2026-01-01', 'completed', '2026-01-01')",
            )
            statement.executeUpdate(
                "INSERT INTO task_reschedules (id, taskId, rescheduledTo, createdAt) " +
                    "VALUES ('r1', '$taskId', '2026-02-02', '2026-01-01')",
            )
            statement.executeUpdate(
                "INSERT INTO scheduled_notifications (id, taskId, fireAt, createdAt) " +
                    "VALUES ('n1', '$taskId', '2026-03-03', '2026-01-01')",
            )
            statement.executeUpdate(
                "INSERT INTO task_tags (task_id, tag_id, created_at) VALUES ('$taskId', 'tag1', '2026-01-01')",
            )
        }
    }

    /** A task whose every column carries [marker], so an omitted column in the SET clause is visible. */
    private fun task(
        id: String,
        marker: String,
        title: String = "title-$marker",
    ): TaskEntity =
        TaskEntity(
            id = id,
            title = title,
            description = "description-$marker",
            status = "status-$marker",
            dueDate = "due-$marker",
            createdAt = "created-$marker",
            updatedAt = "updated-$marker",
            completedAt = "completed-$marker",
            archivedAt = "archived-$marker",
            recurrenceEnabled = 1,
            recurrenceRule = "rule-$marker",
            durationMinutes = 42,
            impactLevel = "impact-$marker",
            goalAlignment = "goal-$marker",
            energyLevel = "energy-$marker",
            controlLevel = "control-$marker",
            lifeIntentionCategory = "category-$marker",
            dimensionId = "dimension-$marker",
            dayKey = "day-$marker",
            explicitUrgency = 0.25,
            focusRequired = 0.5,
            recurrenceStrategy = "strategy-$marker",
            blockedReason = "blocked-$marker",
            completionRate = 0.75,
            externalDependency = "dependency-$marker",
            notificationMode = "mode-$marker",
            customNotificationMinutes = 15,
            taskScore = 0.875,
            lastOccurrenceDate = "occurrence-$marker",
            dayBoundaryHour = 4,
            importSource = "source-$marker",
            importId = "import-$marker",
            importedAt = "imported-$marker",
            importBatchId = "batch-$marker",
        )

    /** The 34 columns, in the order `TaskEntity.toBindings` binds them. */
    private val allColumns =
        listOf(
            "id",
            "title",
            "description",
            "status",
            "dueDate",
            "createdAt",
            "updatedAt",
            "completedAt",
            "archivedAt",
            "recurrenceEnabled",
            "recurrenceRule",
            "durationMinutes",
            "impactLevel",
            "goalAlignment",
            "energyLevel",
            "controlLevel",
            "lifeIntentionCategory",
            "dimension_id",
            "day_key",
            "explicitUrgency",
            "focusRequired",
            "recurrenceStrategy",
            "blockedReason",
            "completionRate",
            "externalDependency",
            "notificationMode",
            "customNotificationMinutes",
            "taskScore",
            "lastOccurrenceDate",
            "dayBoundaryHour",
            "import_source",
            "import_id",
            "imported_at",
            "import_batch_id",
        )

    @Test
    fun `re-inserting an existing task id does not cascade-delete its children`(): Unit =
        runBlocking {
            writeThroughRepositoryInsertPath(task(id = "t1", marker = "one"))
            attachChildren("t1")
            assertThat(childCount("task_occurrences")).isEqualTo(1)
            assertThat(childCount("task_reschedules")).isEqualTo(1)
            assertThat(childCount("scheduled_notifications")).isEqualTo(1)
            assertThat(childCount("task_tags")).isEqualTo(1)

            // The collision. Same id, different row — this is the write that used to wipe the children.
            writeThroughRepositoryInsertPath(task(id = "t1", marker = "two"))

            assertThat(childCount("task_occurrences")).isEqualTo(1)
            assertThat(childCount("task_reschedules")).isEqualTo(1)
            assertThat(childCount("scheduled_notifications")).isEqualTo(1)
            assertThat(childCount("task_tags")).isEqualTo(1)
            // And the write itself must still land, or "children preserved" would be trivial.
            assertThat(titleOf("t1")).isEqualTo("title-two")
        }

    @Test
    fun `the upsert writes every column on a colliding id`(): Unit =
        runBlocking {
            writeThroughRepositoryInsertPath(task(id = "t1", marker = "one"))
            writeThroughRepositoryInsertPath(task(id = "t1", marker = "two"))

            val row =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT ${allColumns.joinToString(", ")} FROM tasks").use { rs ->
                        assertThat(rs.next()).isTrue()
                        rs.readRow()
                    }
                }

            // Every column, not a sample: a SET clause missing one column is silent, and the marker
            // makes the stale value obvious rather than merely wrong. The column name goes in the
            // failure message so a 34-way loop still points at the one field that drifted.
            allColumns.forEach { column ->
                val expected = expectedFor(column, marker = "two")
                assertWithMessage("column %s", column).that(row[column]).isEqualTo(expected)
            }
        }

    @Test
    fun `a colliding id is still an update, not a second row`(): Unit =
        runBlocking {
            writeThroughRepositoryInsertPath(task(id = "t1", marker = "one"))
            writeThroughRepositoryInsertPath(task(id = "t1", marker = "two"))
            writeThroughRepositoryInsertPath(task(id = "t1", marker = "three"))

            assertThat(childCount("task_occurrences")).isEqualTo(0)
            assertThat(rowCountOfTasks()).isEqualTo(1)
            assertThat(titleOf("t1")).isEqualTo("title-three")
        }

    private fun titleOf(id: String): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT title FROM tasks WHERE id = '$id'").use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    private fun rowCountOfTasks(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM tasks").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun ResultSet.readRow(): Map<String, String> =
        buildMap {
            allColumns.forEach { column -> put(column, getString(column)) }
        }

    /**
     * The value a column must hold after the colliding write.
     *
     * Read back as text and compared as text, so INTEGER and REAL columns are checked through the same
     * path as TEXT ones. The numeric values are chosen to survive the column's declared affinity
     * unchanged — `42` on an INTEGER column is `42`, and `0.25` on a REAL column is `0.25`.
     */
    private fun expectedFor(
        column: String,
        marker: String,
    ): String =
        when (column) {
            "id" -> {
                "t1"
            }

            "recurrenceEnabled" -> {
                "1"
            }

            "durationMinutes" -> {
                "42"
            }

            "dayBoundaryHour" -> {
                "4"
            }

            "explicitUrgency" -> {
                "0.25"
            }

            "focusRequired" -> {
                "0.5"
            }

            "completionRate" -> {
                "0.75"
            }

            "taskScore" -> {
                "0.875"
            }

            "customNotificationMinutes" -> {
                "15"
            }

            else -> {
                val property =
                    when (column) {
                        "title" -> "title"
                        "description" -> "description"
                        "status" -> "status"
                        "dueDate" -> "due"
                        "createdAt" -> "created"
                        "updatedAt" -> "updated"
                        "completedAt" -> "completed"
                        "archivedAt" -> "archived"
                        "recurrenceRule" -> "rule"
                        "impactLevel" -> "impact"
                        "goalAlignment" -> "goal"
                        "energyLevel" -> "energy"
                        "controlLevel" -> "control"
                        "lifeIntentionCategory" -> "category"
                        "dimension_id" -> "dimension"
                        "day_key" -> "day"
                        "recurrenceStrategy" -> "strategy"
                        "blockedReason" -> "blocked"
                        "externalDependency" -> "dependency"
                        "notificationMode" -> "mode"
                        "lastOccurrenceDate" -> "occurrence"
                        "import_source" -> "source"
                        "import_id" -> "import"
                        "imported_at" -> "imported"
                        "import_batch_id" -> "batch"
                        else -> error("No expected value mapped for column $column")
                    }
                "$property-$marker"
            }
        }
}
