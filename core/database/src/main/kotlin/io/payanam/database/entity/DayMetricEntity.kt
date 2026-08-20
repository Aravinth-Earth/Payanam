//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.Entity

/**
 * L3 — per-day aggregate metric row (DENSE: every calendar day).
 *
 * Mirrors self-governance `day_metrics` (earn repo).
 * dayScore = weighted average of dimension scores for that day.
 */
@Entity(
    tableName = "day_metrics",
    primaryKeys = ["dayKey"],
)
/**
 * DayMetricEntity.
 */
data class DayMetricEntity(
    /** Day key. */
    val dayKey: String,
    /** Day score. */
    val dayScore: Double,
    /** Running avg. */
    val runningAvg: Double,
    /** Progress. */
    val progress: Double,
    /** Streak pos. */
    val streakPos: Int,
    /** Streak net. */
    val streakNet: Int,
    /** Pos continue. */
    val posContinue: Int,
)
