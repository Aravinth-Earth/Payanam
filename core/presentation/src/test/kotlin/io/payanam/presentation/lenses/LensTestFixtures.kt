//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.repository.AverageDailyTimeTableData
import io.payanam.domain.repository.DailyFocusStat
import io.payanam.domain.repository.DimensionTrendBlock
import io.payanam.domain.repository.HabitPlanItem
import io.payanam.domain.repository.HabitRealityItem
import io.payanam.domain.repository.HeatmapDayData
import io.payanam.domain.repository.LensReflectionRecord
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.MinutePatternData
import io.payanam.domain.repository.PlanningLensData
import io.payanam.domain.repository.RealityLensData
import io.payanam.domain.repository.ScoringConfigRepository
import io.payanam.domain.repository.TaskPlanItem
import io.payanam.domain.repository.TaskRealityItem
import io.payanam.domain.repository.TimeGoalItem
import io.payanam.domain.repository.UnifiedLensSnapshot
import io.payanam.domain.repository.WeekGridData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate

/**
 * The one fake Lens repository the shared lens tests build on.
 *
 * WHY IT IS SHARED RATHER THAN PRIVATE TO ONE TEST CLASS:
 *
 * The Lens workspace is one ViewModel driving a dozen independent loaders, and each of them needs the same
 * repository shape. A private per-class fake means the ninth loader's test re-implements the same fifteen
 * members, and the copies drift - the readers of a shared fixture also get the same snapshot numbers, so an
 * assertion in one test stays comparable with an assertion in another.
 *
 * The members that return chart data are deliberately left at their interface defaults (empty): the loaders
 * must handle an empty day list, and that is the branch a fresh install exercises. Override one here if a test
 * needs a populated chart.
 */
internal class FakeLensRepository : LensRepository {
    /** How many times a snapshot was computed - the cache tests assert on this. */
    var calculateCalls: Int = 0

    /** The day keys the repository reports as stale; the cache re-reads exactly these. */
    var dirtyDays: Set<String> = emptySet()

    /**
     * Reports EVERY day as stale. The reflection refresh runs only on a stale day and only when the day it was
     * asked about is the range's focus day, so a test that wants that path to complete cannot know the day key
     * in advance - it marks the whole repository stale instead.
     */
    var reportAllDaysDirty: Boolean = false

    /** Day keys passed to [generateReflectionCards], in call order. */
    val generatedReflections = mutableListOf<String>()

    /** Addressals recorded as `id to note`. */
    val addressedReflections = mutableListOf<Pair<String, String?>>()

    /** The first tracked day; the range loaders clamp against it. */
    var firstTrackedDate: LocalDate? = null

    /** How many times each chart loader read its dataset. */
    var heatmapCalls: Int = 0
    var weekGridCalls: Int = 0
    var minutePatternCalls: Int = 0
    var dimensionSplitCalls: Int = 0
    var dimensionTrendCalls: Int = 0
    var focusAverageCalls: Int = 0
    var averageDailyTimeCalls: Int = 0

    /** When set, every suspend member throws this - the ViewModel's error path. */
    var failure: RuntimeException? = null

    override suspend fun getFirstTrackedDate(): LocalDate? {
        failure?.let { throw it }
        return firstTrackedDate
    }

    override suspend fun calculateUnifiedSnapshot(dayKey: String): UnifiedLensSnapshot {
        failure?.let { throw it }
        calculateCalls++
        return snapshotFor(dayKey)
    }

