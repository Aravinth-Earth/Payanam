//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.domain.model.ScoringConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Tests for [ScoreRollupStoreJdbc].
 *
 * Every read is tested against what was written through the matching write on a real
 * temp database, and every write is tested against a READ_ONLY session to prove the
 * refusal is explicit rather than a silent driver exception.
 *
 * A generated SQLCipher fixture in a per-class temp directory, never a real export.
 */
class ScoreRollupStoreJdbcTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "score-rollup-store-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var file: java.nio.file.Path

    @Before
    fun setUp() {
        file = tempFolder.newFolder().toPath().resolve("rollup.db")
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
                CREATE TABLE scoring_config (
                    id INTEGER PRIMARY KEY NOT NULL,
                    dimensionWeight REAL NOT NULL,
                    impactWeight REAL NOT NULL,
                    alignmentWeight REAL NOT NULL,
                    energyWeight REAL NOT NULL,
                    controlWeight REAL NOT NULL,
                    durationWeight REAL NOT NULL,
                    impactCritical REAL NOT NULL,
                    impactHigh REAL NOT NULL,
                    impactModerate REAL NOT NULL,
                    impactLow REAL NOT NULL,
                    impactMinimal REAL NOT NULL,
                    alignmentPerfect REAL NOT NULL,
                    alignmentStrong REAL NOT NULL,
                    alignmentModerate REAL NOT NULL,
                    alignmentWeak REAL NOT NULL,
                    alignmentNone REAL NOT NULL,
                    energyHigh REAL NOT NULL,
                    energyModerate REAL NOT NULL,
                    energyLow REAL NOT NULL,
                    controlFull REAL NOT NULL,
                    controlMostly REAL NOT NULL,
                    controlOffice REAL NOT NULL,
                    controlExternal REAL NOT NULL,
                    controlNone REAL NOT NULL,
                    dimensionCareerWork REAL NOT NULL,
                    dimensionHealthWellness REAL NOT NULL,
                    dimensionRelationships REAL NOT NULL,
                    dimensionPersonalGrowth REAL NOT NULL,
                    dimensionFinancial REAL NOT NULL,
                    dimensionSpiritual REAL NOT NULL,
                    dimensionRecreation REAL NOT NULL,
                    dimensionLearning REAL NOT NULL,
                    dimensionContribution REAL NOT NULL,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE app_settings (
                    `key` TEXT PRIMARY KEY NOT NULL,
                    value TEXT,
                    updatedAt TEXT NOT NULL
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

    private fun store() = ScoreRollupStoreJdbc(session)

    // ── Reads ──────────────────────────────────────────────────────────────

    @Test
    fun `getRecurringTasks returns only tasks with recurrenceEnabled = 1`(): Unit =
        runBlocking {
            insertTask("t1", recurrenceEnabled = 0)
            insertTask("t2", recurrenceEnabled = 1)
            insertTask("t3", recurrenceEnabled = 1)

            val tasks = store().getRecurringTasks()

            assertThat(tasks.map { it.id }).containsExactly("t2", "t3")
        }

    @Test
    fun `getOccurrencesForTask returns occurrences for that task ordered by dueDate`(): Unit =
        runBlocking {
            insertTask("t1")
            insertOccurrence("o1", "t1", "2026-03-01")
            insertOccurrence("o2", "t1", "2026-01-01")
            insertOccurrence("o3", "t1", "2026-02-01")
            insertOccurrence("o4", "t2", "2026-01-15")

            val occurrences = store().getOccurrencesForTask("t1")

            assertThat(occurrences.map { it.id }).containsExactly("o2", "o3", "o1").inOrder()
        }

    @Test
    fun `countAllOccurrences returns the total number of occurrence rows`(): Unit =
        runBlocking {
            insertTask("t1")
            insertTask("t2")
            insertOccurrence("o1", "t1", "2026-01-01")
            insertOccurrence("o2", "t1", "2026-02-01")
            insertOccurrence("o3", "t2", "2026-01-15")

            assertThat(store().countAllOccurrences()).isEqualTo(3)
        }

    @Test
    fun `getHabitMetricsForHabit returns rows for that habit ordered by dayKey`(): Unit =
        runBlocking {
            store().upsertHabitMetrics(
                listOf(
                    HabitMetricEntity("h1", "2026-03-01", 1.0, 1.0, 1.0, 1, 1, 1),
                    HabitMetricEntity("h1", "2026-01-01", 0.0, 0.0, 0.0, 0, 0, 0),
                    HabitMetricEntity("h2", "2026-02-01", 1.0, 1.0, 1.0, 1, 1, 1),
                ),
            )

            val rows = store().getHabitMetricsForHabit("h1")

            assertThat(rows.map { it.dayKey }).containsExactly("2026-01-01", "2026-03-01").inOrder()
        }

    @Test
    fun `getDimensionMetricsForDimension returns rows for that dimension ordered by dayKey`(): Unit =
        runBlocking {
            store().upsertDimensionMetrics(
                listOf(
                    DimensionMetricEntity("d1", "2026-03-01", 1.0, 1.0, 1.0, 1, 1, 1),
                    DimensionMetricEntity("d1", "2026-01-01", 0.0, 0.0, 0.0, 0, 0, 0),
                    DimensionMetricEntity("d2", "2026-02-01", 1.0, 1.0, 1.0, 1, 1, 1),
                ),
            )

            val rows = store().getDimensionMetricsForDimension("d1")

            assertThat(rows.map { it.dayKey }).containsExactly("2026-01-01", "2026-03-01").inOrder()
        }

    @Test
    fun `getDayMetrics returns all day rows ordered by dayKey`(): Unit =
        runBlocking {
            store().upsertDayMetrics(
                listOf(
                    DayMetricEntity("2026-03-01", 1.0, 1.0, 1.0, 1, 1, 1),
                    DayMetricEntity("2026-01-01", 0.0, 0.0, 0.0, 0, 0, 0),
                ),
            )

            val rows = store().getDayMetrics()

            assertThat(rows.map { it.dayKey }).containsExactly("2026-01-01", "2026-03-01").inOrder()
        }

    @Test
    fun `getDimensionWeights returns defaults when no scoring_config row exists`(): Unit =
        runBlocking {
            val weights = store().getDimensionWeights()

            val defaults = ScoringConfig.defaults().dimensionWeightsById
            assertThat(weights).isEqualTo(defaults)
        }

    @Test
    fun `getDimensionWeights reads from scoring_config row`(): Unit =
        runBlocking {
            insertScoringConfig(dimensionCareerWork = 0.42, dimensionHealthWellness = 0.99)

            val weights = store().getDimensionWeights()

            assertThat(weights["dim_career_work"]).isEqualTo(0.42)
            assertThat(weights["dim_health_wellness"]).isEqualTo(0.99)
        }

    @Test
    fun `getSetting returns null for an unset key and the value for a set one`(): Unit =
        runBlocking {
            assertThat(store().getSetting("missing_key")).isNull()

            store().insertSetting("my_key", "my_value")

            assertThat(store().getSetting("my_key")).isEqualTo("my_value")
        }

    // ── Writes ─────────────────────────────────────────────────────────────

    @Test
    fun `upsertHabitMetrics writes rows that getHabitMetricsForHabit reads back`(): Unit =
        runBlocking {
            val rows =
                listOf(
                    HabitMetricEntity("h1", "2026-01-01", 1.0, 1.0, 1.0, 1, 1, 1),
                    HabitMetricEntity("h1", "2026-02-01", 0.5, 0.75, -0.5, 0, 0, 0),
                )

            store().upsertHabitMetrics(rows)

            val read = store().getHabitMetricsForHabit("h1")
            assertThat(read).hasSize(2)
            assertThat(read[0].score).isEqualTo(1.0)
            assertThat(read[1].score).isEqualTo(0.5)
        }

    @Test
    fun `upsertDimensionMetrics writes rows that getDimensionMetricsForDimension reads back`(): Unit =
        runBlocking {
            val rows =
                listOf(
                    DimensionMetricEntity("d1", "2026-01-01", 0.8, 0.8, 0.8, 2, 2, 2),
                    DimensionMetricEntity("d1", "2026-02-01", 0.6, 0.7, -0.2, 1, 1, 1),
                )

            store().upsertDimensionMetrics(rows)

            val read = store().getDimensionMetricsForDimension("d1")
            assertThat(read).hasSize(2)
            assertThat(read[0].score).isEqualTo(0.8)
            assertThat(read[1].score).isEqualTo(0.6)
        }

    @Test
    fun `upsertDayMetrics writes rows that getDayMetrics reads back`(): Unit =
        runBlocking {
            val rows =
                listOf(
                    DayMetricEntity("2026-01-01", 0.9, 0.9, 0.9, 3, 3, 3),
                    DayMetricEntity("2026-02-01", 0.7, 0.8, -0.2, 2, 2, 2),
                )

            store().upsertDayMetrics(rows)

            val read = store().getDayMetrics()
            assertThat(read).hasSize(2)
            assertThat(read[0].dayScore).isEqualTo(0.9)
            assertThat(read[1].dayScore).isEqualTo(0.7)
        }

    @Test
    fun `updateRecurrenceRule updates the rule on the matching task`(): Unit =
        runBlocking {
            insertTask("t1", recurrenceEnabled = 1, recurrenceRule = "FREQ=DAILY;INTERVAL=1")

            store().updateRecurrenceRule("t1", "CONFIG:freq=daily;interval=1")

            val tasks = store().getRecurringTasks()
            val task = tasks.single { it.id == "t1" }
            assertThat(task.recurrenceRule).isEqualTo("CONFIG:freq=daily;interval=1")
        }

    @Test
    fun `insertSetting writes a row that getSetting reads back`(): Unit =
        runBlocking {
            store().insertSetting("guard_key", "1")

            assertThat(store().getSetting("guard_key")).isEqualTo("1")
        }

    // ── Read-only session refusal ──────────────────────────────────────────

    @Test
    fun `upsertHabitMetrics throws on a READ_ONLY session`(): Unit =
        runBlocking {
            val readOnlySession = openReadOnlySession()
            try {
                val readOnlyStore = ScoreRollupStoreJdbc(readOnlySession)
                val thrown =
                    runCatching {
                        readOnlyStore.upsertHabitMetrics(
                            listOf(HabitMetricEntity("h1", "2026-01-01", 1.0, 1.0, 1.0, 1, 1, 1)),
                        )
                    }.exceptionOrNull()

                assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
                assertThat(thrown!!.message).contains("READ_ONLY")
            } finally {
                readOnlySession.close()
            }
        }

    @Test
    fun `upsertDimensionMetrics throws on a READ_ONLY session`(): Unit =
        runBlocking {
            val readOnlySession = openReadOnlySession()
            try {
                val readOnlyStore = ScoreRollupStoreJdbc(readOnlySession)
                val thrown =
                    runCatching {
                        readOnlyStore.upsertDimensionMetrics(
                            listOf(DimensionMetricEntity("d1", "2026-01-01", 1.0, 1.0, 1.0, 1, 1, 1)),
                        )
                    }.exceptionOrNull()

                assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
                assertThat(thrown!!.message).contains("READ_ONLY")
            } finally {
                readOnlySession.close()
            }
        }

    @Test
    fun `upsertDayMetrics throws on a READ_ONLY session`(): Unit =
        runBlocking {
            val readOnlySession = openReadOnlySession()
            try {
                val readOnlyStore = ScoreRollupStoreJdbc(readOnlySession)
                val thrown =
                    runCatching {
                        readOnlyStore.upsertDayMetrics(
                            listOf(DayMetricEntity("2026-01-01", 1.0, 1.0, 1.0, 1, 1, 1)),
                        )
                    }.exceptionOrNull()

                assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
                assertThat(thrown!!.message).contains("READ_ONLY")
            } finally {
                readOnlySession.close()
            }
        }

    @Test
    fun `updateRecurrenceRule throws on a READ_ONLY session`(): Unit =
        runBlocking {
            val readOnlySession = openReadOnlySession()
            try {
                val readOnlyStore = ScoreRollupStoreJdbc(readOnlySession)
                val thrown =
                    runCatching { readOnlyStore.updateRecurrenceRule("t1", "CONFIG:freq=daily") }
                        .exceptionOrNull()

                assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
                assertThat(thrown!!.message).contains("READ_ONLY")
            } finally {
                readOnlySession.close()
            }
        }

    @Test
    fun `insertSetting throws on a READ_ONLY session`(): Unit =
        runBlocking {
            val readOnlySession = openReadOnlySession()
            try {
                val readOnlyStore = ScoreRollupStoreJdbc(readOnlySession)
                val thrown =
                    runCatching { readOnlyStore.insertSetting("key", "value") }
                        .exceptionOrNull()

                assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
                assertThat(thrown!!.message).contains("READ_ONLY")
            } finally {
                readOnlySession.close()
            }
        }

    @Test
    fun `reads succeed on a READ_ONLY session`(): Unit =
        runBlocking {
            // Write some data through the read-write session first.
            store().upsertHabitMetrics(
                listOf(HabitMetricEntity("h1", "2026-01-01", 1.0, 1.0, 1.0, 1, 1, 1)),
            )
            store().insertSetting("key", "value")

            val readOnlySession = openReadOnlySession()
            try {
                val readOnlyStore = ScoreRollupStoreJdbc(readOnlySession)

                assertThat(readOnlyStore.getHabitMetricsForHabit("h1")).hasSize(1)
                assertThat(readOnlyStore.getSetting("key")).isEqualTo("value")
                assertThat(readOnlyStore.getDayMetrics()).isEmpty()
                assertThat(readOnlyStore.countAllOccurrences()).isEqualTo(0)
            } finally {
                readOnlySession.close()
            }
        }

    // ── Fixture helpers ────────────────────────────────────────────────────

    private fun openReadOnlySession(): PayanamDatabaseSession {
        val readOnlyConnection =
            DriverManager.getConnection(
                "jdbc:sqlcipher:${file.toAbsolutePath()}",
                Properties().apply { setProperty("key", passphrase) },
            )
        readOnlyConnection.createStatement().use { it.execute("PRAGMA query_only = ON") }
        return PayanamDatabaseSession(readOnlyConnection, 21, DatabaseOpenMode.READ_ONLY)
    }

    private fun insertTask(
        id: String,
        recurrenceEnabled: Int = 0,
        recurrenceRule: String? = null,
    ) {
        connection.prepareStatement(
            "INSERT INTO tasks (id, title, createdAt, updatedAt, recurrenceEnabled, recurrenceRule) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, "Task $id")
            statement.setString(3, "2026-01-01T00:00:00")
            statement.setString(4, "2026-01-01T00:00:00")
            statement.setInt(5, recurrenceEnabled)
            statement.setString(6, recurrenceRule)
            statement.executeUpdate()
        }
    }

    private fun insertOccurrence(
        id: String,
        taskId: String,
        dueDate: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO task_occurrences (id, taskId, dueDate, status, createdAt) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, taskId)
            statement.setString(3, dueDate)
            statement.setString(4, "pending")
            statement.setString(5, "2026-01-01T00:00:00")
            statement.executeUpdate()
        }
    }

    private fun insertScoringConfig(
        dimensionCareerWork: Double = 0.8,
        dimensionHealthWellness: Double = 0.9,
    ) {
        connection.prepareStatement(
            "INSERT INTO scoring_config (id, dimensionWeight, impactWeight, alignmentWeight, energyWeight, controlWeight, durationWeight, impactCritical, impactHigh, impactModerate, impactLow, impactMinimal, alignmentPerfect, alignmentStrong, alignmentModerate, alignmentWeak, alignmentNone, energyHigh, energyModerate, energyLow, controlFull, controlMostly, controlOffice, controlExternal, controlNone, dimensionCareerWork, dimensionHealthWellness, dimensionRelationships, dimensionPersonalGrowth, dimensionFinancial, dimensionSpiritual, dimensionRecreation, dimensionLearning, dimensionContribution, updatedAt) VALUES (1, 2.0, 1.5, 1.3, 1.0, 1.2, 0.8, 1.0, 0.85, 0.6, 0.35, 0.15, 1.0, 0.8, 0.5, 0.25, 0.1, 1.0, 0.7, 0.4, 1.0, 0.85, 0.6, 0.35, 0.1, ?, ?, 0.85, 0.8, 0.75, 0.6, 0.7, 0.8, 0.65, '2026-01-01T00:00:00')",
        ).use { statement ->
            statement.setDouble(1, dimensionCareerWork)
            statement.setDouble(2, dimensionHealthWellness)
            statement.executeUpdate()
        }
    }
}
