//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

/**
 * Latest L1 score roll-up state for a habit (Inc 4 consumer view).
 * Mirrors the 6 self-gov metrics carried by every metric row.
 */
data class HabitL1Summary(
    /** Habit id. */
    val habitId: String,
    override val dayKey: String,
    override val score: Double,
    override val runningAvg: Double,
    override val progress: Double,
    override val streakPos: Int,
    override val streakNet: Int,
    override val posContinue: Int,
) : MetricWindowRow {
    override val key: String get() = habitId
    override val label: String get() = habitId
}
