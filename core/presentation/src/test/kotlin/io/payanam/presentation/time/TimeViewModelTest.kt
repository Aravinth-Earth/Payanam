//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.port.TimeTrackingController
import io.payanam.domain.port.TimeTrackingWidgetRefresher
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import io.payanam.presentation.flags.FeatureFlagsState
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.presentation.tasks.FakeRecurrenceHandler
import io.payanam.presentation.tasks.FakeReminderScheduler
import io.payanam.presentation.tasks.FakeTaskOccurrenceRepository
import io.payanam.presentation.tasks.FakeTaskRepository
import io.payanam.presentation.tasks.TASKS_TEST_DAY
import io.payanam.presentation.tasks.TASKS_TEST_TIME
import io.payanam.presentation.tasks.task
import io.payanam.scoring.port.TaskRecurrenceHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Behavioural lock for the shared Time ViewModel, on plain JVM.
 *
 * Supersedes `app/…/TimeViewModelIntegrationTest`, which needed Robolectric, `ApplicationProvider`,
 * `UnifiedLogger.initialize` and Mockito mocks of `NotificationScheduler`/`TimeTrackingUseCase` merely to
 * stand the ViewModel up. Here the logger is [io.payanam.shared.logging.AppLogger]'s no-op default and
 * every collaborator is a fake in this module, which is also the proof the ViewModel needs no platform
 * to exist. Assertions carried over from the superseded test are marked `[carried]`.
 *
 * The three ports that had no fake here - the tracking controller, the widget refresher and the time-entry
 * repository - are faked below. They are fakes rather than mocks because each of these tests asserts on a
 * value the ViewModel BUILT (the `TimeEntryInput` it assembled, the occurrence it recorded), which a mock
 * cannot return without restating the production logic it is meant to check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeViewModelTest {
    private lateinit var timeEntryRepository: FakeTimeEntryRepository
    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var taskOccurrenceRepository: FakeTaskOccurrenceRepository
    private lateinit var recurrenceHandler: FakeRecurrenceHandler
    private lateinit var reminderScheduler: FakeReminderScheduler
    private lateinit var timeTrackingController: FakeTimeTrackingController
    private lateinit var widgetRefresher: FakeTimeTrackingWidgetRefresher
    private var viewModelScope: CoroutineScope? = null

    @Before
    fun setUp() {
        LocalDataLayer.resetForTests()
        PlatformFeatureFlags.resetForTests()
        timeEntryRepository = FakeTimeEntryRepository()
        taskRepository = FakeTaskRepository()
        taskOccurrenceRepository = FakeTaskOccurrenceRepository()
        recurrenceHandler = FakeRecurrenceHandler()
        reminderScheduler = FakeReminderScheduler()
        timeTrackingController = FakeTimeTrackingController()
        widgetRefresher = FakeTimeTrackingWidgetRefresher()
    }

    @After
    fun tearDown() {
        viewModelScope?.cancel()
        viewModelScope = null
        LocalDataLayer.resetForTests()
        PlatformFeatureFlags.resetForTests()
    }

    private fun TestScope.newViewModelScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { viewModelScope = it }

    private fun TestScope.viewModel(): TimeViewModel {
        val scope = newViewModelScope()
        return TimeViewModel(
            timeEntryRepository = timeEntryRepository,
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            recurrenceHandler = recurrenceHandler,
            reminderScheduler = reminderScheduler,
            timeTrackingController = timeTrackingController,
            widgetRefresher = widgetRefresher,
            scope = scope,
        )
    }

    // ────────────────────────────────────────────────────────────────
    // Initial load
    // ────────────────────────────────────────────────────────────────

    /** [carried] `initialState_loadsCorrectly`: an empty day renders nothing. */
    @Test
    fun `initial load leaves an empty day empty`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.timeEntries.isEmpty())
            assertNull(state.activeEntry)
            assertTrue(state.plannedTasks.isEmpty())
            assertFalse(state.isLoading)
            assertTrue(state.isDateContentReady)
        }

    /** [carried] `loadData_populatesStateWithRepositoryData`. */
    @Test
    fun `initial load populates entries and planned tasks from the repositories`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1", taskId = "task-1"))
            taskRepository.tasks.value = listOf(task(id = "task-1", title = "Test Task"))

            val viewModel = viewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, state.timeEntries.size)
            assertEquals("entry-1", state.timeEntries.first().id)
            assertEquals(listOf("Test Task"), state.plannedTasks.map { it.title })
            assertEquals(listOf("Test Task"), state.tasks.map { it.title })
        }

    /**
     * The screen waits for all THREE sections before it says the date is ready, so a date whose entries
     * and tasks have arrived but whose occurrences have not must still read as loading.
     */
    @Test
    fun `date is not ready until every section has arrived`() =
        runTest {
            taskOccurrenceRepository.holdOccurrences = true
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1"))

            val viewModel = viewModel()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isDateContentReady)
        }

    /** The day renders only once the held occurrences section is allowed to emit. */
    @Test
    fun `date becomes ready when the last section arrives`() =
        runTest {
            taskOccurrenceRepository.holdOccurrences = true
            val viewModel = viewModel()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isLoading)

            taskOccurrenceRepository.releaseOccurrences()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.isDateContentReady)
        }

    /** [carried] `activeTimeEntry_isReflectedInState`. */
    @Test
    fun `the active entry reaches state`() =
        runTest {
            val active = entry("active", taskId = "task-1", endedAt = null)
            timeEntryRepository.activeEntry = active

            val viewModel = viewModel()
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.activeEntry)
            assertEquals(
                "active",
                viewModel.uiState.value.activeEntry
                    ?.id,
            )
        }

    /**
     * `lastEntry` is the most RECENT finished entry, and an entry still running is not a candidate: a
     * session that has not ended cannot be the one to continue from.
     */
    @Test
    fun `last entry is the latest finished entry and ignores the running one`() =
        runTest {
            val older = entry("older", endedAt = TASKS_TEST_TIME)
            val newer = entry("newer", endedAt = TASKS_TEST_TIME.plusHours(2))
            val running = entry("running", endedAt = null, startedAt = TASKS_TEST_TIME.plusHours(5))
            timeEntryRepository.allEntries = listOf(older, newer, running)

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(
                "newer",
                viewModel.uiState.value.lastEntry
                    ?.id,
            )
        }

    /** The task picker is fed from the planned tasks first, then every task. */
    @Test
    fun `task picker carries the planned tasks and every task`() =
        runTest {
            taskRepository.tasks.value =
                listOf(
                    task(id = "planned", title = "Planned"),
                    task(id = "other", title = "Other", dueDate = null),
                )

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(
                listOf("Planned", "Other"),
                viewModel.uiState.value.taskPickerTasks
                    .map { it.title },
            )
        }

    /** A failing task collection is logged, not propagated: the screen keeps whatever it already had. */
    @Test
    fun `a failing task collection surfaces no error`() =
        runTest {
            taskRepository.failGetAllTasks = true

            val viewModel = viewModel()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // loadEntriesForDate
    // ────────────────────────────────────────────────────────────────

    /** [carried] `loadEntriesForDate_loadsEntriesForSpecificDate`. */
    @Test
    fun `loading a date reads that date's entries`() =
        runTest {
            val target = TASKS_TEST_DAY.minusDays(1)
            timeEntryRepository.entriesByDate[target] = listOf(entry("entry-1", taskId = "task-1"))

            val viewModel = viewModel()
            viewModel.loadEntriesForDate(target)
            advanceUntilIdle()

            assertEquals(
                listOf("entry-1"),
                viewModel.uiState.value.timeEntries
                    .map { it.id },
            )
            assertEquals(target, viewModel.uiState.value.selectedDate)
        }

    /** Entries render in the order they began, whatever order the store hands them over. */
    @Test
    fun `entries are ordered by start time`() =
        runTest {
            val day = TASKS_TEST_DAY
            timeEntryRepository.entriesByDate[day] =
                listOf(
                    entry("late", startedAt = day.atTime(15, 0)),
                    entry("early", startedAt = day.atTime(9, 0)),
                    entry("middle", startedAt = day.atTime(12, 0)),
                )

            val viewModel = viewModel()
            viewModel.loadEntriesForDate(day)
            advanceUntilIdle()

            assertEquals(
                listOf("early", "middle", "late"),
                viewModel.uiState.value.timeEntries
                    .map { it.id },
            )
        }

    /** [carried] `plannedTasks_areLoadedAndDisplayed`. */
    @Test
    fun `planned tasks for today load and display`() =
        runTest {
            taskRepository.tasks.value =
                listOf(
                    task(id = "task-1", title = "Morning Task"),
                    task(id = "task-2", title = "Afternoon Task"),
                )

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(
                listOf("Morning Task", "Afternoon Task"),
                viewModel.uiState.value.plannedTasks
                    .map { it.title },
            )
        }

    /**
     * Today reads `getTodaysTasks`, another day reads the tasks DUE that day - so the store is asked the
     * question that matches the date on screen rather than the same question for every date.
     */
    @Test
    fun `a non-today date reads the tasks due on that date`() =
        runTest {
            val other = TASKS_TEST_DAY.minusDays(3)
            taskRepository.tasks.value =
                listOf(
                    task(id = "today", title = "Today Task"),
                    task(id = "other", title = "Other Task", dueDate = other.atStartOfDay()),
                )

            val viewModel = viewModel()
            viewModel.loadEntriesForDate(other)
            advanceUntilIdle()

            assertEquals(
                listOf("Other Task"),
                viewModel.uiState.value.plannedTasks
                    .map { it.title },
            )
        }

    /** Past occurrences are the day's occurrence rows, loaded alongside the entries and tasks. */
    @Test
    fun `past occurrences for the selected date reach state`() =
        runTest {
            taskOccurrenceRepository.occurrences.value =
                listOf(
                    io.payanam.presentation.tasks
                        .occurrence(taskId = "task-1", date = TASKS_TEST_DAY),
                )

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.pastOccurrences.size)
            assertEquals(
                "task-1",
                viewModel.uiState.value.pastOccurrences
                    .first()
                    .taskId,
            )
        }

    /**
     * A failing section still counts as ARRIVED: the screen has to stop showing a spinner on a store it
     * cannot read, and the message is what the user sees.
     */
    @Test
    fun `a failing section surfaces its error and stops loading`() =
        runTest {
            timeEntryRepository.failEntries = true

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals("entries unavailable", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.isDateContentReady)
        }

    @Test
    fun `a failing planned-tasks section surfaces its error`() =
        runTest {
            taskRepository.failTodaysTasks = true

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals("planned unavailable", viewModel.uiState.value.error)
        }

    @Test
    fun `a failing occurrences section surfaces its error`() =
        runTest {
            taskOccurrenceRepository.failForDate = true

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals("occurrences unavailable", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    /** Loading another date clears the previous date's error. */
    @Test
    fun `loading a date clears the error from the last one`() =
        runTest {
            timeEntryRepository.failEntries = true
            val viewModel = viewModel()
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.error)

            timeEntryRepository.failEntries = false
            viewModel.loadEntriesForDate(TASKS_TEST_DAY)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // Minimal mode
    // ────────────────────────────────────────────────────────────────

    /**
     * A minimal build ships without the planning surfaces, so it skips the occurrences load entirely -
     * and because that section is then never needed, the date is ready without it.
     */
    @Test
    fun `minimal mode skips the occurrences load and clears past occurrences`() =
        runTest {
            PlatformFeatureFlags.install(FeatureFlagsState(minimalModeEnabled = true))
            taskOccurrenceRepository.occurrences.value =
                listOf(
                    io.payanam.presentation.tasks
                        .occurrence(taskId = "task-1", date = TASKS_TEST_DAY),
                )

            val viewModel = viewModel()
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.pastOccurrences
                    .isEmpty(),
            )
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.isDateContentReady)
        }

    /** Minimal mode also hides the recurring tasks from the planned list, which is what it ships without. */
    @Test
    fun `minimal mode filters recurring tasks out of the planned list`() =
        runTest {
            PlatformFeatureFlags.install(FeatureFlagsState(minimalModeEnabled = true))
            taskRepository.tasks.value =
                listOf(
                    task(id = "once", title = "One-off"),
                    task(id = "repeats", title = "Repeats", recurrenceEnabled = true),
                )

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals(
                listOf("One-off"),
                viewModel.uiState.value.plannedTasks
                    .map { it.title },
            )
        }

    /** The occurrences store is never even asked in minimal mode. */
    @Test
    fun `minimal mode never reads the occurrences store`() =
        runTest {
            PlatformFeatureFlags.install(FeatureFlagsState(minimalModeEnabled = true))

            val viewModel = viewModel()
            advanceUntilIdle()

            assertTrue(taskOccurrenceRepository.dateQueries.isEmpty())
        }

    // ────────────────────────────────────────────────────────────────
    // Navigation
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `previous day moves the selection back one day`() =
        runTest {
            val viewModel = viewModel()
            viewModel.loadEntriesForDate(TASKS_TEST_DAY)
            advanceUntilIdle()

            viewModel.navigateToPreviousDay()
            advanceUntilIdle()

            assertEquals(TASKS_TEST_DAY.minusDays(1), viewModel.uiState.value.selectedDate)
        }

    @Test
    fun `next day moves the selection forward one day`() =
        runTest {
            val viewModel = viewModel()
            viewModel.loadEntriesForDate(TASKS_TEST_DAY)
            advanceUntilIdle()

            viewModel.navigateToNextDay()
            advanceUntilIdle()

            assertEquals(TASKS_TEST_DAY.plusDays(1), viewModel.uiState.value.selectedDate)
        }

    @Test
    fun `today returns the selection to the current date`() =
        runTest {
            val viewModel = viewModel()
            viewModel.loadEntriesForDate(TASKS_TEST_DAY.minusDays(5))
            advanceUntilIdle()

            viewModel.navigateToToday()
            advanceUntilIdle()

            assertEquals(LocalDate.now(), viewModel.uiState.value.selectedDate)
        }

    // ────────────────────────────────────────────────────────────────
    // startTracking
    // ────────────────────────────────────────────────────────────────

    /** [carried] `startTracking_createsNewTimeEntry`, with the real store standing in for the mock. */
    @Test
    fun `starting tracking creates the entry without an error`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(dimensionId = "d1", dimensionLabel = "Build", taskId = "task-1")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
            assertEquals(1, timeEntryRepository.started.size)
            assertEquals("d1", timeEntryRepository.started.first().dimensionId)
            assertEquals("task-1", timeEntryRepository.started.first().taskId)
        }

    /**
     * Starting hands off to the platform with the TASK's title when the entry is attached to a task, so
     * the notification and widget name the work rather than the dimension.
     */
    @Test
    fun `starting tracking hands the platform the task title`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1", title = "Write the report"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(dimensionId = "d1", dimensionLabel = "Build", taskId = "task-1")
            advanceUntilIdle()

            assertEquals("Write the report", timeTrackingController.started.single().taskTitle)
        }

    /** With no task attached, the dimension label is what names the session. */
    @Test
    fun `starting tracking without a task hands the platform the dimension label`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(dimensionId = "d1", dimensionLabel = "Build")
            advanceUntilIdle()

            assertEquals("Build", timeTrackingController.started.single().taskTitle)
            assertEquals("Build", timeTrackingController.started.single().dimension)
        }

    /**
     * A blank task id is the same as no task at all: a whitespace id would otherwise create an entry
     * pointing at a task that does not exist.
     */
    @Test
    fun `starting tracking treats a blank task id as no task`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(dimensionId = "d1", dimensionLabel = "Build", taskId = "   ")
            advanceUntilIdle()

            assertNull(timeEntryRepository.started.single().taskId)
            assertNull(timeTrackingController.started.single().taskId)
        }

    /** [carried] `startTracking_doesNotRefreshTheWidget`. */
    @Test
    fun `starting tracking refreshes the platform session but not the widget`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(dimensionId = "d1", dimensionLabel = "Build")
            advanceUntilIdle()

            assertEquals(1, timeTrackingController.started.size)
            assertEquals(0, widgetRefresher.refreshes)
        }

    /**
     * A session must not be left running under the one being started, so the running entry is stopped
     * first - in the store AND on the platform.
     */
    @Test
    fun `starting tracking stops any running session first`() =
        runTest {
            timeEntryRepository.activeEntry = entry("running", endedAt = null)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(dimensionId = "d1", dimensionLabel = "Build")
            advanceUntilIdle()

            assertTrue(timeEntryRepository.stopCalls > 0)
            assertEquals(1, timeTrackingController.stopCalls)
        }

    /** [carried] the success callback: the screen navigates on it, so it has to fire. */
    @Test
    fun `starting tracking invokes the success callback`() =
        runTest {
            var called = false
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(dimensionId = "d1", dimensionLabel = "Build", onSuccess = { called = true })
            advanceUntilIdle()

            assertTrue(called)
        }

    /** A store that refuses the new entry is reported, and the callback is not fired. */
    @Test
    fun `a failing start surfaces the error and skips the callback`() =
        runTest {
            timeEntryRepository.failStart = true
            var called = false
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(dimensionId = "d1", dimensionLabel = "Build", onSuccess = { called = true })
            advanceUntilIdle()

            assertEquals("start failed", viewModel.uiState.value.error)
            assertTrue(!called)
        }

    /** Starting on the day already on screen reloads it, so the new entry is visible without a swipe. */
    @Test
    fun `starting on the selected date reloads that date`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(
                dimensionId = "d1",
                dimensionLabel = "Build",
                startedAt = TASKS_TEST_TIME,
            )
            advanceUntilIdle()

            assertEquals(
                "start-1",
                viewModel.uiState.value.timeEntries
                    .single()
                    .id,
            )
        }

    /** Starting on ANOTHER day leaves the selected date alone - the screen did not move. */
    @Test
    fun `starting on another date leaves the selected date alone`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.startTracking(
                dimensionId = "d1",
                dimensionLabel = "Build",
                startedAt = TASKS_TEST_DAY.minusDays(2).atTime(9, 0),
            )
            advanceUntilIdle()

            assertEquals(TASKS_TEST_DAY, viewModel.uiState.value.selectedDate)
        }

    // ────────────────────────────────────────────────────────────────
    // stopTracking
    // ────────────────────────────────────────────────────────────────

    /** [carried] `stopTracking_stopsActiveEntry`. */
    @Test
    fun `stopping tracking surfaces no error`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.stopTracking()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
        }

    /** [carried] `stopTracking_stopsThePlatformSessionOnce`. */
    @Test
    fun `stopping tracking ends the platform session exactly once`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.stopTracking()
            advanceUntilIdle()

            assertEquals(1, timeTrackingController.stopCalls)
        }

    /** [carried] `stopTracking_refreshesTheWidget`. */
    @Test
    fun `stopping tracking refreshes the widget exactly once`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.stopTracking()
            advanceUntilIdle()

            assertEquals(1, widgetRefresher.refreshes)
        }

    /** The no-argument form records a zero rating: stopping without judging is not a rating of zero quality. */
    @Test
    fun `stopping without a rating records a zero rating and no note`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.stopTracking()
            advanceUntilIdle()

            assertEquals(0.0, timeTrackingController.lastFocusRating, 0.0)
            assertNull(timeTrackingController.lastFocusNote)
        }

    /** A rating outside 0..1 is clamped, because the rating is stored on the entry and read as a fraction. */
    @Test
    fun `stopping clamps an out-of-range rating`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.stopTracking(focusRating = 4.2, focusNote = null)
            advanceUntilIdle()

            assertEquals(1.0, timeTrackingController.lastFocusRating, 0.0)
        }

    @Test
    fun `stopping clamps a negative rating`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.stopTracking(focusRating = -3.0, focusNote = null)
            advanceUntilIdle()

            assertEquals(0.0, timeTrackingController.lastFocusRating, 0.0)
        }

    /** A blank note is no note: an empty string would be stored as a rating the user never wrote. */
    @Test
    fun `stopping treats a blank focus note as no note`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.stopTracking(focusRating = 0.8, focusNote = "   ")
            advanceUntilIdle()

            assertNull(timeTrackingController.lastFocusNote)
        }

    @Test
    fun `stopping keeps a real focus note`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.stopTracking(focusRating = 0.8, focusNote = "  deep work  ")
            advanceUntilIdle()

            assertEquals("deep work", timeTrackingController.lastFocusNote)
            assertEquals(0.8, timeTrackingController.lastFocusRating, 0.0)
        }

    /** A platform that refuses the stop is reported rather than swallowed. */
    @Test
    fun `a failing stop surfaces the error`() =
        runTest {
            timeTrackingController.failStop = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.stopTracking()
            advanceUntilIdle()

            assertEquals("stop failed", viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // updateTimeEntry
    // ────────────────────────────────────────────────────────────────

    /** An edit writes the window it was given and reloads the day. */
    @Test
    fun `updating an entry writes the new window`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.updateTimeEntry(
                entryId = "entry-1",
                dimensionId = "d2",
                dimensionLabel = "Learn",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = TASKS_TEST_DAY,
                endTime = LocalTime.of(10, 30),
                focusRating = null,
                focusNote = null,
            )
            advanceUntilIdle()

            val update = timeEntryRepository.updated.single()
            assertEquals("entry-1", update.first)
            assertEquals("d2", update.second.dimensionId)
            assertEquals(TASKS_TEST_DAY.atTime(9, 0), update.second.startedAt)
            assertEquals(TASKS_TEST_DAY.atTime(10, 30), update.second.endedAt)
        }

    /** An edit without an end leaves the entry running rather than half-closing it. */
    @Test
    fun `updating an entry without an end time leaves it running`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.updateTimeEntry(
                entryId = "entry-1",
                dimensionId = "d2",
                dimensionLabel = "Learn",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = null,
                endTime = null,
                focusRating = null,
                focusNote = null,
            )
            advanceUntilIdle()

            assertNull(
                timeEntryRepository.updated
                    .single()
                    .second.endedAt,
            )
        }

    /** Rating an edit stamps WHEN it was rated, so a later rating is distinguishable from this one. */
    @Test
    fun `rating an edit stamps the moment it was rated`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.updateTimeEntry(
                entryId = "entry-1",
                dimensionId = "d2",
                dimensionLabel = "Learn",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = null,
                endTime = null,
                focusRating = 0.5,
                focusNote = null,
            )
            advanceUntilIdle()

            assertNotNull(
                timeEntryRepository.updated
                    .single()
                    .second.focusRatedAt,
            )
        }

    /** An edit with no rating carries no rating timestamp: nothing was rated. */
    @Test
    fun `an unrated edit carries no rating timestamp`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.updateTimeEntry(
                entryId = "entry-1",
                dimensionId = "d2",
                dimensionLabel = "Learn",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = null,
                endTime = null,
                focusRating = null,
                focusNote = null,
            )
            advanceUntilIdle()

            assertNull(
                timeEntryRepository.updated
                    .single()
                    .second.focusRatedAt,
            )
        }

    /** A note alone is still a rating of the work, so it stamps the moment. */
    @Test
    fun `a note without a rating still stamps the rating moment`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.updateTimeEntry(
                entryId = "entry-1",
                dimensionId = "d2",
                dimensionLabel = "Learn",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = null,
                endTime = null,
                focusRating = null,
                focusNote = "hard",
            )
            advanceUntilIdle()

            assertNotNull(
                timeEntryRepository.updated
                    .single()
                    .second.focusRatedAt,
            )
        }

    /** An edit clamps its rating for the same reason stopping does. */
    @Test
    fun `updating an entry clamps the rating`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.updateTimeEntry(
                entryId = "entry-1",
                dimensionId = "d2",
                dimensionLabel = "Learn",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = null,
                endTime = null,
                focusRating = 9.0,
                focusNote = null,
            )
            advanceUntilIdle()

            assertEquals(
                1.0,
                timeEntryRepository.updated
                    .single()
                    .second.focusRating ?: -1.0,
                0.0,
            )
        }

    /** A store that refuses the edit is reported. */
    @Test
    fun `a failing update surfaces the error`() =
        runTest {
            timeEntryRepository.failUpdate = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.updateTimeEntry(
                entryId = "entry-1",
                dimensionId = "d2",
                dimensionLabel = "Learn",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = null,
                endTime = null,
                focusRating = null,
                focusNote = null,
            )
            advanceUntilIdle()

            assertEquals("update failed", viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // deleteTimeEntry + task-completion revert
    // ────────────────────────────────────────────────────────────────

    /** Deleting an entry removes it from the store and from the day. */
    @Test
    fun `deleting an entry removes it`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertEquals(listOf("entry-1"), timeEntryRepository.deleted)
            assertTrue(
                viewModel.uiState.value.timeEntries
                    .isEmpty(),
            )
        }

    /**
     * Deleting an entry that completed a task puts the task back to pending: the work was not done, so
     * neither was the task, and leaving it "completed" would hide it from every list.
     */
    @Test
    fun `deleting an entry reverts its one-time completed task to pending`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1", taskId = "task-1"))
            taskRepository.tasks.value = listOf(task(id = "task-1", status = "completed"))

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertEquals(
                "pending",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
        }

    /** A RECURRING task due today goes back to pending too - today's instance was undone. */
    @Test
    fun `deleting an entry reverts a due-today recurring task to pending`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1", taskId = "task-1"))
            taskRepository.tasks.value =
                listOf(task(id = "task-1", status = "completed", recurrenceEnabled = true))

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertEquals(
                "pending",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
        }

    /**
     * A recurring task due in the PAST cannot go back to pending - the date has passed - so it is marked
     * missed instead, which is what the recurrence rule expects of an undone past instance.
     */
    @Test
    fun `deleting an entry marks a past recurring task missed`() =
        runTest {
            val past = TASKS_TEST_DAY.minusDays(4).atStartOfDay()
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1", taskId = "task-1"))
            taskRepository.tasks.value =
                listOf(
                    task(id = "task-1", status = "completed", recurrenceEnabled = true, dueDate = past),
                )

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertEquals(
                "missed",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
        }

    /** A recurring task with no due date is treated as due now, so it reverts rather than being missed. */
    @Test
    fun `deleting an entry reverts an undated recurring task to pending`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1", taskId = "task-1"))
            taskRepository.tasks.value =
                listOf(
                    task(id = "task-1", status = "completed", recurrenceEnabled = true, dueDate = null),
                )

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertEquals(
                "pending",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
        }

    /** A task that was not completed has nothing to revert. */
    @Test
    fun `deleting an entry leaves an incomplete task alone`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1", taskId = "task-1"))
            taskRepository.tasks.value = listOf(task(id = "task-1", status = "pending"))

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertEquals(
                "pending",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
        }

    /** A task that has since been removed from the store has nothing to revert. */
    @Test
    fun `deleting an entry whose task is gone does not fail`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1", taskId = "task-1"))

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
            assertEquals(listOf("entry-1"), timeEntryRepository.deleted)
        }

    /**
     * A revert that fails does not undo the deletion the user asked for: the entry is gone either way,
     * and reporting a failed DELETE for a failed REVERT would be a lie.
     */
    @Test
    fun `a failing revert still deletes the entry and reports nothing`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1", taskId = "task-1"))
            taskRepository.tasks.value = listOf(task(id = "task-1", status = "completed"))
            taskRepository.failUpdate = true

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertEquals(listOf("entry-1"), timeEntryRepository.deleted)
            assertNull(viewModel.uiState.value.error)
        }

    /** An entry with no task attached deletes without touching any task. */
    @Test
    fun `deleting an entry with no task touches no task`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("entry-1", taskId = null))
            taskRepository.tasks.value = listOf(task(id = "task-1", status = "completed"))

            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertEquals(
                "completed",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
        }

    /** A store that refuses the delete is reported. */
    @Test
    fun `a failing delete surfaces the error`() =
        runTest {
            timeEntryRepository.failDelete = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.deleteTimeEntry("entry-1")
            advanceUntilIdle()

            assertEquals("delete failed", viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // createManualEntry
    // ────────────────────────────────────────────────────────────────

    /** A manual entry is created finished: the window is given whole, so it is stored with its end. */
    @Test
    fun `creating a manual entry stores it finished`() =
        runTest {
            var created: TimeEntry? = null
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.createManualEntry(
                dimensionId = "d1",
                dimensionLabel = "Build",
                taskId = "task-1",
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = TASKS_TEST_DAY,
                endTime = LocalTime.of(10, 0),
                onCreated = { created = it },
            )
            advanceUntilIdle()

            val input = timeEntryRepository.created.single()
            assertEquals(TASKS_TEST_DAY.atTime(9, 0), input.startedAt)
            assertEquals(TASKS_TEST_DAY.atTime(10, 0), input.endedAt)
            assertEquals("manual-1", created?.id)
        }

    /** A manual entry with no rating carries no rating timestamp. */
    @Test
    fun `creating a manual entry without a rating carries no rating timestamp`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.createManualEntry(
                dimensionId = "d1",
                dimensionLabel = "Build",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = TASKS_TEST_DAY,
                endTime = LocalTime.of(10, 0),
            )
            advanceUntilIdle()

            assertNull(timeEntryRepository.created.single().focusRatedAt)
            assertNull(timeEntryRepository.created.single().focusRating)
        }

    @Test
    fun `creating a manual entry with a rating stamps the rating moment`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.createManualEntry(
                dimensionId = "d1",
                dimensionLabel = "Build",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = TASKS_TEST_DAY,
                endTime = LocalTime.of(10, 0),
                focusRating = 2.0,
                focusNote = "  ",
            )
            advanceUntilIdle()

            assertNotNull(timeEntryRepository.created.single().focusRatedAt)
            assertEquals(1.0, timeEntryRepository.created.single().focusRating ?: -1.0, 0.0)
            assertNull(timeEntryRepository.created.single().focusNote)
        }

    /** A store that refuses the manual entry is reported. */
    @Test
    fun `a failing manual create surfaces the error`() =
        runTest {
            timeEntryRepository.failCreate = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.createManualEntry(
                dimensionId = "d1",
                dimensionLabel = "Build",
                taskId = null,
                startDate = TASKS_TEST_DAY,
                startTime = LocalTime.of(9, 0),
                endDate = TASKS_TEST_DAY,
                endTime = LocalTime.of(10, 0),
            )
            advanceUntilIdle()

            assertEquals("create failed", viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // clearError
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `clearError drops the message and leaves the rest of the state`() =
        runTest {
            timeEntryRepository.failEntries = true
            val viewModel = viewModel()
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.error)

            viewModel.clearError()

            assertNull(viewModel.uiState.value.error)
            assertTrue(viewModel.uiState.value.isDateContentReady)
        }

    // ────────────────────────────────────────────────────────────────
    // continueLastSession / continueEntry
    // ────────────────────────────────────────────────────────────────

    /** With nothing finished to continue from, there is nothing to do. */
    @Test
    fun `continuing with no last entry does nothing`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.continueLastSession()
            advanceUntilIdle()

            assertEquals(0, timeEntryRepository.updated.size)
            assertTrue(timeTrackingController.started.isEmpty())
        }

    /** Continuing re-opens the last finished entry as the running one. */
    @Test
    fun `continuing the last session re-opens it as running`() =
        runTest {
            val finished = entry("last", endedAt = TASKS_TEST_TIME)
            timeEntryRepository.allEntries = listOf(finished)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.continueLastSession()
            advanceUntilIdle()

            val update = timeEntryRepository.updated.single()
            assertEquals("last", update.first)
            assertNull(update.second.endedAt)
            assertEquals(1, timeTrackingController.started.size)
        }

    /** An entry outside the selected day can still be continued by id. */
    @Test
    fun `continuing an entry by id works outside the selected day`() =
        runTest {
            timeEntryRepository.allEntries = listOf(entry("elsewhere", endedAt = TASKS_TEST_TIME))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.continueEntry("elsewhere")
            advanceUntilIdle()

            assertEquals("elsewhere", timeEntryRepository.updated.single().first)
        }

    /** Continuing names the session after its task when it has one. */
    @Test
    fun `continuing an attached entry hands the platform the task title`() =
        runTest {
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] =
                listOf(entry("attached", taskId = "task-1", endedAt = TASKS_TEST_TIME))
            taskRepository.tasks.value = listOf(task(id = "task-1", title = "Named work"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.continueEntry("attached")
            advanceUntilIdle()

            assertEquals("Named work", timeTrackingController.started.single().taskTitle)
        }

    /**
     * An entry with no dimension is still continuable: the screen needs a dimension id to hand the
     * platform, so an unassigned one stands in rather than the session being refused.
     */
    @Test
    fun `continuing an entry with no dimension uses the unassigned id`() =
        runTest {
            timeEntryRepository.allEntries =
                listOf(entry("nodim", dimensionId = null, endedAt = TASKS_TEST_TIME))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.continueEntry("nodim")
            advanceUntilIdle()

            assertNull(timeTrackingController.started.single().taskId)
            assertEquals("Build", timeTrackingController.started.single().dimension)
        }

    /** An unknown id is refused before anything is written: continuing nothing must write nothing. */
    @Test
    fun `continuing an unknown entry writes nothing`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.continueEntry("does-not-exist")
            advanceUntilIdle()

            assertTrue(timeEntryRepository.updated.isEmpty())
            assertTrue(timeTrackingController.started.isEmpty())
        }

    /** A store that refuses the re-open is reported. */
    @Test
    fun `a failing continue surfaces the error`() =
        runTest {
            timeEntryRepository.allEntries = listOf(entry("last", endedAt = TASKS_TEST_TIME))
            timeEntryRepository.failUpdate = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.continueLastSession()
            advanceUntilIdle()

            assertEquals("update failed", viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // completeTask
    // ────────────────────────────────────────────────────────────────

    /** Completing a one-time task completes it and cancels its reminder. */
    @Test
    fun `completing a one-time task completes it and cancels its reminder`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("task-1", note = "done")
            advanceUntilIdle()

            assertEquals(
                "completed",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
            assertEquals(listOf("task-1"), reminderScheduler.cancelled)
            assertTrue(taskOccurrenceRepository.occurrences.value.isEmpty())
        }

    /** An unknown task is not completed: there is nothing to mark. */
    @Test
    fun `completing an unknown task changes nothing`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("does-not-exist", note = null)
            advanceUntilIdle()

            assertTrue(taskOccurrenceRepository.occurrences.value.isEmpty())
            assertTrue(reminderScheduler.cancelled.isEmpty())
        }

    /**
     * A recurring task is completed AND its occurrence recorded: the completion is an instance of the
     * rule, so both the task row and the dated occurrence have to show it.
     */
    @Test
    fun `completing a recurring task records an occurrence and advances the rule`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1", recurrenceEnabled = true))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("task-1", note = "done")
            advanceUntilIdle()

            val occurrence = taskOccurrenceRepository.occurrences.value.single()
            assertEquals("task-1", occurrence.taskId)
            assertEquals("completed", occurrence.status)
            assertEquals("done", occurrence.statusNote)
            assertEquals(listOf("task-1"), recurrenceHandler.completed)
        }

    /** Completing a recurring task schedules its next reminder: the rule still has dates ahead. */
    @Test
    fun `completing a recurring task schedules the next reminder`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1", recurrenceEnabled = true))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("task-1", note = null)
            advanceUntilIdle()

            assertEquals(listOf("task-1"), reminderScheduler.scheduled)
            assertTrue(reminderScheduler.cancelled.isEmpty())
        }

    /**
     * A FREQUENCY habit (a target number of times a period, not a date rule) is not marked completed by
     * one instance - the recurrence port owns its completion, so the screen must not also complete it.
     */
    @Test
    fun `completing a frequency habit leaves the completion to the recurrence port`() =
        runTest {
            recurrenceHandler.frequencyHabit = true
            taskRepository.tasks.value = listOf(task(id = "habit-1", recurrenceEnabled = true))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("habit-1", note = null)
            advanceUntilIdle()

            assertEquals(
                "pending",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
            assertEquals(1, taskOccurrenceRepository.occurrences.value.size)
            assertEquals(listOf("habit-1"), recurrenceHandler.completed)
        }

    /** The explicit form carries when and how long, which is what the occurrence row records. */
    @Test
    fun `completing with details records when and how long`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1", recurrenceEnabled = true))
            val completedAt = TASKS_TEST_TIME.plusHours(3)
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTaskWithDetails(
                taskId = "task-1",
                note = "done",
                actualCompletedAt = completedAt,
                actualDurationMinutes = 45,
            )
            advanceUntilIdle()

            val occurrence = taskOccurrenceRepository.occurrences.value.single()
            assertEquals(completedAt, occurrence.actualCompletedAt)
            assertEquals(45, occurrence.actualDurationMinutes)
        }

    /** A recurring task with no due date records the occurrence against today. */
    @Test
    fun `completing an undated recurring task records the occurrence today`() =
        runTest {
            taskRepository.tasks.value =
                listOf(task(id = "task-1", recurrenceEnabled = true, dueDate = null))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("task-1", note = null)
            advanceUntilIdle()

            assertTrue(
                taskOccurrenceRepository.occurrences.value
                    .single()
                    .occurrenceDate
                    .startsWith(LocalDate.now().toString()),
            )
        }

    /** A store that refuses the completion is reported. */
    @Test
    fun `a failing complete surfaces the error`() =
        runTest {
            taskRepository.failComplete = true
            taskRepository.tasks.value = listOf(task(id = "task-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("task-1", note = null)
            advanceUntilIdle()

            assertEquals("complete failed", viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // skipTask
    // ────────────────────────────────────────────────────────────────

    /** Skipping a one-time task skips it and cancels its reminder. */
    @Test
    fun `skipping a one-time task skips it and cancels its reminder`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.skipTask("task-1", note = "no time")
            advanceUntilIdle()

            assertEquals(
                "skipped",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
            assertEquals(listOf("task-1"), reminderScheduler.cancelled)
        }

    @Test
    fun `skipping an unknown task changes nothing`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.skipTask("does-not-exist", note = null)
            advanceUntilIdle()

            assertTrue(reminderScheduler.cancelled.isEmpty())
        }

    /** A skipped recurring task decays: the occurrence and the rule both record the skip. */
    @Test
    fun `skipping a recurring task records the occurrence and applies decay`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1", recurrenceEnabled = true))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.skipTask("task-1", note = "no time")
            advanceUntilIdle()

            assertEquals(
                "skipped",
                taskOccurrenceRepository.occurrences.value
                    .single()
                    .status,
            )
            assertEquals(listOf("task-1"), recurrenceHandler.skipped)
            assertEquals(listOf("task-1"), reminderScheduler.scheduled)
        }

    /** A frequency habit's skip is the recurrence port's to record, not the task row's. */
    @Test
    fun `skipping a frequency habit leaves the skip to the recurrence port`() =
        runTest {
            recurrenceHandler.frequencyHabit = true
            taskRepository.tasks.value = listOf(task(id = "habit-1", recurrenceEnabled = true))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.skipTask("habit-1", note = null)
            advanceUntilIdle()

            assertEquals(
                "pending",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
            assertEquals(listOf("habit-1"), recurrenceHandler.skipped)
        }

    /** A store that refuses the skip is reported. */
    @Test
    fun `a failing skip surfaces the error`() =
        runTest {
            taskRepository.failSkip = true
            taskRepository.tasks.value = listOf(task(id = "task-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.skipTask("task-1", note = null)
            advanceUntilIdle()

            assertEquals("skip failed", viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // missTask
    // ────────────────────────────────────────────────────────────────

    /** Missing a one-time task misses it and cancels its reminder. */
    @Test
    fun `missing a one-time task misses it and cancels its reminder`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.missTask("task-1", note = "blocked")
            advanceUntilIdle()

            assertEquals(
                "missed",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
            assertEquals(listOf("task-1"), reminderScheduler.cancelled)
        }

    @Test
    fun `missing an unknown task changes nothing`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.missTask("does-not-exist", note = null)
            advanceUntilIdle()

            assertTrue(reminderScheduler.cancelled.isEmpty())
        }

    /** A missed recurring task decays the same way a skipped one does. */
    @Test
    fun `missing a recurring task records the occurrence and applies decay`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1", recurrenceEnabled = true))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.missTask("task-1", note = "blocked")
            advanceUntilIdle()

            assertEquals(
                "missed",
                taskOccurrenceRepository.occurrences.value
                    .single()
                    .status,
            )
            assertEquals(listOf("task-1"), recurrenceHandler.missed)
            assertEquals(listOf("task-1"), reminderScheduler.scheduled)
        }

    /** A frequency habit's miss is the recurrence port's to record. */
    @Test
    fun `missing a frequency habit leaves the miss to the recurrence port`() =
        runTest {
            recurrenceHandler.frequencyHabit = true
            taskRepository.tasks.value = listOf(task(id = "habit-1", recurrenceEnabled = true))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.missTask("habit-1", note = null)
            advanceUntilIdle()

            assertEquals(
                "pending",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
            assertEquals(listOf("habit-1"), recurrenceHandler.missed)
        }

    /** A store that refuses the miss is reported. */
    @Test
    fun `a failing miss surfaces the error`() =
        runTest {
            taskRepository.failMiss = true
            taskRepository.tasks.value = listOf(task(id = "task-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.missTask("task-1", note = null)
            advanceUntilIdle()

            assertEquals("miss failed", viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // archiveTask / deleteTask
    // ────────────────────────────────────────────────────────────────

    /** Archiving takes the task out of the active lists. */
    @Test
    fun `archiving a task archives it`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.archiveTask("task-1")
            advanceUntilIdle()

            assertEquals(
                "archived",
                taskRepository.tasks.value
                    .single()
                    .status,
            )
        }

    /** A store that refuses the archive is reported. */
    @Test
    fun `a failing archive surfaces the error`() =
        runTest {
            taskRepository.failArchive = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.archiveTask("task-1")
            advanceUntilIdle()

            assertEquals("archive failed", viewModel.uiState.value.error)
        }

    /** Deleting a task from the Time screen cancels its reminder first: a deleted task must not nag. */
    @Test
    fun `deleting a task cancels its reminder first`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.deleteTask("task-1")
            advanceUntilIdle()

            assertEquals(listOf("task-1"), reminderScheduler.cancelled)
            assertTrue(taskRepository.tasks.value.isEmpty())
        }

    /** A store that refuses the delete is reported. */
    @Test
    fun `a failing task delete surfaces the error`() =
        runTest {
            taskRepository.failDelete = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.deleteTask("task-1")
            advanceUntilIdle()

            assertEquals("delete failed", viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // Reminder-scheduling edge cases
    // ────────────────────────────────────────────────────────────────

    /** A rule that turned recurrence OFF by the time the reminder is due schedules nothing. */
    @Test
    fun `no reminder is scheduled when the task is no longer recurring`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1", recurrenceEnabled = true))
            // The completion below reads the task back with recurrence off, as a rule rewrite would leave it.
            taskRepository.recurrenceOnRead = false
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("task-1", note = null)
            advanceUntilIdle()

            assertTrue(reminderScheduler.scheduled.isEmpty())
        }

    /** A task that vanishes between completing and scheduling schedules nothing and reports nothing. */
    @Test
    fun `no reminder is scheduled when the task has been removed`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1", recurrenceEnabled = true))
            taskRepository.dropOnSecondRead = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("task-1", note = null)
            advanceUntilIdle()

            assertTrue(reminderScheduler.scheduled.isEmpty())
            assertNull(viewModel.uiState.value.error)
        }

    /**
     * A reminder that cannot be scheduled is logged, not surfaced: the user's work was recorded, and
     * reporting a failed NOTIFICATION as a failed completion would be a lie about what happened.
     */
    @Test
    fun `a failing reminder schedule does not surface an error`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1", recurrenceEnabled = true))
            reminderScheduler.failSchedule = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("task-1", note = null)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
            assertEquals(
                "completed",
                taskOccurrenceRepository.occurrences.value
                    .single()
                    .status,
            )
        }

    /** The same holds for a cancellation that fails. */
    @Test
    fun `a failing reminder cancel does not surface an error`() =
        runTest {
            taskRepository.tasks.value = listOf(task(id = "task-1"))
            reminderScheduler.failCancel = true
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.completeTask("task-1", note = null)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.error)
        }

    // ────────────────────────────────────────────────────────────────
    // dispose
    // ────────────────────────────────────────────────────────────────

    /**
     * After disposal the ViewModel starts no further WORK: the scope that ran its collectors is gone, so
     * a later date load never reads the day it asked for.
     *
     * The selection still moves, because `loadEntriesForDate` records the chosen date in state
     * synchronously and the only thing disposal stops is the coroutine work that follows. Asserting on
     * the date instead of the read would pass even with a live scope, so it would not be a test of this.
     */
    @Test
    fun `dispose stops the view model reading the next day`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()
            assertTrue(
                viewModel.uiState.value.timeEntries
                    .isEmpty(),
            )

            viewModel.dispose()
            viewModel.loadEntriesForDate(TASKS_TEST_DAY)
            timeEntryRepository.entriesByDate[TASKS_TEST_DAY] = listOf(entry("after-dispose"))
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.timeEntries
                    .isEmpty(),
            )
        }

    // ────────────────────────────────────────────────────────────────
    // Factory
    // ────────────────────────────────────────────────────────────────

    /** The factory wires the platform's installed repositories, so neither platform hand-wires its own. */
    @Test
    fun `factory builds a view model from the installed data layer`() =
        runTest {
            LocalDataLayer.install(
                object : PayanamDataLayer by UninstalledDataLayer {
                    override val tasks: TaskRepository = taskRepository
                    override val timeEntries: TimeEntryRepository = timeEntryRepository
                    override val taskOccurrences: TaskOccurrenceRepository = taskOccurrenceRepository
                    override val reminderScheduler: TaskReminderScheduler = this@TimeViewModelTest.reminderScheduler
                    override val timeTrackingController: TimeTrackingController = this@TimeViewModelTest.timeTrackingController
                    override val timeTrackingWidgetRefresher: TimeTrackingWidgetRefresher =
                        this@TimeViewModelTest.widgetRefresher
                    override val recurrenceHandler: TaskRecurrenceHandler = this@TimeViewModelTest.recurrenceHandler
                },
            )

            val viewModel = timeViewModel(newViewModelScope())
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.isDateContentReady)
        }

    /** An uninstalled data layer is refused by name, so the missing installation is obvious. */
    @Test
    fun `reading the uninstalled data layer for tasks throws`() {
        val failure =
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                @Suppress("UnusedExpression")
                LocalDataLayer.current.tasks
            }
        assertTrue(failure.message!!.contains("before a data layer was installed"))
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────

    private fun entry(
        id: String,
        taskId: String? = null,
        startedAt: LocalDateTime = TASKS_TEST_TIME,
        endedAt: LocalDateTime? = startedAt,
        dimensionId: String? = null,
    ) = TimeEntry(
        id = id,
        lifeIntentionCategory = "Build",
        taskId = taskId,
        startedAt = startedAt,
        endedAt = endedAt,
        createdAt = startedAt,
        updatedAt = startedAt,
        dimensionId = dimensionId,
    )

    // ────────────────────────────────────────────────────────────────
    // Fakes for the ports the Time screen drives
    // ────────────────────────────────────────────────────────────────

    /**
     * The time-entry store, recording every write so a test can assert on the payload the ViewModel
     * ASSEMBLED rather than on that a call happened.
     */
    private class FakeTimeEntryRepository : TimeEntryRepository {
        val entriesByDate = mutableMapOf<LocalDate, List<TimeEntry>>()
        val started = mutableListOf<TimeEntryInput>()
        val created = mutableListOf<TimeEntryInput>()
        val updated = mutableListOf<Pair<String, TimeEntryInput>>()
        val deleted = mutableListOf<String>()

        var activeEntry: TimeEntry? = null
        var allEntries: List<TimeEntry> = emptyList()
        var stopCalls = 0

        var failEntries = false
        var failStart = false
        var failCreate = false
        var failUpdate = false
        var failDelete = false

        override fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>> =
            if (failEntries) {
                flow { throw IllegalStateException("entries unavailable") }
            } else {
                flowOf(entriesByDate[date].orEmpty())
            }

        override fun observeActiveTimeEntry(): Flow<TimeEntry?> = flowOf(activeEntry)

        override fun getAllTimeEntries(): Flow<List<TimeEntry>> = flowOf(allEntries)

        override suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry {
            if (failStart) throw IllegalStateException("start failed")
            started += input
            val entry =
                TimeEntry(
                    id = "start-${started.size}",
                    lifeIntentionCategory = input.lifeIntentionCategory,
                    taskId = input.taskId,
                    startedAt = input.startedAt,
                    endedAt = null,
                    createdAt = input.startedAt,
                    updatedAt = input.startedAt,
                    dimensionId = input.dimensionId,
                )
            entriesByDate[input.startedAt.toLocalDate()] =
                entriesByDate.getOrElse(input.startedAt.toLocalDate()) { emptyList() } + entry
            activeEntry = entry
            return entry
        }

        override suspend fun stopActiveTimeEntry(): TimeEntry? {
            stopCalls++
            activeEntry = null
            return null
        }

        override suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry {
            if (failCreate) throw IllegalStateException("create failed")
            created += input
            return TimeEntry(
                id = "manual-${created.size}",
                lifeIntentionCategory = input.lifeIntentionCategory,
                taskId = input.taskId,
                startedAt = input.startedAt,
                endedAt = input.endedAt,
                createdAt = input.startedAt,
                updatedAt = input.startedAt,
                dimensionId = input.dimensionId,
            )
        }

        override suspend fun updateTimeEntry(
            id: String,
            input: TimeEntryInput,
        ): TimeEntry {
            if (failUpdate) throw IllegalStateException("update failed")
            updated += id to input
            return TimeEntry(
                id = id,
                lifeIntentionCategory = input.lifeIntentionCategory,
                taskId = input.taskId,
                startedAt = input.startedAt,
                endedAt = input.endedAt,
                focusRating = input.focusRating,
                focusNote = input.focusNote,
                focusRatedAt = input.focusRatedAt,
                createdAt = input.startedAt,
                updatedAt = input.startedAt,
                dimensionId = input.dimensionId,
            )
        }

        override suspend fun deleteTimeEntry(id: String) {
            if (failDelete) throw IllegalStateException("delete failed")
            deleted += id
            entriesByDate.replaceAll { _, entries -> entries.filterNot { it.id == id } }
            activeEntry = null
        }

        // --- Members the Time screen never calls ---

        override suspend fun getActiveTimeEntry(): TimeEntry? = activeEntry

        override suspend fun stopActiveTimeEntryWithFocus(
            focusRating: Double,
            focusNote: String?,
        ): TimeEntry? = null

        override fun getTimeEntriesForRange(
            start: LocalDateTime,
            end: LocalDateTime,
        ): Flow<List<TimeEntry>> = flowOf(emptyList())

        override fun getActiveTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

        override suspend fun updateTimeEntry(entry: TimeEntry) = Unit
    }

    /**
     * The tracking controller: records what the platform was asked to do, and can be told to fail so the
     * screen's error path is reachable.
     */
    private class FakeTimeTrackingController : TimeTrackingController {
        data class Start(
            val taskId: String?,
            val taskTitle: String?,
            val dimension: String,
            val startTime: LocalDateTime,
        )

        val started = mutableListOf<Start>()
        var stopCalls = 0
        var lastFocusRating = 0.0
        var lastFocusNote: String? = null
        var failStop = false

        override suspend fun startTracking(
            taskId: String?,
            taskTitle: String?,
            dimension: String,
            startTime: LocalDateTime,
        ) {
            started += Start(taskId, taskTitle, dimension, startTime)
        }

        override fun stopTracking() {
            stopCalls++
        }

        override suspend fun stopTrackingAndCompleteTask(
            focusRating: Double,
            focusNote: String?,
        ): TimeEntry? {
            if (failStop) throw IllegalStateException("stop failed")
            lastFocusRating = focusRating
            lastFocusNote = focusNote
            return null
        }
    }

    /** The widget refresher: counts repaints, because the start path must not cause one. */
    private class FakeTimeTrackingWidgetRefresher : TimeTrackingWidgetRefresher {
        var refreshes = 0

        override fun refreshWidget() {
            refreshes++
        }
    }
}
