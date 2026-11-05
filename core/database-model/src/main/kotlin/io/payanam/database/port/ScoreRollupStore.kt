//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.port

import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity

/**
 * Store port for the score roll-up compute (L1/L2/L3 metric cascade).
 *
 * MODULE BOUNDARY: This port lives in `:core:database-model` and NOT in `:core:domain`
 * because its signatures carry [TaskEntity], [TaskOccurrenceEntity], [HabitMetricEntity],
 * [DimensionMetricEntity], and [DayMetricEntity] — all defined in `:core:database-model`.
 * The dependency runs one way: `:core:database-model` → `:core:domain`. Placing this port
 * in `:core:domain` would create a module cycle.
 */
interface ScoreRollupStore {
    // ── Reads ──────────────────────────────────────────────────────────────

    /**
     * Returns all recurring tasks (recurrenceEnabled = 1).
     * Needed by: buildHabitMetrics / buildDimensionMetrics (L1/L2 input).
     */
    suspend fun getRecurringTasks(): List<TaskEntity>

    /**
     * Returns all occurrences for [taskId], ordered by dueDate ascending.
     * Needed by: buildHabitMetrics (L1 score computation).
     */
    suspend fun getOccurrencesForTask(taskId: String): List<TaskOccurrenceEntity>

    /**
     * Returns the total number of occurrence rows across all tasks.
     * Needed by: fresh-install fast-path check in runIfNeeded.
     */
    suspend fun countAllOccurrences(): Int

    /**
     * Returns all habit-metric rows for [habitId], ordered by dayKey ascending.
     * Needed by: MetricBaseline.fromHabitRows (L1 baseline).
     */
    suspend fun getHabitMetricsForHabit(habitId: String): List<HabitMetricEntity>

    /**
     * Returns all dimension-metric rows for [dimensionId], ordered by dayKey ascending.
     * Needed by: MetricBaseline.fromDimensionRows (L2 baseline).
     */
    suspend fun getDimensionMetricsForDimension(dimensionId: String): List<DimensionMetricEntity>

    /**
     * Returns all day-metric rows, ordered by dayKey ascending.
     * Needed by: MetricBaseline.fromDayRows (L3 baseline).
     */
    suspend fun getDayMetrics(): List<DayMetricEntity>

    /**
     * Returns dimension weights as a map of dimensionId → weight.
     * Needed by: buildDayMetrics (L3 weighted aggregation).
     */
    suspend fun getDimensionWeights(): Map<String, Double>

    /**
     * Returns the value of the setting stored under [key], or null when unset.
     * Needed by: backfill guard check in runIfNeeded.
     */
    suspend fun getSetting(key: String): String?

    // ── Writes ─────────────────────────────────────────────────────────────

    /**
     * Upserts a batch of L1 habit-metric rows.
     * Needed by: backfill L1 write.
     */
    suspend fun upsertHabitMetrics(rows: List<HabitMetricEntity>)

    /**
     * Upserts a batch of L2 dimension-metric rows.
     * Needed by: backfill L2 write.
     */
    suspend fun upsertDimensionMetrics(rows: List<DimensionMetricEntity>)

    /**
     * Upserts a batch of L3 day-metric rows.
     * Needed by: backfill L3 write.
     */
    suspend fun upsertDayMetrics(rows: List<DayMetricEntity>)

    /**
     * Updates the recurrence rule of the task with [taskId].
     * Needed by: num/den → CONFIG rule conversion in buildHabitMetrics.
     */
    suspend fun updateRecurrenceRule(taskId: String, rule: String)

    /**
     * Inserts or replaces the setting stored under [key].
     * Needed by: backfill guard write in runIfNeeded.
     */
    suspend fun insertSetting(key: String, value: String)
}
