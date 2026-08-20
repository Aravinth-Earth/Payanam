//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * L1 — per-habit per-due-day metric row (SPARSE: due days only).
 *
 * Mirrors self-governance `activity_metrics` (earn repo).
 * Only due-days produce rows; non-due days have no row at all.
 */
@Entity(
    tableName = "habit_metrics",
    primaryKeys = ["habitId", "dayKey"],
    indices = [Index("habitId"), Index("dayKey")],
)
/**
 * HabitMetricEntity.
 */
data class HabitMetricEntity(
    /** Habit id. */
    val habitId: String,
    /** Day key. */
    val dayKey: String,
    /** 1.0 done / 0.0 missed / 1.0 manual-skip (never null). */
    val score: Double,
    /** Cumulative average of due-day scores from first due day. */
    val runningAvg: Double,
    /** runningAvg delta vs previous due-day row (first row: score). */
    val progress: Double,
    /** Streak pos. */
    val streakPos: Int,
    /** Streak net. */
    val streakNet: Int,
    /** Pos continue. */
    val posContinue: Int,
)
