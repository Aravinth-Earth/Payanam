//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.backfill

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.dao.DayMetricDao
import io.payanam.database.dao.DimensionMetricDao
import io.payanam.database.dao.HabitMetricDao
import io.payanam.database.dao.TaskDao
import io.payanam.database.dao.TaskOccurrenceDao
import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.RecurrenceConfig
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inc 3 — live cascade recalc replacing the old decay score update.
 *
 * On every checkmark/status change (completed / skipped / missed / un-toggle):
 *   L1: rebuild the habit's metric tail from the changed day onward
 *       (deleteFrom(fromDay) then recompute; running avg is cumulative so
 *       only the tail needs recomputing — state before fromDay is intact).
 *   L2: rebuild the affected dimension's tail from the same day onward
 *       (equal-weight average of member habits with carry-forward).
 *   L3: rebuild the day-level tail.
 *
 * Startup catch-up (catchUpTail): after the backfill guard is set, every
 * launch extends each habit's L1 through yesterday (missed days → 0.0,
 * logged → 1.0), then refreshes L2/L3 tails. This covers the
 * "app unused for days/weeks" case: the gap appears as 0.0 rows on the
 * next open — no manual action needed.
 *
 * Inc 4b: the currentScore bridge is removed — consumers read the L1
 * metrics directly (HabitMetricRepository.getLatestPerHabit).
 *
 * Fully trace-logged: CASCADE_* and CATCHUP_* events for every
 * positive/edge/error outcome.
 */
