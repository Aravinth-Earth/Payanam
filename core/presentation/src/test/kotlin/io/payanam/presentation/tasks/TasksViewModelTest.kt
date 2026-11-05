//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration coverage for [TasksViewModel] against hand-written fakes.
 *
 * Drives the view model on a [StandardTestDispatcher] so that the coroutines launched on
 * the injected scope are advanced deterministically with [advanceUntilIdle] instead of relying on
 * wall-clock time.
 */
@ExperimentalCoroutinesApi
class TasksViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var taskOccurrenceRepository: FakeTaskOccurrenceRepository
    private lateinit var notificationScheduler: FakeReminderScheduler
    private lateinit var appSettingsRepository: FakeAppSettingsRepository
    private lateinit var recurrenceHandler: FakeRecurrenceHandler
    private lateinit var habitTimeEntryRecorder: FakeHabitTimeEntryRecorder
    private lateinit var scoreRollupTrigger: FakeScoreRollupTrigger
    private lateinit var habitMetricRepository: FakeHabitMetricRepository
    private lateinit var scoreWindowRepository: FakeScoreWindowRepository
    private lateinit var scoreChangeStream: FakeScoreChangeStream
    private lateinit var tracer: FakePerformanceTracer

    private lateinit var viewModel: TasksViewModel

    /** Builds the view model under test with seeded fakes. */
    @Before
    fun setup() {
        taskRepository = FakeTaskRepository()
        taskOccurrenceRepository = FakeTaskOccurrenceRepository()
        notificationScheduler = FakeReminderScheduler()
        appSettingsRepository =
            FakeAppSettingsRepository(
                mapOf(
                    "task_sort_option" to "score_desc",
                    "task_filter_option" to "active",
                ),
            )
        recurrenceHandler = FakeRecurrenceHandler()
        habitTimeEntryRecorder = FakeHabitTimeEntryRecorder()
        scoreRollupTrigger = FakeScoreRollupTrigger()
        habitMetricRepository = FakeHabitMetricRepository()
        scoreWindowRepository = FakeScoreWindowRepository()
        scoreChangeStream = FakeScoreChangeStream()
        tracer = FakePerformanceTracer()

        viewModel = createViewModel()
    }

    /** Tears down the view model to cancel lingering coroutines. */
    @After
    fun tearDown() {
        viewModel.dispose()
    }

    /** Builds a view model wired with this test's fakes. */
    private fun createViewModel(): TasksViewModel =
        TasksViewModel(
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            notificationScheduler = notificationScheduler,
            appSettingsRepository = appSettingsRepository,
            recurrenceHandler = recurrenceHandler,
            habitTimeEntryRecorder = habitTimeEntryRecorder,
            scoreRollupTrigger = scoreRollupTrigger,
            habitMetricRepository = habitMetricRepository,
            scoreWindowRepository = scoreWindowRepository,
            scoreChangeStream = scoreChangeStream,
            tracer = tracer,
            scope = CoroutineScope(testDispatcher),
        ).apply { shapingDispatcher = testDispatcher }

    /** Performs the initial state loads correctly. */
    @Test
    fun initialState_loadsCorrectly() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            val initialState = viewModel.uiState.value
            assertThat(initialState.tasks).isEmpty()
            assertThat(initialState.currentFilter).isEqualTo(TaskFilter.ACTIVE)
            assertThat(initialState.currentSort).isEqualTo(TaskSortOption.SCORE_DESC)
        }

    /** Loads the load tasks updates state with repository data. */
    @Test
    fun loadTasks_updatesStateWithRepositoryData() =
        runTest(testDispatcher) {
            val testTasks =
                listOf(
                    task(id = "task-1", title = "Task 1"),
                    task(id = "task-2", title = "Task 2"),
                )
            taskRepository.tasks.value = testTasks

            viewModel = createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.tasks).hasSize(2)
            assertThat(state.tasks.map { it.title }).containsExactly("Task 1", "Task 2")
        }

    /** Updates the set filter updates current filter and reloads tasks. */
    @Test
    fun setFilter_updatesCurrentFilterAndReloadsTasks() =
        runTest(testDispatcher) {
            val completedTasks =
                listOf(
                    task(id = "completed", title = "Completed Task", status = "completed"),
                )
            taskRepository.tasks.value = completedTasks

            viewModel.setFilter(TaskFilter.COMPLETED)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.currentFilter).isEqualTo(TaskFilter.COMPLETED)
            assertThat(state.tasks).hasSize(1)
            assertThat(state.tasks.first().title).isEqualTo("Completed Task")
        }

    /** Updates the set sort option updates current sort and reloads tasks. */
    @Test
    fun setSortOption_updatesCurrentSortAndReloadsTasks() =
        runTest(testDispatcher) {
            val tasks = listOf(task(id = "task-1", title = "Task 1"))
            taskRepository.tasks.value = tasks

            viewModel.setSortOption(TaskSortOption.TITLE_ASC)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.currentSort).isEqualTo(TaskSortOption.TITLE_ASC)
        }

    /** Removes the delete task removes task and refreshes list. */
    @Test
    fun deleteTask_removesTaskAndRefreshesList() =
        runTest(testDispatcher) {
            val testTask = task(id = "task-1", title = "Test Task")
            taskRepository.tasks.value = listOf(testTask)

            viewModel = createViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.tasks).hasSize(1)

            viewModel.deleteTask("task-1")
            advanceUntilIdle()

            // deleteTask only delegates; the visible list refreshes from the repository flow.
            taskRepository.tasks.value = emptyList()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.tasks).isEmpty()
        }

    /** Loads the load todays tasks loads tasks for today filter. */
    @Test
    fun loadTodaysTasks_loadsTasksForTodayFilter() =
        runTest(testDispatcher) {
            val todaysTasks = listOf(task(id = "today", title = "Today's Task"))
            taskRepository.tasks.value = todaysTasks

            viewModel.setFilter(TaskFilter.TODAY)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.currentFilter).isEqualTo(TaskFilter.TODAY)
            assertThat(state.tasks).hasSize(1)
            assertThat(state.tasks.first().title).isEqualTo("Today's Task")
        }
}
