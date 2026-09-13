//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.backfill.ScoreRollupCascadeService
import io.payanam.database.event.ScoreChangeEventBus
import io.payanam.domain.model.Task
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.HabitMetricRepository
import io.payanam.domain.repository.ScoreWindowRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.usecase.CreateTimeEntryForHabitUseCase
import io.payanam.usecase.RecurrenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * Integration coverage for [TasksViewModel] against mocked repositories.
 *
 * Drives the view model on a [StandardTestDispatcher] so that the coroutines launched on
 * `viewModelScope` are advanced deterministically with [advanceUntilIdle] instead of relying on
 * wall-clock time.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class TasksViewModelIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Mock
    private lateinit var taskRepository: TaskRepository

    @Mock
    private lateinit var taskOccurrenceRepository: TaskOccurrenceRepository

    @Mock
    private lateinit var notificationScheduler: NotificationScheduler

    @Mock
    private lateinit var appSettingsRepository: AppSettingsRepository

    @Mock
    private lateinit var recurrenceManager: RecurrenceManager

    @Mock
    private lateinit var createTimeEntryForHabitUseCase: CreateTimeEntryForHabitUseCase

    @Mock
    private lateinit var scoreRollupCascadeService: ScoreRollupCascadeService

    @Mock
    private lateinit var habitMetricRepository: HabitMetricRepository

    @Mock
    private lateinit var scoreWindowRepository: ScoreWindowRepository

    @Mock
    private lateinit var scoreChangeEventBus: ScoreChangeEventBus

    private lateinit var viewModel: TasksViewModel

    @Before
    /**
     * Installs the test dispatcher, stubs the default (empty) repository results and builds the
     * view model under test.
     */
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        UnifiedLogger.initialize(ApplicationProvider.getApplicationContext<Context>(), "test", 0)

        // Default mock behaviours: no tasks, default filter and sort.
        `when`(taskRepository.getAllTasks()).thenReturn(flowOf(emptyList()))
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(emptyList()))
        `when`(taskRepository.getTasksByStatus("pending")).thenReturn(flowOf(emptyList()))
        runBlocking {
            runBlocking {
            `when`(habitMetricRepository.getLatestPerHabit()).thenReturn(emptyMap())
        }
        `when`(scoreChangeEventBus.events).thenReturn(MutableSharedFlow())
        `when`(appSettingsRepository.getSetting("task_sort_option")).thenReturn("score_desc")
            `when`(appSettingsRepository.getSetting("task_filter_option")).thenReturn("active")
        }

        viewModel = createViewModel()
    }

    @After
    /**
     * Restores the Android main dispatcher.
     */
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Builds a view model wired with this test's mocks. */
    private fun createViewModel(): TasksViewModel = TasksViewModel(
        taskRepository = taskRepository,
        taskOccurrenceRepository = taskOccurrenceRepository,
        notificationScheduler = notificationScheduler,
        appSettingsRepository = appSettingsRepository,
        recurrenceManager = recurrenceManager,
        createTimeEntryForHabitUseCase = createTimeEntryForHabitUseCase,
        scoreRollupCascadeService = scoreRollupCascadeService,
        habitMetricRepository = habitMetricRepository,
        scoreWindowRepository = scoreWindowRepository,
        scoreChangeEventBus = scoreChangeEventBus,
    )

    @Test
    /**
     * Performs the initial state loads correctly.
     */
    fun initialState_loadsCorrectly() = runTest {
        advanceUntilIdle()

        val initialState = viewModel.uiState.value
        assertThat(initialState.tasks).isEmpty()
        assertThat(initialState.currentFilter).isEqualTo(TaskFilter.ACTIVE)
        assertThat(initialState.currentSort).isEqualTo(TaskSortOption.SCORE_DESC)
    }

    @Test
    /**
     * Loads the load tasks updates state with repository data.
     */
    fun loadTasks_updatesStateWithRepositoryData() = runTest {
        val testTasks = listOf(
            createTestTask("task-1", "Task 1"),
            createTestTask("task-2", "Task 2"),
        )
        `when`(taskRepository.getAllTasks()).thenReturn(flowOf(testTasks))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.tasks).hasSize(2)
        assertThat(state.tasks.map { it.title }).containsExactly("Task 1", "Task 2")
    }

    @Test
    /**
     * Updates the set filter updates current filter and reloads tasks.
     */
    fun setFilter_updatesCurrentFilterAndReloadsTasks() = runTest {
        val completedTasks = listOf(createTestTask("completed", "Completed Task").copy(status = "completed"))
        `when`(taskRepository.getAllTasks()).thenReturn(flowOf(completedTasks))

        viewModel.setFilter(TaskFilter.COMPLETED)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.currentFilter).isEqualTo(TaskFilter.COMPLETED)
        assertThat(state.tasks).hasSize(1)
        assertThat(state.tasks.first().title).isEqualTo("Completed Task")
    }

    @Test
    /**
     * Updates the set sort option updates current sort and reloads tasks.
     */
    fun setSortOption_updatesCurrentSortAndReloadsTasks() = runTest {
        val tasks = listOf(createTestTask("task-1", "Task 1"))
        `when`(taskRepository.getAllTasks()).thenReturn(flowOf(tasks))

        viewModel.setSortOption(TaskSortOption.TITLE_ASC)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.currentSort).isEqualTo(TaskSortOption.TITLE_ASC)
    }

    @Test
    /**
     * Removes the delete task removes task and refreshes list.
     */
    fun deleteTask_removesTaskAndRefreshesList() = runTest {
        val task = createTestTask("task-1", "Test Task")
        val tasks = MutableStateFlow(listOf(task))
        `when`(taskRepository.getAllTasks()).thenReturn(tasks)

        viewModel = createViewModel()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.tasks).hasSize(1)

        viewModel.deleteTask("task-1")
        advanceUntilIdle()
        verify(taskRepository).deleteTask("task-1")

        // deleteTask only delegates; the visible list refreshes from the repository flow.
        tasks.value = emptyList()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.tasks).isEmpty()
    }

    @Test
    /**
     * Loads the load todays tasks loads tasks for today filter.
     */
    fun loadTodaysTasks_loadsTasksForTodayFilter() = runTest {
        val todaysTasks = listOf(createTestTask("today", "Today's Task"))
        `when`(taskRepository.getAllTasks()).thenReturn(flowOf(todaysTasks))

        viewModel.setFilter(TaskFilter.TODAY)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.currentFilter).isEqualTo(TaskFilter.TODAY)
        assertThat(state.tasks).hasSize(1)
        assertThat(state.tasks.first().title).isEqualTo("Today's Task")
    }

    /**
     * Builds a [Task] for the tests.
     */
    private fun createTestTask(id: String, title: String): Task {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        return Task(
            id = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            impactLevel = "Moderate Impact",
            goalAlignment = "Moderate Alignment",
            energyLevel = "Moderate",
            controlLevel = "Office/Colleagues Dependent",
            lifeIntentionCategory = "Career & Work",
            durationMinutes = 30,
        )
    }
}
