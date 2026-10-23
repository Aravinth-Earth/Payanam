//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import java.time.LocalDate

/**
 * Recomputes scores whose inputs just changed.
 *
 * The implementation is a cascade over the metric tables and lives behind the database module, which the
 * shared state layer cannot see, so the three calls it needs are named here instead.
 */
interface ScoreRollupTrigger {
    /**
     * Recomputes the rollup for the day of a status change on [taskId].
     */
    suspend fun recalcForStatusChange(taskId: String, date: LocalDate)

    /**
     * Recomputes the rollup for a task whose recurrence rule changed.
     */
    suspend fun recalcForRuleChange(taskId: String)

    /**
     * Recomputes every metric for [changeDate] when no single task is responsible.
     */
    suspend fun recalcDayOnly(changeDate: LocalDate)
}
