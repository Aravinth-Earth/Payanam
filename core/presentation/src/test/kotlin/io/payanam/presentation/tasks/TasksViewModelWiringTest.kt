//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import io.payanam.presentation.preferences.AppPreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The wiring half of the Tasks family: that each factory builds its ViewModel from the installed
 * [PayanamDataLayer], that the derived flows and the `init` observers actually run once something collects
 * them, and that a disposed ViewModel stops reacting.
 *
 * A factory is only reachable through the layer, so a test that never installs one never runs the factory
 * body nor the constructor it calls - which is exactly the state this file exists to leave.
 */
class TasksViewModelWiringTest {
    private val testDispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(testDispatcher)

    private val taskRepo = FakeTaskRepository()
    private val occurrenceRepo = FakeTaskOccurrenceRepository()
    private val rescheduleRepo = FakeTaskRescheduleRepository()
    private val habitMetricRepo = FakeHabitMetricRepository()
    private val scoreWindowRepo = FakeScoreWindowRepository()
    private val settingsRepo = FakeAppSettingsRepository()
    private val tagRepo = FakeTagRepository()
    private val reminders = FakeReminderScheduler()
    private val rollups = FakeScoreRollupTrigger()
    private val recurrence = FakeRecurrenceHandler()
    private val habitRecorder = FakeHabitTimeEntryRecorder()
    private val scoreChangeStream = FakeScoreChangeStream()
    private val perfTracer = FakePerformanceTracer()

    /** The score calculator port the constructor reads. */
    private val scoreCalc = FakeScoreCalculator()

    @Before
    fun setUp() {
        // A module's test classes share one JVM, so an assertion must not pass on a layer leaked by the
        // previous class - and this class must not leak into the next one either.
        LocalDataLayer.resetForTests()
        LocalDataLayer.install(
            object : PayanamDataLayer by UninstalledDataLayer {
                override val tasks: FakeTaskRepository = taskRepo
                override val tags: FakeTagRepository = tagRepo
                override val taskOccurrences: FakeTaskOccurrenceRepository = occurrenceRepo
                override val taskReschedules: FakeTaskRescheduleRepository = rescheduleRepo
                override val habitMetrics: FakeHabitMetricRepository = habitMetricRepo
                override val scoreWindows: FakeScoreWindowRepository = scoreWindowRepo
                override val appSettings: FakeAppSettingsRepository = settingsRepo
                override val reminderScheduler: FakeReminderScheduler = reminders
                override val scoreRollupTrigger: FakeScoreRollupTrigger = rollups
                override val recurrenceHandler: FakeRecurrenceHandler = recurrence
                override val habitTimeEntryRecorder: FakeHabitTimeEntryRecorder = habitRecorder
                override val scoreChanges: FakeScoreChangeStream = scoreChangeStream
                override val scoreCalculator: FakeScoreCalculator = scoreCalc
                override val tracer: FakePerformanceTracer = perfTracer
            },
        )
    }

    @After
    fun tearDown() {
        LocalDataLayer.resetForTests()
    }

    @Test
    fun `every factory builds its ViewModel from the installed layer`() =
        runTest(testDispatcher) {
            val tasks = tasksViewModel(scope = scope).apply { shapingDispatcher = testDispatcher }
            val detail = taskDetailViewModel(scope = scope)
            val add = addTaskViewModel(scope = scope)
            val edit = editTaskViewModel(scope = scope)
            val score = scoreDetailViewModel(scope = scope)
            advanceUntilIdle()

            assertThat(tasks.uiState.value.isLoading).isFalse()
            assertThat(detail.uiState.value).isNotNull()
            assertThat(add.tagSuggestions.value).isEmpty()
            assertThat(edit.uiState.value).isNotNull()
            assertThat(score.uiState.value).isNotNull()

            tasks.dispose()
            detail.dispose()
            add.dispose()
            edit.dispose()
            score.dispose()
        }

