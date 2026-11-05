//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.scoring

/**
 * The score metrics a score-matrix dropdown or a day-metric chip row can show.
 *
 * It lives in its own package rather than inside whichever ViewModel declared it first: two verticals read
 * it (the tasks board's day-metric chips and the Lenses score matrix), and [key] is the persisted form, so a
 * rename here is a stored-preference migration rather than a refactor.
 */
enum class ScoreMetricColumn(
    val key: String,
) {
    /** The composite score for the day. */
    SCORE("score"),

    /** The running average of the composite score. */
    RUNNING_AVG("running"),

    /** Average progress across the day's targets. */
    PROGRESS("progress"),

    /** Positive-day streak. */
    STREAK_POS("streak"),

    /** Net (positive minus negative) streak. */
    STREAK_NET("net"),

    /** Consecutive-day streak. */
    POS_CONTINUE("continue"),
}
