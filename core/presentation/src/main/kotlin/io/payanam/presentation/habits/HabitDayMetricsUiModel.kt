//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.habits

/**
 * One chip in the Habits day-metrics strip: a habit-cascade value for today
 * plus its ordinal rank ("X/Y" where Y = distinct historical values).
 *
 * [value] is the formatted display string for today's metric (already rounded/
 * localized by the producer). [rank] is "X/Y" or "—" when no history exists.
 * [label] is the localized chip caption (Day Score, Run Avg, …).
 * [isPlaceholderRank] marks chips whose rank model is not yet built (Due) so the
 * UI can render the rank dimmed/neutral instead of a real ordinal.
 *
 * [rankNumber] and [rankTotal] are the SAME rank as [rank], kept as numbers:
 * today's 1-based position among [rankTotal] distinct historical values. They
 * let a renderer scale this chip by its OWN rank (1 of N → best, N of N → worst)
 * without parsing the "X/Y" text back apart. Both are null when there is no
 * history (the "—" rank) or when the rank is a placeholder (Due).
 */
data class DayMetricChipData(
    val value: String,
    val rank: String,
    val label: String,
    val isPlaceholderRank: Boolean = false,
    val rankNumber: Int? = null,
    val rankTotal: Int? = null,
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