    @Test
    fun `the constructor restores the saved filter, sort and habit visibility settings`() =
        runTest(testDispatcher) {
            // Every one of these is a getSetting() read on the construction path, and each is the only place its
            // key is read - a fixture that seeds none of them leaves the whole restore path unexecuted.
            settingsRepo.emit(AppPreferenceKeys.KEY_TASK_FILTER_OPTION, "overdue")
            settingsRepo.emit(AppPreferenceKeys.KEY_TASK_SORT_OPTION, "score_desc")
            settingsRepo.emit(AppPreferenceKeys.KEY_HABIT_SORT_OPTION, "due_date")
            settingsRepo.emit(AppPreferenceKeys.KEY_SHOW_ARCHIVED_HABITS, "true")
            settingsRepo.emit(AppPreferenceKeys.KEY_SHOW_COMPLETED_HABITS, "false")
            settingsRepo.emit(AppPreferenceKeys.KEY_HIDE_ALL_MARKED_TODAY, "true")
            settingsRepo.emit(AppPreferenceKeys.KEY_DUE_TODAY_ONLY, "true")
            taskRepo.tasks.value =
                listOf(
                    task("overdue", status = "pending", dueDate = TASKS_TEST_TIME.minusDays(2)),
                    task("today", status = "pending", dueDate = TASKS_TEST_TIME),
                )

            val viewModel = tasksViewModel(scope = scope).apply { shapingDispatcher = testDispatcher }
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.currentFilter.key).isEqualTo("overdue")
            assertThat(viewModel.uiState.value.currentSort.key).isEqualTo("score_desc")
            assertThat(viewModel.uiState.value.showArchivedHabits).isTrue()
            assertThat(viewModel.uiState.value.showCompletedHabits).isFalse()
            assertThat(viewModel.uiState.value.hideAllMarkedToday).isTrue()
            assertThat(viewModel.uiState.value.dueTodayOnly).isTrue()
            assertThat(
                viewModel.uiState.value.filteredOneTimeTasks
                    .map { it.id },
            ).containsAtLeast("overdue", "today")
            viewModel.dispose()
        }

    @Test
    fun `an unreadable saved setting falls back instead of failing the constructor`() =
        runTest(testDispatcher) {
            settingsRepo.emit(AppPreferenceKeys.KEY_TASK_FILTER_OPTION, "not-a-filter")
            settingsRepo.emit(AppPreferenceKeys.KEY_TASK_SORT_OPTION, "not-a-sort")
            settingsRepo.emit(AppPreferenceKeys.KEY_HABIT_SORT_OPTION, "not-a-sort")
            settingsRepo.emit(AppPreferenceKeys.KEY_SHOW_ARCHIVED_HABITS, "not-a-boolean")

            val viewModel = tasksViewModel(scope = scope).apply { shapingDispatcher = testDispatcher }
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.currentFilter).isEqualTo(TaskFilter.TODAY)
            assertThat(viewModel.uiState.value.currentSort).isEqualTo(TaskSortOption.DUE_DATE_ASC)
            assertThat(viewModel.uiState.value.showArchivedHabits).isFalse()
            assertThat(viewModel.uiState.value.error).isNull()
            viewModel.dispose()
        }

    @Test
    fun `collecting the exposed state runs the derived flows`() =
        runTest(testDispatcher) {
            val viewModel = tasksViewModel(scope = scope).apply { shapingDispatcher = testDispatcher }
            val chrome = mutableListOf<TasksChromeUiState>()
            val habits = mutableListOf<HabitsTabUiState>()
            val dayMetrics = mutableListOf<Any>()
            backgroundScope.launch { viewModel.chromeUiState.collect { chrome += it } }
            backgroundScope.launch { viewModel.habitsTabUiState.collect { habits += it } }
            backgroundScope.launch { viewModel.dayMetricsState.collect { dayMetrics += it } }
            advanceUntilIdle()

            taskRepo.tasks.value =
                listOf(
                    task("t1", status = "pending", dueDate = TASKS_TEST_TIME),
                    habit("h1", rule = "1/1"),
                )
            habitMetricRepo.latestPerHabit = mapOf("h1" to habitL1(habitId = "h1", dayKey = TASKS_TEST_DAY.toString(), score = 4.0))
            advanceUntilIdle()

            assertThat(habits).isNotEmpty()
            assertThat(chrome).isNotEmpty()
            assertThat(
                viewModel.uiState.value.oneTimeTasks
                    .map { it.id },
            ).contains("t1")
            assertThat(dayMetrics).isNotEmpty()
            viewModel.dispose()
        }

    @Test
    fun `a score change emission and a repository change both reach the derived state`() =
        runTest(testDispatcher) {
            val viewModel = tasksViewModel(scope = scope).apply { shapingDispatcher = testDispatcher }
            backgroundScope.launch { viewModel.uiState.collect { } }
            backgroundScope.launch { viewModel.dayMetricsState.collect { } }
            advanceUntilIdle()

            viewModel.loadHabitDayMetrics()
            taskRepo.tasks.value = listOf(habit("h1", rule = "1/1"))
            habitMetricRepo.latestPerHabit = mapOf("h1" to habitL1(habitId = "h1", dayKey = TASKS_TEST_DAY.toString(), score = 5.0))
            scoreChangeStream.emit(TASKS_TEST_DAY)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.recurringTasks).hasSize(1)
            assertThat(viewModel.uiState.value.isLoading).isFalse()
            assertThat(viewModel.uiState.value.error).isNull()
            viewModel.dispose()
        }

    @Test
    fun `dispose then a repository change leaves the state untouched`() =
        runTest(testDispatcher) {
            val viewModel = tasksViewModel(scope = scope).apply { shapingDispatcher = testDispatcher }
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.tasks).isEmpty()

            viewModel.dispose()
            taskRepo.tasks.value = listOf(task("t1", status = "pending", dueDate = TASKS_TEST_TIME))
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.tasks).isEmpty()
        }

    @Test
    fun `a task created through the AddTask factory is visible to the Tasks factory`() =
        runTest(testDispatcher) {
            val add = addTaskViewModel(scope = scope)
            val tasks = tasksViewModel(scope = scope).apply { shapingDispatcher = testDispatcher }
            advanceUntilIdle()

            add.createTask(
                title = "Cross-factory task",
                dueDate = TASKS_TEST_TIME,
                dimensionId = "dim_work",
                dimensionLabel = "Work",
            )
            advanceUntilIdle()

            assertThat(taskRepo.tasks.value.map { it.title }).contains("Cross-factory task")
            assertThat(
                tasks.uiState.value.tasks
                    .map { it.title },
            ).contains("Cross-factory task")
            assertThat(reminders.scheduled).contains("created-1")
            add.dispose()
            tasks.dispose()
        }
}
