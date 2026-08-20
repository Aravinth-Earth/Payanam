//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")

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
import io.payanam.database.event.ScoreChangeEventBus
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.RecurrenceConfig
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
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
 * Inc 5: single-line value traces (CASCADE_TRACE / CASCADE_RULE_TRACE /
 * CASCADE_DAY_ONLY_TRACE / CATCHUP_TRACE) replace the per-layer count-only
 * lines: one line carries old→new for all 6 metrics (S/A/P/sp/sn/pc, ∅ = no
 * row, %.4f doubles) across the affected layers, so a toggle's effect is
 * diagnosable from the log without re-deriving the math.
 *
 * Fully trace-logged: CASCADE_* and CATCHUP_* events for every
 * positive/edge/error outcome.
 */
@Singleton
/**
 * ScoreRollupCascadeService.
 */
class ScoreRollupCascadeService
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
        private val scoreChangeEventBus: ScoreChangeEventBus,
    ) {
        private val logger = UnifiedLogger.getInstance()

        /** Recompute L1/L2/L3 tails after a status change on [date]. */
        suspend fun recalcForStatusChange(taskId: String, date: LocalDate) {
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Tag. */
            val tag = "ScoreRollupCascadeService.recalcForStatusChange"
            /** Started. */
            val started = System.currentTimeMillis()
            try {
                /** Task dao. */
                val taskDao = db.taskDao()
                /** Task. */
                val task = taskDao.getTaskById(taskId) ?: return
                /** If. */
                if (task.recurrenceEnabled != 1) return
                logger.i(tag, "CASCADE_START", mapOf("taskId" to taskId, "date" to date.toString()))

                /** Habit dao. */
                val habitDao = db.habitMetricDao()
                /** Dim dao. */
                val dimDao = db.dimensionMetricDao()
                /** Day dao. */
                val dayDao = db.dayMetricDao()
                /** Occ dao. */
                val occDao = db.taskOccurrenceDao()
                /** Date str. */
                val dateStr = date.toString()

                // ── L1: habit tail (C1 baseline carry-forward) ────────────
                // Baseline = cumulative state from rows strictly BEFORE the
                // change day; tail loop runs changeDay → today only, so toggle
                // cost stays O(gap) regardless of habit history length.
                /** All habit rows. */
                val allHabitRows = habitDao.getForHabit(taskId)
                /** Existing habit rows. */
                val existingHabitRows = allHabitRows.filter { it.dayKey < dateStr }
                /** L1baseline. */
                val l1Baseline = MetricBaseline.fromHabitRows(existingHabitRows)
                logger.d(
                    /** Tag. */
                    tag,
                    "CASCADE_BASELINE_L1",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "rowsBefore" to existingHabitRows.size,
                        "sumScores" to l1Baseline.sumScores,
                        "count" to l1Baseline.count,
                        "prevAvg" to (l1Baseline.prevAvg ?: "null"),
                        "streaks" to "${l1Baseline.streakPos}/${l1Baseline.streakNet}/${l1Baseline.posContinue}",
                    ),
                )
                /** Occurrences. */
                val occurrences = occDao.getOccurrencesForTaskForBackfill(taskId)
                /** Val. */
                val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetricsFrom(
                    task = task,
                    occurrences = occurrences,
                    fromDay = date,
                    includeToday = true,
                    baseline = l1Baseline,
                )
                habitDao.deleteFrom(taskId, dateStr)
                /** If. */
                if (rows.isNotEmpty()) habitDao.upsertAll(rows)

                // ── L2: affected dimension tail (C1 baseline) ─────────────
                /** Dimension id. */
                val dimensionId = task.dimensionId ?: "dim_unassigned"
                /** Members. */
                val members = taskDao.getRecurringTasks()
                    .filter { it.status != "archived" && it.recurrenceEnabled == 1 && (it.dimensionId ?: "dim_unassigned") == dimensionId }
                /** Member rows. */
                val memberRows = habitDao.getAll().filter { row -> members.any { it.id == row.habitId } }
                /** First due per habit. */
                val firstDuePerHabit = memberRows.groupBy { it.habitId }
                    .mapValues { (_, rows) -> rows.minOfOrNull { parseDate(it.dayKey) } }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
                /** All dim rows. */
                val allDimRows = dimDao.getAll()
                /** Existing dim rows. */
                val existingDimRows = allDimRows
                    .filter { it.dimensionId == dimensionId && it.dayKey < dateStr }
                    .sortedBy { it.dayKey }
                /** Dim baseline. */
                val dimBaseline = MetricBaseline.fromDimensionRows(existingDimRows)
                // Carry-forward seed: each member habit's last L1 score BEFORE the change day.
                /** Last scores. */
                val lastScores = memberRows
                    .groupBy { it.habitId }
                    .mapValues { (_, rs) -> rs.filter { it.dayKey < dateStr }.maxByOrNull { it.dayKey } }
                    .filterValues { it != null }
                    .mapValues { it.value!!.score }
                logger.d(
                    /** Tag. */
                    tag,
                    "CASCADE_BASELINE_L2",
                    /** Map of. */
                    mapOf(
                        "dimensionId" to dimensionId,
                        "rowsBefore" to existingDimRows.size,
                        "carryForwardHabits" to lastScores.size,
                        "count" to dimBaseline.count,
                        "prevAvg" to (dimBaseline.prevAvg ?: "null"),
                    ),
                )
                /** Dim rows. */
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
                /** If. */
                if (dimRows.isNotEmpty()) dimDao.upsertAll(dimRows)

                // ── L3: day tail (C1 baseline, C2 dimension weights) ──────
                /** All day rows. */
                val allDayRows = dayDao.getAll()
                /** Existing day rows. */
                val existingDayRows = allDayRows.filter { it.dayKey < dateStr }.sortedBy { it.dayKey }
                /** Day baseline. */
                val dayBaseline = MetricBaseline.fromDayRows(existingDayRows)
                /** Dim weights. */
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                logger.d(
                    /** Tag. */
                    tag,
                    "CASCADE_BASELINE_L3",
                    /** Map of. */
                    mapOf(
                        "rowsBefore" to existingDayRows.size,
                        "count" to dayBaseline.count,
                        "prevAvg" to (dayBaseline.prevAvg ?: "null"),
                        "weightedDims" to dimWeights.size,
                    ),
                )
                /** Day rows. */
                val dayRows = ScoreRollupBackfillService.buildDayMetricsFrom(
                    dimensionRows = dimDao.getAll(),
                    fromDay = date,
                    baseline = dayBaseline,
                    dimWeights = dimWeights,
                )
                dayDao.deleteFrom(dateStr)
                /** If. */
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)

                // ── Value trace: one line, old→new per metric per layer ──
                // Old rows are the pre-rebuild tail (≥ change day) captured
                // from the same queries that fed the baselines; ∅ = no row.
                logger.i(
                    /** Tag. */
                    tag,
                    /** List of. */
                    listOf(
                        "CASCADE_TRACE | t=$taskId d=$dateStr",
                        /** Trace section. */
                        traceSection(
                            "L1",
                            allHabitRows.filter { it.dayKey >= dateStr }
                                .associate { it.dayKey to it.toTraceValues() },
                            rows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        /** Trace section. */
                        traceSection(
                            "L2",
                            /** All dim rows. */
                            allDimRows
                                .filter { it.dimensionId == dimensionId && it.dayKey >= dateStr }
                                .associate { it.dayKey to it.toTraceValues() },
                            dimRows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        /** Trace section. */
                        traceSection(
                            "L3",
                            allDayRows.filter { it.dayKey >= dateStr }
                                .associate { it.dayKey to it.toTraceValues() },
                            dayRows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        "ms=${System.currentTimeMillis() - started}",
                    ).filter { it.isNotEmpty() }.joinToString(" | "),
                )
                logger.i(tag, "CASCADE_END", mapOf("elapsedMs" to (System.currentTimeMillis() - started)))
                scoreChangeEventBus.emit(date)
                logger.i(tag, "Score change event emitted", mapOf("date" to date.toString()))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Tag. */
            val tag = "ScoreRollupCascadeService.recalcForRuleChange"
            /** Started. */
            val started = System.currentTimeMillis()
            try {
                /** Task dao. */
                val taskDao = db.taskDao()
                /** Task. */
                val task = taskDao.getTaskById(taskId) ?: return
                /** If. */
                if (task.recurrenceEnabled != 1) return
                logger.i(tag, "CASCADE_RULE_CHANGE_START", mapOf("taskId" to taskId))

                /** Habit dao. */
                val habitDao = db.habitMetricDao()
                /** Dim dao. */
                val dimDao = db.dimensionMetricDao()
                /** Day dao. */
                val dayDao = db.dayMetricDao()
                /** Occ dao. */
                val occDao = db.taskOccurrenceDao()

                /** Occurrences. */
                val occurrences = occDao.getOccurrencesForTaskForBackfill(taskId)
                /** Val. */
                val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetrics(task, occurrences, includeToday = true)

                // Full L1 rebuild for the habit (old grid rows removed).
                /** Earliest. */
                val earliest = rows.minOfOrNull { it.dayKey } ?: LocalDate.now().toString()
                /** Old habit rows. */
                val oldHabitRows = habitDao.getForHabit(taskId)
                habitDao.deleteFrom(taskId, "0000-01-01")
                /** If. */
                if (rows.isNotEmpty()) habitDao.upsertAll(rows)

                // L2: rebuild the affected dimension fully (from its earliest member row).
                /** Dimension id. */
                val dimensionId = task.dimensionId ?: "dim_unassigned"
                /** Members. */
                val members = taskDao.getRecurringTasks()
                    .filter { it.status != "archived" && it.recurrenceEnabled == 1 && (it.dimensionId ?: "dim_unassigned") == dimensionId }
                /** Member rows. */
                val memberRows = habitDao.getAll().filter { row -> members.any { it.id == row.habitId } }
                /** First due per habit. */
                val firstDuePerHabit = memberRows.groupBy { it.habitId }
                    .mapValues { (_, rs) -> rs.minOfOrNull { parseDate(it.dayKey) } }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
                /** Dim rows. */
                val dimRows = ScoreRollupBackfillService.buildDimensionMetrics(members, firstDuePerHabit, memberRows, includeToday = true)
                /** Old dim rows. */
                val oldDimRows = dimDao.getAll()
                    .filter { it.dimensionId == dimensionId && it.dayKey >= earliest }
                dimDao.deleteFrom(dimensionId, earliest)
                /** If. */
                if (dimRows.isNotEmpty()) dimDao.upsertAll(dimRows)

                // L3: rebuild the day tail from the earliest affected day.
                /** Dim weights. */
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                /** Day rows. */
                val dayRows = ScoreRollupBackfillService.buildDayMetrics(dimDao.getAll(), dimWeights)
                    .filter { it.dayKey >= earliest }
                /** Old day rows. */
                val oldDayRows = dayDao.getAll().filter { it.dayKey >= earliest }
                dayDao.deleteFrom(earliest)
                /** If. */
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)

                logger.i(
                    /** Tag. */
                    tag,
                    /** List of. */
                    listOf(
                        "CASCADE_RULE_TRACE | t=$taskId",
                        /** Trace section. */
                        traceSection(
                            "L1",
                            oldHabitRows.associate { it.dayKey to it.toTraceValues() },
                            rows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        /** Trace section. */
                        traceSection(
                            "L2",
                            oldDimRows.associate { it.dayKey to it.toTraceValues() },
                            dimRows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        /** Trace section. */
                        traceSection(
                            "L3",
                            oldDayRows.associate { it.dayKey to it.toTraceValues() },
                            dayRows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        "ms=${System.currentTimeMillis() - started}",
                    ).filter { it.isNotEmpty() }.joinToString(" | "),
                )
                scoreChangeEventBus.emit(LocalDate.now())
                logger.i(tag, "Score change event emitted", mapOf("date" to "rule-change"))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Tag. */
            val tag = "ScoreRollupCascadeService.recalcDayOnly"
            /** Started. */
            val started = System.currentTimeMillis()
            try {
                /** Day dao. */
                val dayDao = db.dayMetricDao()
                /** Dim dao. */
                val dimDao = db.dimensionMetricDao()
                /** All dim rows. */
                val allDimRows = dimDao.getAll()
                /** From day. */
                val fromDay = allDimRows.minOfOrNull { parseDate(it.dayKey) } ?: changeDate
                /** Date str. */
                val dateStr = fromDay.toString()

                // Full L3 pass: empty baseline — every day re-aggregates with
                // the new weights (cumulative running avg recomputed).
                /** Dim weights. */
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                /** Old day rows. */
                val oldDayRows = dayDao.getAll()
                logger.i(
                    /** Tag. */
                    tag,
                    "CASCADE_DAY_ONLY_START",
                    /** Map of. */
                    mapOf(
                        "changeDate" to changeDate.toString(),
                        "fromDay" to dateStr,
                        "rowsBefore" to oldDayRows.size,
                        "weightedDims" to dimWeights.size,
                        "weights" to dimWeights.toString(),
                    ),
                )
                /** Day rows. */
                val dayRows = ScoreRollupBackfillService.buildDayMetricsFrom(
                    dimensionRows = allDimRows,
                    fromDay = fromDay,
                    baseline = MetricBaseline.empty(),
                    dimWeights = dimWeights,
                )
                dayDao.deleteFrom(dateStr)
                /** If. */
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
                // Notify subscribers (e.g. Lenses matrix) so derived values refresh.
                scoreChangeEventBus.emit(changeDate)
                logger.i(tag, "Score change event emitted", mapOf("date" to changeDate.toString()))
                logger.i(
                    /** Tag. */
                    tag,
                    /** List of. */
                    listOf(
                        "CASCADE_DAY_ONLY_TRACE | d=$dateStr",
                        /** Trace section. */
                        traceSection(
                            "L3",
                            oldDayRows.filter { it.dayKey >= dateStr }
                                .associate { it.dayKey to it.toTraceValues() },
                            dayRows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        "ms=${System.currentTimeMillis() - started}",
                    ).filter { it.isNotEmpty() }.joinToString(" | "),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(tag, "CASCADE_DAY_ONLY_FAILED", e, mapOf("fromDay" to changeDate.toString()))
            }
        }

        /** Startup catch-up: extend every habit's L1 through yesterday, then L2/L3 tails. */
        suspend fun catchUpTail() {
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Tag. */
            val tag = "ScoreRollupCascadeService.catchUpTail"
            /** Started. */
            val started = System.currentTimeMillis()
            try {
                /** Task dao. */
                val taskDao = db.taskDao()
                /** Habit dao. */
                val habitDao = db.habitMetricDao()
                /** Occ dao. */
                val occDao = db.taskOccurrenceDao()
                /** Dim dao. */
                val dimDao = db.dimensionMetricDao()
                /** Day dao. */
                val dayDao = db.dayMetricDao()

                /** Recurring. */
                val recurring = taskDao.getRecurringTasks()
                    .filter { it.status != "archived" && it.recurrenceEnabled == 1 }
                /** Yesterday. */
                val yesterday = LocalDate.now().minusDays(1)

                // ── Phase 1: extend L1 tails for habits lagging behind ────
                // Compare the DB's actual max dayKey per habit against
                // yesterday. A habit "lags" when its stored rows end before
                // yesterday — e.g. the app was unused for days/weeks. Missed
                // gap days get 0.0 rows. C1: tail build seeded from the
                // cumulative baseline of existing rows (O(gap) per habit).
                // GROUP BY aggregate — O(rows) not O(all rows in memory).
                /** Db max by habit. */
                val dbMaxByHabit = habitDao.maxDayKeyPerHabit()
                    .associate { it.habitId to it.maxDayKey }

                /** Gap starts. */
                val gapStarts = mutableMapOf<String, String>() // habitId → first missing dayKey
                /** Extended rows. */
                var extendedRows = 0
                /** Computed habits. */
                var computedHabits = 0
                /** For. */
                for (task in recurring) {
                    /** Db max. */
                    val dbMax = dbMaxByHabit[task.id]
                    // Fast path: habit already current (rows through yesterday or
                    // later) — skip occurrence fetch + timeline compute.
                    /** If. */
                    if (dbMax != null && dbMax >= yesterday.toString()) continue
                    /** Occurrences. */
                    val occurrences = occDao.getOccurrencesForTaskForBackfill(task.id)
                    computedHabits++
                    // Baseline = all existing rows (they end at dbMax < fromDay).
                    /** From day. */
                    val fromDay = dbMax?.let { parseDate(it).plusDays(1) } ?: run {
                        /** First occurrence. */
                        val firstOccurrence = occurrences.mapNotNull { runCatching { LocalDate.parse(it.dueDate.take(10)) }.getOrNull() }.minOrNull()
                            ?: continue
                        /** First occurrence. */
                        firstOccurrence
                    }
                    /** All rows. */
                    val allRows = habitDao.getForHabit(task.id)
                    /** Existing. */
                    val existing = allRows.filter { it.dayKey < fromDay.toString() }
                    /** Baseline. */
                    val baseline = MetricBaseline.fromHabitRows(existing)
                    logger.d(
                        /** Tag. */
                        tag,
                        "CATCHUP_BASELINE_L1",
                        /** Map of. */
                        mapOf(
                            "taskId" to task.id,
                            "fromDay" to fromDay.toString(),
                            "rowsBefore" to existing.size,
                            "count" to baseline.count,
                            "prevAvg" to (baseline.prevAvg ?: "null"),
                        ),
                    )
                    /** Val. */
                    val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetricsFrom(
                        task = task,
                        occurrences = occurrences,
                        fromDay = fromDay,
                        includeToday = false,
                        baseline = baseline,
                    )
                    /** If. */
                    if (rows.isEmpty()) continue
                    // Rows from fromDay are strictly newer than dbMax — no-op
                    // rewrites impossible; interval habits with next-due in the
                    // future simply produce an empty tail and are skipped.
                    habitDao.deleteFrom(task.id, fromDay.toString())
                    habitDao.upsertAll(rows)
                    extendedRows += rows.size
                    gapStarts[task.id] = fromDay.toString()
                    logger.i(
                        /** Tag. */
                        tag,
                        /** List of. */
                        listOf(
                            "CATCHUP_TRACE | t=${task.id} d=$fromDay",
                            /** Trace section. */
                            traceSection(
                                "L1",
                                allRows.filter { it.dayKey >= fromDay.toString() }
                                    .associate { it.dayKey to it.toTraceValues() },
                                rows.associate { it.dayKey to it.toTraceValues() },
                            ),
                            "ms=${System.currentTimeMillis() - started}",
                        ).filter { it.isNotEmpty() }.joinToString(" | "),
                    )
                }
                /** If. */
                if (gapStarts.isEmpty()) {
                    logger.d(
                        /** Tag. */
                        tag,
                        "CATCHUP_NO_GAP",
                        /** Map of. */
                        mapOf(
                            "elapsedMs" to (System.currentTimeMillis() - started),
                            "computedHabits" to computedHabits,
                        ),
                    )
                    /** Return. */
                    return
                }
                logger.i(tag, "CATCHUP_L1_EXTENDED", mapOf("habits" to gapStarts.size, "rows" to extendedRows, "computedHabits" to computedHabits))

                // ── Phase 2: refresh affected dimension tails ──────────────
                /** All habit rows. */
                val allHabitRows = habitDao.getAll()
                /** First due per habit. */
                val firstDuePerHabit = allHabitRows.groupBy { it.habitId }
                    .mapValues { (_, rows) -> rows.minOfOrNull { parseDate(it.dayKey) } }
                    .filterValues { it != null }
                    .mapValues { it.value!! }

                /** Affected dim from day. */
                val affectedDimFromDay = mutableMapOf<String, String>() // dimensionId → min gapStart
                /** For. */
                for (task in recurring) {
                    /** Gap start. */
                    val gapStart = gapStarts[task.id] ?: continue
                    /** Dim id. */
                    val dimId = task.dimensionId ?: "dim_unassigned"
                    /** Existing. */
                    val existing = affectedDimFromDay[dimId]
                    /** If. */
                    if (existing == null || gapStart < existing) affectedDimFromDay[dimId] = gapStart
                }

                /** Dim tail rows. */
                var dimTailRows = 0
                /** For. */
                for ((dimId, fromDay) in affectedDimFromDay) {
                    /** Members. */
                    val members = recurring.filter { (it.dimensionId ?: "dim_unassigned") == dimId }
                    /** If. */
                    if (members.isEmpty()) continue
                    /** Member rows. */
                    val memberRows = allHabitRows.filter { row -> members.any { it.id == row.habitId } }
                    /** Dim baseline. */
                    val dimBaseline = MetricBaseline.fromDimensionRows(
                        dimDao.getAll().filter { it.dimensionId == dimId && it.dayKey < fromDay }.sortedBy { it.dayKey },
                    )
                    /** Last scores. */
                    val lastScores = memberRows
                        .groupBy { it.habitId }
                        .mapValues { (_, rs) -> rs.filter { it.dayKey < fromDay }.maxByOrNull { it.dayKey } }
                        .filterValues { it != null }
                        .mapValues { it.value!!.score }
                    logger.d(
                        /** Tag. */
                        tag,
                        "CATCHUP_BASELINE_L2",
                        /** Map of. */
                        mapOf(
                            "dimensionId" to dimId,
                            "fromDay" to fromDay,
                            "carryForwardHabits" to lastScores.size,
                            "count" to dimBaseline.count,
                        ),
                    )
                    /** Dim rows. */
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
                    /** If. */
                    if (dimRows.isNotEmpty()) {
                        dimDao.upsertAll(dimRows)
                        dimTailRows += dimRows.size
                    }
                }
                logger.i(tag, "CATCHUP_L2_REFRESHED", mapOf("dimensions" to affectedDimFromDay.size, "rows" to dimTailRows))

                // ── Phase 3: refresh day tail ──────────────────────────────
                /** Global from day. */
                val globalFromDay = affectedDimFromDay.values.minOrNull() ?: yesterday.toString()
                /** Day baseline. */
                val dayBaseline = MetricBaseline.fromDayRows(
                    dayDao.getAll().filter { it.dayKey < globalFromDay }.sortedBy { it.dayKey },
                )
                /** Dim weights. */
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                logger.d(
                    /** Tag. */
                    tag,
                    "CATCHUP_BASELINE_L3",
                    /** Map of. */
                    mapOf("fromDay" to globalFromDay, "count" to dayBaseline.count, "prevAvg" to (dayBaseline.prevAvg ?: "null"), "weightedDims" to dimWeights.size),
                )
                /** Day rows. */
                val dayRows = ScoreRollupBackfillService.buildDayMetricsFrom(
                    dimensionRows = dimDao.getAll(),
                    fromDay = parseDate(globalFromDay),
                    baseline = dayBaseline,
                    dimWeights = dimWeights,
                )
                dayDao.deleteFrom(globalFromDay)
                /** If. */
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
                logger.i(
                    /** Tag. */
                    tag,
                    "CATCHUP_END",
                    /** Map of. */
                    mapOf(
                        "habits" to gapStarts.size,
                        "l1Rows" to extendedRows,
                        "l2Rows" to dimTailRows,
                        "l3Rows" to dayRows.size,
                        "fromDay" to globalFromDay,
                        "elapsedMs" to (System.currentTimeMillis() - started),
                    ),
                )
                scoreChangeEventBus.emit(LocalDate.now())
                logger.i(tag, "Score change event emitted", mapOf("date" to "catch-up"))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(tag, "CATCHUP_FAILED", e)
            }
        }

        // ── Value trace helpers (Inc 5) ──────────────────────────────────
        // One line per recalc: old→new for all 6 metrics per affected layer.
        // ∅ = no row existed (row created/deleted); doubles at %.4f, streaks
        // as ints; sub-0.0001 shifts round to .0000 — full precision stays in
        // the CASCADE_BASELINE_* debug lines.

        /** One row's 6 metric values; null = the row did not exist. */
        private data class TraceValues(
            /** Score. */
            val score: Double?,
            /** Avg. */
            val avg: Double?,
            /** Progress. */
            val progress: Double?,
            /** Streak pos. */
            val streakPos: Int?,
            /** Streak net. */
            val streakNet: Int?,
            /** Pos continue. */
            val posContinue: Int?,
        )

        /** Converts a habit metric row to trace values (all 6 metrics). */
        private fun HabitMetricEntity.toTraceValues() =
            /** Trace values. */
            TraceValues(score, runningAvg, progress, streakPos, streakNet, posContinue)

        /** Converts a dimension metric row to trace values (all 6 metrics). */
        private fun DimensionMetricEntity.toTraceValues() =
            /** Trace values. */
            TraceValues(score, runningAvg, progress, streakPos, streakNet, posContinue)

        /** Converts a day metric row to trace values (all 6 metrics). */
        private fun DayMetricEntity.toTraceValues() =
            /** Trace values. */
            TraceValues(dayScore, runningAvg, progress, streakPos, streakNet, posContinue)

        /** Formats a nullable double for trace output at 5-decimal precision (∅ when null). */
        private fun traceValue(v: Double?): String =
            v?.let { String.format(Locale.US, "%.5f", it) } ?: "∅"

        /** Formats a nullable int for trace output (∅ when null). */
        private fun traceValue(v: Int?): String = v?.toString() ?: "∅"

        /**
         * Builds one layer section, e.g.
         * `L1 d=2026-08-15 S:∅→1.0000 A:∅→1.0000 P:∅→1.0000 sp:∅→1 sn:∅→1 pc:∅→1`.
         *
         * Days = union of old/new rows. When more than 2 days changed, the
         * section shows `[n=Xd]` plus first (edited) and last (today) day
         * details only — middle days are cumulative shifts.
         */
        private fun traceSection(
            /** Label. */
            label: String,
            oldByDay: Map<String, TraceValues>,
            newByDay: Map<String, TraceValues>,
        ): String {
            /** Days. */
            val days = (oldByDay.keys + newByDay.keys).sorted()
            /** If. */
            if (days.isEmpty()) return ""
            /** Detail. */
            val detail: (String) -> String = { day ->
                /** Old. */
                val old = oldByDay[day]
                /** New. */
                val new = newByDay[day]
                "d=$day S:${traceValue(old?.score)}→${traceValue(new?.score)} " +
                    "A:${traceValue(old?.avg)}→${traceValue(new?.avg)} " +
                    "P:${traceValue(old?.progress)}→${traceValue(new?.progress)} " +
                    "sp:${traceValue(old?.streakPos)}→${traceValue(new?.streakPos)} " +
                    "sn:${traceValue(old?.streakNet)}→${traceValue(new?.streakNet)} " +
                    "pc:${traceValue(old?.posContinue)}→${traceValue(new?.posContinue)}"
            }
            /** Shown. */
            val shown = if (days.size <= 2) days else listOf(days.first(), days.last())
            /** Header. */
            val header = if (days.size <= 2) label else "$label[n=${days.size}d]"
            return shown.joinToString(" , ", prefix = "$header ", transform = detail)
        }

        /** Parses a dayKey (or longer timestamp) to a [LocalDate] via the first 10 chars. */
        private fun parseDate(s: String): LocalDate =
            try {
                LocalDate.parse(s.take(10))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                LocalDate.now()
            }
    }