    private fun snapshotFor(dayKey: String): UnifiedLensSnapshot =
        UnifiedLensSnapshot(
            planning =
                PlanningLensData(
                    dayKey = dayKey,
                    totalPlannedMinutes = 60,
                    plannedTimeByDimension = mapOf("career_work" to 60),
                    budgetAllocationsByDimension = mapOf("career_work" to 60),
                    plannedTasks = listOf(TaskPlanItem("task_$dayKey", "Task", "career_work", 60, "${dayKey}T09:00:00", "Medium")),
                    plannedHabits = emptyList<HabitPlanItem>(),
                    timeGoals = emptyList<TimeGoalItem>(),
                    planCompletenessScore = 1f,
                ),
            reality =
                RealityLensData(
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

    override suspend fun generateReflectionCards(dayKey: String) {
        failure?.let { throw it }
        generatedReflections += dayKey
    }

    override fun observeReflections(dayKey: String): Flow<List<LensReflectionRecord>> = flowOf(listOf(reflectionRecordFor(dayKey)))

    /** One unaddressed reflection, so a refresh has something to publish. */
    private fun reflectionRecordFor(dayKey: String): LensReflectionRecord =
        LensReflectionRecord(
            id = "reflection-$dayKey",
            dayKey = dayKey,
            dimensionId = "career_work",
            reflectionType = "untracked_time",
            title = "Untracked time on $dayKey",
            description = "30 minutes were not tracked",
            gapMinutes = 30,
            relatedEntityId = null,
            isAddressed = false,
            userNote = null,
            createdAt = "${dayKey}T20:00:00",
        )

    override suspend fun markReflectionAddressed(
        id: String,
        note: String?,
    ) {
        failure?.let { throw it }
        addressedReflections += id to note
    }

    override suspend fun calculatePlanCompleteness(dayKey: String): Float = 1f

    override suspend fun calculateAdherence(dayKey: String): Float = 0.5f

    // These five keep the interface's default (empty) return, and count the call instead: a chart loader is
    // asserted to have REACHED the repository, which an empty-data equality check could not show.
    override suspend fun getHeatmapDays(): List<HeatmapDayData> {
        failure?.let { throw it }
        heatmapCalls++
        return emptyList()
    }

    override suspend fun getWeekGridData(excludeEmptyDays: Boolean): WeekGridData {
        failure?.let { throw it }
        weekGridCalls++
        return WeekGridData(emptyList())
    }

    override suspend fun getMinutePatternData(excludeEmptyDays: Boolean): MinutePatternData {
        failure?.let { throw it }
        minutePatternCalls++
        return MinutePatternData(emptyList())
    }

    override suspend fun getDimensionSplitForRange(
        start: LocalDate,
        end: LocalDate,
    ): Map<String?, Int> {
        failure?.let { throw it }
        dimensionSplitCalls++
        return emptyMap()
    }

    override suspend fun getDimensionTrendBlocks(windowDays: Int): List<DimensionTrendBlock> {
        failure?.let { throw it }
        dimensionTrendCalls++
        return emptyList()
    }

    override suspend fun getDailyFocusAverages(): List<DailyFocusStat> {
        failure?.let { throw it }
        focusAverageCalls++
        return emptyList()
    }

    override suspend fun getAverageDailyTimeTableData(): AverageDailyTimeTableData? {
        failure?.let { throw it }
        averageDailyTimeCalls++
        return null
    }

    override suspend fun getDirtyDayKeys(dayKeys: Set<String>): Set<String> =
        if (reportAllDaysDirty) dayKeys else dayKeys.intersect(dirtyDays)

    override suspend fun isDayDirty(dayKey: String): Boolean = reportAllDaysDirty || dayKey in dirtyDays
}

/** The scoring-configuration repository, recording what the ViewModel asked it to persist. */
internal class FakeScoringConfigRepository(
    private var config: ScoringConfig = ScoringConfig.defaults(),
) : ScoringConfigRepository {
    /** The last config passed to [saveConfig]. */
    var saved: ScoringConfig? = null

    /** How many times a reset was requested. */
    var resetCount: Int = 0

    /** When set, every suspend member throws this - the ViewModel's error path. */
    var failure: RuntimeException? = null

    override suspend fun getConfig(): ScoringConfig {
        failure?.let { throw it }
        return config
    }

    override fun observeConfig(): Flow<ScoringConfig> = flowOf(config)

    override suspend fun saveConfig(config: ScoringConfig) {
        failure?.let { throw it }
        saved = config
        this.config = config
    }

    override suspend fun resetToDefaults() {
        failure?.let { throw it }
        resetCount++
        config = ScoringConfig.defaults()
    }
}