@Singleton
class ScoreRollupCascadeService
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) {
        private val logger = UnifiedLogger.getInstance()

        /** Recompute L1/L2/L3 tails after a status change on [date]. */
        suspend fun recalcForStatusChange(taskId: String, date: LocalDate) {
            val db = sessionManager.requireDatabase()
            val tag = "ScoreRollupCascadeService.recalcForStatusChange"
            val started = System.currentTimeMillis()
            try {
                val taskDao = db.taskDao()
                val task = taskDao.getTaskById(taskId) ?: return
                if (task.recurrenceEnabled != 1) return
                logger.i(tag, "CASCADE_START", mapOf("taskId" to taskId, "date" to date.toString()))

                val habitDao = db.habitMetricDao()
                val dimDao = db.dimensionMetricDao()
                val dayDao = db.dayMetricDao()
                val occDao = db.taskOccurrenceDao()

                // ── L1: habit tail ───────────────────────────────────────
                val occurrences = occDao.getOccurrencesForTaskForBackfill(taskId)
                val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetrics(task, occurrences, includeToday = true)
                if (rows.isNotEmpty()) {
                    habitDao.deleteFrom(taskId, date.toString())
                    habitDao.upsertAll(rows.filter { it.dayKey >= date.toString() })
                } else {
                    habitDao.deleteFrom(taskId, date.toString())
                }
                logger.i(
                    tag,
                    "CASCADE_L1_HABIT",
                    mapOf("taskId" to taskId, "tailRows" to rows.count { it.dayKey >= date.toString() }),
                )

                // ── L2: affected dimension tail ──────────────────────────
                val dimensionId = task.dimensionId ?: "dim_unassigned"
                val members = taskDao.getRecurringTasks()
                    .filter { it.status != "archived" && it.recurrenceEnabled == 1 && (it.dimensionId ?: "dim_unassigned") == dimensionId }
                val memberRows = habitDao.getAll().filter { row -> members.any { it.id == row.habitId } }
                val firstDuePerHabit = memberRows.groupBy { it.habitId }
                    .mapValues { (_, rows) -> rows.minOfOrNull { parseDate(it.dayKey) } }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
                val dimRows = ScoreRollupBackfillService.buildDimensionMetrics(members, firstDuePerHabit, memberRows, includeToday = true)
                    .filter { it.dayKey >= date.toString() }
                dimDao.deleteFrom(dimensionId, date.toString())
                if (dimRows.isNotEmpty()) dimDao.upsertAll(dimRows)
                logger.i(
                    tag,
                    "CASCADE_L2_DIMENSION",
                    mapOf("dimensionId" to dimensionId, "tailRows" to dimRows.size),
                )

                // ── L3: day tail ─────────────────────────────────────────
                val dayRows = ScoreRollupBackfillService.buildDayMetrics(dimDao.getAll())
                    .filter { it.dayKey >= date.toString() }
                dayDao.deleteFrom(date.toString())
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
                logger.i(
                    tag,
                    "CASCADE_L3_DAY",
                    mapOf("tailRows" to dayRows.size, "elapsedMs" to (System.currentTimeMillis() - started)),
                )
                logger.i(tag, "CASCADE_END", mapOf("elapsedMs" to (System.currentTimeMillis() - started)))
            } catch (e: Exception) {
                logger.e(tag, "CASCADE_FAILED", e, mapOf("taskId" to taskId, "date" to date.toString()))
            }
        }

        /**
         * Full rebuild after a habit edit (rule/dimension change): the old grid's
         * L1 rows are stale (e.g. daily → weekly leaves daily rows behind), so
         * delete the habit's rows and recompute L1 from firstDue → today, then
         * refresh the affected dimension L2 tail and day L3 tail.
         */
        suspend fun recalcForRuleChange(taskId: String) {
            val db = sessionManager.requireDatabase()
            val tag = "ScoreRollupCascadeService.recalcForRuleChange"
            val started = System.currentTimeMillis()
            try {
                val taskDao = db.taskDao()
                val task = taskDao.getTaskById(taskId) ?: return
                if (task.recurrenceEnabled != 1) return
                logger.i(tag, "CASCADE_RULE_CHANGE_START", mapOf("taskId" to taskId))

                val habitDao = db.habitMetricDao()
                val dimDao = db.dimensionMetricDao()
                val dayDao = db.dayMetricDao()
                val occDao = db.taskOccurrenceDao()

                val occurrences = occDao.getOccurrencesForTaskForBackfill(taskId)
                val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetrics(task, occurrences, includeToday = true)

                // Full L1 rebuild for the habit (old grid rows removed).
                val earliest = rows.minOfOrNull { it.dayKey } ?: LocalDate.now().toString()
                habitDao.deleteFrom(taskId, "0000-01-01")
                if (rows.isNotEmpty()) habitDao.upsertAll(rows)

                // L2: rebuild the affected dimension fully (from its earliest member row).
                val dimensionId = task.dimensionId ?: "dim_unassigned"
                val members = taskDao.getRecurringTasks()
                    .filter { it.status != "archived" && it.recurrenceEnabled == 1 && (it.dimensionId ?: "dim_unassigned") == dimensionId }
                val memberRows = habitDao.getAll().filter { row -> members.any { it.id == row.habitId } }
                val firstDuePerHabit = memberRows.groupBy { it.habitId }
                    .mapValues { (_, rs) -> rs.minOfOrNull { parseDate(it.dayKey) } }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
                val dimRows = ScoreRollupBackfillService.buildDimensionMetrics(members, firstDuePerHabit, memberRows, includeToday = true)
                dimDao.deleteFrom(dimensionId, earliest)
                if (dimRows.isNotEmpty()) dimDao.upsertAll(dimRows)

                // L3: rebuild the day tail from the earliest affected day.
                val dayRows = ScoreRollupBackfillService.buildDayMetrics(dimDao.getAll())
                    .filter { it.dayKey >= earliest }
                dayDao.deleteFrom(earliest)
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)

                logger.i(
                    tag,
                    "CASCADE_RULE_CHANGE_END",
                    mapOf(
                        "habitL1Rows" to rows.size,
                        "dimTailRows" to dimRows.size,
                        "dayTailRows" to dayRows.size,
                        "elapsedMs" to (System.currentTimeMillis() - started),
                    ),
                )
            } catch (e: Exception) {
                logger.e(tag, "CASCADE_RULE_CHANGE_FAILED", e, mapOf("taskId" to taskId))
            }
        }

        /** Startup catch-up: extend every habit's L1 through yesterday, then L2/L3 tails. */
        suspend fun catchUpTail() {
            val db = sessionManager.requireDatabase()
            val tag = "ScoreRollupCascadeService.catchUpTail"
            val started = System.currentTimeMillis()
            try {
                val taskDao = db.taskDao()
                val habitDao = db.habitMetricDao()
                val occDao = db.taskOccurrenceDao()
                val dimDao = db.dimensionMetricDao()
                val dayDao = db.dayMetricDao()

                val recurring = taskDao.getRecurringTasks()
                    .filter { it.status != "archived" && it.recurrenceEnabled == 1 }
                val yesterday = LocalDate.now().minusDays(1)

                // ── Phase 1: extend L1 tails for habits lagging behind ────
                // Compare the DB's actual max dayKey per habit against the
                // computed timeline (firstDue → yesterday). A habit "lags"
                // when its stored rows end before yesterday — e.g. the app was
                // unused for days/weeks. Missed gap days get 0.0 rows.
                val dbMaxByHabit = habitDao.getAll()
                    .groupBy { it.habitId }
                    .mapValues { (_, rows) -> rows.maxOfOrNull { it.dayKey } }

                val gapStarts = mutableMapOf<String, String>() // habitId → first missing dayKey
                var extendedRows = 0
                var computedHabits = 0
                for (task in recurring) {
                    val dbMax = dbMaxByHabit[task.id]
                    // Fast path: habit already current (rows through yesterday or
                    // later) — skip occurrence fetch + full timeline compute.
                    if (dbMax != null && dbMax >= yesterday.toString()) continue
                    val occurrences = occDao.getOccurrencesForTaskForBackfill(task.id)
                    computedHabits++
                    val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetrics(task, occurrences)
                    if (rows.isEmpty()) continue
                    // Strictly newer rows only: interval habits have dbMax <
                    // yesterday as NORMAL (last due was days ago, next due today
                    // or later) — their stored tail is already complete, so a
                    // no-op rewrite must not be reported as an extension.
                    val tail = if (dbMax == null) rows else rows.filter { it.dayKey > dbMax }
                    if (tail.isEmpty()) continue
                    habitDao.deleteFrom(task.id, tail.first().dayKey)
                    habitDao.upsertAll(tail)
                    extendedRows += tail.size
                    gapStarts[task.id] = tail.first().dayKey
                    logger.i(
                        tag,
                        "CATCHUP_HABIT_EXTENDED",
                        mapOf("taskId" to task.id, "fromDay" to tail.first().dayKey, "rows" to tail.size),
                    )
                }
                if (gapStarts.isEmpty()) {
                    logger.d(
                        tag,
                        "CATCHUP_NO_GAP",
                        mapOf(
                            "elapsedMs" to (System.currentTimeMillis() - started),
                            "computedHabits" to computedHabits,
                        ),
                    )
                    return
                }
                logger.i(tag, "CATCHUP_L1_EXTENDED", mapOf("habits" to gapStarts.size, "rows" to extendedRows, "computedHabits" to computedHabits))

                // ── Phase 2: refresh affected dimension tails ──────────────
                val allHabitRows = habitDao.getAll()
                val firstDuePerHabit = allHabitRows.groupBy { it.habitId }
                    .mapValues { (_, rows) -> rows.minOfOrNull { parseDate(it.dayKey) } }
                    .filterValues { it != null }
                    .mapValues { it.value!! }

                val affectedDimFromDay = mutableMapOf<String, String>() // dimensionId → min gapStart
                for (task in recurring) {
                    val gapStart = gapStarts[task.id] ?: continue
                    val dimId = task.dimensionId ?: "dim_unassigned"
                    val existing = affectedDimFromDay[dimId]
                    if (existing == null || gapStart < existing) affectedDimFromDay[dimId] = gapStart
                }

                var dimTailRows = 0
                for ((dimId, fromDay) in affectedDimFromDay) {
                    val members = recurring.filter { (it.dimensionId ?: "dim_unassigned") == dimId }
                    if (members.isEmpty()) continue
                    val memberRows = allHabitRows.filter { row -> members.any { it.id == row.habitId } }
                    val dimRows = ScoreRollupBackfillService.buildDimensionMetrics(members, firstDuePerHabit, memberRows)
                        .filter { it.dayKey >= fromDay }
                    dimDao.deleteFrom(dimId, fromDay)
                    if (dimRows.isNotEmpty()) {
                        dimDao.upsertAll(dimRows)
                        dimTailRows += dimRows.size
                    }
                }
                logger.i(tag, "CATCHUP_L2_REFRESHED", mapOf("dimensions" to affectedDimFromDay.size, "rows" to dimTailRows))

                // ── Phase 3: refresh day tail ──────────────────────────────
                val globalFromDay = affectedDimFromDay.values.minOrNull() ?: yesterday.toString()
                val dayRows = ScoreRollupBackfillService.buildDayMetrics(dimDao.getAll())
                    .filter { it.dayKey >= globalFromDay }
                dayDao.deleteFrom(globalFromDay)
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
                logger.i(
                    tag,
                    "CATCHUP_END",
                    mapOf(
                        "habits" to gapStarts.size,
                        "l1Rows" to extendedRows,
                        "l2Rows" to dimTailRows,
                        "l3Rows" to dayRows.size,
                        "fromDay" to globalFromDay,
                        "elapsedMs" to (System.currentTimeMillis() - started),
                    ),
                )
            } catch (e: Exception) {
                logger.e(tag, "CATCHUP_FAILED", e)
            }
        }

        private fun parseDate(s: String): LocalDate =
            try {
                LocalDate.parse(s.take(10))
            } catch (e: Exception) {
                LocalDate.now()
            }
    }
