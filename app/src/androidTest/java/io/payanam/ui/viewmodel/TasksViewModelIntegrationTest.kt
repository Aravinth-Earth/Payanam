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
 * Provides the tasks view model integration test.
 */
class TasksViewModelIntegrationTest {

    @get:Rule
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
     * Updates the setup.
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
     * Performs the tear down.
     */
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    /**
     * Performs the initial state loads correctly.
     */
    fun initialState_loadsCorrectly() = runTest {
        // Given - default mocks return empty lists

        // When - ViewModel is created
        // Then - should have default state
        viewModel.uiState.test {
            val initialState = awaitItem()
            assertThat(initialState.tasks).isEmpty()
            assertThat(initialState.currentFilter).isEqualTo(TaskFilter.ACTIVE)
            assertThat(initialState.currentSort).isEqualTo(TaskSortOption.SCORE_DESC)
            assertThat(initialState.isLoading).isFalse()
        }
    }

    @Test
    /**
     * Loads the load tasks updates state with repository data.
     */
    fun loadTasks_updatesStateWithRepositoryData() = runTest {
        // Given
        val testTasks = listOf(
            createTestTask("task-1", "Task 1"),
            createTestTask("task-2", "Task 2"),
        )
        `when`(taskRepository.getTasksByStatus("pending")).thenReturn(flowOf(testTasks))

        // When - ViewModel is created (triggers loadTasks in init)
        val viewModel = TasksViewModel(
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            notificationScheduler = notificationScheduler,
            appSettingsRepository = appSettingsRepository,
        )

        // Then
        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.tasks).hasSize(2)
            assertThat(state.tasks.map { it.title }).containsExactly("Task 1", "Task 2")
        }
    }

    @Test
    /**
     * Updates the set filter updates current filter and reloads tasks.
     */
    fun setFilter_updatesCurrentFilterAndReloadsTasks() = runTest {
        // Given
        val completedTasks = listOf(createTestTask("completed", "Completed Task").copy(status = "completed"))
        `when`(taskRepository.getTasksByStatus("completed")).thenReturn(flowOf(completedTasks))

        // When
        viewModel.setFilter(TaskFilter.COMPLETED)

        // Then
        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.currentFilter).isEqualTo(TaskFilter.COMPLETED)
            assertThat(state.tasks).hasSize(1)
            assertThat(state.tasks.first().title).isEqualTo("Completed Task")
        }
    }

    @Test
    /**
     * Updates the set sort option updates current sort and reloads tasks.
     */
    fun setSortOption_updatesCurrentSortAndReloadsTasks() = runTest {
        // Given
        val tasks = listOf(createTestTask("task-1", "Task 1"))
        `when`(taskRepository.getTasksByStatus("pending")).thenReturn(flowOf(tasks))

        // When
        viewModel.setSortOption(TaskSortOption.TITLE_ASC)

        // Then
        viewModel.uiState.test {
            val state = awaitItem()
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
     * Removes the delete task removes task and refreshes list.
     */
    fun deleteTask_removesTaskAndRefreshesList() = runTest {
        // Given
        val task = createTestTask("task-1", "Test Task")
        `when`(taskRepository.getTasksByStatus("pending")).thenReturn(flowOf(listOf(task)), flowOf(emptyList()))

        // When
        viewModel.deleteTask("task-1")

        // Then
        viewModel.uiState.test {
            awaitItem() // Initial state
            val stateAfterDelete = awaitItem()
            assertThat(stateAfterDelete.tasks).isEmpty()
        }
    }

    @Test
    /**
     * Loads the load todays tasks loads tasks for today filter.
     */
    fun loadTodaysTasks_loadsTasksForTodayFilter() = runTest {
        // Given
        val todaysTasks = listOf(createTestTask("today", "Today's Task"))
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(todaysTasks))

        // When
        viewModel.setFilter(TaskFilter.TODAY)

        // Then
        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.currentFilter).isEqualTo(TaskFilter.TODAY)
            assertThat(state.tasks).hasSize(1)
            assertThat(state.tasks.first().title).isEqualTo("Today's Task")
        }
    }

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
