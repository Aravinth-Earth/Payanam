//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.model.Task
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.usecase.RecurrenceManager
import io.payanam.usecase.TimeTrackingUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Integration coverage for [TimeViewModel] against mocked repositories.
 *
 * Drives the view model on a [StandardTestDispatcher] so that the coroutines launched on
 * `viewModelScope` are advanced deterministically with [advanceUntilIdle] instead of relying on
 * wall-clock time.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class TimeViewModelIntegrationTest {

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
    private lateinit var notificationScheduler: NotificationScheduler

    @Mock
    private lateinit var timeTrackingUseCase: TimeTrackingUseCase

    private lateinit var viewModel: TimeViewModel

    @Before
    /**
     * Installs the test dispatcher, stubs the default (empty) repository results and builds the
     * view model under test.
     */
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        UnifiedLogger.initialize(context, "test", 0)

        // Default mock behaviours: an empty day with nothing running.
        `when`(timeEntryRepository.getTimeEntriesForDate(LocalDate.now())).thenReturn(flowOf(emptyList()))
        runBlocking {
            `when`(timeEntryRepository.getActiveTimeEntry()).thenReturn(null)
        }
        `when`(timeEntryRepository.observeActiveTimeEntry()).thenReturn(flowOf(null))
        `when`(timeEntryRepository.getAllTimeEntries()).thenReturn(flowOf(emptyList()))
        `when`(taskRepository.getAllTasks()).thenReturn(flowOf(emptyList()))
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(emptyList()))
        `when`(taskOccurrenceRepository.getOccurrencesForDate(LocalDate.now())).thenReturn(flowOf(emptyList()))

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
    private fun createViewModel(): TimeViewModel = TimeViewModel(
        context = context,
        timeEntryRepository = timeEntryRepository,
        taskRepository = taskRepository,
        taskOccurrenceRepository = taskOccurrenceRepository,
        recurrenceManager = recurrenceManager,
        notificationScheduler = notificationScheduler,
        timeTrackingUseCase = timeTrackingUseCase,
    )

    @Test
    /**
     * Performs the initial state loads correctly.
     */
    fun initialState_loadsCorrectly() = runTest {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.timeEntries).isEmpty()
        assertThat(state.activeEntry).isNull()
        assertThat(state.plannedTasks).isEmpty()
    }

    @Test
    /**
     * Loads the load data populates state with repository data.
     */
    fun loadData_populatesStateWithRepositoryData() = runTest {
        val today = LocalDate.now()
        val timeEntries = listOf(createTestTimeEntry("entry-1", "task-1"))
        val tasks = listOf(createTestTask("task-1", "Test Task"))

        `when`(timeEntryRepository.getTimeEntriesForDate(today)).thenReturn(flowOf(timeEntries))
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(tasks))
        `when`(timeEntryRepository.getActiveTimeEntry()).thenReturn(null)

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.timeEntries).hasSize(1)
        assertThat(state.timeEntries.first().id).isEqualTo("entry-1")
        assertThat(state.plannedTasks).hasSize(1)
        assertThat(state.plannedTasks.first().title).isEqualTo("Test Task")
    }

    @Test
    /**
     * Performs the start tracking call reaches the repository layer without surfacing an error.
     */
    fun startTracking_createsNewTimeEntry() = runTest {
        runBlocking {
            `when`(timeEntryRepository.startTimeEntry(any()))
                .thenReturn(createTestTimeEntry("entry-1", "task-1"))
        }

        viewModel.startTracking(
            dimensionId = LifeDimension.CAREER_WORK.id,
            dimensionLabel = LifeDimension.CAREER_WORK.displayName,
            taskId = "task-1",
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    /**
     * Performs the stop tracking call reaches the use case without surfacing an error.
     */
    fun stopTracking_stopsActiveEntry() = runTest {
        runBlocking {
            `when`(timeEntryRepository.getActiveTimeEntry()).thenReturn(createTestTimeEntry("active", "task-1"))
        }

        viewModel.stopTracking()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    /**
     * Loads the load entries for date loads entries for specific date.
     */
    fun loadEntriesForDate_loadsEntriesForSpecificDate() = runTest {
        val targetDate = LocalDate.now().minusDays(1)
        val entriesForDate = listOf(createTestTimeEntry("entry-1", "task-1"))

        `when`(timeEntryRepository.getTimeEntriesForDate(targetDate)).thenReturn(flowOf(entriesForDate))

        viewModel.loadEntriesForDate(targetDate)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.timeEntries).hasSize(1)
        assertThat(state.timeEntries.first().id).isEqualTo("entry-1")
    }

    @Test
    /**
     * Performs the active time entry is reflected in state.
     */
    fun activeTimeEntry_isReflectedInState() = runTest {
        val activeEntry = createTestTimeEntry("active", "task-1", endedAt = null)
        runBlocking {
            `when`(timeEntryRepository.getActiveTimeEntry()).thenReturn(activeEntry)
        }
        `when`(timeEntryRepository.observeActiveTimeEntry()).thenReturn(flowOf(activeEntry))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.activeEntry).isNotNull()
        assertThat(state.activeEntry?.id).isEqualTo("active")
    }

    @Test
    /**
     * Performs the planned tasks are loaded and displayed.
     */
    fun plannedTasks_areLoadedAndDisplayed() = runTest {
        val plannedTasks = listOf(
            createTestTask("task-1", "Morning Task"),
            createTestTask("task-2", "Afternoon Task"),
        )
        `when`(taskRepository.getTodaysTasks()).thenReturn(flowOf(plannedTasks))

        viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.plannedTasks).hasSize(2)
        assertThat(state.plannedTasks.map { it.title }).containsExactly("Morning Task", "Afternoon Task")
    }

    /**
     * Builds a [TimeEntry] for the tests. Pass [endedAt] as null to represent a running entry.
     */
    private fun createTestTimeEntry(
        id: String,
        taskId: String,
        endedAt: LocalDateTime? = LocalDateTime.now(),
    ): TimeEntry {
        val now = LocalDateTime.now()
        val startedAt = now.minusMinutes(30)
        return TimeEntry(
            id = id,
            lifeIntentionCategory = LifeDimension.CAREER_WORK.displayName,
            taskId = taskId,
            startedAt = startedAt,
            endedAt = endedAt,
            createdAt = startedAt,
            updatedAt = now,
        )
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
            lifeIntentionCategory = LifeDimension.CAREER_WORK.displayName,
            durationMinutes = 30,
        )
    }
}
