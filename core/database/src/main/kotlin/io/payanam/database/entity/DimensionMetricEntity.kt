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
/**
 * DimensionMetricEntity.
 */
data class DimensionMetricEntity(
    /** Dimension id. */
    val dimensionId: String,
    /** Day key. */
    val dayKey: String,
    /** Score. */
    val score: Double,
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
