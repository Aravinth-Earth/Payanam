//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.domain.repository.AverageDailyTimeTableData
import io.payanam.domain.repository.DailyFocusStat
import io.payanam.domain.repository.DailyFocusedHoursStat
import io.payanam.domain.repository.DailyTrackedTimeStat
import io.payanam.domain.repository.DimensionTrendBlock
import io.payanam.domain.repository.HeatmapDayData
import io.payanam.domain.repository.LensReflectionRecord
import io.payanam.domain.repository.MinutePatternData
import io.payanam.domain.repository.PlanningLensData
import io.payanam.domain.repository.RealityLensData
import io.payanam.domain.repository.WeekGridData
import java.time.LocalDate

/**
 * Full UI state for the Lens workspace: selection, loaded planning/reality
 * data, reflections, range summaries, and every chart's state object.
 */
data class LensUiState(
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val errorMessage: String? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedMoment: LensMoment = LensMoment.LIVE_DAY,
    val selectedTimeMode: LensTimeMode = LensTimeMode.TODAY,
    val selectedTimeWindow: LensTimeWindow = LensTimeWindow.TODAY,
    val windowPageIndex: Int = 0,
    val canGoToPreviousWindowPage: Boolean = false,
    val canGoToNextWindowPage: Boolean = false,
    val selectedGrouping: LensGrouping = LensGrouping.BY_MODULE,
    val planningData: PlanningLensData? = null,
    val planCompletenessScore: Float = 0f,
    val realityData: RealityLensData? = null,
    val adherenceScore: Float = 0f,
    val reflections: List<LensReflectionRecord> = emptyList(),
    val selectedRangeSummary: LensRangeSummary? = null,
    val timeModuleHistorySummary: TimeModuleHistorySummary? = null,
    val averageDailyTimeTable: AverageDailyTimeTableData? = null,
    val longHorizonSummaries: List<LensRangeSummary> = emptyList(),
    val dailyFocusAverages: List<DailyFocusStat> = emptyList(),
    val dailyTrackedTimeStats: List<DailyTrackedTimeStat> = emptyList(),
    val dailyFocusedHoursStats: List<DailyFocusedHoursStat> = emptyList(),
    val dimensionSplit: DimensionSplitState = DimensionSplitState(),
    val dimensionTrend: DimensionTrendState = DimensionTrendState(),
    val heatmap: HeatmapState = HeatmapState(),
    val weekGrid: WeekGridState = WeekGridState(),
    val minutePattern: MinutePatternState = MinutePatternState(),
)

/**
 * Which phase of the day the lens is viewing: the planned start, the live
 * in-progress day, or the closed-day review.
 */
enum class LensMoment {
    START_DAY,
    LIVE_DAY,
    CLOSE_DAY,
}

/**
 * How lens content is aggregated for display: the overall day, by feature
 * module, or by life dimension.
 */
enum class LensGrouping {
    OVERALL,
    BY_MODULE,
    BY_DIMENSION,
}

/**
 * The browsing direction the lens is showing: the current day, a past window,
 * or a future window.
 */
enum class LensTimeMode {
    TODAY,
    PAST,
    FUTURE,
}

/**
 * The concrete time window offered for a given [LensTimeMode] — from a single
 * day up to a year, plus the unbounded all-past range.
 */
enum class LensTimeWindow {
    TODAY,
    LAST_DAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_90_DAYS,
    LAST_180_DAYS,
    LAST_365_DAYS,
    ALL_PAST_DAYS,
    NEXT_DAY,
    NEXT_7_DAYS,
    NEXT_30_DAYS,
    NEXT_90_DAYS,
    NEXT_180_DAYS,
    NEXT_365_DAYS,
}

/**
 * A single point in a planning-vs-reality trend series: the [dayKey] with its
 * planned and actual tracked minutes.
 */
data class LensTrendPoint(
    val dayKey: String,
    val plannedMinutes: Int,
    val actualMinutes: Int,
)

/**
 * Aggregate planning-vs-reality summary for a resolved range: totals, gaps,
 * task/habit completion counts, and per-dimension planned/actual splits with
 * their trend points.
 */
data class LensRangeSummary(
    val mode: LensTimeMode,
    val window: LensTimeWindow,
    val pageIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalPlannedMinutes: Int,
    val totalActualMinutes: Int,
    val totalUntrackedMinutes: Int,
    val totalFocusGapMinutes: Int,
    val supplementalActualMinutes: Int = 0,
    val plannedTaskMinutes: Int = 0,
    val plannedHabitMinutes: Int = 0,
    val plannedTimeOnlyMinutes: Int = 0,
    val unplannedDayMinutes: Int = 0,
    val actualTimeOnlyMinutes: Int = 0,
    val actualTaskMinutes: Int = 0,
    val actualHabitMinutes: Int = 0,
    val plannedTaskCount: Int,
    val completedTaskCount: Int,
    val missedTaskCount: Int,
    val plannedHabitCount: Int,
    val completedHabitCount: Int,
    val missedHabitCount: Int,
    val averagePlanCompleteness: Float,
    val averageAdherence: Float,
    val plannedByDimension: Map<String, Int>,
    val actualByDimension: Map<String, Int>,
    val supplementalActualByDimension: Map<String, Int> = emptyMap(),
    val plannedTasksByDimension: Map<String, Int>,
    val completedTasksByDimension: Map<String, Int>,
    val missedTasksByDimension: Map<String, Int>,
    val plannedHabitsByDimension: Map<String, Int>,
    val completedHabitsByDimension: Map<String, Int>,
    val missedHabitsByDimension: Map<String, Int>,
    val trendPoints: List<LensTrendPoint>,
)

