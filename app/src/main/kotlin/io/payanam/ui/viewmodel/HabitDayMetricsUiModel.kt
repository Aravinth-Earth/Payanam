//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.ui.viewmodel

/**
 * One chip in the Habits day-metrics strip: a habit-cascade value for today
 * plus its ordinal rank ("X/Y" where Y = distinct historical values).
 *
 * [value] is the formatted display string for today's metric (already rounded/
 * localized by the producer). [rank] is "X/Y" or "—" when no history exists.
 * [label] is the localized chip caption (Day Score, Run Avg, …).
 * [isPlaceholderRank] marks chips whose rank model is not yet built (Due) so the
 * UI can render the rank dimmed/neutral instead of a real ordinal.
 */
data class DayMetricChipData(
    val value: String,
    val rank: String,
    val label: String,
    val isPlaceholderRank: Boolean = false,
)

/**
 * Immutable state for the Habits day-metrics strip (mounted atop the Habits
 * listing in HABITS_ONLY mode). [chips] are in natural cascade order
 * (score → runningAvg → progress → streakPos → streakNet → posContinue, then Due).
 * [isLoading] suppresses the strip until the first load settles.
 */
data class HabitDayMetricsState(
    val isLoading: Boolean = true,
    val chips: List<DayMetricChipData> = emptyList(),
)
