//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
/**
 * LensTimeHistoryMetricsTest.
 */
class LensTimeHistoryMetricsTest {

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    /**
     * Build time module day metrics negative but improved delta increments streak.
     */
    fun buildTimeModuleDayMetrics_negativeButImprovedDelta_incrementsStreak() {
        val start = LocalDate.of(2026, 2, 15)
        val days = listOf(
            start to snapshotWithSingleDimension(planned = 100, actual = 100),
            start.plusDays(1) to snapshotWithSingleDimension(planned = 100, actual = 50),
            start.plusDays(2) to snapshotWithSingleDimension(planned = 100, actual = 40),
        )
        val metrics = buildTimeModuleDayMetrics(days)
        assertEquals(3, metrics.size)
        assertEquals(-0.5, metrics[1].progressDelta, 0.000001)
        assertEquals(-0.1, metrics[2].progressDelta, 0.000001)
        assertEquals(1, metrics[2].progressStreak)
        assertEquals(1, metrics[2].perDimensionScores.size)
        assertEquals(0.4, metrics[2].perDimensionScores.values.first(), 0.000001)
    }

    @Test
    /**
     * Build time module history summary returns ranks against all history.
     */
    fun buildTimeModuleHistorySummary_returnsRanksAgainstAllHistory() {
        val firstDay = LocalDate.of(2026, 2, 10)
        val snapshots: Map<String, UnifiedLensSnapshot> = linkedMapOf(
            firstDay.toString() to snapshotWithSingleDimension(planned = 100, actual = 100),
            firstDay.plusDays(1).toString() to snapshotWithSingleDimension(planned = 100, actual = 90),
            firstDay.plusDays(2).toString() to snapshotWithSingleDimension(planned = 100, actual = 80),
        )
        val repo = FakeHistoryLensRepository(firstTrackedDate = firstDay, snapshots = snapshots)
        val summary = runBlocking {
            buildTimeModuleHistorySummary(
                lensRepository = repo,
                focusDate = firstDay.plusDays(2),
            )
        }
        assertNotNull(summary)
        assertEquals(3, summary?.totalDays)
        assertTrue((summary?.dayScoreRank ?: 0) >= 1)
        assertTrue((summary?.progressRank ?: 0) >= 1)
        assertTrue((summary?.streakRank ?: 0) >= 1)
    }

    @Test
    /**
     * Calculate per dimension time scores returns scores for all available dimensions.
     */
    fun calculatePerDimensionTimeScores_returnsScoresForAllAvailableDimensions() {
        val result = calculatePerDimensionTimeScores(
            plannedByDimension = mapOf(
                "dim_physical_health" to 120,
                "dim_work_livelihood" to 60,
            ),
            actualByDimension = mapOf(
                "dim_physical_health" to 120,
                "dim_learning_growth" to 30,
            ),
        )
        assertEquals(3, result.size)
        assertEquals(1.0, result["dim_physical_health"] ?: 0.0, 0.000001)
        assertNotNull(result["dim_work_livelihood"])
        assertNotNull(result["dim_learning_growth"])
    }

    @Test
    /**
     * Calculate weighted time module score uses canonical dimension weights for legacy ids.
     */
    fun calculateWeightedTimeModuleScore_usesCanonicalDimensionWeightsForLegacyIds() {
        val weighted = calculateWeightedTimeModuleScore(
            plannedByDimension = mapOf(
                "dim_work_livelihood" to 100,
                "dim_community_service" to 100,
            ),
            actualByDimension = mapOf(
                "dim_work_livelihood" to 100,
                "dim_community_service" to 0,
            ),
        )
        assertEquals(1.0 / 1.7, weighted, 0.000001)
    }

    private fun snapshotWithSingleDimension(planned: Int, actual: Int): UnifiedLensSnapshot {
        val dim = "dim_physical_health"
        return UnifiedLensSnapshot(
            planning = PlanningLensData(
                dayKey = "2026-02-01",
                totalPlannedMinutes = planned,
                plannedTimeByDimension = emptyMap(),
                budgetAllocationsByDimension = mapOf(dim to planned),
                plannedTasks = emptyList<TaskPlanItem>(),
                plannedHabits = emptyList<HabitPlanItem>(),
                timeGoals = emptyList<TimeGoalItem>(),
                planCompletenessScore = 0f,
            ),
            reality = RealityLensData(
                dayKey = "2026-02-01",
                totalActualMinutes = actual,
                actualTimeByDimension = mapOf(dim to actual),
                budgetAllocationsByDimension = mapOf(dim to planned),
                completedTasks = emptyList<TaskRealityItem>(),
                completedHabits = emptyList<HabitRealityItem>(),
                untrackedMinutes = 0,
                focusGapMinutes = 0,
                adherenceScore = 0f,
            ),
        )
    }

    private class FakeHistoryLensRepository(
        private val firstTrackedDate: LocalDate,
        private val snapshots: Map<String, UnifiedLensSnapshot>,
    ) : LensRepository {
        override suspend fun getFirstTrackedDate(): LocalDate? = firstTrackedDate

        override suspend fun calculateUnifiedSnapshot(dayKey: String): UnifiedLensSnapshot = snapshots[dayKey] ?: snapshotWithDefaults(dayKey)

        override suspend fun calculatePlanningData(dayKey: String): PlanningLensData = calculateUnifiedSnapshot(dayKey).planning

        override fun observePlanningData(dayKey: String): Flow<PlanningLensData> = flowOf(snapshotWithDefaults(dayKey).planning)

        override suspend fun calculateRealityData(dayKey: String): RealityLensData = calculateUnifiedSnapshot(dayKey).reality

        override fun observeRealityData(dayKey: String): Flow<RealityLensData> = flowOf(snapshotWithDefaults(dayKey).reality)

        override suspend fun generateReflectionCards(dayKey: String) = Unit

        override fun observeReflections(dayKey: String): Flow<List<LensReflectionRecord>> = flowOf(emptyList())

        override suspend fun markReflectionAddressed(id: String, note: String?) = Unit

        override suspend fun calculatePlanCompleteness(dayKey: String): Float = 0f

        override suspend fun calculateAdherence(dayKey: String): Float = 0f

        private fun snapshotWithDefaults(dayKey: String): UnifiedLensSnapshot = UnifiedLensSnapshot(
            planning = PlanningLensData(
                dayKey = dayKey,
                totalPlannedMinutes = 0,
                plannedTimeByDimension = emptyMap(),
                budgetAllocationsByDimension = emptyMap(),
                plannedTasks = emptyList<TaskPlanItem>(),
                plannedHabits = emptyList<HabitPlanItem>(),
                timeGoals = emptyList<TimeGoalItem>(),
                planCompletenessScore = 0f,
            ),
            reality = RealityLensData(
                dayKey = dayKey,
                totalActualMinutes = 0,
                actualTimeByDimension = emptyMap(),
                budgetAllocationsByDimension = emptyMap(),
                completedTasks = emptyList<TaskRealityItem>(),
                completedHabits = emptyList<HabitRealityItem>(),
                untrackedMinutes = 0,
                focusGapMinutes = 0,
                adherenceScore = 0f,
            ),
        )
    }
}