/**
 * A mode+window+page combination already resolved into concrete start/end
 * dates, the canonical key the lens uses to load and cache a day's data.
 */
data class ResolvedLensWindowRange(
    val mode: LensTimeMode,
    val window: LensTimeWindow,
    val pageIndex: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

/**
 * Whether the current window range can be paged backward/forward (derived from
 * the mode and page index).
 */
data class LensWindowNavigationState(
    val canGoPrevious: Boolean,
    val canGoNext: Boolean,
)

/**
 * The set of time windows legal for a given [mode] (today only today; past and
 * future expose their respective day/week/month/year ranges).
 */
fun windowsForMode(mode: LensTimeMode): List<LensTimeWindow> =
    when (mode) {
        LensTimeMode.TODAY -> {
            listOf(LensTimeWindow.TODAY)
        }

        LensTimeMode.PAST -> {
            listOf(
                LensTimeWindow.LAST_DAY,
                LensTimeWindow.LAST_7_DAYS,
                LensTimeWindow.LAST_30_DAYS,
                LensTimeWindow.LAST_90_DAYS,
                LensTimeWindow.LAST_180_DAYS,
                LensTimeWindow.LAST_365_DAYS,
                LensTimeWindow.ALL_PAST_DAYS,
            )
        }

        LensTimeMode.FUTURE -> {
            listOf(
                LensTimeWindow.NEXT_DAY,
                LensTimeWindow.NEXT_7_DAYS,
                LensTimeWindow.NEXT_30_DAYS,
                LensTimeWindow.NEXT_90_DAYS,
                LensTimeWindow.NEXT_180_DAYS,
                LensTimeWindow.NEXT_365_DAYS,
            )
        }
    }

/**
 * The dimension-split windows the chart can page through, each with its
 * [spanDays] (null for the unbounded ALL range).
 */
enum class DimensionSplitWindow(
    val spanDays: Int?,
) {
    W1(1),
    W7(7),
    W30(30),
    W90(90),
    W180(180),
    W365(365),
    ALL(null),
}

/**
 * State for the dimension-split chart: the selected window/offset, the loaded
 * per-dimension minute totals, clamping flags, and paging availability.
 */
data class DimensionSplitState(
    val window: DimensionSplitWindow = DimensionSplitWindow.W1,
    val windowOffset: Int = 0,
    val firstEntryDate: LocalDate? = null,
    val effectiveStart: LocalDate = LocalDate.now(),
    val effectiveEnd: LocalDate = LocalDate.now(),
    val requestedStart: LocalDate = LocalDate.now(),
    val requestedEnd: LocalDate = LocalDate.now(),
    val byDimension: Map<String?, Int> = emptyMap(),
    val totalMinutes: Int = 0,
    val isClamped: Boolean = false,
    val clampedDays: Int = 0,
    val requestedDays: Int = 0,
    val canShiftLeft: Boolean = false,
    val canShiftRight: Boolean = false,
    val isLoading: Boolean = false,
)

/**
 * The dimension-trend windows the chart can show, each a fixed [spanDays].
 */
enum class DimensionTrendWindow(
    val spanDays: Int,
) {
    W1(1),
    W7(7),
    W30(30),
    W90(90),
    W180(180),
    W365(365),
}

/**
 * State for the dimension-trend chart: the selected window and the loaded
 * trend [blocks].
 */
data class DimensionTrendState(
    val window: DimensionTrendWindow = DimensionTrendWindow.W1,
    val blocks: List<DimensionTrendBlock> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * State for the activity heatmap chart: the loaded per-day [days] and a loading
 * flag.
 */
data class HeatmapState(
    val days: List<HeatmapDayData> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * State for the weekly-pattern chart: the loaded [WeekGridData] and a loading
 * flag.
 */
data class WeekGridState(
    val data: WeekGridData = WeekGridData(emptyList()),
    val isLoading: Boolean = false,
)

/**
 * State for the daily-rhythm chart: the loaded [MinutePatternData] and a loading
 * flag.
 */
data class MinutePatternState(
    val data: MinutePatternData = MinutePatternData(emptyList()),
    val isLoading: Boolean = false,
)
