//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.lenses

import io.payanam.presentation.lenses.LensUiState

/**
 * The copy-free Lenses helpers the chart sections share with the screen.
 *
 * They lived in `LensesScreen.kt` until the sections moved to this module. A screen and a section that
 * render the same numbers must read the same constants and formatters, so they travel with the sections.
 */

const val LENS_UNASSIGNED_DIMENSION_KEY = "unassigned"

/** Unit conversion: minutes in an hour. Every `60` in this file is this. */
private const val MINUTES_PER_HOUR = 60

const val LENS_DAY_MINUTES = 24 * MINUTES_PER_HOUR

/**
 * Renders a minute count in the compact `45m` / `2h` / `2h 30m` shape every Lenses time chip
 * uses, where every `60` is [MINUTES_PER_HOUR].
 *
 * A whole number of hours drops the minute part (`120` → `2h`); anything else keeps both.
 * Counts under an hour — including negatives — are emitted verbatim with an `m` suffix, and
 * there is no day rollup, so `1500` renders as `25h` rather than `1d 1h`. The `h`/`m`
 * suffixes are hardcoded, not localized.
 */
fun formatMinutes(minutes: Int): String =
    when {
        minutes < MINUTES_PER_HOUR -> "${minutes}m"
        minutes % MINUTES_PER_HOUR == 0 -> "${minutes / MINUTES_PER_HOUR}h"
        else -> "${minutes / MINUTES_PER_HOUR}h ${minutes % MINUTES_PER_HOUR}m"
    }

/**
 * The distinct dimension ids appearing anywhere in the selected range summary, so a caller can
 * render one card per dimension that has any time, task, or habit data.
 *
 * Keys are unioned from all eight summary maps — planned and actual time, then planned,
 * completed and missed tasks, then planned, completed and missed habits — and de-duplicated.
 * A null `selectedRangeSummary` contributes no keys at all, so the result is an empty list and
 * the caller shows its "no dimension data" empty state. Order follows first appearance in that
 * fixed map order rather than taxonomy sort order; visibility filtering and ordering are the
 * caller's call.
 */
fun collectDimensionIds(uiState: LensUiState): List<String> {
    val summary = uiState.selectedRangeSummary
    val summaryKeys =
        buildList {
            addAll(summary?.plannedByDimension?.keys ?: emptySet())
            addAll(summary?.actualByDimension?.keys ?: emptySet())
            addAll(summary?.plannedTasksByDimension?.keys ?: emptySet())
            addAll(summary?.completedTasksByDimension?.keys ?: emptySet())
            addAll(summary?.missedTasksByDimension?.keys ?: emptySet())
            addAll(summary?.plannedHabitsByDimension?.keys ?: emptySet())
            addAll(summary?.completedHabitsByDimension?.keys ?: emptySet())
            addAll(summary?.missedHabitsByDimension?.keys ?: emptySet())
        }
    return summaryKeys.toSet().toList()
}
