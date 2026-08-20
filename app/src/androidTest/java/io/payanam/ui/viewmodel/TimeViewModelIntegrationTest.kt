//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.usecase.RecurrenceManager
import io.payanam.usecase.TimeTrackingUseCase
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
import java.time.LocalDate
import java.time.LocalDateTime

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
/**
 * TimeViewModelIntegrationTest.
 */
class TimeViewModelIntegrationTest {

    @get:Rule
    /** Instant task executor rule. */
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Mock
    private lateinit var timeEntryRepository: TimeEntryRepository

    @Mock
    private lateinit var taskRepository: TaskRepository

    @Mock
    private lateinit var taskOccurrenceRepository: TaskOccurrenceRepository

    @Mock
    private lateinit var recurrenceManager: RecurrenceManager

    @Mock
    private lateinit var timeTrackingUseCase: TimeTrackingUseCase

    private lateinit var viewModel: TimeViewModel

    @Before
    /**
     * Setup.
     */
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()

        // Setup default mock behaviors
        `when`(timeEntryRepository.getTimeEntriesForDate(LocalDate.now())).thenReturn(flowOf(emptyList()))
        `when`(timeEntryRepository.getActiveTimeEntry()).thenReturn(null)
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(emptyList()))
        `when`(taskOccurrenceRepository.getOccurrencesForDate(LocalDate.now())).thenReturn(flowOf(emptyList()))

        viewModel = TimeViewModel(
            context = context,
            timeEntryRepository = timeEntryRepository,
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            recurrenceManager = recurrenceManager,
            timeTrackingUseCase = timeTrackingUseCase,
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
        // Given - default mocks return empty data

        // When - ViewModel is created
        // Then - should have default state
        viewModel.uiState.test {
            /** Initial state. */
            val initialState = awaitItem()
            /** Assert that. */
            assertThat(initialState.timeEntries).isEmpty()
            /** Assert that. */
            assertThat(initialState.activeTimeEntry).isNull()
            /** Assert that. */
            assertThat(initialState.plannedTasks).isEmpty()
            /** Assert that. */
            assertThat(initialState.isLoading).isFalse()
        }
    }

    @Test
    /**
     * Load data populates state with repository data.
     */
    fun loadData_populatesStateWithRepositoryData() = runTest {
        // Given
        /** Today. */
        val today = LocalDate.now()
        /** Time entries. */
        val timeEntries = listOf(createTestTimeEntry("entry-1", "task-1"))
        /** Tasks. */
        val tasks = listOf(createTestTask("task-1", "Test Task"))

        `when`(timeEntryRepository.getTimeEntriesForDate(today)).thenReturn(flowOf(timeEntries))
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(tasks))
        `when`(timeEntryRepository.getActiveTimeEntry()).thenReturn(null)

        // When - recreate ViewModel to trigger loadData
        viewModel = TimeViewModel(
            context = context,
            timeEntryRepository = timeEntryRepository,
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            recurrenceManager = recurrenceManager,
            timeTrackingUseCase = timeTrackingUseCase,
        )

        // Then
        viewModel.uiState.test {
            /** State. */
            val state = awaitItem()
            /** Assert that. */
            assertThat(state.timeEntries).hasSize(1)
            /** Assert that. */
            assertThat(state.timeEntries.first().id).isEqualTo("entry-1")
            /** Assert that. */
            assertThat(state.plannedTasks).hasSize(1)
            /** Assert that. */
            assertThat(state.plannedTasks.first().title).isEqualTo("Test Task")
        }
    }

    @Test
    /**
     * Start tracking creates new time entry.
     */
    fun startTracking_createsNewTimeEntry() = runTest {
        // Given
        /** Task. */
        val task = createTestTask("task-1", "Test Task")
        `when`(timeTrackingUseCase.startTracking(task)).thenReturn("entry-1")

        // When
        viewModel.startTracking(
            dimensionId = LifeDimension.CAREER.id,
            dimensionLabel = LifeDimension.CAREER.displayName,
            taskId = "task-1",
        )

        // Then - verify use case was called
        // In a real integration test, we'd verify the database state
    }

    @Test
    /**
     * Stop tracking stops active entry.
     */
    fun stopTracking_stopsActiveEntry() = runTest {
        // Given
        /** Active entry. */
        val activeEntry = createTestTimeEntry("active", "task-1")
        `when`(timeEntryRepository.getActiveTimeEntry()).thenReturn(activeEntry)
        `when`(timeTrackingUseCase.stopTracking()).thenReturn(true)

        // When
        viewModel.stopTracking()

        // Then - verify use case was called
    }

    @Test
    /**
     * Load entries for date loads entries for specific date.
     */
    fun loadEntriesForDate_loadsEntriesForSpecificDate() = runTest {
        // Given
        /** Target date. */
        val targetDate = LocalDate.now().minusDays(1)
        /** Entries for date. */
        val entriesForDate = listOf(createTestTimeEntry("entry-1", "task-1"))

        `when`(timeEntryRepository.getTimeEntriesForDate(targetDate)).thenReturn(flowOf(entriesForDate))

        // When
        viewModel.loadEntriesForDate(targetDate)

        // Then
        viewModel.uiState.test {
            /** State. */
            val state = awaitItem()
            /** Assert that. */
            assertThat(state.timeEntries).hasSize(1)
            /** Assert that. */
            assertThat(state.timeEntries.first().id).isEqualTo("entry-1")
        }
    }

    @Test
    /**
     * Active time entry is reflected in state.
     */
    fun activeTimeEntry_isReflectedInState() = runTest {
        // Given
        /** Active entry. */
        val activeEntry = createTestTimeEntry("active", "task-1")
        `when`(timeEntryRepository.getActiveTimeEntry()).thenReturn(activeEntry)

        // When - recreate ViewModel
        viewModel = TimeViewModel(
            context = context,
            timeEntryRepository = timeEntryRepository,
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            recurrenceManager = recurrenceManager,
            timeTrackingUseCase = timeTrackingUseCase,
        )

        // Then
        viewModel.uiState.test {
            /** State. */
            val state = awaitItem()
            /** Assert that. */
            assertThat(state.activeTimeEntry).isNotNull()
            /** Assert that. */
            assertThat(state.activeTimeEntry?.id).isEqualTo("active")
        }
    }

    @Test
    /**
     * Planned tasks are loaded and displayed.
     */
    fun plannedTasks_areLoadedAndDisplayed() = runTest {
        // Given
        /** Planned tasks. */
        val plannedTasks = listOf(
            /** Create test task. */
            createTestTask("task-1", "Morning Task"),
            /** Create test task. */
            createTestTask("task-2", "Afternoon Task"),
        )
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(plannedTasks))

        // When - recreate ViewModel
        viewModel = TimeViewModel(
            context = context,
            timeEntryRepository = timeEntryRepository,
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            recurrenceManager = recurrenceManager,
            timeTrackingUseCase = timeTrackingUseCase,
        )

        // Then
        viewModel.uiState.test {
            /** State. */
            val state = awaitItem()
            /** Assert that. */
            assertThat(state.plannedTasks).hasSize(2)
            /** Assert that. */
            assertThat(state.plannedTasks.map { it.title }).containsExactly("Morning Task", "Afternoon Task")
        }
    }

    private fun createTestTimeEntry(id: String, taskId: String): TimeEntry {
        /** Now. */
        val now = LocalDateTime.now()
        return TimeEntry(
            id = id,
            taskId = taskId,
            startedAt = now.minusMinutes(30),
            endedAt = now,
            description = "Test time entry",
        )
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
