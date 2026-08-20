//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.common.logging.UnifiedLogger
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Repository for lens calculations (Planning and Reality).
 * Provides data aggregation and gap detection for lens views.
 */
@Suppress("TooManyFunctions")
/**
 * LensRepository.
 */
interface LensRepository {
    /**
     * First day with any tracked time entry.
     */
    suspend fun getFirstTrackedDate(): LocalDate?

    /**
     * Calculate canonical unified snapshot for a specific day.
     * @param dayKey The day in YYYY-MM-DD format
     * @return Unified planning + reality snapshot for the day
     */
    suspend fun calculateUnifiedSnapshot(dayKey: String): UnifiedLensSnapshot

    /**
     * Calculate planning lens data for a specific day.
     * @param dayKey The day in YYYY-MM-DD format
     * @return Planning lens data with completeness score
     */
    suspend fun calculatePlanningData(dayKey: String): PlanningLensData

    /**
     * Observe planning lens data reactively.
     * @param dayKey The day in YYYY-MM-DD format
     * @return Flow of planning lens data
     */
    fun observePlanningData(dayKey: String): Flow<PlanningLensData>

    /**
     * Calculate reality lens data for a specific day.
     * @param dayKey The day in YYYY-MM-DD format
     * @return Reality lens data with adherence score
     */
    suspend fun calculateRealityData(dayKey: String): RealityLensData

    /**
     * Observe reality lens data reactively.
     * @param dayKey The day in YYYY-MM-DD format
     * @return Flow of reality lens data
     */
    fun observeRealityData(dayKey: String): Flow<RealityLensData>

    /**
     * Generate reflection cards (gap detection) for a specific day.
     * @param dayKey The day in YYYY-MM-DD format
     */
    suspend fun generateReflectionCards(dayKey: String)

    /**
     * Observe reflection cards for a specific day.
     * @param dayKey The day in YYYY-MM-DD format
     * @return Flow of reflection records
     */
    fun observeReflections(dayKey: String): Flow<List<LensReflectionRecord>>

    /**
     * Mark a reflection as addressed with optional note.
     * @param id The reflection ID
     * @param note Optional user note
     */
    suspend fun markReflectionAddressed(id: String, note: String?)

    /**
     * Calculate plan completeness score (0.0 to 1.0).
     * @param dayKey The day in YYYY-MM-DD format
     * @return Plan completeness score
     */
    suspend fun calculatePlanCompleteness(dayKey: String): Float

    /**
     * Calculate adherence score (0.0 to 1.0).
     * @param dayKey The day in YYYY-MM-DD format
     * @return Adherence score
     */
    suspend fun calculateAdherence(dayKey: String): Float

    /**
     * Returns the subset of day keys that are marked dirty for lens snapshot recomputation.
     */
    suspend fun getDirtyDayKeys(dayKeys: Set<String>): Set<String> = emptySet()

    /**
     * Returns true when the day has a dirty marker and should be recomputed.
     */
    suspend fun isDayDirty(dayKey: String): Boolean = false

    /**
     * Returns the per-day average focus rating across all tracked time entries.
     * Days with no rated entries have avgFocus = null.
     */
    suspend fun getDailyFocusAverages(): List<DailyFocusStat> = emptyList()

    /**
     * Returns the per-day tracked time percentage (0.0 to 100.0).
     * Calculated as (sum of tracked entry durations) / 1440 minutes * 100.
     * Sorted by dayKey ascending (oldest first).
     */
    suspend fun getDailyTrackedTimeStats(): List<DailyTrackedTimeStat> = emptyList()

    /**
     * Returns the per-day focused hours (0.0 to 24.0).
     * Calculated as sum(clampedMinutes * focusRating) / 60 per day.
     * Entries with no focusRating contribute 0 focused hours.
     * Midnight-crossing entries are split to their respective day boundaries.
     */
    suspend fun getDailyFocusedHoursStats(): List<DailyFocusedHoursStat> = emptyList()

    /**
     * Returns a compact average-daily-time table covering the calendar span since the first tracked day.
     * Today is captured as of load time, yesterday is a single-day snapshot, and longer windows are
     * averaged across their calendar-day spans.
     */
    suspend fun getAverageDailyTimeTableData(): AverageDailyTimeTableData? {
        runCatching { UnifiedLogger.getInstance() }
            .getOrNull()
            ?.d(
                "LensRepository.getAverageDailyTimeTableData",
                "Average daily time table not implemented",
            )
        return null
    }

    /**
     * Returns total tracked minutes by dimension (dimensionId -> minutes) for the given date range.
     * Entries that span midnight are split to their respective day boundaries.
     * A null key represents entries with no dimension assigned (unassigned time).
     */
    suspend fun getDimensionSplitForRange(start: LocalDate, end: LocalDate): Map<String?, Int> = emptyMap()

    /**
     * Returns dimension time split blocks for the stacked proportional bar trend chart.
     * @param windowDays Number of days per block
     * @param blockCount Number of blocks to return (most recent first)
     */
    suspend fun getDimensionTrendBlocks(windowDays: Int): List<DimensionTrendBlock> = emptyList()

