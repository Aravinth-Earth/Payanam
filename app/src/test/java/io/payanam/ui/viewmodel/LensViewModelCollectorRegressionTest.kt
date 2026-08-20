//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.repository.HabitPlanItem
import io.payanam.domain.repository.HabitRealityItem
import io.payanam.domain.repository.LensReflectionRecord
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.PlanningLensData
import io.payanam.domain.repository.RealityLensData
import io.payanam.domain.repository.TaskPlanItem
import io.payanam.domain.repository.TaskRealityItem
import io.payanam.domain.repository.TimeGoalItem
import io.payanam.domain.repository.UnifiedLensSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
/**
 * LensViewModelCollectorRegressionTest.
 */
class LensViewModelCollectorRegressionTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var lensRepository: FakeLensRepository

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "test", 0)
        }
        lensRepository = FakeLensRepository()
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
     * Select date does not leak reflection collectors.
     */
    fun selectDate_does_not_leak_reflection_collectors() = runTest {
        /** View model. */
        val viewModel = LensViewModel(lensRepository)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel, requireSummary = false)
        /** Assert equals. */
        assertEquals(0, lensRepository.activeReflectionCollectors.get())

        viewModel.selectDate(LocalDate.now().minusDays(1))
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel, requireSummary = false)
        /** Assert equals. */
        assertEquals(0, lensRepository.activeReflectionCollectors.get())

        viewModel.selectDate(LocalDate.now().minusDays(2))
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel, requireSummary = false)
        /** Assert equals. */
        assertEquals(0, lensRepository.activeReflectionCollectors.get())
    }

    @Test
    /**
     * Default day load keeps range as primary filter.
     */
    fun default_day_load_keeps_range_as_primary_filter() = runTest {
        /** View model. */
        val viewModel = LensViewModel(lensRepository)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        /** State. */
        val state = viewModel.uiState.value
        /** Today key. */
        val todayKey = LocalDate.now().toString()
        /** Assert equals. */
        assertEquals(LensTimeMode.TODAY, state.selectedTimeMode)
        /** Assert equals. */
        assertEquals(LensTimeWindow.TODAY, state.selectedTimeWindow)
        /** Assert equals. */
        assertEquals(setOf(todayKey), lensRepository.planningDayKeys.toSet())
        /** Assert equals. */
        assertEquals(setOf(todayKey), lensRepository.realityDayKeys.toSet())
    }

    @Test
    /**
     * Selecting default today mode and window is a no op.
     */
    fun selecting_default_today_mode_and_window_is_a_no_op() = runTest {
        /** View model. */
        val viewModel = LensViewModel(lensRepository)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)
        /** Initial snapshot calls. */
        val initialSnapshotCalls = lensRepository.snapshotCallCount.get()

        viewModel.selectTimeMode(LensTimeMode.TODAY)
        viewModel.selectTimeWindow(LensTimeWindow.TODAY)
        /** Advance until idle. */
        advanceUntilIdle()

        /** Assert equals. */
        assertEquals(initialSnapshotCalls, lensRepository.snapshotCallCount.get())
    }

    @Test
    /**
     * Select time mode past defaults to last day window.
     */
    fun selectTimeMode_past_defaults_to_last_day_window() = runTest {
        /** View model. */
        val viewModel = LensViewModel(lensRepository)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        viewModel.selectTimeMode(LensTimeMode.PAST)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        /** State. */
        val state = viewModel.uiState.value
        /** Summary. */
        val summary = state.selectedRangeSummary
        /** Assert not null. */
        assertNotNull(summary)
        /** Assert equals. */
        assertEquals(LensTimeMode.PAST, state.selectedTimeMode)
        /** Assert equals. */
        assertEquals(LensTimeWindow.LAST_DAY, state.selectedTimeWindow)
        /** Assert equals. */
        assertEquals(LocalDate.now().minusDays(1), summary?.startDate)
        /** Assert equals. */
        assertEquals(LocalDate.now().minusDays(1), summary?.endDate)
    }

    @Test
    /**
     * Past last7 paginates inside past only.
     */
    fun past_last7_paginates_inside_past_only() = runTest {
        /** View model. */
        val viewModel = LensViewModel(lensRepository)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        viewModel.selectTimeMode(LensTimeMode.PAST)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)
        viewModel.selectTimeWindow(LensTimeWindow.LAST_7_DAYS)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        /** State. */
        var state = viewModel.uiState.value
        /** Summary. */
        var summary = state.selectedRangeSummary
        /** Assert equals. */
        assertEquals(LocalDate.now().minusDays(7), summary?.startDate)
        /** Assert equals. */
        assertEquals(LocalDate.now().minusDays(1), summary?.endDate)
        /** Assert true. */
        assertTrue(state.canGoToPreviousWindowPage)
        /** Assert false. */
        assertFalse(state.canGoToNextWindowPage)

        viewModel.goToPreviousWindowPage()
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        state = viewModel.uiState.value
        summary = state.selectedRangeSummary
        /** Assert equals. */
        assertEquals(LocalDate.now().minusDays(14), summary?.startDate)
        /** Assert equals. */
        assertEquals(LocalDate.now().minusDays(8), summary?.endDate)
        /** Assert true. */
        assertTrue(state.canGoToPreviousWindowPage)
        /** Assert true. */
        assertTrue(state.canGoToNextWindowPage)
    }

    @Test
    /**
     * Future next7 paginates inside future only.
     */
    fun future_next7_paginates_inside_future_only() = runTest {
        /** View model. */
        val viewModel = LensViewModel(lensRepository)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        viewModel.selectTimeMode(LensTimeMode.FUTURE)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)
        viewModel.selectTimeWindow(LensTimeWindow.NEXT_7_DAYS)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        /** State. */
        var state = viewModel.uiState.value
        /** Summary. */
        var summary = state.selectedRangeSummary
        /** Assert equals. */
        assertEquals(LocalDate.now().plusDays(1), summary?.startDate)
        /** Assert equals. */
        assertEquals(LocalDate.now().plusDays(7), summary?.endDate)
        /** Assert false. */
        assertFalse(state.canGoToPreviousWindowPage)
        /** Assert true. */
        assertTrue(state.canGoToNextWindowPage)

        viewModel.goToNextWindowPage()
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        state = viewModel.uiState.value
        summary = state.selectedRangeSummary
        /** Assert equals. */
        assertEquals(LocalDate.now().plusDays(8), summary?.startDate)
        /** Assert equals. */
        assertEquals(LocalDate.now().plusDays(14), summary?.endDate)
        /** Assert true. */
        assertTrue(state.canGoToPreviousWindowPage)
        /** Assert true. */
        assertTrue(state.canGoToNextWindowPage)
    }

    @Test
    /**
     * Range summary aggregates task and habit dimension maps.
     */
    fun range_summary_aggregates_task_and_habit_dimension_maps() = runTest {
        /** Today key. */
        val todayKey = LocalDate.now().toString()
        lensRepository.planningByDay[todayKey] = PlanningLensData(
            dayKey = todayKey,
            totalPlannedMinutes = 60,
            plannedTimeByDimension = emptyMap(),
            budgetAllocationsByDimension = mapOf(LifeDimension.HEALTH_WELLNESS.id to 60),
            plannedTasks = listOf(
                /** Task plan item. */
                TaskPlanItem("task1", "Task 1", LifeDimension.HEALTH_WELLNESS.id, 20, todayKey, "medium"),
                /** Task plan item. */
                TaskPlanItem("task2", "Task 2", null, 10, todayKey, "low"),
            ),
            plannedHabits = listOf(
                /** Habit plan item. */
                HabitPlanItem("habit1", "Habit 1", LifeDimension.HEALTH_WELLNESS.id, 15, "DAILY"),
                /** Habit plan item. */
                HabitPlanItem("habit2", "Habit 2", LifeDimension.LEARNING.id, 10, "DAILY"),
            ),
            timeGoals = emptyList(),
            planCompletenessScore = 0.5f,
        )
        lensRepository.realityByDay[todayKey] = RealityLensData(
            dayKey = todayKey,
            totalActualMinutes = 45,
            actualTimeByDimension = mapOf(LifeDimension.HEALTH_WELLNESS.id to 45),
            budgetAllocationsByDimension = mapOf(LifeDimension.HEALTH_WELLNESS.id to 60),
            completedTasks = listOf(
                /** Task reality item. */
                TaskRealityItem("task1", "Task 1", LifeDimension.HEALTH_WELLNESS.id, 20, null, "completed", 0),
                /** Task reality item. */
                TaskRealityItem("task2", "Task 2", null, 0, null, "missed", null),
            ),
            completedHabits = listOf(
                /** Habit reality item. */
                HabitRealityItem("habit1", "Habit 1", LifeDimension.HEALTH_WELLNESS.id, 15, null, "completed"),
                /** Habit reality item. */
                HabitRealityItem("habit2", "Habit 2", null, 0, null, "missed"),
            ),
            untrackedMinutes = 0,
            focusGapMinutes = 0,
            adherenceScore = 0.5f,
        )

        /** View model. */
        val viewModel = LensViewModel(lensRepository)
        /** Await lens data loaded. */
        awaitLensDataLoaded(viewModel)

        /** Summary. */
        val summary = viewModel.uiState.value.selectedRangeSummary
        /** Assert not null. */
        assertNotNull(summary)
        /** Assert equals. */
        assertEquals(1, summary?.plannedTasksByDimension?.get(LifeDimension.HEALTH_WELLNESS.id))
        /** Assert equals. */
        assertEquals(1, summary?.plannedTasksByDimension?.get("unassigned"))
        /** Assert equals. */
        assertEquals(1, summary?.completedTasksByDimension?.get(LifeDimension.HEALTH_WELLNESS.id))
        /** Assert equals. */
        assertEquals(1, summary?.missedTasksByDimension?.get("unassigned"))
        /** Assert equals. */
        assertEquals(1, summary?.plannedHabitsByDimension?.get(LifeDimension.HEALTH_WELLNESS.id))
        /** Assert equals. */
        assertEquals(1, summary?.plannedHabitsByDimension?.get(LifeDimension.LEARNING.id))
        /** Assert equals. */
        assertEquals(1, summary?.completedHabitsByDimension?.get(LifeDimension.HEALTH_WELLNESS.id))
        /** Assert equals. */
        assertEquals(1, summary?.missedHabitsByDimension?.get("unassigned"))
    }

    private suspend fun TestScope.awaitLensDataLoaded(
        /** View model. */
        viewModel: LensViewModel,
        requireSummary: Boolean = true,
    ) {
        /** Repeat. */
        repeat(500) {
            /** Advance until idle. */
            advanceUntilIdle()
            /** State. */
            val state = viewModel.uiState.value
            /** If. */
            if (!state.isLoading && (!requireSummary || state.selectedRangeSummary != null)) {
                /** Return. */
                return
            }
            Thread.sleep(10)
        }
        /** Final state. */
        val finalState = viewModel.uiState.value
        /** Fail. */
        fail("Lens data did not load: hasError=${finalState.hasError}, error=${finalState.errorMessage}")
    }

    private class FakeLensRepository : LensRepository {
        /** Active reflection collectors. */
        val activeReflectionCollectors = AtomicInteger(0)
        /** Snapshot call count. */
        val snapshotCallCount = AtomicInteger(0)
        /** Planning day keys. */
        val planningDayKeys = mutableListOf<String>()
        /** Reality day keys. */
        val realityDayKeys = mutableListOf<String>()
        /** Planning by day. */
        val planningByDay = mutableMapOf<String, PlanningLensData>()
        /** Reality by day. */
        val realityByDay = mutableMapOf<String, RealityLensData>()

        override suspend fun getFirstTrackedDate(): LocalDate? = LocalDate.now()

        override suspend fun calculateUnifiedSnapshot(dayKey: String): UnifiedLensSnapshot {
            snapshotCallCount.incrementAndGet()
            /** Planning. */
            val planning = calculatePlanningData(dayKey)
            /** Reality. */
            val reality = calculateRealityData(dayKey)
            return UnifiedLensSnapshot(planning = planning, reality = reality)
        }

        override suspend fun calculatePlanningData(dayKey: String): PlanningLensData {
            planningDayKeys.add(dayKey)
            return planningByDay[dayKey] ?: PlanningLensData(
                dayKey = dayKey,
                totalPlannedMinutes = 0,
                plannedTimeByDimension = emptyMap(),
                budgetAllocationsByDimension = emptyMap(),
                plannedTasks = emptyList<TaskPlanItem>(),
                plannedHabits = emptyList<HabitPlanItem>(),
                timeGoals = emptyList<TimeGoalItem>(),
                planCompletenessScore = 0f,
            )
        }

        override fun observePlanningData(dayKey: String): Flow<PlanningLensData> = flow { emit(calculatePlanningData(dayKey)) }

        override suspend fun calculateRealityData(dayKey: String): RealityLensData {
            realityDayKeys.add(dayKey)
            return realityByDay[dayKey] ?: RealityLensData(
                dayKey = dayKey,
                totalActualMinutes = 0,
                actualTimeByDimension = emptyMap(),
                budgetAllocationsByDimension = emptyMap(),
                completedTasks = emptyList<TaskRealityItem>(),
                completedHabits = emptyList<HabitRealityItem>(),
                untrackedMinutes = 0,
                focusGapMinutes = 0,
                adherenceScore = 0f,
            )
        }

        override fun observeRealityData(dayKey: String): Flow<RealityLensData> = flow { emit(calculateRealityData(dayKey)) }

        override suspend fun generateReflectionCards(dayKey: String) = Unit

        override fun observeReflections(dayKey: String): Flow<List<LensReflectionRecord>> = flow {
            activeReflectionCollectors.incrementAndGet()
            try {
                /** Emit. */
                emit(emptyList())
                /** Await cancellation. */
                awaitCancellation()
            } finally {
                activeReflectionCollectors.decrementAndGet()
            }
        }

        override suspend fun markReflectionAddressed(id: String, note: String?) = Unit

        override suspend fun calculatePlanCompleteness(dayKey: String): Float = 0f

        override suspend fun calculateAdherence(dayKey: String): Float = 0f
    }
}
