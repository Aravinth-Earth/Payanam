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
    val dayKey: String,
    val dayScore: Double,
    val runningAvg: Double,
    val progress: Double,
    val streakPos: Int,
    val streakNet: Int,
    val posContinue: Int,
)
