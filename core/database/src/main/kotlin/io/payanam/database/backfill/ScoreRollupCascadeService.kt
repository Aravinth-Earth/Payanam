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
                val dateStr = date.toString()

                // ── L1: habit tail (C1 baseline carry-forward) ────────────
                // Baseline = cumulative state from rows strictly BEFORE the
                // change day; tail loop runs changeDay → today only, so toggle
                // cost stays O(gap) regardless of habit history length.
                val existingHabitRows = habitDao.getForHabit(taskId).filter { it.dayKey < dateStr }
                val l1Baseline = MetricBaseline.fromHabitRows(existingHabitRows)
                logger.d(
                    tag,
                    "CASCADE_BASELINE_L1",
                    mapOf(
                        "taskId" to taskId,
                        "rowsBefore" to existingHabitRows.size,
                        "sumScores" to l1Baseline.sumScores,
                        "count" to l1Baseline.count,
                        "prevAvg" to (l1Baseline.prevAvg ?: "null"),
                        "streaks" to "${l1Baseline.streakPos}/${l1Baseline.streakNet}/${l1Baseline.posContinue}",
                    ),
                )
                val occurrences = occDao.getOccurrencesForTaskForBackfill(taskId)
                val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetricsFrom(
                    task = task,
                    occurrences = occurrences,
                    fromDay = date,
                    includeToday = true,
                    baseline = l1Baseline,
                )
                habitDao.deleteFrom(taskId, dateStr)
                if (rows.isNotEmpty()) habitDao.upsertAll(rows)
                logger.i(
                    tag,
                    "CASCADE_L1_HABIT",
                    mapOf("taskId" to taskId, "fromDay" to dateStr, "tailRows" to rows.size),
                )

                // ── L2: affected dimension tail (C1 baseline) ─────────────
                val dimensionId = task.dimensionId ?: "dim_unassigned"
                val members = taskDao.getRecurringTasks()
                    .filter { it.status != "archived" && it.recurrenceEnabled == 1 && (it.dimensionId ?: "dim_unassigned") == dimensionId }
                val memberRows = habitDao.getAll().filter { row -> members.any { it.id == row.habitId } }
                val firstDuePerHabit = memberRows.groupBy { it.habitId }
                    .mapValues { (_, rows) -> rows.minOfOrNull { parseDate(it.dayKey) } }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
                val existingDimRows = dimDao.getAll()
                    .filter { it.dimensionId == dimensionId && it.dayKey < dateStr }
                    .sortedBy { it.dayKey }
                val dimBaseline = MetricBaseline.fromDimensionRows(existingDimRows)
                // Carry-forward seed: each member habit's last L1 score BEFORE the change day.
                val lastScores = memberRows
                    .groupBy { it.habitId }
                    .mapValues { (_, rs) -> rs.filter { it.dayKey < dateStr }.maxByOrNull { it.dayKey } }
                    .filterValues { it != null }
                    .mapValues { it.value!!.score }
                logger.d(
                    tag,
                    "CASCADE_BASELINE_L2",
                    mapOf(
                        "dimensionId" to dimensionId,
                        "rowsBefore" to existingDimRows.size,
                        "carryForwardHabits" to lastScores.size,
                        "count" to dimBaseline.count,
                        "prevAvg" to (dimBaseline.prevAvg ?: "null"),
                    ),
                )
                val dimRows = ScoreRollupBackfillService.buildDimensionMetricsFrom(
                    recurring = members,
                    firstDuePerHabit = firstDuePerHabit,
                    habitRows = memberRows,
                    fromDay = date,
                    includeToday = true,
                    baseline = dimBaseline,
                    lastScores = lastScores,
                )
                dimDao.deleteFrom(dimensionId, dateStr)
                if (dimRows.isNotEmpty()) dimDao.upsertAll(dimRows)
                logger.i(
                    tag,
                    "CASCADE_L2_DIMENSION",
                    mapOf("dimensionId" to dimensionId, "fromDay" to dateStr, "tailRows" to dimRows.size),
                )

                // ── L3: day tail (C1 baseline, C2 dimension weights) ──────
                val existingDayRows = dayDao.getAll().filter { it.dayKey < dateStr }.sortedBy { it.dayKey }
                val dayBaseline = MetricBaseline.fromDayRows(existingDayRows)
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                logger.d(
                    tag,
                    "CASCADE_BASELINE_L3",
                    mapOf(
                        "rowsBefore" to existingDayRows.size,
                        "count" to dayBaseline.count,
                        "prevAvg" to (dayBaseline.prevAvg ?: "null"),
                        "weightedDims" to dimWeights.size,
                    ),
                )
                val dayRows = ScoreRollupBackfillService.buildDayMetricsFrom(
                    dimensionRows = dimDao.getAll(),
                    fromDay = date,
                    baseline = dayBaseline,
                    dimWeights = dimWeights,
                )
                dayDao.deleteFrom(dateStr)
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
                logger.i(
                    tag,
                    "CASCADE_L3_DAY",
                    mapOf("fromDay" to dateStr, "tailRows" to dayRows.size, "elapsedMs" to (System.currentTimeMillis() - started)),
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
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                val dayRows = ScoreRollupBackfillService.buildDayMetrics(dimDao.getAll(), dimWeights)
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

        /**
         * L3-only recalc after a dimension-weight change (C2, self-gov
         * `dim_weight_change` path: Skip L1+L2 → only L3). L1/L2 rows are
         * weight-independent; only the day score aggregation changes.
         *
         * Rebuilds from the EARLIEST available dimension day (full L3 pass):
         * weights apply to every day's aggregation, so a weight edit must
         * re-aggregate ALL history — not just from today onward. Cost is
         * O(days × dimensions), which stays small even for decade-long
         * histories (day rows are dense by design).
         */
        suspend fun recalcDayOnly(changeDate: LocalDate) {
            val db = sessionManager.requireDatabase()
            val tag = "ScoreRollupCascadeService.recalcDayOnly"
            val started = System.currentTimeMillis()
            try {
                val dayDao = db.dayMetricDao()
                val dimDao = db.dimensionMetricDao()
                val allDimRows = dimDao.getAll()
                val fromDay = allDimRows.minOfOrNull { parseDate(it.dayKey) } ?: changeDate
                val dateStr = fromDay.toString()

                // Full L3 pass: empty baseline — every day re-aggregates with
                // the new weights (cumulative running avg recomputed).
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                logger.i(
                    tag,
                    "CASCADE_DAY_ONLY_START",
                    mapOf(
                        "changeDate" to changeDate.toString(),
                        "fromDay" to dateStr,
                        "rowsBefore" to dayDao.getAll().size,
                        "weightedDims" to dimWeights.size,
                        "weights" to dimWeights.toString(),
                    ),
                )
                val dayRows = ScoreRollupBackfillService.buildDayMetricsFrom(
                    dimensionRows = allDimRows,
                    fromDay = fromDay,
                    baseline = MetricBaseline.empty(),
                    dimWeights = dimWeights,
                )
                dayDao.deleteFrom(dateStr)
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
                logger.i(
                    tag,
                    "CASCADE_DAY_ONLY_END",
                    mapOf("fromDay" to dateStr, "tailRows" to dayRows.size, "elapsedMs" to (System.currentTimeMillis() - started)),
                )
            } catch (e: Exception) {
                logger.e(tag, "CASCADE_DAY_ONLY_FAILED", e, mapOf("fromDay" to changeDate.toString()))
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
                // Compare the DB's actual max dayKey per habit against
                // yesterday. A habit "lags" when its stored rows end before
                // yesterday — e.g. the app was unused for days/weeks. Missed
                // gap days get 0.0 rows. C1: tail build seeded from the
                // cumulative baseline of existing rows (O(gap) per habit).
                // GROUP BY aggregate — O(rows) not O(all rows in memory).
                val dbMaxByHabit = habitDao.maxDayKeyPerHabit()
                    .associate { it.habitId to it.maxDayKey }

                val gapStarts = mutableMapOf<String, String>() // habitId → first missing dayKey
                var extendedRows = 0
                var computedHabits = 0
                for (task in recurring) {
                    val dbMax = dbMaxByHabit[task.id]
                    // Fast path: habit already current (rows through yesterday or
                    // later) — skip occurrence fetch + timeline compute.
                    if (dbMax != null && dbMax >= yesterday.toString()) continue
                    val occurrences = occDao.getOccurrencesForTaskForBackfill(task.id)
                    computedHabits++
                    // Baseline = all existing rows (they end at dbMax < fromDay).
                    val fromDay = dbMax?.let { parseDate(it).plusDays(1) } ?: run {
                        val firstOccurrence = occurrences.mapNotNull { runCatching { LocalDate.parse(it.dueDate.take(10)) }.getOrNull() }.minOrNull()
                            ?: continue
                        firstOccurrence
                    }
                    val existing = habitDao.getForHabit(task.id).filter { it.dayKey < fromDay.toString() }
                    val baseline = MetricBaseline.fromHabitRows(existing)
                    logger.d(
                        tag,
                        "CATCHUP_BASELINE_L1",
                        mapOf(
                            "taskId" to task.id,
                            "fromDay" to fromDay.toString(),
                            "rowsBefore" to existing.size,
                            "count" to baseline.count,
                            "prevAvg" to (baseline.prevAvg ?: "null"),
                        ),
                    )
                    val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetricsFrom(
                        task = task,
                        occurrences = occurrences,
                        fromDay = fromDay,
                        includeToday = false,
                        baseline = baseline,
                    )
                    if (rows.isEmpty()) continue
                    // Rows from fromDay are strictly newer than dbMax — no-op
                    // rewrites impossible; interval habits with next-due in the
                    // future simply produce an empty tail and are skipped.
                    habitDao.deleteFrom(task.id, fromDay.toString())
                    habitDao.upsertAll(rows)
                    extendedRows += rows.size
                    gapStarts[task.id] = fromDay.toString()
                    logger.i(
                        tag,
                        "CATCHUP_HABIT_EXTENDED",
                        mapOf("taskId" to task.id, "fromDay" to fromDay.toString(), "rows" to rows.size),
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
                    val dimBaseline = MetricBaseline.fromDimensionRows(
                        dimDao.getAll().filter { it.dimensionId == dimId && it.dayKey < fromDay }.sortedBy { it.dayKey },
                    )
                    val lastScores = memberRows
                        .groupBy { it.habitId }
                        .mapValues { (_, rs) -> rs.filter { it.dayKey < fromDay }.maxByOrNull { it.dayKey } }
                        .filterValues { it != null }
                        .mapValues { it.value!!.score }
                    logger.d(
                        tag,
                        "CATCHUP_BASELINE_L2",
                        mapOf(
                            "dimensionId" to dimId,
                            "fromDay" to fromDay,
                            "carryForwardHabits" to lastScores.size,
                            "count" to dimBaseline.count,
                        ),
                    )
                    val dimRows = ScoreRollupBackfillService.buildDimensionMetricsFrom(
                        recurring = members,
                        firstDuePerHabit = firstDuePerHabit,
                        habitRows = memberRows,
                        fromDay = parseDate(fromDay),
                        includeToday = false,
                        baseline = dimBaseline,
                        lastScores = lastScores,
                    )
                    dimDao.deleteFrom(dimId, fromDay)
                    if (dimRows.isNotEmpty()) {
                        dimDao.upsertAll(dimRows)
                        dimTailRows += dimRows.size
                    }
                }
                logger.i(tag, "CATCHUP_L2_REFRESHED", mapOf("dimensions" to affectedDimFromDay.size, "rows" to dimTailRows))

                // ── Phase 3: refresh day tail ──────────────────────────────
                val globalFromDay = affectedDimFromDay.values.minOrNull() ?: yesterday.toString()
                val dayBaseline = MetricBaseline.fromDayRows(
                    dayDao.getAll().filter { it.dayKey < globalFromDay }.sortedBy { it.dayKey },
                )
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                logger.d(
                    tag,
                    "CATCHUP_BASELINE_L3",
                    mapOf("fromDay" to globalFromDay, "count" to dayBaseline.count, "prevAvg" to (dayBaseline.prevAvg ?: "null"), "weightedDims" to dimWeights.size),
                )
                val dayRows = ScoreRollupBackfillService.buildDayMetricsFrom(
                    dimensionRows = dimDao.getAll(),
                    fromDay = parseDate(globalFromDay),
                    baseline = dayBaseline,
                    dimWeights = dimWeights,
                )
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
