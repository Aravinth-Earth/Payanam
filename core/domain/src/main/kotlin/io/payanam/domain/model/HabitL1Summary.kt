//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

/**
 * Latest L1 score roll-up state for a habit (Inc 4 consumer view).
 * Mirrors the 6 self-gov metrics carried by every metric row.
 */
data class HabitL1Summary(
    val habitId: String,
    val dayKey: String,
    val score: Double,
    val runningAvg: Double,
    val progress: Double,
    val streakPos: Int,
    val streakNet: Int,
    val posContinue: Int,
)
