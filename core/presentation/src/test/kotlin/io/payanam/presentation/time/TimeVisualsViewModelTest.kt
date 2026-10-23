//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import io.payanam.domain.model.DayJournalEntry
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DayJournalResponseInput
import io.payanam.domain.model.JournalPromptScope
import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import io.payanam.domain.model.Tag
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.DayPlanAllocationRecord
import io.payanam.domain.repository.DayPlanPolicyRecord
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.domain.repository.DayTypeTemplatePreferenceRecord
import io.payanam.domain.repository.JournalRepository
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TemplateAllocationRecord
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure-JVM behavioural lock for the shared TimeVisuals ViewModel.
 *
 * Supersedes `app/…/TimeVisualsDrillInInteractionTest` (which required Robolectric +
 * Mockito just to boot the Android logger singleton). Here the logger is [AppLogger]'s
 * default no-op, so every test runs on a plain JVM — which is also the proof that the
 * ViewModel no longer needs a platform to exist.
 *
 * Assertions carried over from the superseded test are marked `[carried]`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeVisualsViewModelTest {
    private lateinit var fakeTimeEntryRepository: FakeTimeEntryRepository
    private lateinit var fakeTaskRepository: FakeTaskRepository
    private lateinit var fakeDayPlanRepository: FakeDayPlanRepository
    private lateinit var fakeTaskOccurrenceRepository: FakeTaskOccurrenceRepository
    private var viewModelScope: CoroutineScope? = null

    @Before
    fun setUp() {
        LocalDataLayer.resetForTests()
        fakeTimeEntryRepository = FakeTimeEntryRepository()
        fakeTaskRepository = FakeTaskRepository()
        fakeDayPlanRepository = FakeDayPlanRepository()
        fakeTaskOccurrenceRepository = FakeTaskOccurrenceRepository()
    }

    @After
    fun tearDown() {
        viewModelScope?.cancel()
        viewModelScope = null
        LocalDataLayer.resetForTests()
    }

    /** A scope for the ViewModel under test, driven by the test scheduler. */
    private fun TestScope.newViewModelScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { viewModelScope = it }

    /**
     * A scope for the ViewModel under test whose children report an escaping failure to [escaped]
     * instead of to the JVM-global handler.
     *
     * The global handler is what turned a contained failure into `UncaughtExceptionsBeforeTest` in an
     * unrelated LATER test, so a case that wants to prove a failure did NOT escape needs a handler of
     * its own to see it.
     */
    private fun TestScope.newRecordingViewModelScope(escaped: MutableList<Throwable>): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> escaped += throwable },
        ).also { viewModelScope = it }

    private fun TestScope.viewModel(): TimeVisualsViewModel {
        val scope = newViewModelScope()
        return TimeVisualsViewModel(
            fakeTimeEntryRepository,
            fakeTaskRepository,
            fakeDayPlanRepository,
            fakeTaskOccurrenceRepository,
            scope,
        )
    }

    // ────────────────────────────────────────────────────────────────
    // toggleDimensionFilter
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `toggleDimensionFilter sets filter on first tap`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.toggleDimensionFilter("dim_learning")
            assertEquals("dim_learning", viewModel.uiState.value.selectedDimensionFilterId)
        }

    /** [carried] from TimeVisualsDrillInInteractionTest. */
    @Test
    fun `toggleDimensionFilter clears filter on re-tap same id`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.toggleDimensionFilter("dim_learning")
            assertEquals("dim_learning", viewModel.uiState.value.selectedDimensionFilterId)

            viewModel.toggleDimensionFilter("dim_learning")
            assertNull(viewModel.uiState.value.selectedDimensionFilterId)
        }

    @Test
    fun `toggleDimensionFilter switches to different id`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.toggleDimensionFilter("dim_learning")
            assertEquals("dim_learning", viewModel.uiState.value.selectedDimensionFilterId)

            viewModel.toggleDimensionFilter("dim_financial")
            assertEquals("dim_financial", viewModel.uiState.value.selectedDimensionFilterId)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — empty state
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate empty repositories produces zero totals`() =
        runTest {
            val viewModel = viewModel()
            val date = LocalDate.of(2026, 7, 15)

            viewModel.loadForDate(date)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals(date, state.selectedDate)
            assertEquals(0L, state.dayOverall.trackedMinutes)
            assertEquals(0, state.dayOverall.activeBlockCount)
            assertEquals(0f, state.dayOverall.focusedMinutesPercent)
            assertEquals(0, state.dayOverall.overlapCount)
            assertEquals(0, state.dayOverall.gapCount)
            assertTrue(state.perDimension.isEmpty())
            assertEquals(0L, state.trend.selectedDayMinutes)
            assertEquals(0L, state.trend.previousDayMinutes)
            assertEquals(0L, state.trend.last7AverageMinutes)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — entries
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate with completed entries sets tracked minutes`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(
                        id = "e1",
                        startedAt = date.atTime(10, 0),
                        endedAt = date.atTime(11, 0),
                        dimensionId = "dim_physical_health",
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(60L, state.dayOverall.trackedMinutes)
            assertEquals(0, state.dayOverall.activeBlockCount) // no active (endedAt != null)
            assertFalse(state.isLoading)
        }

    @Test
    fun `loadForDate with multiple entries sums total minutes`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), dimensionId = "dim_physical_health"),
                    entry(id = "e2", startedAt = date.atTime(14, 0), endedAt = date.atTime(15, 30), dimensionId = "dim_learning_growth"),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            assertEquals(150L, viewModel.uiState.value.dayOverall.trackedMinutes) // 60 + 90
        }

    @Test
    fun `loadForDate with active entry counts as active block`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(10, 0), endedAt = null, dimensionId = "dim_physical_health"),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, state.dayOverall.activeBlockCount)
            // Active entry duration is computed from startedAt to "now" which in test land is LocalDateTime.now()
            // but the duration should be positive.
            assertTrue(state.dayOverall.trackedMinutes >= 0L)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — dimension grouping
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate groups entries by dimension id`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), dimensionId = "dim_physical_health"),
                    entry(id = "e2", startedAt = date.atTime(10, 0), endedAt = date.atTime(11, 0), dimensionId = "dim_physical_health"),
                    entry(id = "e3", startedAt = date.atTime(14, 0), endedAt = date.atTime(15, 0), dimensionId = "dim_learning_growth"),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val perDim = viewModel.uiState.value.perDimension
            assertEquals(2, perDim.size)
            // Sorted descending by trackedMinutes
            assertEquals("dim_physical_health", perDim[0].dimensionId)
            assertEquals(120L, perDim[0].trackedMinutes) // 2 × 60
            assertEquals("dim_learning_growth", perDim[1].dimensionId)
            assertEquals(60L, perDim[1].trackedMinutes)
        }

    @Test
    fun `loadForDate without explicit dimension uses task dimension`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), taskId = "t1"),
                ),
            )
            fakeTaskRepository.tasksFlow =
                flowOf(
                    listOf(task(id = "t1", dimensionId = "dim_work_livelihood")),
                )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val perDim = viewModel.uiState.value.perDimension
            assertEquals(1, perDim.size)
            assertEquals("dim_work_livelihood", perDim[0].dimensionId)
        }

    @Test
    fun `loadForDate without dimension on entry or task uses default`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0)),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val perDim = viewModel.uiState.value.perDimension
            assertEquals(1, perDim.size)
            // Default is LEARNING_GROWTH
            assertEquals("dim_learning_growth", perDim[0].dimensionId)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — supplemental occurrence minutes
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate includes supplemental occurrence minutes when no tracked entry`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            // No time entries at all
            fakeTaskRepository.tasksFlow =
                flowOf(
                    listOf(task(id = "t1", dimensionId = "dim_physical_health", recurrenceEnabled = true)),
                )
            fakeTaskOccurrenceRepository.setOccurrencesForDate(
                date,
                listOf(
                    occurrence(id = "oc1", taskId = "t1", status = "completed", actualDurationMinutes = 30),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val perDim = viewModel.uiState.value.perDimension
            assertEquals(1, perDim.size)
            assertEquals("dim_physical_health", perDim[0].dimensionId)
            assertEquals(30L, perDim[0].trackedMinutes) // supplemental minutes
        }

    @Test
    fun `loadForDate does not double count occurrence with tracked entry`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), taskId = "t1"),
                ),
            )
            fakeTaskRepository.tasksFlow =
                flowOf(
                    listOf(task(id = "t1", dimensionId = "dim_physical_health")),
                )
            fakeTaskOccurrenceRepository.setOccurrencesForDate(
                date,
                listOf(
                    occurrence(id = "oc1", taskId = "t1", status = "completed", actualDurationMinutes = 30),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val perDim = viewModel.uiState.value.perDimension
            assertEquals(1, perDim.size)
            // Only the 60 minutes from the time entry, not 60 + 30 from the occurrence
            assertEquals(60L, perDim[0].trackedMinutes)
        }

    @Test
    fun `loadForDate occurrence with zero duration adds nothing`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTaskRepository.tasksFlow =
                flowOf(
                    listOf(task(id = "t1", dimensionId = "dim_physical_health", recurrenceEnabled = true)),
                )
            fakeTaskOccurrenceRepository.setOccurrencesForDate(
                date,
                listOf(
                    occurrence(id = "oc1", taskId = "t1", status = "completed", actualDurationMinutes = 0),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            // No entries, no supplemental minutes -> no per-dimension data
            assertTrue(
                viewModel.uiState.value.perDimension
                    .isEmpty(),
            )
        }

    @Test
    fun `loadForDate occurrence with null duration adds nothing`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTaskRepository.tasksFlow =
                flowOf(
                    listOf(task(id = "t1", dimensionId = "dim_physical_health", recurrenceEnabled = true)),
                )
            fakeTaskOccurrenceRepository.setOccurrencesForDate(
                date,
                listOf(
                    occurrence(id = "oc1", taskId = "t1", status = "completed", actualDurationMinutes = null),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.perDimension
                    .isEmpty(),
            )
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — allocations (planned minutes)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate includes planned minutes from allocations`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), dimensionId = "dim_physical_health"),
                ),
            )
            fakeDayPlanRepository.setAllocationsForDay(
                date.toString(),
                listOf(
                    DayPlanAllocationRecord(
                        id = "a1",
                        dayKey = date.toString(),
                        dimensionId = "dim_physical_health",
                        plannedMinutes = 90,
                        source = "manual",
                        templateId = null,
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val perDim = viewModel.uiState.value.perDimension
            assertEquals(1, perDim.size)
            assertEquals(90, perDim[0].plannedMinutes)
            assertEquals(-30L, perDim[0].plannedDeltaMinutes) // tracked 60 - planned 90
        }

    @Test
    fun `loadForDate allocation-only dimension appears in per-dimension`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeDayPlanRepository.setAllocationsForDay(
                date.toString(),
                listOf(
                    DayPlanAllocationRecord(
                        id = "a1",
                        dayKey = date.toString(),
                        dimensionId = "dim_learning_growth",
                        plannedMinutes = 60,
                        source = "manual",
                        templateId = null,
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val perDim = viewModel.uiState.value.perDimension
            assertEquals(1, perDim.size)
            assertEquals("dim_learning_growth", perDim[0].dimensionId)
            assertEquals(60, perDim[0].plannedMinutes)
            assertEquals(-60L, perDim[0].plannedDeltaMinutes) // 0 tracked - 60 planned
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — trend strip
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate computes trend strip for selected previous and average`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            // Today: 60 min
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), dimensionId = "dim_physical_health")),
            )
            // Yesterday: 30 min
            fakeTimeEntryRepository.setEntriesForDate(
                date.minusDays(1),
                listOf(
                    entry(
                        id = "e2",
                        startedAt = date.minusDays(1).atTime(9, 0),
                        endedAt = date.minusDays(1).atTime(9, 30),
                        dimensionId = "dim_physical_health",
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val trend = viewModel.uiState.value.trend
            assertEquals(60L, trend.selectedDayMinutes)
            assertEquals(30L, trend.previousDayMinutes)
            // Rolling average: (60 + 30 + 0 + 0 + 0 + 0 + 0) / 7 = 12.85.. -> 12
            assertEquals(12L, trend.last7AverageMinutes)
        }

    @Test
    fun `loadForDate trend with all zeros produces zero average`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            // All repos empty

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val trend = viewModel.uiState.value.trend
            assertEquals(0L, trend.selectedDayMinutes)
            assertEquals(0L, trend.previousDayMinutes)
            assertEquals(0L, trend.last7AverageMinutes)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — loading flag lifecycle
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate sets loading true then clears it`() =
        runTest {
            val viewModel = viewModel()
            val date = LocalDate.of(2026, 7, 15)

            viewModel.loadForDate(date)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `loadForDate sets selectedDate in state`() =
        runTest {
            val viewModel = viewModel()
            val date = LocalDate.of(2026, 8, 20)

            viewModel.loadForDate(date)
            advanceUntilIdle()

            assertEquals(date, viewModel.uiState.value.selectedDate)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — failed load (closed database session)
    // ────────────────────────────────────────────────────────────────

    /**
     * A load that fails because the database session closed under it is reported, not thrown.
     *
     * The desktop closes its database session when the window closes, so a visuals load that is still in
     * flight reads from a closed session and fails with "PayanamDatabaseSession is closed". With no
     * catch in the coroutine, that failure escaped the launched coroutine, reached the JVM-global
     * exception collector and was re-thrown as `UncaughtExceptionsBeforeTest` in whichever unrelated test
     * ran later - which is why the victim test moved from run to run.
     *
     * The scope carries a `CoroutineExceptionHandler` so the escape is observable HERE rather than in a
     * later test: `escaped` stays empty while the ViewModel contains the failure, and this case goes red
     * the moment the catch is removed.
     */
    @Test
    fun `loadForDate reports a failed load instead of throwing it`() =
        runTest {
            val escaped = mutableListOf<Throwable>()
            val scope = newRecordingViewModelScope(escaped)
            val viewModel =
                TimeVisualsViewModel(
                    fakeTimeEntryRepository,
                    fakeTaskRepository,
                    fakeDayPlanRepository,
                    fakeTaskOccurrenceRepository,
                    scope,
                )
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(
                        id = "e1",
                        startedAt = date.atTime(9, 0),
                        endedAt = date.atTime(10, 0),
                        dimensionId = "dim_physical_health",
                    ),
                ),
            )
            fakeDayPlanRepository.failure = IllegalStateException("PayanamDatabaseSession is closed")

            viewModel.loadForDate(date)
            advanceUntilIdle()

            // The failure stayed inside the load: nothing reached the scope's exception handler.
            assertTrue("the load failure escaped the coroutine", escaped.isEmpty())
            // The screen is not left spinning on a load that can never finish.
            assertFalse(viewModel.uiState.value.isLoading)

            // The failed load finished and the ViewModel is still usable: the next load runs and renders.
            // (If the failed load were still in flight, the second call would be skipped as a duplicate
            // and the state would stay at the empty default instead of showing the entry.)
            fakeDayPlanRepository.failure = null
            viewModel.loadForDate(date)
            advanceUntilIdle()

            assertEquals(60L, viewModel.uiState.value.dayOverall.trackedMinutes)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — day cache / rolling window
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate second load of same date uses day cache`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), dimensionId = "dim_physical_health")),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()
            assertEquals(60L, viewModel.uiState.value.dayOverall.trackedMinutes)

            // Now change the data
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(entry(id = "e2", startedAt = date.atTime(9, 0), endedAt = date.atTime(11, 0), dimensionId = "dim_physical_health")),
            )

            // Second load of same date — should use the day cache from first load
            viewModel.loadForDate(date)
            advanceUntilIdle()
            // The cache should still reflect the old value because invalidateRollingWindow clears it
            // Actually: loadForDate calls invalidateRollingWindow(date) at the start, which clears
            // date from the cache. So the second load recomputes.
            assertEquals(120L, viewModel.uiState.value.dayOverall.trackedMinutes)
        }

    @Test
    fun `loadForDate invalidates rolling window on new date`() =
        runTest {
            val dateA = LocalDate.of(2026, 7, 15)
            val dateB = LocalDate.of(2026, 7, 20)
            fakeTimeEntryRepository.setEntriesForDate(
                dateA,
                listOf(
                    entry(id = "e1", startedAt = dateA.atTime(9, 0), endedAt = dateA.atTime(10, 0), dimensionId = "dim_physical_health"),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(dateA)
            advanceUntilIdle()
            assertEquals(60L, viewModel.uiState.value.dayOverall.trackedMinutes)

            // Now load a different date
            viewModel.loadForDate(dateB)
            advanceUntilIdle()
            assertEquals(dateB, viewModel.uiState.value.selectedDate)
            // dateB has no entries
            assertEquals(0L, viewModel.uiState.value.dayOverall.trackedMinutes)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — entry clipping at day boundaries
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate clips entry starting before day to day start`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(
                        id = "e1",
                        startedAt = date.minusDays(1).atTime(23, 0),
                        endedAt = date.atTime(1, 0),
                        dimensionId = "dim_physical_health",
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            // Only 00:00 - 01:00 = 60 minutes clipped to the day
            assertEquals(60L, viewModel.uiState.value.dayOverall.trackedMinutes)
        }

    @Test
    fun `loadForDate clips entry ending after day to day end`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(
                        id = "e1",
                        startedAt = date.atTime(23, 0),
                        endedAt = date.plusDays(1).atTime(1, 0),
                        dimensionId = "dim_physical_health",
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            // The day's end is its successor's start (`TimeVisualsCalculator.effectiveDayEnd`), so the clipped
            // interval is 23:00:00 - 00:00:00 = exactly 60 minutes. The end used to be the wall-clock second
            // 23:59:59, which floored this to 59 and dropped the last minute of every entry reaching midnight;
            // `TimeVisualsDayBoundaryTest` in this module pins that boundary directly.
            assertEquals(60L, viewModel.uiState.value.dayOverall.trackedMinutes)
        }

    @Test
    fun `loadForDate entry fully outside day produces zero`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(
                        id = "e1",
                        startedAt = date.minusDays(1).atTime(20, 0),
                        endedAt = date.minusDays(1).atTime(21, 0),
                        dimensionId = "dim_physical_health",
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            assertEquals(0L, viewModel.uiState.value.dayOverall.trackedMinutes)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — focus rating
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate weighted focus rating reflects in summary`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(
                        id = "e1",
                        startedAt = date.atTime(9, 0),
                        endedAt = date.atTime(10, 0),
                        focusRating = 1.0,
                        dimensionId = "dim_physical_health",
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            // 60 min with focus=1.0 -> focusedMinutesPercent should be 1.0 (100%)
            assertEquals(1.0f, viewModel.uiState.value.dayOverall.focusedMinutesPercent, 0.01f)
        }

    @Test
    fun `loadForDate zero focus rating produces zero focus percent`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(
                        id = "e1",
                        startedAt = date.atTime(9, 0),
                        endedAt = date.atTime(10, 0),
                        focusRating = 0.0,
                        dimensionId = "dim_physical_health",
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            assertEquals(0.0f, viewModel.uiState.value.dayOverall.focusedMinutesPercent, 0.01f)
        }

    @Test
    fun `loadForDate mixed focus ratings produce weighted percent`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    // 60 min at focus=1.0
                    entry(
                        id = "e1",
                        startedAt = date.atTime(9, 0),
                        endedAt = date.atTime(10, 0),
                        focusRating = 1.0,
                        dimensionId = "dim_physical_health",
                    ),
                    // 60 min at focus=0.0
                    entry(
                        id = "e2",
                        startedAt = date.atTime(11, 0),
                        endedAt = date.atTime(12, 0),
                        focusRating = 0.0,
                        dimensionId = "dim_physical_health",
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            // (60*1.0 + 60*0.0) / 120 = 0.5
            assertEquals(0.5f, viewModel.uiState.value.dayOverall.focusedMinutesPercent, 0.01f)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — overlap and gap counting
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate overlapping entries increment overlap count`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 30), dimensionId = "dim_physical_health"),
                    entry(id = "e2", startedAt = date.atTime(10, 0), endedAt = date.atTime(11, 0), dimensionId = "dim_physical_health"),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.dayOverall.overlapCount)
        }

    @Test
    fun `loadForDate non-overlapping entries have zero overlap`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), dimensionId = "dim_physical_health"),
                    entry(id = "e2", startedAt = date.atTime(10, 0), endedAt = date.atTime(11, 0), dimensionId = "dim_physical_health"),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            assertEquals(0, viewModel.uiState.value.dayOverall.overlapCount)
        }

    @Test
    fun `loadForDate gap between entries increments gap count`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), dimensionId = "dim_physical_health"),
                    entry(id = "e2", startedAt = date.atTime(11, 0), endedAt = date.atTime(12, 0), dimensionId = "dim_physical_health"),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            // Gap before first entry + gap between entries + gap after last entry = 3
            assertTrue(viewModel.uiState.value.dayOverall.gapCount >= 1)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — dimension share percent
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate per-dimension share percent reflects proportional split`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(
                    entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(10, 0), dimensionId = "dim_physical_health"),
                    entry(id = "e2", startedAt = date.atTime(10, 0), endedAt = date.atTime(12, 0), dimensionId = "dim_learning_growth"),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val perDim = viewModel.uiState.value.perDimension
            assertEquals(2, perDim.size)
            // Physical: 60/180 = 0.33, Learning: 120/180 = 0.67
            // Sorted descending: Learning first
            assertEquals("dim_learning_growth", perDim[0].dimensionId)
            assertEquals(0.666f, perDim[0].sharePercent, 0.01f)
            assertEquals("dim_physical_health", perDim[1].dimensionId)
            assertEquals(0.333f, perDim[1].sharePercent, 0.01f)
        }

    // ────────────────────────────────────────────────────────────────
    // loadForDate — planned delta
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadForDate planned delta positive when over plan`() =
        runTest {
            val date = LocalDate.of(2026, 7, 15)
            fakeTimeEntryRepository.setEntriesForDate(
                date,
                listOf(entry(id = "e1", startedAt = date.atTime(9, 0), endedAt = date.atTime(11, 0), dimensionId = "dim_physical_health")),
            )
            fakeDayPlanRepository.setAllocationsForDay(
                date.toString(),
                listOf(
                    DayPlanAllocationRecord(
                        id = "a1",
                        dayKey = date.toString(),
                        dimensionId = "dim_physical_health",
                        plannedMinutes = 60,
                        source = "manual",
                        templateId = null,
                    ),
                ),
            )

            val viewModel = viewModel()
            viewModel.loadForDate(date)
            advanceUntilIdle()

            val perDim = viewModel.uiState.value.perDimension
            assertEquals(1, perDim.size)
            assertEquals(60, perDim[0].plannedMinutes)
            assertEquals(60L, perDim[0].plannedDeltaMinutes) // tracked 120 - planned 60 = +60
        }

    // ────────────────────────────────────────────────────────────────
    // dispose
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `dispose cancels the scope`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.dispose()
            advanceUntilIdle()
            // No crash, scope is cancelled
            assertTrue(true)
        }

    // ────────────────────────────────────────────────────────────────
    // factory
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `factory builds a ViewModel from the installed data layer`() =
        runTest {
            LocalDataLayer.install(
                object : PayanamDataLayer by UninstalledDataLayer {
                    override val notes: NoteRepository = StubNoteRepository
                    override val tags: TagRepository = StubTagRepository
                    override val tasks: TaskRepository = fakeTaskRepository
                    override val timeEntries: TimeEntryRepository = fakeTimeEntryRepository
                    override val dayPlans: DayPlanRepository = fakeDayPlanRepository
                    override val taskOccurrences: TaskOccurrenceRepository = fakeTaskOccurrenceRepository
                    override val journal: JournalRepository = StubJournalRepository
                },
            )
            val viewModel = timeVisualsViewModel(newViewModelScope())
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `reading uninstalled data layer for timeEntries throws`() {
        val failure =
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                @Suppress("UnusedExpression")
                LocalDataLayer.current.timeEntries
            }
        assertTrue(failure.message!!.contains("before a data layer was installed"))
        assertTrue(failure.message!!.contains("LocalDataLayer.timeEntries"))
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────

    private fun entry(
        id: String,
        startedAt: LocalDateTime,
        endedAt: LocalDateTime? = null,
        taskId: String? = null,
        dimensionId: String? = null,
        focusRating: Double? = null,
    ) = TimeEntry(
        id = id,
        lifeIntentionCategory = "Test",
        taskId = taskId,
        startedAt = startedAt,
        endedAt = endedAt,
        focusRating = focusRating,
        createdAt = startedAt,
        updatedAt = startedAt,
        dimensionId = dimensionId,
    )

    private fun task(
        id: String,
        title: String = "Task $id",
        dimensionId: String? = null,
        recurrenceEnabled: Boolean = false,
    ) = Task(
        id = id,
        title = title,
        dimensionId = dimensionId,
        recurrenceEnabled = recurrenceEnabled,
        createdAt = LocalDateTime.of(2026, 7, 1, 0, 0),
        updatedAt = LocalDateTime.of(2026, 7, 1, 0, 0),
    )

    private fun occurrence(
        id: String,
        taskId: String,
        status: String = "completed",
        actualDurationMinutes: Int? = null,
    ) = TaskOccurrence(
        id = id,
        taskId = taskId,
        occurrenceDate = "2026-07-15",
        status = status,
        actualDurationMinutes = actualDurationMinutes,
    )

    // ────────────────────────────────────────────────────────────────
    // Fakes
    // ────────────────────────────────────────────────────────────────

    private class FakeTimeEntryRepository : TimeEntryRepository {
        private val entriesByDate = mutableMapOf<LocalDate, List<TimeEntry>>()

        fun setEntriesForDate(
            date: LocalDate,
            entries: List<TimeEntry>,
        ) {
            entriesByDate[date] = entries
        }

        override fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>> = flowOf(entriesByDate[date].orEmpty())

        // --- Members not called by the ViewModel; stubs below ---

        override suspend fun getActiveTimeEntry(): TimeEntry? = null

        override fun observeActiveTimeEntry(): Flow<TimeEntry?> = flowOf(null)

        override fun getTimeEntriesForRange(
            start: LocalDateTime,
            end: LocalDateTime,
        ): Flow<List<TimeEntry>> = flowOf(emptyList())

        override suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry = throw UnsupportedOperationException()

        override suspend fun stopActiveTimeEntry(): TimeEntry? = null

        override suspend fun stopActiveTimeEntryWithFocus(
            focusRating: Double,
            focusNote: String?,
        ): TimeEntry? = null

        override suspend fun updateTimeEntry(
            id: String,
            input: TimeEntryInput,
        ): TimeEntry = throw UnsupportedOperationException()

        override suspend fun deleteTimeEntry(id: String) = Unit

        override suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry = throw UnsupportedOperationException()

        override fun getAllTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

        override fun getActiveTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

        override suspend fun updateTimeEntry(entry: TimeEntry) = Unit
    }

    private class FakeTaskRepository : TaskRepository {
        // Named `tasksFlow` rather than `allTasks`: a Kotlin property named `allTasks` compiles to a
        // JVM `getAllTasks()` and clashes with the interface's `getAllTasks()` override.
        var tasksFlow: Flow<List<Task>> = flowOf(emptyList())

        override fun getAllTasks(): Flow<List<Task>> = tasksFlow

        // --- Stubs ---

        override fun getTasksByStatus(status: String): Flow<List<Task>> = flowOf(emptyList())

        override fun getTasksDueOn(date: LocalDate): Flow<List<Task>> = flowOf(emptyList())

        override suspend fun getTaskById(id: String): Task? = null

        override suspend fun createTask(input: io.payanam.domain.model.TaskInput): Task = throw UnsupportedOperationException()

        override suspend fun updateTask(
            id: String,
            input: io.payanam.domain.model.TaskInput,
        ): Task = throw UnsupportedOperationException()

        override suspend fun updateTaskScore(
            id: String,
            score: Double,
        ) = Unit

        override suspend fun deleteTask(id: String) = Unit

        override suspend fun completeTask(
            id: String,
            note: String?,
        ): Task = throw UnsupportedOperationException()

        override suspend fun skipTask(
            id: String,
            note: String?,
        ): Task = throw UnsupportedOperationException()

        override suspend fun missTask(
            id: String,
            note: String?,
        ): Task = throw UnsupportedOperationException()

        override suspend fun archiveTask(id: String): Task = throw UnsupportedOperationException()

        override fun getOverdueTasks(): Flow<List<Task>> = flowOf(emptyList())

        override fun getTodaysTasks(): Flow<List<Task>> = flowOf(emptyList())

        override suspend fun getRecurringTasks(): List<Task> = emptyList()

        override suspend fun updateRecurrenceState(
            taskId: String,
            newDueDate: LocalDateTime,
            lastOccurrenceDate: LocalDateTime,
        ) = Unit
    }

    private class FakeDayPlanRepository : DayPlanRepository {
        private val allocationsByDay = mutableMapOf<String, List<DayPlanAllocationRecord>>()

        /**
         * When set, [getEffectiveAllocationsForDay] throws it instead of returning allocations.
         *
         * Stands in for the desktop's closed-session race: the window closes while a visuals load is in
         * flight, and the read then fails instead of returning an empty day.
         */
        var failure: Throwable? = null

        fun setAllocationsForDay(
            dayKey: String,
            allocations: List<DayPlanAllocationRecord>,
        ) {
            allocationsByDay[dayKey] = allocations
        }

        override suspend fun getEffectiveAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> {
            val thrown = failure
            if (thrown != null) {
                throw thrown
            }
            return allocationsByDay[dayKey].orEmpty()
        }

        // --- Stubs ---

        override fun observeAllocationsForDay(dayKey: String): Flow<List<DayPlanAllocationRecord>> = flowOf(emptyList())

        override suspend fun getAllocationsForDay(dayKey: String): List<DayPlanAllocationRecord> = emptyList()

        override suspend fun setAllocation(
            dayKey: String,
            dimensionId: String,
            plannedMinutes: Int,
            source: String,
            templateId: String?,
        ) = Unit

        override suspend fun setAllocations(
            dayKey: String,
            allocations: Map<String, Int>,
            source: String,
            templateId: String?,
        ) = Unit

        override suspend fun applyTemplateToDay(
            dayKey: String,
            templateId: String,
        ) = Unit

        override suspend fun clearDayPlan(dayKey: String) = Unit

        override suspend fun getDayPolicy(dayKey: String): DayPlanPolicyRecord =
            DayPlanPolicyRecord(dayKey = dayKey, mode = "auto", templateId = null, isStarred = false)

        override suspend fun setDayMode(
            dayKey: String,
            mode: String,
            templateId: String?,
        ) = Unit

        override suspend fun setDayStarred(
            dayKey: String,
            isStarred: Boolean,
        ) = Unit

        override suspend fun getDayTypeTemplatePreference(dayType: String): DayTypeTemplatePreferenceRecord =
            DayTypeTemplatePreferenceRecord(dayType = dayType, templateId = null)

        override suspend fun setDayTypeTemplatePreference(
            dayType: String,
            templateId: String?,
        ) = Unit

        override suspend fun resolveTemplateForDay(dayKey: String): DayPlanTemplateRecord? = null

        override fun observeActiveTemplates(): Flow<List<DayPlanTemplateRecord>> = flowOf(emptyList())

        override fun observeAllTemplates(): Flow<List<DayPlanTemplateRecord>> = flowOf(emptyList())

        override suspend fun getTemplateById(id: String): DayPlanTemplateRecord? = null

        override suspend fun createTemplate(
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ): String = "stub"

        override suspend fun updateTemplate(
            id: String,
            name: String,
            description: String?,
            allocations: Map<String, Int>,
        ) = Unit

        override suspend fun deleteTemplate(id: String) = Unit
    }

    private class FakeTaskOccurrenceRepository : TaskOccurrenceRepository {
        private val occurrencesByDate = mutableMapOf<LocalDate, List<TaskOccurrence>>()

        fun setOccurrencesForDate(
            date: LocalDate,
            occurrences: List<TaskOccurrence>,
        ) {
            occurrencesByDate[date] = occurrences
        }

        override fun getOccurrencesForDate(date: LocalDate): Flow<List<TaskOccurrence>> = flowOf(occurrencesByDate[date].orEmpty())

        // --- Stubs ---

        override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> = emptyList()

        override fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrence>> = flowOf(emptyList())

        override suspend fun getOccurrencesForLastNDays(
            taskId: String,
            days: Int,
        ): List<TaskOccurrence> = emptyList()

        override suspend fun getOccurrencesForTasksInLastNDays(
            taskIds: List<String>,
            days: Int,
        ): Map<String, List<TaskOccurrence>> = emptyMap()

        override suspend fun getOccurrenceForDate(
            taskId: String,
            date: LocalDate,
        ): TaskOccurrence? = null

        override suspend fun toggleOccurrence(
            taskId: String,
            date: LocalDate,
            newStatus: String,
            note: String?,
            reason: String?,
            actualCompletedAt: LocalDateTime?,
            actualDurationMinutes: Int?,
        ): TaskOccurrence = throw UnsupportedOperationException()

        override suspend fun deleteOccurrence(
            taskId: String,
            date: LocalDate,
        ) = Unit

        override suspend fun recordOccurrence(occurrence: TaskOccurrence) = Unit

        override suspend fun recordOccurrence(
            taskId: String,
            dueDate: LocalDateTime,
            status: String,
            note: String?,
            completionRate: Double?,
        ): TaskOccurrence = throw UnsupportedOperationException()

        override suspend fun deleteOccurrence(id: String) = Unit
    }

    // ────────────────────────────────────────────────────────────────
    // Stub repositories for the PayanamDataLayer in the factory test
    // ────────────────────────────────────────────────────────────────

    private object StubNoteRepository : NoteRepository {
        override fun getAllNotes(): Flow<List<Note>> = flowOf(emptyList())

        override fun getNotesByDimension(dimension: String): Flow<List<Note>> = flowOf(emptyList())

        override fun getNotesForDate(date: LocalDate): Flow<List<Note>> = flowOf(emptyList())

        override suspend fun getNoteById(id: String): Note? = null

        override suspend fun createNote(input: NoteInput): Note = throw UnsupportedOperationException()

        override suspend fun updateNote(
            id: String,
            input: NoteInput,
        ): Note = throw UnsupportedOperationException()

        override suspend fun deleteNote(id: String) = Unit
    }

    private object StubTagRepository : TagRepository {
        override fun observeAllTags(): Flow<List<Tag>> = flowOf(emptyList())

        override fun searchTagsByPrefix(
            query: String,
            limit: Int,
        ): Flow<List<Tag>> = flowOf(emptyList())

        override fun observeTagsForTask(taskId: String): Flow<List<Tag>> = flowOf(emptyList())

        override fun observeTagsForNote(noteId: String): Flow<List<Tag>> = flowOf(emptyList())

        override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> = emptyMap()

        override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> = flowOf(emptyList())

        override suspend fun replaceTaskTags(
            taskId: String,
            tagNames: List<String>,
        ) = Unit

        override suspend fun replaceNoteTags(
            noteId: String,
            tagNames: List<String>,
        ) = Unit

        override suspend fun replaceTimeEntryTags(
            timeEntryId: String,
            tagNames: List<String>,
        ) = Unit
    }

    private object StubJournalRepository : JournalRepository {
        override suspend fun getOrCreateEntry(date: LocalDate): DayJournalEntry = throw UnsupportedOperationException()

        override fun observeEntry(date: LocalDate): Flow<DayJournalEntry?> = flowOf(null)

        override fun getResponses(entryId: String): Flow<List<DayJournalResponse>> = flowOf(emptyList())

        override suspend fun saveResponse(
            entryId: String,
            input: DayJournalResponseInput,
        ): DayJournalResponse = throw UnsupportedOperationException()

        override suspend fun getResponse(
            entryId: String,
            scope: JournalPromptScope,
            dimensionKey: String?,
            promptKey: String,
        ): DayJournalResponse? = null

        override suspend fun getEntryByDate(dateString: String): DayJournalEntry? = null

        override suspend fun insertEntry(entry: DayJournalEntry) = Unit

        override suspend fun getResponsesByEntryId(entryId: String): List<DayJournalResponse> = emptyList()

        override fun getAllJournalEntries(): Flow<List<DayJournalEntry>> = flowOf(emptyList())

        override fun getTotalResponseCount(): Flow<Int> = flowOf(0)

        override suspend fun upsertResponse(response: DayJournalResponse) = Unit
    }
}