    /**
     * Returns all heatmap day data for the daily timeline chart, most recent first.
     */
    suspend fun getHeatmapDays(): List<HeatmapDayData> = emptyList()

    /**
     * Returns a 7-column × 48-row behavioral pattern grid aggregated over all history.
     * Each cell shows the top 3 ranked candidates (tracked dimensions + untracked) by total minutes.
     */
    suspend fun getWeekGridData(excludeEmptyDays: Boolean = false): WeekGridData = WeekGridData(emptyList())

    /**
     * Returns a 7-column × 1440-row minute-level pattern grid aggregated over all history.
     * Each row shows the top-1 winner dimension (or untracked sentinel) for that minute-of-day.
     * When excludeEmptyDays=true, days with zero tracked minutes are excluded from DOW occurrence counts.
     */
    suspend fun getMinutePatternData(excludeEmptyDays: Boolean = false): MinutePatternData = MinutePatternData(emptyList())
}

/**
 * One proportional block for the stacked dimension trend bar chart.
 */
data class DimensionTrendBlock(
    /** Start date. */
    val startDate: LocalDate,
    /** End date. */
    val endDate: LocalDate,
    /** By dimension. */
    val byDimension: Map<String?, Int>,
    /** Total possible minutes. */
    val totalPossibleMinutes: Int
)

/**
 * One entry segment for the 24h heatmap.
 */
data class HeatmapEntrySegment(
    /** Start minute. */
    val startMinute: Int,
    /** Duration minutes. */
    val durationMinutes: Int,
    /** Dimension id. */
    val dimensionId: String?
)

/**
 * One day's heatmap data for the daily timeline chart.
 */
data class HeatmapDayData(
    /** Day key. */
    val dayKey: String,
    /** Segments. */
    val segments: List<HeatmapEntrySegment>
)

/**
 * Canonical planning + reality aggregate for one day.
 */
data class UnifiedLensSnapshot(
    /** Planning. */
    val planning: PlanningLensData,
    /** Reality. */
    val reality: RealityLensData
)

/**
 * Planning lens data for a specific day.
 */
data class PlanningLensData(
    /** Day key. */
    val dayKey: String,
    /** Total planned minutes. */
    val totalPlannedMinutes: Int,
    /** Planned time by dimension. */
    val plannedTimeByDimension: Map<String, Int>, // dimensionId -> minutes (from tasks/habits)
    /** Budget allocations by dimension. */
    val budgetAllocationsByDimension: Map<String, Int>, // dimensionId -> budget minutes (from day plan)
    /** Planned tasks. */
    val plannedTasks: List<TaskPlanItem>,
    /** Planned habits. */
    val plannedHabits: List<HabitPlanItem>,
    /** Time goals. */
    val timeGoals: List<TimeGoalItem>,
    /** Plan completeness score. */
    val planCompletenessScore: Float // 0.0 to 1.0
)

/**
 * Reality lens data for a specific day.
 */
data class RealityLensData(
    /** Day key. */
    val dayKey: String,
    /** Total actual minutes. */
    val totalActualMinutes: Int,
    /** Actual time by dimension. */
    val actualTimeByDimension: Map<String, Int>, // dimensionId -> minutes
    /** Budget allocations by dimension. */
    val budgetAllocationsByDimension: Map<String, Int>, // dimensionId -> budget minutes (from day plan)
    /** Completed tasks. */
    val completedTasks: List<TaskRealityItem>,
    /** Completed habits. */
    val completedHabits: List<HabitRealityItem>,
    /** Untracked minutes. */
    val untrackedMinutes: Int,
    /** Focus gap minutes. */
    val focusGapMinutes: Int, // Planned focus - actual focus
    /** Adherence score. */
    val adherenceScore: Float, // 0.0 to 1.0
    /** Supplemental actual minutes. */
    val supplementalActualMinutes: Int = 0, // occurrence-based minutes not represented by time entries
    /** Supplemental actual by dimension. */
    val supplementalActualByDimension: Map<String, Int> = emptyMap(),
    /** Actual time only minutes. */
    val actualTimeOnlyMinutes: Int = 0, // time-entry minutes not linked to task/habit
    /** Actual task minutes. */
    val actualTaskMinutes: Int = 0, // time-entry minutes linked to one-time tasks
    /** Actual habit minutes. */
    val actualHabitMinutes: Int = 0 // recurring-habit minutes (entry-linked + supplemental occurrences)
)

/**
 * Planned task item for Planning Lens.
 */
data class TaskPlanItem(
    /** Task id. */
    val taskId: String,
    /** Title. */
    val title: String,
    /** Dimension id. */
    val dimensionId: String?,
    /** Estimated minutes. */
    val estimatedMinutes: Int,
    /** Due date. */
    val dueDate: String,
    /** Priority. */
    val priority: String
)

/**
 * Completed/missed task item for Reality Lens.
 */
