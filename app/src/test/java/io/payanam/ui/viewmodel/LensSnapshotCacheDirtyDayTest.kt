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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
/**
 * LensSnapshotCacheDirtyDayTest.
 */
class LensSnapshotCacheDirtyDayTest {

    private lateinit var repository: FakeLensRepository
    private lateinit var cache: LensSnapshotCache

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        repository = FakeLensRepository()
        cache = LensSnapshotCache(repository, UnifiedLogger.getInstance())
    }

    @Test
    /**
     * Get or load recomputes when day is marked dirty.
     */
    fun getOrLoad_recomputesWhenDayIsMarkedDirty() = runBlocking {
        /** Day key. */
        val dayKey = "2026-02-20"
        cache.getOrLoad(dayKey)
        /** Assert equals. */
        assertEquals(1, repository.calculateCalls)

        repository.dirtyDays = setOf(dayKey)
        cache.getOrLoad(dayKey)

        /** Assert equals. */
        assertEquals(2, repository.calculateCalls)
    }

    @Test
    /**
     * Load for days batches unique day keys.
     */
    fun loadForDays_batchesUniqueDayKeys() = runBlocking {
        /** Day a. */
        val dayA = "2026-02-20"
        /** Day b. */
        val dayB = "2026-02-21"

        cache.loadForDays(listOf(dayA, dayA, dayB))

        /** Assert equals. */
        assertEquals(2, repository.calculateCalls)
    }

    private class FakeLensRepository : LensRepository {
        /** Calculate calls. */
        var calculateCalls: Int = 0
        /** Dirty days. */
        var dirtyDays: Set<String> = emptySet()

        override suspend fun getFirstTrackedDate(): LocalDate? = null

        override suspend fun calculateUnifiedSnapshot(dayKey: String): UnifiedLensSnapshot {
            calculateCalls++
            return snapshotFor(dayKey)
        }

        private fun snapshotFor(dayKey: String): UnifiedLensSnapshot = UnifiedLensSnapshot(
            planning = PlanningLensData(
                dayKey = dayKey,
                totalPlannedMinutes = 60,
                plannedTimeByDimension = mapOf("career_work" to 60),
                budgetAllocationsByDimension = mapOf("career_work" to 60),
                plannedTasks = listOf(TaskPlanItem("task_$dayKey", "Task", "career_work", 60, "${dayKey}T09:00:00", "Medium")),
                plannedHabits = emptyList<HabitPlanItem>(),
                timeGoals = emptyList<TimeGoalItem>(),
                planCompletenessScore = 1f,
            ),
            reality = RealityLensData(
                dayKey = dayKey,
                totalActualMinutes = 30,
                actualTimeByDimension = mapOf("career_work" to 30),
                budgetAllocationsByDimension = mapOf("career_work" to 60),
                completedTasks = emptyList<TaskRealityItem>(),
                completedHabits = emptyList<HabitRealityItem>(),
                untrackedMinutes = 0,
                focusGapMinutes = 30,
                adherenceScore = 0.5f,
            ),
        )

        override suspend fun calculatePlanningData(dayKey: String): PlanningLensData = snapshotFor(dayKey).planning
        override fun observePlanningData(dayKey: String): Flow<PlanningLensData> = flowOf(snapshotFor(dayKey).planning)
        override suspend fun calculateRealityData(dayKey: String): RealityLensData = snapshotFor(dayKey).reality
        override fun observeRealityData(dayKey: String): Flow<RealityLensData> = flowOf(snapshotFor(dayKey).reality)
        override suspend fun generateReflectionCards(dayKey: String) = Unit
        override fun observeReflections(dayKey: String): Flow<List<LensReflectionRecord>> = flowOf(emptyList())
        override suspend fun markReflectionAddressed(id: String, note: String?) = Unit
        override suspend fun calculatePlanCompleteness(dayKey: String): Float = 1f
        override suspend fun calculateAdherence(dayKey: String): Float = 0.5f
        override suspend fun getDirtyDayKeys(dayKeys: Set<String>): Set<String> = dayKeys.intersect(dirtyDays)
        override suspend fun isDayDirty(dayKey: String): Boolean = dayKey in dirtyDays
    }
}
