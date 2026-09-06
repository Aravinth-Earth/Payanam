//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import java.time.LocalDate
/**
 * Whole-day tracking totals: tracked minutes, block count, focus share,
 * estimated untracked time, and timeline overlap/gap counts.
 */
data class TimeDayOverallSummary(
    val trackedMinutes: Long = 0,
    val activeBlockCount: Int = 0,
    val focusedMinutesPercent: Float = 0f,
    val untrackedMinutesEstimate: Long = 0,
    val overlapCount: Int = 0,
    val gapCount: Int = 0,
)
/**
 * One dimension's day rollup: tracked minutes, share of the day, focused
 * minutes, block count, and planned-vs-actual delta.
 */
data class TimeDimensionDaySummary(
    val dimensionId: String,
    val dimensionLabel: String,
    val trackedMinutes: Long,
    val sharePercent: Float,
    val focusedMinutes: Long,
    val blockCount: Int,
    val plannedMinutes: Int,
    val plannedDeltaMinutes: Long,
)
/**
 * Trend-strip figures for the selected day: its minutes, the previous day's,
 * and the trailing 7-day average.
 */
data class TimeTrendStripSummary(
    val selectedDayMinutes: Long = 0,
    val previousDayMinutes: Long = 0,
    val last7AverageMinutes: Long = 0,
)
/**
 * UI state for the Time screen visuals section: selected date, loading flag,
 * overall + per-dimension summaries, trend strip, and the active dimension
 * filter.
 */
data class TimeVisualsState(
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val dayOverall: TimeDayOverallSummary = TimeDayOverallSummary(),
    val perDimension: List<TimeDimensionDaySummary> = emptyList(),
    val trend: TimeTrendStripSummary = TimeTrendStripSummary(),
    val selectedDimensionFilterId: String? = null,
)
