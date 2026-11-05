//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.jvm.backfill.ScoreRollupTriggerJdbc
import io.payanam.database.jvm.event.ScoreChangeEventBusJvm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.Properties

/**
 * Tests for [ScoreRollupTriggerJdbc] — the JDBC implementation of [ScoreRollupTrigger].
 *
 * Verifies that status changes, rule changes, and day-only recalculations
 * correctly rebuild L1 (habit_metrics) and cascade L2 (dimension_metrics)
 * and L3 (day_metrics).
 */
class ScoreRollupTriggerRecalcTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "score-rollup-trigger-recalc-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var file: java.nio.file.Path

    private val today: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        file = tempFolder.newFolder().toPath().resolve("trigger-recalc.db")
        val path = file
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
                CREATE TABLE habit_metrics (
                    habitId TEXT NOT NULL,
                    dayKey TEXT NOT NULL,
                    score REAL NOT NULL,
                    runningAvg REAL NOT NULL,
                    progress REAL NOT NULL,
                    streakPos INTEGER NOT NULL,
                    streakNet INTEGER NOT NULL,
                    posContinue INTEGER NOT NULL,
                    PRIMARY KEY (habitId, dayKey)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE dimension_metrics (
                    dimensionId TEXT NOT NULL,
                    dayKey TEXT NOT NULL,
                    score REAL NOT NULL,
                    runningAvg REAL NOT NULL,
                    progress REAL NOT NULL,
                    streakPos INTEGER NOT NULL,
                    streakNet INTEGER NOT NULL,
                    posContinue INTEGER NOT NULL,
                    PRIMARY KEY (dimensionId, dayKey)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_metrics (
                    dayKey TEXT PRIMARY KEY NOT NULL,
                    dayScore REAL NOT NULL,
                    runningAvg REAL NOT NULL,
                    progress REAL NOT NULL,
                    streakPos INTEGER NOT NULL,
                    streakNet INTEGER NOT NULL,
                    posContinue INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE life_dimensions (
                    id TEXT PRIMARY KEY NOT NULL,
                    weight REAL NOT NULL
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

    private fun trigger() = ScoreRollupTriggerJdbc(session, ScoreChangeEventBusJvm())

    // ── recalcForStatusChange ────────────────────────────────────────────

    @Test
    fun `recalcForStatusChange rebuilds L1 from baseline and cascades L2 L3`(): Unit =
        runBlocking {
            insertRecurringTask("h1", "dim_test")
            insertOccurrences("h1", 5, "completed")
            insertHabitMetrics("h1", 5)
            insertLifeDimension("dim_test", 1.0)

            val changeDate = today.minusDays(2)
            val rowsBefore = readHabitMetrics("h1")

            trigger().recalcForStatusChange("h1", changeDate)

            val habitRows = readHabitMetrics("h1")
            val rowsBeforeChange = habitRows.filter { it.dayKey < changeDate.toString() }
            val rowsFromChange = habitRows.filter { it.dayKey >= changeDate.toString() }

            assertThat(rowsBeforeChange).isNotEmpty()
            assertThat(rowsBeforeChange.map { it.dayKey })
                .containsExactlyElementsIn(rowsBefore.filter { it.dayKey < changeDate.toString() }.map { it.dayKey })
            assertThat(rowsFromChange).isNotEmpty()

            assertThat(readDimensionMetrics()).isNotEmpty()
            assertThat(readDayMetrics()).isNotEmpty()
        }

    @Test
    fun `recalcForStatusChange with non-recurring task leaves habit metrics untouched`(): Unit =
        runBlocking {
            insertNonRecurringTask("h1")
            insertHabitMetrics("h1", 3)

            val rowsBefore = readHabitMetrics("h1")

            trigger().recalcForStatusChange("h1", today)

            // Android's cascade returns without writes for non-recurring tasks, so desktop must not delete here either (parity).
            assertThat(readHabitMetrics("h1")).isEqualTo(rowsBefore)
        }

    @Test
    fun `recalcForStatusChange with non-existent task is a no-op`(): Unit =
        runBlocking {
            trigger().recalcForStatusChange("nonexistent", today)
        }

    @Test
    fun `recalcForStatusChange emits a score-change event for the changed date`(): Unit =
        runBlocking {
            insertRecurringTask("h1", "dim_test")
            insertOccurrences("h1", 5, "completed")
            insertHabitMetrics("h1", 5)
            insertLifeDimension("dim_test", 1.0)

            val bus = ScoreChangeEventBusJvm()
            val trigger = ScoreRollupTriggerJdbc(session, bus)
            val collected = mutableListOf<LocalDate>()
            val changeDate = today.minusDays(2)

            val collector = launch {
                bus.events.collect { date -> collected.add(date) }
            }
            try {
                // replay is 0 — give the collector a moment to start collecting
                delay(50)

                trigger.recalcForStatusChange("h1", changeDate)

                // Give the collector time to receive the event
                delay(50)
            } finally {
                collector.cancel()
            }

            assertThat(collected).containsExactly(changeDate)
        }

    // ── recalcForRuleChange ─────────────────────────────────────────────

    @Test
    fun `recalcForRuleChange rebuilds L1 from scratch and cascades L2 L3`(): Unit =
        runBlocking {
            insertRecurringTask("h1", "dim_test")
            insertOccurrences("h1", 5, "completed")
            insertHabitMetrics("h1", 5)
            insertLifeDimension("dim_test", 1.0)

            trigger().recalcForRuleChange("h1")

            assertThat(readHabitMetrics("h1")).isNotEmpty()
            assertThat(readDimensionMetrics()).isNotEmpty()
            assertThat(readDayMetrics()).isNotEmpty()
        }

    @Test
    fun `recalcForRuleChange with non-recurring task leaves habit metrics untouched`(): Unit =
        runBlocking {
            insertNonRecurringTask("h1")
            insertHabitMetrics("h1", 3)

            trigger().recalcForRuleChange("h1")

            // Parity: Android returns immediately for a task without a recurrence rule, so a
            // rule change on a non-recurring task must not delete its metrics.
            assertThat(readHabitMetrics("h1")).hasSize(3)
        }

    @Test
    fun `recalcForRuleChange with non-existent task is a no-op`(): Unit =
        runBlocking {
            trigger().recalcForRuleChange("nonexistent")
        }

    // ── recalcDayOnly ───────────────────────────────────────────────────

    @Test
    fun `recalcDayOnly cascades L2 L3 without touching L1`(): Unit =
        runBlocking {
            insertRecurringTask("h1", "dim_test")
            insertOccurrences("h1", 5, "completed")
            insertHabitMetrics("h1", 5)
            insertLifeDimension("dim_test", 1.0)

            val habitRowsBefore = readHabitMetrics("h1")

            trigger().recalcDayOnly(today.minusDays(2))

            assertThat(readHabitMetrics("h1")).isEqualTo(habitRowsBefore)
            assertThat(readDimensionMetrics()).isNotEmpty()
            assertThat(readDayMetrics()).isNotEmpty()
        }

    // ── Fixture helpers ─────────────────────────────────────────────────

    private fun insertRecurringTask(
        id: String,
        dimensionId: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO tasks (id, title, createdAt, updatedAt, recurrenceEnabled, recurrenceRule, dimension_id) VALUES (?, ?, ?, ?, 1, '1/1', ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, "Task $id")
            statement.setString(3, today.minusDays(10).toString())
            statement.setString(4, today.minusDays(10).toString())
            statement.setString(5, dimensionId)
            statement.executeUpdate()
        }
    }

    private fun insertNonRecurringTask(id: String) {
        connection.prepareStatement(
            "INSERT INTO tasks (id, title, createdAt, updatedAt, recurrenceEnabled, recurrenceRule) VALUES (?, ?, ?, ?, 0, NULL)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, "Task $id")
            statement.setString(3, today.minusDays(10).toString())
            statement.setString(4, today.minusDays(10).toString())
            statement.executeUpdate()
        }
    }

    private fun insertOccurrences(
        taskId: String,
        count: Int,
        status: String,
    ) {
        for (i in 1..count) {
            val dueDate = today.minusDays(i.toLong())
            connection.prepareStatement(
                "INSERT INTO task_occurrences (id, taskId, dueDate, status, createdAt) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "$taskId-$dueDate-$status")
                statement.setString(2, taskId)
                statement.setString(3, dueDate.toString())
                statement.setString(4, status)
                statement.setString(5, dueDate.toString())
                statement.executeUpdate()
            }
        }
    }

    private fun insertHabitMetrics(
        habitId: String,
        count: Int,
    ) {
        for (i in 1..count) {
            val dayKey = today.minusDays(i.toLong()).toString()
            connection.prepareStatement(
                "INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, habitId)
                statement.setString(2, dayKey)
                statement.setDouble(3, 1.0)
                statement.setDouble(4, 1.0)
                statement.setDouble(5, 0.0)
                statement.setInt(6, i)
                statement.setInt(7, i)
                statement.setInt(8, i)
                statement.executeUpdate()
            }
        }
    }

    private fun insertLifeDimension(
        id: String,
        weight: Double,
    ) {
        connection.prepareStatement(
            "INSERT INTO life_dimensions (id, weight) VALUES (?, ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setDouble(2, weight)
            statement.executeUpdate()
        }
    }

    private fun readHabitMetrics(habitId: String): List<HabitMetricEntity> {
        return connection.prepareStatement(
            "SELECT * FROM habit_metrics WHERE habitId = ? ORDER BY dayKey",
        ).use { statement ->
            statement.setString(1, habitId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            HabitMetricEntity(
                                habitId = resultSet.getString("habitId"),
                                dayKey = resultSet.getString("dayKey"),
                                score = resultSet.getDouble("score"),
                                runningAvg = resultSet.getDouble("runningAvg"),
                                progress = resultSet.getDouble("progress"),
                                streakPos = resultSet.getInt("streakPos"),
                                streakNet = resultSet.getInt("streakNet"),
                                posContinue = resultSet.getInt("posContinue"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun readDimensionMetrics(): List<DimensionMetricEntity> {
        return connection.prepareStatement(
            "SELECT * FROM dimension_metrics ORDER BY dayKey",
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            DimensionMetricEntity(
                                dimensionId = resultSet.getString("dimensionId"),
                                dayKey = resultSet.getString("dayKey"),
                                score = resultSet.getDouble("score"),
                                runningAvg = resultSet.getDouble("runningAvg"),
                                progress = resultSet.getDouble("progress"),
                                streakPos = resultSet.getInt("streakPos"),
                                streakNet = resultSet.getInt("streakNet"),
                                posContinue = resultSet.getInt("posContinue"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun readDayMetrics(): List<DayMetricEntity> {
        return connection.prepareStatement(
            "SELECT * FROM day_metrics ORDER BY dayKey",
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            DayMetricEntity(
                                dayKey = resultSet.getString("dayKey"),
                                dayScore = resultSet.getDouble("dayScore"),
                                runningAvg = resultSet.getDouble("runningAvg"),
                                progress = resultSet.getDouble("progress"),
                                streakPos = resultSet.getInt("streakPos"),
                                streakNet = resultSet.getInt("streakNet"),
                                posContinue = resultSet.getInt("posContinue"),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `archived sibling habit rows do not extend the dimension history`(): Unit =
        runBlocking {
            insertRecurringTask("hActive", "dim_test")
            insertOccurrences("hActive", 3, "completed")
            insertHabitMetrics("hActive", 3)
            insertLifeDimension("dim_test", 1.0)

            // Archived sibling: same dimension, older habit rows (today-8..today-6).
            // The recurring-task SQL and the cascade both filter status != archived,
            // so the archived sibling must not widen the rebuilt range.
            connection.prepareStatement(
                "INSERT INTO tasks (id, title, createdAt, updatedAt, recurrenceEnabled, recurrenceRule, dimension_id, status) VALUES (?, ?, ?, ?, 1, '1/1', ?, 'archived')",
            ).use { statement ->
                statement.setString(1, "hArch")
                statement.setString(2, "Task hArch")
                statement.setString(3, today.minusDays(10).toString())
                statement.setString(4, today.minusDays(10).toString())
                statement.setString(5, "dim_test")
                statement.executeUpdate()
            }
            for (i in 6..8) {
                val dayKey = today.minusDays(i.toLong()).toString()
                connection.prepareStatement(
                    "INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, "hArch")
                    statement.setString(2, dayKey)
                    statement.setDouble(3, 1.0)
                    statement.setDouble(4, 1.0)
                    statement.setDouble(5, 0.0)
                    statement.setInt(6, i)
                    statement.setInt(7, i)
                    statement.setInt(8, i)
                    statement.executeUpdate()
                }
            }

            trigger().recalcForStatusChange("hActive", today.minusDays(10))

            val dimensionRows = readDimensionMetrics()
            assertThat(dimensionRows).isNotEmpty()
            assertThat(dimensionRows.minOf { it.dayKey }).isEqualTo(today.minusDays(3).toString())
            assertThat(dimensionRows.map { it.dayKey }.none { it in (today.minusDays(10).toString()..today.minusDays(4).toString()) }).isTrue()
        }

    @Test
    fun `each dimension uses its own first due date for its L2 row`(): Unit =
        runBlocking {
            insertRecurringTask("hA", "dim_a")
            insertOccurrences("hA", 3, "completed")
            insertHabitMetrics("hA", 3)
            insertLifeDimension("dim_a", 1.0)

            insertRecurringTask("hB", "dim_b")
            insertOccurrences("hB", 6, "completed")
            insertHabitMetrics("hB", 6)
            insertLifeDimension("dim_b", 1.0)

            trigger().recalcForStatusChange("hA", today.minusDays(10))
            trigger().recalcForStatusChange("hB", today.minusDays(10))

            val dimensionRows = readDimensionMetrics()
            val dimARows = dimensionRows.filter { it.dimensionId == "dim_a" }
            val dimBRows = dimensionRows.filter { it.dimensionId == "dim_b" }

            assertThat(dimARows).isNotEmpty()
            assertThat(dimBRows).isNotEmpty()
            assertThat(dimARows.minOf { it.dayKey }).isEqualTo(today.minusDays(3).toString())
            assertThat(dimBRows.minOf { it.dayKey }).isEqualTo(today.minusDays(6).toString())
        }

    @Test
    fun `recalc keeps an unsorted dimension baseline consistent`(): Unit =
        runBlocking {
            insertRecurringTask("h1", "dim_test")
            insertOccurrences("h1", 5, "completed")
            insertHabitMetrics("h1", 5)
            insertLifeDimension("dim_test", 1.0)

            // Raw-insert baseline dimension_metrics rows in UNSORTED dayKey order with
            // distinct runningAvg values. Column list mirrors UPSERT_DIMENSION_METRIC_SQL.
            val baselineRows =
                listOf(
                    today.minusDays(5) to 0.5,
                    today.minusDays(3) to 0.9,
                    today.minusDays(4) to 0.7,
                )
            for ((day, runningAvg) in baselineRows) {
                connection.prepareStatement(
                    "INSERT INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, "dim_test")
                    statement.setString(2, day.toString())
                    statement.setDouble(3, runningAvg)
                    statement.setDouble(4, runningAvg)
                    statement.setDouble(5, 0.0)
                    statement.setInt(6, 0)
                    statement.setInt(7, 0)
                    statement.setInt(8, 0)
                    statement.executeUpdate()
                }
            }

            trigger().recalcForStatusChange("h1", today)

            val dimensionRows = readDimensionMetrics()
            val baselineDayKeys = baselineRows.map { it.first.toString() }
            val keptBaselineRows = dimensionRows.filter { it.dayKey in baselineDayKeys }

            // Baseline rows are kept untouched (count 3, exact dayKey set).
            assertThat(keptBaselineRows).hasSize(3)
            assertThat(keptBaselineRows.map { it.dayKey }).containsExactlyElementsIn(baselineDayKeys)
            // runningAvg values are preserved — the unsorted baseline did not corrupt them.
            val runningAvgByDay = keptBaselineRows.associate { it.dayKey to it.runningAvg }
            assertThat(runningAvgByDay[today.minusDays(5).toString()]).isEqualTo(0.5)
            assertThat(runningAvgByDay[today.minusDays(3).toString()]).isEqualTo(0.9)
            assertThat(runningAvgByDay[today.minusDays(4).toString()]).isEqualTo(0.7)
            // Rebuild ran: today's row exists.
            assertThat(dimensionRows.map { it.dayKey }).contains(today.toString())
        }
    }
