//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

/**
 * One day's time-module result: day score, day-over-day progress delta,
 * improving streak length, and per-dimension scores.
 */
data class TimeModuleDayMetric(
    val dayKey: String,
    val dayScore: Double,
    val progressDelta: Double,
    val progressStreak: Int,
    val perDimensionScores: Map<String, Double>,
)

/**
 * Time-module history for a lens window: the focus day's metric plus its
 * rank against all loaded days.
 */
data class TimeModuleHistorySummary(
    val currentDayKey: String,
    val currentDayScore: Double,
    val currentProgressDelta: Double,
    val currentProgressStreak: Int,
    val dayScoreRank: Int,
    val progressRank: Int,
    val streakRank: Int,
    val totalDays: Int,
    val metrics: List<TimeModuleDayMetric>,
)
