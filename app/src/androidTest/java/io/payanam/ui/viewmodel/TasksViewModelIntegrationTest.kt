//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.ui.viewmodel.TasksViewModel.TaskFilter
import io.payanam.ui.viewmodel.TasksViewModel.TaskSortOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
/**
 * TasksViewModelIntegrationTest.
 */
class TasksViewModelIntegrationTest {

    @get:Rule
    /** Instant task executor rule. */
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Mock
    private lateinit var taskRepository: TaskRepository

    @Mock
    private lateinit var taskOccurrenceRepository: TaskOccurrenceRepository

    @Mock
    private lateinit var notificationScheduler: NotificationScheduler

    @Mock
    private lateinit var appSettingsRepository: AppSettingsRepository

    private lateinit var viewModel: TasksViewModel

    @Before
    /**
     * Setup.
     */
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)

        // Setup default mock behaviors
        `when`(taskRepository.getAllTasks()).thenReturn(flowOf(emptyList()))
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(emptyList()))
        `when`(taskRepository.getTasksByStatus("pending")).thenReturn(flowOf(emptyList()))
        `when`(appSettingsRepository.getSetting("task_sort_option")).thenReturn("score_desc")
        `when`(appSettingsRepository.getSetting("task_filter_option")).thenReturn("active")

        viewModel = TasksViewModel(
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            notificationScheduler = notificationScheduler,
            appSettingsRepository = appSettingsRepository,
        )
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    /**
     * Initial state loads correctly.
     */
    fun initialState_loadsCorrectly() = runTest {
        // Given - default mocks return empty lists

        // When - ViewModel is created
        // Then - should have default state
        viewModel.uiState.test {
            /** Initial state. */
            val initialState = awaitItem()
            /** Assert that. */
            assertThat(initialState.tasks).isEmpty()
            /** Assert that. */
            assertThat(initialState.currentFilter).isEqualTo(TaskFilter.ACTIVE)
            /** Assert that. */
            assertThat(initialState.currentSort).isEqualTo(TaskSortOption.SCORE_DESC)
            /** Assert that. */
            assertThat(initialState.isLoading).isFalse()
        }
    }

    @Test
    /**
     * Load tasks updates state with repository data.
     */
    fun loadTasks_updatesStateWithRepositoryData() = runTest {
        // Given
        /** Test tasks. */
        val testTasks = listOf(
            /** Create test task. */
            createTestTask("task-1", "Task 1"),
            /** Create test task. */
            createTestTask("task-2", "Task 2"),
        )
        `when`(taskRepository.getTasksByStatus("pending")).thenReturn(flowOf(testTasks))

        // When - ViewModel is created (triggers loadTasks in init)
        /** View model. */
        val viewModel = TasksViewModel(
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            notificationScheduler = notificationScheduler,
            appSettingsRepository = appSettingsRepository,
        )

        // Then
        viewModel.uiState.test {
            /** State. */
            val state = awaitItem()
            /** Assert that. */
            assertThat(state.tasks).hasSize(2)
            /** Assert that. */
            assertThat(state.tasks.map { it.title }).containsExactly("Task 1", "Task 2")
        }
    }

    @Test
    /**
     * Set filter updates current filter and reloads tasks.
     */
    fun setFilter_updatesCurrentFilterAndReloadsTasks() = runTest {
        // Given
        /** Completed tasks. */
        val completedTasks = listOf(createTestTask("completed", "Completed Task").copy(status = "completed"))
        `when`(taskRepository.getTasksByStatus("completed")).thenReturn(flowOf(completedTasks))

        // When
        viewModel.setFilter(TaskFilter.COMPLETED)

        // Then
        viewModel.uiState.test {
            /** State. */
            val state = awaitItem()
            /** Assert that. */
            assertThat(state.currentFilter).isEqualTo(TaskFilter.COMPLETED)
            /** Assert that. */
            assertThat(state.tasks).hasSize(1)
            /** Assert that. */
            assertThat(state.tasks.first().title).isEqualTo("Completed Task")
        }
    }

    @Test
    /**
     * Set sort option updates current sort and reloads tasks.
     */
    fun setSortOption_updatesCurrentSortAndReloadsTasks() = runTest {
        // Given
        /** Tasks. */
        val tasks = listOf(createTestTask("task-1", "Task 1"))
        `when`(taskRepository.getTasksByStatus("pending")).thenReturn(flowOf(tasks))

        // When
        viewModel.setSortOption(TaskSortOption.TITLE_ASC)

        // Then
        viewModel.uiState.test {
            /** State. */
            val state = awaitItem()
            /** Assert that. */
            assertThat(state.currentSort).isEqualTo(TaskSortOption.TITLE_ASC)
        }
    }

    // TODO: Implement toggleTaskStatus method in TasksViewModel or remove this test
    // @Test
    // fun `toggleTaskStatus updates task and refreshes list`() = runTest {
    //     // Given
    //     val task = createTestTask("task-1", "Test Task")
    //     `when`(taskRepository.getTaskById("task-1")).thenReturn(task)
    //     `when`(taskRepository.getTasksByStatus("pending")).thenReturn(flowOf(listOf(task)))
    //
    //     // When
    //     viewModel.toggleTaskStatus("task-1")
    //
    //     // Then - verify repository methods were called
    //     // Note: In a real integration test, we'd verify the database was updated
    //     // For this mock-based test, we verify the repository interaction
    // }

    @Test
    /**
     * Delete task removes task and refreshes list.
     */
    fun deleteTask_removesTaskAndRefreshesList() = runTest {
        // Given
        /** Task. */
        val task = createTestTask("task-1", "Test Task")
        `when`(taskRepository.getTasksByStatus("pending")).thenReturn(flowOf(listOf(task)), flowOf(emptyList()))

        // When
        viewModel.deleteTask("task-1")

        // Then
        viewModel.uiState.test {
            /** Await item. */
            awaitItem() // Initial state
            /** State after delete. */
            val stateAfterDelete = awaitItem()
            /** Assert that. */
            assertThat(stateAfterDelete.tasks).isEmpty()
        }
    }

    @Test
    /**
     * Load todays tasks loads tasks for today filter.
     */
    fun loadTodaysTasks_loadsTasksForTodayFilter() = runTest {
        // Given
        /** Todays tasks. */
        val todaysTasks = listOf(createTestTask("today", "Today's Task"))
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(todaysTasks))

        // When
        viewModel.setFilter(TaskFilter.TODAY)

        // Then
        viewModel.uiState.test {
            /** State. */
            val state = awaitItem()
            /** Assert that. */
            assertThat(state.currentFilter).isEqualTo(TaskFilter.TODAY)
            /** Assert that. */
            assertThat(state.tasks).hasSize(1)
            /** Assert that. */
            assertThat(state.tasks.first().title).isEqualTo("Today's Task")
        }
    }

    private fun createTestTask(id: String, title: String): Task {
        /** Now. */
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
