//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import java.time.LocalDate

/**
 * TimeDayOverallSummary.
 */
data class TimeDayOverallSummary(
    /** Tracked minutes. */
    val trackedMinutes: Long = 0,
    /** Active block count. */
    val activeBlockCount: Int = 0,
    /** Focused minutes percent. */
    val focusedMinutesPercent: Float = 0f,
    /** Untracked minutes estimate. */
    val untrackedMinutesEstimate: Long = 0,
    /** Overlap count. */
    val overlapCount: Int = 0,
    /** Gap count. */
    val gapCount: Int = 0,
)

/**
 * TimeDimensionDaySummary.
 */
data class TimeDimensionDaySummary(
    /** Dimension id. */
    val dimensionId: String,
    /** Dimension label. */
    val dimensionLabel: String,
    /** Tracked minutes. */
    val trackedMinutes: Long,
    /** Share percent. */
    val sharePercent: Float,
    /** Focused minutes. */
    val focusedMinutes: Long,
    /** Block count. */
    val blockCount: Int,
    /** Planned minutes. */
    val plannedMinutes: Int,
    /** Planned delta minutes. */
    val plannedDeltaMinutes: Long,
)

/**
 * TimeTrendStripSummary.
 */
data class TimeTrendStripSummary(
    /** Selected day minutes. */
    val selectedDayMinutes: Long = 0,
    /** Previous day minutes. */
    val previousDayMinutes: Long = 0,
    /** Last7average minutes. */
    val last7AverageMinutes: Long = 0,
)

/**
 * TimeVisualsState.
 */
data class TimeVisualsState(
    /** Selected date. */
    val selectedDate: LocalDate = LocalDate.now(),
    /** Is loading. */
    val isLoading: Boolean = false,
    /** Day overall. */
    val dayOverall: TimeDayOverallSummary = TimeDayOverallSummary(),
    /** Per dimension. */
    val perDimension: List<TimeDimensionDaySummary> = emptyList(),
    /** Trend. */
    val trend: TimeTrendStripSummary = TimeTrendStripSummary(),
    /** Selected dimension filter id. */
    val selectedDimensionFilterId: String? = null,
)