data class TaskRealityItem(
    /** Task id. */
    val taskId: String,
    /** Title. */
    val title: String,
    /** Dimension id. */
    val dimensionId: String?,
    /** Actual minutes. */
    val actualMinutes: Int?,
    /** Completed at. */
    val completedAt: String?,
    /** Status. */
    val status: String, // completed | skipped | missed
    /** Adherence gap. */
    val adherenceGap: Int? // estimated - actual minutes
)

/**
 * Planned habit item for Planning Lens.
 */
data class HabitPlanItem(
    /** Habit id. */
    val habitId: String,
    /** Title. */
    val title: String,
    /** Dimension id. */
    val dimensionId: String?,
    /** Estimated minutes. */
    val estimatedMinutes: Int,
    /** Recurrence rule. */
    val recurrenceRule: String
)

/**
 * Completed habit item for Reality Lens.
 */
data class HabitRealityItem(
    /** Habit id. */
    val habitId: String,
    /** Title. */
    val title: String,
    /** Dimension id. */
    val dimensionId: String?,
    /** Actual minutes. */
    val actualMinutes: Int?,
    /** Completed at. */
    val completedAt: String?,
    /** Status. */
    val status: String // completed | skipped | missed
)

/**
 * Time goal item for Planning Lens.
 */
data class TimeGoalItem(
    /** Goal id. */
    val goalId: String,
    /** Dimension id. */
    val dimensionId: String?,
    /** Dimension label. */
    val dimensionLabel: String,
    /** Target minutes. */
    val targetMinutes: Int,
    /** Is active. */
    val isActive: Boolean
)

/**
 * Per-day average focus rating across all tracked time entries.
 *
 * @property dayKey ISO date key for the day.
 * @property avgFocus Average focus rating for the day, or null if unavailable.
 */
data class DailyFocusStat(val dayKey: String, val avgFocus: Double?)

/**
 * Per-day tracked time percentage (0.0 to 100.0).
 * Calculated as: (tracked minutes in day) / 1440 * 100.
 *
 * @property dayKey ISO date key for the day.
 * @property trackedPercent Percentage of the day spent in tracked activities.
 */
data class DailyTrackedTimeStat(val dayKey: String, val trackedPercent: Double)

/**
 * Per-day focused hours (0.0 to 24.0).
 * Calculated as: sum(clampedMinutes * focusRating) / 60 per day.
 * Entries without a focusRating contribute 0 focused hours (not a fallback value).
 *
 * @property dayKey ISO date key for the day.
 * @property focusedHours Total focused hours logged for the day.
 */
data class DailyFocusedHoursStat(val dayKey: String, val focusedHours: Double)

/**
 * Lens reflection record (gap detection).
 */
data class LensReflectionRecord(
    /** Id. */
    val id: String,
    /** Day key. */
    val dayKey: String,
    /** Dimension id. */
    val dimensionId: String?,
    /** Reflection type. */
    val reflectionType: String, // untracked_time | missed_task | missed_habit | focus_gap | dimension_gap
    /** Title. */
    val title: String,
    /** Description. */
    val description: String?,
    /** Gap minutes. */
    val gapMinutes: Int?,
    /** Related entity id. */
    val relatedEntityId: String?,
    /** Is addressed. */
    val isAddressed: Boolean,
    /** User note. */
    val userNote: String?,
    /** Created at. */
    val createdAt: String
)

/**
 * One slot entry in the weekly pattern grid.
 * dimensionId == null means unassigned tracked time.
 * dimensionId == "__untracked__" means untracked (synthetic) bucket.
 */
data class SlotEntry(
    /** Dimension id. */
    val dimensionId: String?,
    /** Proportion. */
    val proportion: Float
)

/**
 * One 30-min slot in the weekly pattern grid, holding up to 3 ranked candidates.
 */
data class WeekGridSlot(
    /** Rank1. */
    val rank1: SlotEntry?,
    /** Rank2. */
    val rank2: SlotEntry?,
    /** Rank3. */
    val rank3: SlotEntry?
) {
    companion object {
        /** Empty. */
        val EMPTY = WeekGridSlot(null, null, null)
    }
}

/**
 * One day column in the weekly pattern grid.
 */
data class WeekGridDay(
    /** Day of week. */
    val dayOfWeek: java.time.DayOfWeek,
    /** Slots. */
    val slots: List<WeekGridSlot>
)

/**
 * Full 7-column × 48-row weekly behavioral pattern grid.
 */
data class WeekGridData(
    /** Days. */
    val days: List<WeekGridDay>
)

/**
 * One day column in the minute-level pattern grid.
 * minuteWinners has 1440 entries (index 0 = 00:00, index 1439 = 23:59).
 * null = tracked-unassigned won, "__minute_untracked__" = untracked won, else = dimensionId string.
 */
data class MinutePatternDay(
    /** Day of week. */
    val dayOfWeek: java.time.DayOfWeek,
    /** Minute winners. */
    val minuteWinners: List<String?>
)

/**
 * Full 7-column × 1440-row minute-level behavioral pattern grid.
 */
data class MinutePatternData(
    /** Days. */
    val days: List<MinutePatternDay>
)
