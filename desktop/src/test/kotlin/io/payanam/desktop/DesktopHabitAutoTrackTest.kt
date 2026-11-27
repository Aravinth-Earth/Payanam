//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.database.jvm.PayanamDatabaseOpener
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.desktop.database.DesktopPayanamDataLayer
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TimeEntry
import io.payanam.presentation.tasks.TasksViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The desktop auto-track path, end to end over a REAL encrypted database and the shared
 * [TasksViewModel] - the same entry points the UI uses, not the recorder called in isolation.
 *
 * The unit test next to the recorder pins its rules with fakes; this one proves the wiring those
 * rules depend on: the desktop data layer binds the recorder, `confirmCompletion` calls it, and the
 * write lands in the same session's time entries. A log-and-drop port would fail every assertion here
 * while the unit test stayed green, which is exactly the regression this covers.
 */
class DesktopHabitAutoTrackTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "desktop-habit-auto-track-test-passphrase"
    private lateinit var session: PayanamDatabaseSession
    private lateinit var layer: DesktopPayanamDataLayer
    private lateinit var scope: CoroutineScope
    private lateinit var uiWatch: Job
    private lateinit var viewModel: TasksViewModel

    private val today: LocalDate = LocalDate.now()
    private val completedAt: LocalDateTime = LocalDateTime.of(today, java.time.LocalTime.of(9, 30))
    private val canonical = DimensionTaxonomyCatalog.entries.first()

    @Before
    fun setUp() {
        val file = tempFolder.newFolder().toPath().resolve("habit-auto-track.db")
        val created = runBlocking { PayanamDatabaseOpener.create(file, passphrase) }
        session = (created as DatabaseOpenResult.Opened).session
        layer = DesktopPayanamDataLayer(session)
        // The real schema has tasks.dimensionId as a foreign key into life_dimensions, and a freshly
        // created database has no dimension rows until setup writes them. Seed the canonical dimension
        // first, exactly as the app's dimension step would, or the task insert fails the constraint.
        runBlocking {
            layer.lifeDimensions.replaceAllDimensions(
                listOf(
                    ConfiguredLifeDimension(
                        id = canonical.id,
                        key = "health",
                        label = canonical.fallbackLabel,
                        description = null,
                        colorHex = "#2E7D32",
                        iconKey = "health",
                        sortOrder = 1,
                        isActive = true,
                        weight = 1.0,
                    ),
                ),
            )
        }

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
        uiWatch = scope.launch { viewModel.uiState.collect {} }
    }

    @After
    fun tearDown() {
        uiWatch.cancel()
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        session.close()
    }

    private fun seedHabit(): String =
        runBlocking {
            layer.tasks
                .createTask(
                    TaskInput(
                        title = "Auto-tracked habit",
                        status = "pending",
                        recurrenceEnabled = true,
                        recurrenceRule = "1/1",
                        durationMinutes = 60,
                        dimensionId = canonical.id,
                    ),
                ).id
        }

    private fun enableAutoTrack(global: Boolean = true, dimension: Boolean? = null) {
        runBlocking {
            layer.appSettings.setSetting("auto_track_habit_time_global", global.toString())
            if (dimension != null) {
                layer.appSettings.setSetting("auto_track_dimension_${canonical.id}", dimension.toString())
            }
        }
    }

    /** The UI's own path: the checkmark opens the dialog, confirmCompletion writes and auto-tracks. */
    private fun completeThroughSharedViewModel(habitId: String) {
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
            viewModel.confirmCompletion(actualCompletedAt = completedAt, actualDurationMinutes = 20)
            deadline = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < deadline) {
                if (entriesForToday().any { it.taskId == habitId }) break
                delay(50)
            }
        }
    }

    private fun entriesForToday(): List<TimeEntry> = runBlocking { layer.timeEntries.getTimeEntriesForDate(today).first() }

    @Test
    fun `completing a habit writes the auto-tracked entry into the same session`() {
        enableAutoTrack()
        val habitId = seedHabit()

        completeThroughSharedViewModel(habitId)

        val entry = entriesForToday().single { it.taskId == habitId }
        assertThat(entry.dimensionId).isEqualTo(canonical.id)
        assertThat(entry.lifeIntentionCategory).isEqualTo(canonical.fallbackLabel)
        assertThat(entry.startedAt).isEqualTo(completedAt.minusMinutes(20))
        assertThat(entry.endedAt).isEqualTo(completedAt)
        assertThat(entry.focusNote).isEqualTo("Auto-tracked from habit completion")
    }

    @Test
    fun `the global switch off leaves the completion without a time entry`() {
        enableAutoTrack(global = false)
        val habitId = seedHabit()

        completeThroughSharedViewModel(habitId)

        assertThat(entriesForToday().filter { it.taskId == habitId }).isEmpty()
    }

    @Test
    fun `a dimension switched off under a global on leaves the completion without a time entry`() {
        enableAutoTrack(global = true, dimension = false)
        val habitId = seedHabit()

        completeThroughSharedViewModel(habitId)

        assertThat(entriesForToday().filter { it.taskId == habitId }).isEmpty()
    }
}
