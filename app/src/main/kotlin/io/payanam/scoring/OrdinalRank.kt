//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.scoring

import java.util.Locale

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
 */
fun ordinalRankToday(series: List<Double?>): String {
    val cleaned = series.filterNotNull()
    if (cleaned.isEmpty()) return "—"
    // Today = latest value in the chronological history list.
    val today = cleaned.last()
    val unique = cleaned.distinct().sortedDescending()
    val y = unique.size
    val rank = unique.indexOfFirst { it == today }.let { if (it < 0) y else it + 1 }
    return String.format(Locale.US, "%d/%d", rank, y)
}
