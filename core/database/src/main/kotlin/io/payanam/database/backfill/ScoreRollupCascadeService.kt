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
class ScoreRollupCascadeService
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
        private val scoreChangeEventBus: ScoreChangeEventBus,
    ) {
        private val logger = UnifiedLogger.getInstance()

        /** Recompute L1/L2/L3 tails after a status change on [date]. */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
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
                val allHabitRows = habitDao.getForHabit(taskId)
                val existingHabitRows = allHabitRows.filter { it.dayKey < dateStr }
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
                val occForChangeDay = occurrences.filter { it.dueDate.startsWith(dateStr) }
                val hadRowBefore = allHabitRows.any { it.dayKey == dateStr }
                val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetricsFrom(
                    task = task,
                    occurrences = occurrences,
                    fromDay = date,
                    includeToday = true,
                    baseline = l1Baseline,
                )
                habitDao.deleteFrom(taskId, dateStr)
                if (rows.isNotEmpty()) habitDao.upsertAll(rows)
                val todayLogged = occurrences.any { it.dueDate.take(10) == dateStr }
                val firstDue = occurrences.mapNotNull { runCatching { LocalDate.parse(it.dueDate.take(10)) }.getOrNull() }.minOrNull()
                logger.i(
                    tag,
                    "CASCADE_L1_BEFORE",
                    mapOf(
                        "taskId" to taskId,
                        "dimensionId" to (task.dimensionId ?: "dim_unassigned"),
                        "changeDate" to dateStr,
                        "hadRowBefore" to hadRowBefore,
                        "todayLogged" to todayLogged,
                        "firstDue" to (firstDue?.toString() ?: "null"),
                        "occTotal" to occurrences.size,
                        "occForChangeDay" to occForChangeDay.size,
                        "occStatuses" to occForChangeDay.map { it.status },
                        "rowsAfter" to rows.size,
                        "rowDays" to rows.map { it.dayKey },
                        "scoreNow" to (rows.find { it.dayKey == dateStr }?.score ?: "null"),
                    ),
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
                val allDimRows = dimDao.getAll()
                val existingDimRows = allDimRows
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

                // ── L3: day tail (C1 baseline, C2 dimension weights) ──────
                val allDayRows = dayDao.getAll()
                val existingDayRows = allDayRows.filter { it.dayKey < dateStr }.sortedBy { it.dayKey }
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

                // ── Value trace: one line, old→new per metric per layer ──
                // Old rows are the pre-rebuild tail (≥ change day) captured
                // from the same queries that fed the baselines; ∅ = no row.
                logger.i(
                    tag,
                    listOf(
                        "CASCADE_TRACE | t=$taskId d=$dateStr",
                        traceSection(
                            "L1",
                            allHabitRows.filter { it.dayKey >= dateStr }
                                .associate { it.dayKey to it.toTraceValues() },
                            rows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        traceSection(
                            "L2",
                            allDimRows
                                .filter { it.dimensionId == dimensionId && it.dayKey >= dateStr }
                                .associate { it.dayKey to it.toTraceValues() },
                            dimRows.associate { it.dayKey to it.toTraceValues() },
                        ),
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
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
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
                val oldHabitRows = habitDao.getForHabit(taskId)
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
                val oldDimRows = dimDao.getAll()
                    .filter { it.dimensionId == dimensionId && it.dayKey >= earliest }
                dimDao.deleteFrom(dimensionId, earliest)
                if (dimRows.isNotEmpty()) dimDao.upsertAll(dimRows)

                // L3: rebuild the day tail from the earliest affected day.
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                val dayRows = ScoreRollupBackfillService.buildDayMetrics(dimDao.getAll(), dimWeights)
                    .filter { it.dayKey >= earliest }
                val oldDayRows = dayDao.getAll().filter { it.dayKey >= earliest }
                dayDao.deleteFrom(earliest)
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)

                logger.i(
                    tag,
                    listOf(
                        "CASCADE_RULE_TRACE | t=$taskId",
                        traceSection(
                            "L1",
                            oldHabitRows.associate { it.dayKey to it.toTraceValues() },
                            rows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        traceSection(
                            "L2",
                            oldDimRows.associate { it.dayKey to it.toTraceValues() },
                            dimRows.associate { it.dayKey to it.toTraceValues() },
                        ),
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
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
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
                val oldDayRows = dayDao.getAll()
                logger.i(
                    tag,
                    "CASCADE_DAY_ONLY_START",
                    mapOf(
                        "changeDate" to changeDate.toString(),
                        "fromDay" to dateStr,
                        "rowsBefore" to oldDayRows.size,
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
                // Notify subscribers (e.g. Lenses matrix) so derived values refresh.
                scoreChangeEventBus.emit(changeDate)
                logger.i(tag, "Score change event emitted", mapOf("date" to changeDate.toString()))
                logger.i(
                    tag,
                    listOf(
                        "CASCADE_DAY_ONLY_TRACE | d=$dateStr",
                        traceSection(
                            "L3",
                            oldDayRows.filter { it.dayKey >= dateStr }
                                .associate { it.dayKey to it.toTraceValues() },
                            dayRows.associate { it.dayKey to it.toTraceValues() },
                        ),
                        "ms=${System.currentTimeMillis() - started}",
                    ).filter { it.isNotEmpty() }.joinToString(" | "),
                )
            } catch (e: Exception) {
                logger.e(tag, "CASCADE_DAY_ONLY_FAILED", e, mapOf("fromDay" to changeDate.toString()))
            }
        }

        /**
         * Day-start pre-fill (earn-style): for every due habit that has no L1 row
         * for [today], seed a not-done row (score 0.0) so L2/L3 always consume a
         * real today value instead of carry-forwarding yesterday's score.
         *
         * The not-done row is computed via [ScoreRollupBackfillService.buildHabitMetricsFrom]
         * with includeToday=true and no occurrence for today → it produces the
         * correct 0.0 row with runningAvg pulled DOWN (cumulative avg drops) and
         * streaks decremented, exactly like a missed day. After seeding, L2/L3
         * tails are rebuilt for today so the day score reflects "nothing done yet".
         *
         * Runs at app launch (post-backfill) and on day rollover. Idempotent:
         * habits that already have a today row are skipped.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun prefillToday() {
            if (!sessionManager.isOpen.value) return
            val db = sessionManager.requireDatabase()
            val tag = "ScoreRollupCascadeService.prefillToday"
            val started = System.currentTimeMillis()
            try {
                val taskDao = db.taskDao()
                val habitDao = db.habitMetricDao()
                val dimDao = db.dimensionMetricDao()
                val dayDao = db.dayMetricDao()
                val occDao = db.taskOccurrenceDao()
                val today = LocalDate.now()
                val todayStr = today.toString()
                val recurring = taskDao.getRecurringTasks()
                    .filter { it.status != "archived" && it.recurrenceEnabled == 1 }
                var seeded = 0
                val affectedDims = mutableSetOf<String>()
                for (task in recurring) {
                    if (!RecurrenceConfig.parse(task.recurrenceRule ?: "").isScheduledDay(today)) continue
                    val existing = habitDao.getForHabit(task.id)
                    if (existing.any { it.dayKey == todayStr }) continue // already has a today row
                    val baselineRows = existing.filter { it.dayKey < todayStr }
                    val baseline = MetricBaseline.fromHabitRows(baselineRows)
                    // Synthetic missed occurrence for today so buildHabitMetricsFrom
                    // emits the not-done (0.0) row with correct runningAvg pull-down
                    // and streak decrements — same as a real missed due day.
                    val missedToday = TaskOccurrenceEntity(
                        id = "prefill-${task.id}-$todayStr",
                        taskId = task.id,
                        dueDate = todayStr,
                        status = "missed",
                        createdAt = LocalDateTime.now().toString(),
                    )
                    val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetricsFrom(
                        task = task,
                        occurrences = listOf(missedToday),
                        fromDay = today,
                        includeToday = true,
                        baseline = baseline,
                    )
                    if (rows.isNotEmpty()) {
                        habitDao.upsertAll(rows)
                        seeded++
                        affectedDims.add(task.dimensionId ?: "dim_unassigned")
                    }
                }
                if (seeded == 0) {
                    logger.d(tag, "PREFILL_TODAY_NOOP", mapOf("today" to todayStr))
                    return
                }
                // ── Rebuild L2/L3 tails for today so the day score reflects the seed ──
                val allHabitRows = habitDao.getAll()
                val firstDuePerHabit = allHabitRows.groupBy { it.habitId }
                    .mapValues { (_, rows) -> rows.minOfOrNull { parseDate(it.dayKey) } }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
                var dimRows = 0
                for (dimId in affectedDims) {
                    val members = recurring.filter { (it.dimensionId ?: "dim_unassigned") == dimId }
                    if (members.isEmpty()) continue
                    val memberRows = allHabitRows.filter { row -> members.any { it.id == row.habitId } }
                    val dimBaseline = MetricBaseline.fromDimensionRows(
                        dimDao.getAll().filter { it.dimensionId == dimId && it.dayKey < todayStr }.sortedBy { it.dayKey },
                    )
                    val lastScores = memberRows
                        .groupBy { it.habitId }
                        .mapValues { (_, rs) -> rs.filter { it.dayKey < todayStr }.maxByOrNull { it.dayKey } }
                        .filterValues { it != null }
                        .mapValues { it.value!!.score }
                    val built = ScoreRollupBackfillService.buildDimensionMetricsFrom(
                        recurring = members,
                        firstDuePerHabit = firstDuePerHabit,
                        habitRows = memberRows,
                        fromDay = today,
                        includeToday = true,
                        baseline = dimBaseline,
                        lastScores = lastScores,
                    )
                    dimDao.deleteFrom(dimId, todayStr)
                    if (built.isNotEmpty()) {
                        dimDao.upsertAll(built)
                        dimRows += built.size
                    }
                }
                val allDimRows = dimDao.getAll()
                val dayBaseline = MetricBaseline.fromDayRows(dayDao.getAll().filter { it.dayKey < todayStr }.sortedBy { it.dayKey })
                val dimWeights = db.lifeDimensionDao().allWeights().associate { it.id to it.weight }
                val dayRows = ScoreRollupBackfillService.buildDayMetricsFrom(
                    dimensionRows = allDimRows,
                    fromDay = today,
                    baseline = dayBaseline,
                    dimWeights = dimWeights,
                )
                dayDao.deleteFrom(todayStr)
                if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
                scoreChangeEventBus.emit(today)
                if (UnifiedLogger.isInitialized()) {
                    logger.i(
                        tag,
                        "PREFILL_TODAY_DONE",
                        mapOf(
                            "today" to todayStr,
                            "seededHabits" to seeded,
                            "dimensions" to affectedDims.size,
                            "dimRows" to dimRows,
                            "dayRows" to dayRows.size,
                            "ms" to (System.currentTimeMillis() - started),
                        ),
                    )
                }
            } catch (e: Exception) {
                logger.e(tag, "PREFILL_TODAY_FAILED", e, mapOf("today" to LocalDate.now().toString()))
            }
        }

        /** Startup catch-up: extend every habit's L1 through yesterday, then L2/L3 tails. */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
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
                    val allRows = habitDao.getForHabit(task.id)
                    val existing = allRows.filter { it.dayKey < fromDay.toString() }
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
                        listOf(
                            "CATCHUP_TRACE | t=${task.id} d=$fromDay",
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
                scoreChangeEventBus.emit(LocalDate.now())
                logger.i(tag, "Score change event emitted", mapOf("date" to "catch-up"))
            } catch (e: Exception) {
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
            val score: Double?,
            val avg: Double?,
            val progress: Double?,
            val streakPos: Int?,
            val streakNet: Int?,
            val posContinue: Int?,
        )

        /** Converts a habit metric row to trace values (all 6 metrics). */
        private fun HabitMetricEntity.toTraceValues() =
            TraceValues(score, runningAvg, progress, streakPos, streakNet, posContinue)

        /** Converts a dimension metric row to trace values (all 6 metrics). */
        private fun DimensionMetricEntity.toTraceValues() =
            TraceValues(score, runningAvg, progress, streakPos, streakNet, posContinue)

        /** Converts a day metric row to trace values (all 6 metrics). */
        private fun DayMetricEntity.toTraceValues() =
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
            label: String,
            oldByDay: Map<String, TraceValues>,
            newByDay: Map<String, TraceValues>,
        ): String {
            val days = (oldByDay.keys + newByDay.keys).sorted()
            if (days.isEmpty()) return ""
            val detail: (String) -> String = { day ->
                val old = oldByDay[day]
                val new = newByDay[day]
                "d=$day S:${traceValue(old?.score)}→${traceValue(new?.score)} " +
                    "A:${traceValue(old?.avg)}→${traceValue(new?.avg)} " +
                    "P:${traceValue(old?.progress)}→${traceValue(new?.progress)} " +
                    "sp:${traceValue(old?.streakPos)}→${traceValue(new?.streakPos)} " +
                    "sn:${traceValue(old?.streakNet)}→${traceValue(new?.streakNet)} " +
                    "pc:${traceValue(old?.posContinue)}→${traceValue(new?.posContinue)}"
            }
            val shown = if (days.size <= 2) days else listOf(days.first(), days.last())
            val header = if (days.size <= 2) label else "$label[n=${days.size}d]"
            return shown.joinToString(" , ", prefix = "$header ", transform = detail)
        }

        /** Parses a dayKey (or longer timestamp) to a [LocalDate] via the first 10 chars. */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun parseDate(s: String): LocalDate =
            try {
                LocalDate.parse(s.take(10))
            } catch (e: Exception) {
                logger.w("ScoreRollupCascadeService", "Unparseable date, falling back to today", mapOf("date" to s))
                LocalDate.now()
            }
    }
