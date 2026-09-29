//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * L2 — per-dimension per-day metric row (DENSE: every calendar day).
 *
 * Mirrors self-governance `dimension_metrics` (earn repo).
 * Score = weighted average of member habits' due-day scores; non-due days
 * carry forward the previous day's values.
 */
@Entity(
    tableName = "dimension_metrics",
    primaryKeys = ["dimensionId", "dayKey"],
    indices = [Index("dimensionId"), Index("dayKey")],
)
data class DimensionMetricEntity(
    val dimensionId: String,
    val dayKey: String,
    val score: Double,
    val runningAvg: Double,
    val progress: Double,
    val streakPos: Int,
    val streakNet: Int,
    val posContinue: Int,
)
