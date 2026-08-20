//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

/**
 * Shared shape for score-window rows so ONE detail layout renders habit /
 * dimension / day windows — only the data source differs.
 *
 * Mirrors the 6 self-gov metrics carried by habit_metrics, dimension_metrics
 * and day_metrics (score, running_avg, progress, streak_pos, streak_net,
 * pos_continue).
 */
interface MetricWindowRow {
    /** Stable key: habitId / dimensionId / "DAY". */
    val key: String

    /** Display label (habit name / dimension label / "Day"). */
    val label: String

    /** Day this row belongs to, ISO yyyy-MM-dd. */
    val dayKey: String

    /** Score. */
    val score: Double
    /** Running avg. */
    val runningAvg: Double
    /** Progress. */
    val progress: Double
    /** Streak pos. */
    val streakPos: Int
    /** Streak net. */
    val streakNet: Int
    /** Pos continue. */
    val posContinue: Int
}
