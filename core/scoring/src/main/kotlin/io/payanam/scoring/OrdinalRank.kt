//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.scoring

import java.util.Locale

/**
 * The numeric halves of an ordinal rank: today's 1-based position [rank] among
 * [total] distinct historical values.
 *
 * Carried next to the rendered "X/Y" string so a surface can scale a value by
 * its OWN rank (1 of total → best, total of total → worst) without re-parsing
 * the text back into numbers.
 */
data class OrdinalRankParts(
    val rank: Int,
    val total: Int,
)

/**
 * The numeric parts of today's ordinal rank in [series], or null when there is
 * no history at all — the same case [ordinalRankToday] renders as "—" (no data).
 *
 * The rank SEMANTICS live here and only here: denominator = count of DISTINCT
 * historical values (repeats collapsed); highest value → #1; ties share the same
 * (dense) rank. [ordinalRankToday] delegates to this function, so the rendered
 * string and the numeric pair can never disagree.
 *
 * @param series the metric's full chronological history, newest last.
 * @return today's rank and the number of distinct historical values, or null
 *   when [series] holds no non-null value.
 */
fun ordinalRankParts(series: List<Double?>): OrdinalRankParts? {
    val cleaned = series.filterNotNull()
    if (cleaned.isEmpty()) return null
    // Today = latest value in the chronological history list.
    val today = cleaned.last()
    val unique = cleaned.distinct().sortedDescending()
    val y = unique.size
    val rank = unique.indexOfFirst { it == today }.let { if (it < 0) y else it + 1 }
    return OrdinalRankParts(rank = rank, total = y)
}

/**
 * Ordinal rank of the latest value in [series] across its OWN full history.
 *
 * Denominator = count of DISTINCT historical values (repeats collapsed), so the
 * rank reflects how today compares to every unique past state, not raw row count.
 * Highest value → #1. Ties share the same (dense) rank.
 *
 * Returns "X/Y" where X = today's 1-based ordinal rank (1 = best) and Y = number
 * of distinct historical values. When [series] is empty, returns "—" (no data).
 *
 * This is the single source of truth for the "today / max" rank format used by
 * both the Lenses score matrix (`LensHabitScoreViewModel.computeRankMap`) and the
 * Habits day-metrics strip. Extracted so the two surfaces never diverge.
 *
 * Delegates to [ordinalRankParts]; the numeric pair is the same computation the
 * string is rendered from.
 */
fun ordinalRankToday(series: List<Double?>): String {
    val parts = ordinalRankParts(series) ?: return "—"
    return String.format(Locale.US, "%d/%d", parts.rank, parts.total)
}
