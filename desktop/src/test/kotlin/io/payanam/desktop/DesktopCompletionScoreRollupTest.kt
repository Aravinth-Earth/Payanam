//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseOpener
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.domain.model.TaskInput
import io.payanam.presentation.tasks.TasksViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
 * Verifies that completing a task through the shared [TasksViewModel] on desktop
 * causes the habit's stored metrics to change in the same session — the same
 * entry points Android uses, not a re-implementation.
 *
 * The UI completion path is the shared TasksViewModel: its factory at
 * TasksViewModel.kt:1428-1445 constructs it by mapping EVERY dependency from
 * LocalDataLayer.current.*. Methods that already call scoreRollupTrigger.recalcForStatusChange:
 * toggleCheckmark :726, confirmCompletion :789, updateCheckmark :856, completeTask :1331 —
 * the SAME entry points Android uses (Android binds cascade at DatabaseModule.kt:115).
 *
 * The desktop layer already exposes the JDBC trigger: DesktopPayanamDataLayer.kt:117
 * `override val scoreRollupTrigger = ScoreRollupTriggerJdbc(session)`.
 *
 * Shared compute via 636ed332, no arithmetic re-implemented.
 */
class DesktopCompletionScoreRollupTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "desktop-completion-score-rollup-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession
    private lateinit var file: java.nio.file.Path
    private lateinit var layer: DesktopPayanamDataLayer
    private lateinit var habitId: String
    private lateinit var uiWatch: Job
    private lateinit var viewModel: TasksViewModel

    /**
     * The scope the ViewModel runs on — a FIELD, not a local.
     *
     * WHY: the ViewModel's init collectors never complete on their own, so tearDown has to be able
     * to cancel AND join them. A scope that only existed inside setUp could be cancelled but never
     * waited for, and a collector still inside the session when the session closes turns into an
     * exception that escapes into the JVM-global collector.
     */
    private lateinit var scope: CoroutineScope

    private val today: LocalDate = LocalDate.now()
    private val yesterday: LocalDate = today.minusDays(1)

    @Before
    fun setUp() {
        file = tempFolder.newFolder().toPath().resolve("completion-rollup.db")
        val properties = Properties().apply { setProperty("key", passphrase) }

        // The session constructor is internal to :core:database-jvm, so the session comes from the
        // SAME opener the app uses: create() builds the REAL schema (critical tables + user_version)
        // - a hand-rolled minimal DDL file is refused by the opener's boot checks, and the layer
        // needs a session over a database the app itself would accept.
        val createResult = runBlocking { PayanamDatabaseOpener.create(file, passphrase) }
        session = (createResult as DatabaseOpenResult.Opened).session

        // A second handle on the same file for the raw INSERT/SELECT the seeds and assertions use.
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${file.toAbsolutePath()}", properties)
        // This handle gets the opener's own busy_timeout, because it is the SAME convention every
        // connection in :core:database-jvm gets (PayanamDatabaseOpener.applyBusyTimeout, 5000 ms).
        // Without it this handle has no wait at all: a lock held for a moment by the session's
        // writer is a hard "database is locked" instead of a wait — a failure that depends on WHEN
        // the read lands, not on what the code does.
        connection.createStatement().use { it.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS") }
        layer = DesktopPayanamDataLayer(session)

        // Construct TasksViewModel by hand using the factory mapping at TasksViewModel.kt:1428-1445
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        viewModel =
            TasksViewModel(
                taskRepository = layer.tasks,
                taskOccurrenceRepository = layer.taskOccurrences,
                notificationScheduler = layer.reminderScheduler,
                appSettingsRepository = layer.appSettings,
                recurrenceHandler = layer.recurrenceHandler,
                habitTimeEntryRecorder = layer.habitTimeEntryRecorder,
                scoreRollupTrigger = layer.scoreRollupTrigger,
                habitMetricRepository = layer.habitMetrics,
                scoreWindowRepository = layer.scoreWindows,
                scoreChangeStream = layer.scoreChanges,
                tracer = layer.tracer,
                scope = scope,
            )

        // stateIn(WhileSubscribed): uiState stays a placeholder until collected (shared-VM test trap)
        uiWatch = scope.launch { viewModel.uiState.collect {} }

        // Seed: recurring task (habit) - created through the REPOSITORY (the real schema the
        // opener built has NOT NULL columns without defaults, so raw SQL here drifts).
        habitId = insertRecurringTask()
        // Seed: habit_metrics for yesterday
        insertHabitMetrics(habitId, yesterday)
    }

    @After
    fun tearDown() {
        // WHY THIS ORDER: uiWatch runs ON the scope, so it is cancelled first; then the scope is
        // cancelled AND JOINED, so no collector is still inside the session; only then is the
        // session closed.
        //
        // The join is what makes this safe. The ViewModel's init collectors (TasksViewModel.kt:146,
        // :158, :181) never complete on their own, and closing the session out from under one
        // makes it throw "PayanamDatabaseSession is closed" - with no CoroutineExceptionHandler on
        // this scope, that failure reaches the JVM-global exception collector and is re-thrown as
        // UncaughtExceptionsBeforeTest in whichever LATER test opens a TestScope. That is what made
        // the victim move from run to run. Cancelling without joining would leave the same window
        // open; closing the session first would guarantee the escape.
        uiWatch.cancel()
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        session.close()
        connection.close()
    }

    /**
     * THE property: completing a task via the UI's checkmark (toggleCheckmark) causes
     * the habit's stored metrics to change in the same session.
     *
     * The UI's checkmark click calls `viewModel.toggleCheckmark(taskId, checkmark.date)`
     * at TasksScreen.kt:431. This is the same entry point Android uses.
     */
    @Test
    fun `completing a task via toggleCheckmark rebuilds habit_metrics for today`() {
        // BEFORE
        val before = readHabitMetrics(habitId)
        assertThat(before).isNotEmpty()
        assertThat(before.map { it.dayKey }).doesNotContain(today.toString())

        // ACT: the UI-equivalent path, same entry points the screen uses:
        // TasksScreen.kt:431 toggleCheckmark; for a RECURRING task it opens the completion
        // dialog (no write yet), then TasksScreen.kt:493 confirmCompletion records the
        // occurrence and triggers the roll-up. The checkmark state arrives via repository
        // flows polling at 1s, so wait for it before clicking.
        runBlocking {
            val waitMs = 15_000L
            var deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                val marks = viewModel.uiState.value.taskCheckmarks[habitId]
                if (marks != null && marks.any { it.date == today }) break
                delay(50)
            }
            viewModel.toggleCheckmark(habitId, today)
            deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                if (viewModel.uiState.value.showCompletionDialog) break
                delay(50)
            }
            viewModel.confirmCompletion(actualCompletedAt = null, actualDurationMinutes = null)
            deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                val rows = readHabitMetrics(habitId)
                if (rows.any { it.dayKey == today.toString() }) break
                delay(50)
            }
        }

        // AFTER - stepwise, so the FIRST broken link names itself:
        // (1) did the toggle's occurrence row land at all?
        val occurrenceStatus = readOccurrenceStatus(today.toString())
        assertThat(occurrenceStatus).isEqualTo("completed")
        // (2) did the trigger then write today's habit_metrics row?
        val after = readHabitMetrics(habitId)
        assertThat(after.map { it.dayKey }).contains(today.toString())
        // Yesterday row still present
        assertThat(after.map { it.dayKey }).contains(yesterday.toString())
    }

    private fun insertRecurringTask(): String =
        runBlocking {
            layer.tasks
                .createTask(
                    TaskInput(
                        title = "Recurring habit",
                        status = "pending",
                        recurrenceEnabled = true,
                        recurrenceRule = "1/1",
                        durationMinutes = 60,
                    ),
                ).id
        }

    private fun insertHabitMetrics(
        habitId: String,
        date: LocalDate,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, habitId)
                statement.setString(2, date.toString())
                statement.setDouble(3, 1.0)
                statement.setDouble(4, 1.0)
                statement.setDouble(5, 0.0)
                statement.setInt(6, 1)
                statement.setInt(7, 1)
                statement.setInt(8, 1)
                statement.executeUpdate()
            }
    }

    /** Occurrence status for (habit, date) - diagnosis probe for the stepwise assertion. */
    private fun readOccurrenceStatus(date: String): String? {
        connection
            .prepareStatement(
                "SELECT status FROM task_occurrences WHERE taskId = ? AND dueDate LIKE ? ORDER BY createdAt DESC LIMIT 1",
            ).use { statement ->
                statement.setString(1, habitId)
                statement.setString(2, "$date%")
                val rs = statement.executeQuery()
                return if (rs.next()) rs.getString(1) else null
            }
    }

    /**
     * Reads the habit's stored metric rows through the SESSION, not the second handle.
     *
     * WHY: the cascade writer (ScoreRollupTriggerJdbc.recalcForStatusChange) rebuilds these rows
     * while holding the session's connection, and this read used to run on the SECOND handle - a
     * different file handle, so a cascade write in flight turned the read into
     * "java.sql.SQLException: database is locked" depending on WHEN the read landed.
     *
     * [PayanamDatabaseSession.withSerializedAccess] is the path production uses: the writer's
     * transaction and this read are serialised by the session's own lock, and the writer COMMITS
     * before it releases that lock - so the rows read here are the committed rows the cascade
     * wrote, not a partial view of an open transaction. The assertion is unchanged: it still reads
     * the real rows the cascade wrote.
     */
    private fun readHabitMetrics(habitId: String): List<HabitMetricEntity> =
        session.withSerializedAccess { sessionConnection ->
            sessionConnection
                .prepareStatement(
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

    private companion object {
        /** The same wait the opener gives every connection it opens — see PayanamDatabaseOpener. */
        const val BUSY_TIMEOUT_MS = 5000
    }
}
