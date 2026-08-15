//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.HabitL1Summary

/**
 * Read access to the L1 habit score roll-up (Inc 4 consumer rewiring).
 * UI consumers (HabitCard ring, sorting, cache fingerprints) need the
 * latest L1 state per habit — runningAvg doubles as the new "score".
 */
interface HabitMetricRepository {
    /** Latest L1 row per habit (deterministic via MAX(dayKey) subquery). */
    suspend fun getLatestPerHabit(): Map<String, HabitL1Summary>

    /** Latest L1 row for one habit, or null when no metrics yet. */
    suspend fun getLatestForHabit(habitId: String): HabitL1Summary?

    /** L1 rows for one habit within [start]..[end] (inclusive), ascending — activity detail window. */
    suspend fun getForHabitRange(habitId: String, start: String, end: String): List<HabitL1Summary>
}
