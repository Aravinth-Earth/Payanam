//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import java.time.LocalDate
import kotlin.math.abs

/**
 * The three decisions the Time screen makes from state alone, split out of the composable so both
 * platforms render the same answer and each decision can be pinned on its own.
 *
 * They are public because the rules are the screen's contract with the renderer's inputs, and a
 * shared module's `internal` would hide them from the test that proves each one.
 */
fun shouldAutoScrollForHourHeightChange(
    selectedDate: LocalDate,
    today: LocalDate,
    currentHourHeightDp: Float,
    lastAutoScrollHourHeightDp: Float?,
    epsilonDp: Float = 0.1f,
): Boolean {
    if (selectedDate != today) return false
    val previous = lastAutoScrollHourHeightDp ?: return false
    return abs(previous - currentHourHeightDp) > epsilonDp
}

/**
 * Whether the timeline scrolls when the screen first lands on a date: only for today, and only once the
 * stored preferences have finished loading.
 *
 * Public for the same reason as [shouldAutoScrollForHourHeightChange] above: both platforms must render
 * the same decision, and the test pins the decision itself rather than one platform's composition.
 */
fun shouldAutoScrollOnInitialDateSelection(
    selectedDate: LocalDate,
    today: LocalDate,
    preferencesLoading: Boolean,
): Boolean = selectedDate == today && !preferencesLoading

internal fun shouldShowTimelineLoadingPlaceholder(
    isLoading: Boolean,
    isDateContentReady: Boolean,
): Boolean = isLoading && !isDateContentReady
