//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import java.time.LocalDate

/**
 * TimeDayOverallSummary.
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
 * TimeDimensionDaySummary.
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
 * TimeTrendStripSummary.
 */
data class TimeTrendStripSummary(
    val selectedDayMinutes: Long = 0,
    val previousDayMinutes: Long = 0,
    val last7AverageMinutes: Long = 0,
)

/**
 * TimeVisualsState.
 */
data class TimeVisualsState(
    val selectedDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val dayOverall: TimeDayOverallSummary = TimeDayOverallSummary(),
    val perDimension: List<TimeDimensionDaySummary> = emptyList(),
    val trend: TimeTrendStripSummary = TimeTrendStripSummary(),
    val selectedDimensionFilterId: String? = null,
)
