//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements", "UnusedParameter")

package io.payanam.database.backfill

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.dao.AppSettingsDao
import io.payanam.database.dao.DayMetricDao
import io.payanam.database.dao.DimensionMetricDao
import io.payanam.database.dao.HabitMetricDao
import io.payanam.database.dao.TaskDao
import io.payanam.database.dao.TaskOccurrenceDao
import io.payanam.database.entity.AppSettingEntity
import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.NumDenToConfigConverter
import io.payanam.domain.model.RecurrenceConfig
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cumulative rolling state before a change date — C1 baseline (self-gov ss/cs/pa).
 * Seeded from existing metric rows strictly BEFORE a change day so tail builds
 * (O(gap days)) reproduce the same math as a full rebuild (O(history)).
 */
internal data class MetricBaseline(
    /** Sum scores. */
    val sumScores: Double,
    /** Count. */
    val count: Int,
    /** Prev avg. */
    val prevAvg: Double?,
    /** Streak pos. */
    val streakPos: Int,
    /** Streak net. */
    val streakNet: Int,
    /** Pos continue. */
    val posContinue: Int,
) {
    companion object {
        /**
         * Empty.
         */
        fun empty(): MetricBaseline = MetricBaseline(0.0, 0, null, 0, 0, 0)

        /** Derive from rows strictly BEFORE the change day (L1 shape). */
        fun fromHabitRows(rows: List<HabitMetricEntity>): MetricBaseline =
            /** If. */
            if (rows.isEmpty()) {
                /** Empty. */
                empty()
            } else {
                /** Metric baseline. */
                MetricBaseline(
                    sumScores = rows.sumOf { it.score },
                    count = rows.size,
                    prevAvg = rows.last().runningAvg,
                    streakPos = rows.last().streakPos,
                    streakNet = rows.last().streakNet,
                    posContinue = rows.last().posContinue,
                )
            }

        /** Derive from rows strictly BEFORE the change day (L2 shape). */
        fun fromDimensionRows(rows: List<DimensionMetricEntity>): MetricBaseline =
            /** If. */
            if (rows.isEmpty()) {
                /** Empty. */
                empty()
            } else {
                /** Metric baseline. */
                MetricBaseline(
                    sumScores = rows.sumOf { it.score },
                    count = rows.size,
                    prevAvg = rows.last().runningAvg,
                    streakPos = rows.last().streakPos,
                    streakNet = rows.last().streakNet,
                    posContinue = rows.last().posContinue,
                )
            }

        /** Derive from rows strictly BEFORE the change day (L3 shape). */
        fun fromDayRows(rows: List<DayMetricEntity>): MetricBaseline =
            /** If. */
            if (rows.isEmpty()) {
                /** Empty. */
                empty()
            } else {
                /** Metric baseline. */
                MetricBaseline(
                    sumScores = rows.sumOf { it.dayScore },
                    count = rows.size,
                    prevAvg = rows.last().runningAvg,
                    streakPos = rows.last().streakPos,
                    streakNet = rows.last().streakNet,
                    posContinue = rows.last().posContinue,
                )
            }
    }
}

/**
 * One-time post-migration backfill for the self-governance score roll-up
 * (Inc 2). Runs after schema v18 migration on first launch:
 *
 *  1. Converts every habit's num/den recurrenceRule → canonical CONFIG
 *     (via [NumDenToConfigConverter]) and persists it.
 *  2. L1 (habit_metrics, sparse): due-date scan from first occurrence →
 *     yesterday (today stays pending), binary score from occurrences
 *     (completed/skipped → 1.0, missed/no-entry → 0.0), running avg,
 *     progress and streaks per self-gov engine semantics.
 *  3. L2 (dimension_metrics, dense): weighted average of member habit
 *     scores per calendar day, carry-forward from L1 rows (equal weights —
 *     no per-habit weights in Payanam yet).
 *  4. L3 (day_metrics, dense): weighted average of dimension scores per
 *     calendar day, carry-forward (equal dimension weights for now).
 *
 * Idempotent: guarded by an app_settings key. Fresh-install fast path:
 * when no legacy num/den rules AND no occurrences exist, the guard is set
 * immediately and nothing is computed (new habits are written in CONFIG
 * format by the UI; live metrics flow through the cascade, Inc 3).
 * Fully trace-logged so any positive/negative/edge/error outcome is
 * recoverable from logs.
 */
@Singleton
/**
 * ScoreRollupBackfillService.
 */
class ScoreRollupBackfillService
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
        private val cascadeService: ScoreRollupCascadeService,
    ) {
        private val logger = UnifiedLogger.getInstance()

        companion object {
            /** Backfill done key. */
            const val BACKFILL_DONE_KEY = "score_rollup_backfill_v1_done"

            /** Self-gov `_compute_streaks` port incl. ceiling fix (spec §9). */
            internal fun computeStreaks(
                /** Progress. */
                progress: Double,
                /** Running avg. */
                runningAvg: Double,
                /** Streak pos. */
                streakPos: Int,
                /** Streak net. */
                streakNet: Int,
                /** Pos continue. */
                posContinue: Int,
            ): IntArray {
                /** At ceiling. */
                val atCeiling = Math.abs(progress) < 1e-10 && Math.abs(runningAvg - 1.0) < 1e-10
                /** New pos. */
                val newPos = if ((progress > 1e-10) || atCeiling) streakPos + 1 else 0
                /** New net. */
                val newNet = streakNet + when {
                    progress > 1e-10 -> 1
                    progress < -1e-10 -> -1
                    else -> 0
                }
                /** New continue. */
                val newContinue = if (progress > 1e-10) posContinue + 1 else posContinue
                return intArrayOf(newPos, newNet, newContinue)
            }

            private fun parseLocalDate(s: String): LocalDate? =
                try {
                    LocalDate.parse(s.take(10))
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    /** Null. */
                    null
                }

            /**
             * L1: sparse due-day rows for one habit. Returns (rows, firstDue, convertedRule).
             * Today is NOT scored by default (stays pending). Off-grid completions are NOT
             * counted (strict-grid semantics — user decision).
             *
             * [includeToday] — cascade mode: when true, today gets a row ONLY if an
             * occurrence is logged for today (completed/skipped/missed). Un-logged
             * today stays pending (no row).
             *
             * Edge case: a habit with zero occurrences still gets its rule converted
             * (anchor = createdAt date fallback) so the live cascade (Inc 3) never
             * sees a stale num/den rule (which would degrade to FREQUENCY = always due).
             */
            internal fun buildHabitMetrics(
                /** Task. */
                task: TaskEntity,
                occurrences: List<TaskOccurrenceEntity>,
                includeToday: Boolean = false,
            ): Triple<List<HabitMetricEntity>, LocalDate?, String?> {
                // Full build = tail build from the first due day with an empty baseline.
                /** Val. */
                val (firstDue, convertedRule) = habitTimelineStart(task, occurrences)
                /** If. */
                if (firstDue == null) {
                    return Triple(emptyList(), null, convertedRule)
                }
                return buildHabitMetricsFrom(
                    task = task,
                    occurrences = occurrences,
                    fromDay = firstDue,
                    includeToday = includeToday,
                    baseline = MetricBaseline.empty(),
                )
            }

            /**
             * L1 tail: sparse due-day rows for one habit starting at [fromDay],
             * seeded from a cumulative [baseline] (rows before fromDay). O(gap days)
             * — cost is constant over the habit's lifetime (C1 baseline carry-forward).
             */
            internal fun buildHabitMetricsFrom(
                /** Task. */
                task: TaskEntity,
                occurrences: List<TaskOccurrenceEntity>,
                /** From day. */
                fromDay: LocalDate,
                includeToday: Boolean = false,
                baseline: MetricBaseline = MetricBaseline.empty(),
            ): Triple<List<HabitMetricEntity>, LocalDate?, String?> {
                /** If. */
                if (task.recurrenceEnabled != 1) return Triple(emptyList(), null, null)

                /** Occ by date. */
                val occByDate = occurrences
                    .filter { it.status == "completed" || it.status == "skipped" }
                    .associateBy { it.dueDate.take(10) }
                // Any occurrence today (any status) — controls whether today
                // gets a row in cascade mode (includeToday=true).
                /** Today logged. */
                val todayLogged = occurrences.any { it.dueDate.take(10) == LocalDate.now().toString() }

                /** First due. */
                val firstDue = occurrences
                    .mapNotNull { parseLocalDate(it.dueDate) }
                    .minOrNull()

                // Anchor for interval grids: first occurrence, else createdAt.
                /** Anchor. */
                val anchor = firstDue ?: parseLocalDate(task.createdAt) ?: LocalDate.now()
                /** Converted rule. */
                val convertedRule = convertRule(task, anchor)
                /** First due day. */
                val firstDueDay = firstDue
                    ?: return Triple(emptyList(), null, convertedRule) // no history — rule still converted

                /** Config. */
                val config = RecurrenceConfig.parse(convertedRule)

                /** Today. */
                val today = LocalDate.now()
                /** Rows. */
                val rows = mutableListOf<HabitMetricEntity>()
                /** Sum scores. */
                var sumScores = baseline.sumScores
                /** Count. */
                var count = baseline.count
                /** Prev avg. */
                var prevAvg = baseline.prevAvg
                /** Streak pos. */
                var streakPos = baseline.streakPos
                /** Streak net. */
                var streakNet = baseline.streakNet
                /** Pos continue. */
                var posContinue = baseline.posContinue

                /** Day. */
                var day = if (fromDay.isAfter(firstDueDay)) fromDay else firstDueDay
                /** Loop end. */
                val loopEnd = if (includeToday && todayLogged) today.plusDays(1) else today
                /** While. */
                while (day.isBefore(loopEnd)) { // today stays pending unless logged (cascade)
                    /** If. */
                    if (config.isScheduledDay(day)) {
                        /** Score. */
                        val score = if (occByDate[day.toString()] != null) 1.0 else 0.0
                        sumScores += score
                        count++
                        /** Running avg. */
                        val runningAvg = sumScores / count
                        /** Progress. */
                        val progress = if (prevAvg == null) score else runningAvg - prevAvg
                        /** Streaks. */
                        val streaks = computeStreaks(progress, runningAvg, streakPos, streakNet, posContinue)
                        streakPos = streaks[0]
                        streakNet = streaks[1]
                        posContinue = streaks[2]
                        rows += HabitMetricEntity(
                            habitId = task.id,
                            dayKey = day.toString(),
                            score = score,
                            runningAvg = runningAvg,
                            progress = progress,
                            streakPos = streakPos,
                            streakNet = streakNet,
                            posContinue = posContinue,
                        )
                        prevAvg = runningAvg
                    }
                    day = day.plusDays(1)
                }
                return Triple(rows, firstDue, convertedRule)
            }

            /** Shared L1 timeline start: (firstDue, convertedRule). */
            private fun habitTimelineStart(
                /** Task. */
                task: TaskEntity,
                occurrences: List<TaskOccurrenceEntity>,
            ): Pair<LocalDate?, String?> {
                /** If. */
                if (task.recurrenceEnabled != 1) return null to null
                /** First due. */
                val firstDue = occurrences
                    .mapNotNull { parseLocalDate(it.dueDate) }
                    .minOrNull()
                /** Anchor. */
                val anchor = firstDue ?: parseLocalDate(task.createdAt) ?: LocalDate.now()
                /** Converted rule. */
                val convertedRule = convertRule(task, anchor)
                return firstDue to convertedRule
            }

            /** num/den → CONFIG with anchor = first due date (fallback: createdAt).
             *  Already-canonical rules (CONFIG: / FREQ= RRULE) pass through untouched —
             *  both are deterministic and must never be re-mapped. */
            private fun convertRule(task: TaskEntity, firstDue: LocalDate): String {
                /** Rule. */
                val rule = task.recurrenceRule
                /** If. */
                if (rule.isNullOrBlank()) return NumDenToConfigConverter.convert(null, firstDue)
                /** If. */
                if (rule.startsWith("CONFIG:") || rule.startsWith("FREQ=")) return rule
                return NumDenToConfigConverter.convert(rule, firstDue)
            }

            /**
             * L2: dense per-dimension rows from L1 rows.
             * Equal weights within a dimension (no per-habit weights in Payanam yet);
             * carry-forward of each habit's last known score for non-due days;
             * first-due exclusion (habit not yet started contributes nothing).
             * [includeToday] — cascade mode: also emit today's row (today logged).
             */
            internal fun buildDimensionMetrics(
                recurring: List<TaskEntity>,
                firstDuePerHabit: Map<String, LocalDate>,
                habitRows: List<HabitMetricEntity>,
                includeToday: Boolean = false,
            ): List<DimensionMetricEntity> {
                /** Earliest. */
                val earliest = recurring
                    .mapNotNull { firstDuePerHabit[it.id] }
                    .minOrNull() ?: return emptyList()
                return buildDimensionMetricsFrom(
                    recurring = recurring,
                    firstDuePerHabit = firstDuePerHabit,
                    habitRows = habitRows,
                    fromDay = earliest,
                    includeToday = includeToday,
                    baseline = MetricBaseline.empty(),
                    lastScores = emptyMap(),
                )
            }

            /**
             * L2 tail: dense per-dimension rows from [fromDay] onward, seeded from a
             * cumulative [baseline] (dimension rows before fromDay) and a carry-forward
             * [lastScores] map (each habit's last known L1 score before fromDay).
             * O(gap days × dimension members) — C1 baseline carry-forward.
             */
            internal fun buildDimensionMetricsFrom(
                recurring: List<TaskEntity>,
                firstDuePerHabit: Map<String, LocalDate>,
                habitRows: List<HabitMetricEntity>,
                /** From day. */
                fromDay: LocalDate,
                includeToday: Boolean = false,
                baseline: MetricBaseline = MetricBaseline.empty(),
                lastScores: Map<String, Double> = emptyMap(),
            ): List<DimensionMetricEntity> {
                /** If. */
                if (habitRows.isEmpty()) return emptyList()

                /** By dim. */
                val byDim = recurring
                    .filter { firstDuePerHabit.containsKey(it.id) }
                    .groupBy { it.dimensionId ?: "dim_unassigned" }

                // Per habit: sorted (dayKey -> score) timeline for carry-forward
                /** Habit timelines. */
                val habitTimelines = habitRows
                    .groupBy { it.habitId }
                    .mapValues { (_, rows) -> rows.sortedBy { it.dayKey } }

                /** Rows. */
                val rows = mutableListOf<DimensionMetricEntity>()

                /** For. */
                for ((dimId, habits) in byDim) {
                    /** If. */
                    if (habits.isEmpty()) continue
                    /** Weight. */
                    val weight = 1.0 / habits.size
                    /** Earliest. */
                    val earliest = habits.mapNotNull { firstDuePerHabit[it.id] }.minOrNull() ?: continue
                    /** Today. */
                    val today = LocalDate.now()
                    /** Loop end. */
                    val loopEnd = if (includeToday) today.plusDays(1) else today

                    // C1: start at the later of (fromDay, earliest); seed rolling state
                    // from the dimension baseline and the carry-forward lastScores map.
                    /** Day. */
                    var day = if (fromDay.isAfter(earliest)) fromDay else earliest
                    /** Sum scores. */
                    var sumScores = baseline.sumScores
                    /** Count. */
                    var count = baseline.count
                    /** Prev avg. */
                    var prevAvg = baseline.prevAvg
                    /** Streak pos. */
                    var streakPos = baseline.streakPos
                    /** Streak net. */
                    var streakNet = baseline.streakNet
                    /** Pos continue. */
                    var posContinue = baseline.posContinue
                    /** Carry scores. */
                    val carryScores = lastScores.toMutableMap()

                    /** While. */
                    while (day.isBefore(loopEnd)) {
                        /** Dim score numerator. */
                        var dimScoreNumerator = 0.0
                        /** Weight sum. */
                        var weightSum = 0.0
                        /** For. */
                        for (habit in habits) {
                            /** Timeline. */
                            val timeline = habitTimelines[habit.id] ?: continue
                            /** If. */
                            if (timeline.isEmpty()) continue
                            // carry-forward: last L1 row on-or-before this day
                            /** Idx. */
                            val idx = timeline.binarySearchBy(day.toString()) { it.dayKey }
                            /** Last. */
                            val last = if (idx >= 0) timeline[idx] else timeline.getOrNull(-idx - 2)
                            /** Score. */
                            val score = when {
                                // exact row today — authoritative, refresh carry map
                                idx >= 0 -> {
                                    carryScores[habit.id] = last!!.score
                                    last.score
                                }
                                // no row today — fall back to carry-forward map (seeded
                                // from rows before fromDay), else to binarySearch result
                                carryScores.containsKey(habit.id) -> carryScores.getValue(habit.id)
                                last != null -> last.score
                                else -> continue
                            }
                            dimScoreNumerator += score * weight
                            weightSum += weight
                        }
                        /** If. */
                        if (weightSum > 0.0) {
                            /** Dim score. */
                            val dimScore = dimScoreNumerator / weightSum
                            sumScores += dimScore
                            count++
                            /** Running avg. */
                            val runningAvg = sumScores / count
                            /** Progress. */
                            val progress = if (prevAvg == null) dimScore else runningAvg - prevAvg
                            /** Streaks. */
                            val streaks = computeStreaks(progress, runningAvg, streakPos, streakNet, posContinue)
                            streakPos = streaks[0]
                            streakNet = streaks[1]
                            posContinue = streaks[2]
                            rows += DimensionMetricEntity(
                                dimensionId = dimId,
                                dayKey = day.toString(),
                                score = dimScore,
                                runningAvg = runningAvg,
                                progress = progress,
                                streakPos = streakPos,
                                streakNet = streakNet,
                                posContinue = posContinue,
                            )
                            prevAvg = runningAvg
                        }
                        day = day.plusDays(1)
                    }
                }
                return rows
            }

            /**
             * L3: dense per-day rows from dimension rows (weighted dimension
             * aggregation; carry-forward across days with no dimension activity).
             * [dimWeights] — dimensionId → weight (C2, default 1.0 = equal weights).
             */
            internal fun buildDayMetrics(
                dimensionRows: List<DimensionMetricEntity>,
                dimWeights: Map<String, Double> = emptyMap(),
            ): List<DayMetricEntity> {
                /** If. */
                if (dimensionRows.isEmpty()) return emptyList()

                /** By day. */
                val byDay = dimensionRows.groupBy { it.dayKey }
                /** All days. */
                val allDays = byDay.keys.sorted()
                /** If. */
                if (allDays.isEmpty()) return emptyList()

                /** First day. */
                val firstDay = LocalDate.parse(allDays.first())
                return buildDayMetricsFrom(
                    dimensionRows = dimensionRows,
                    fromDay = firstDay,
                    baseline = MetricBaseline.empty(),
                    dimWeights = dimWeights,
                )
            }

            /**
             * L3 tail: dense per-day rows from [fromDay] onward, seeded from a
             * cumulative [baseline] (day rows before fromDay). Iterates the days
             * present in [dimensionRows] (sparse-safe: skips days with no
             * dimension activity). O(gap days) — C1 baseline carry-forward.
             * [dimWeights] — dimensionId → weight (C2; missing dim → 1.0).
             */
            internal fun buildDayMetricsFrom(
                dimensionRows: List<DimensionMetricEntity>,
                /** From day. */
                fromDay: LocalDate,
                baseline: MetricBaseline = MetricBaseline.empty(),
                dimWeights: Map<String, Double> = emptyMap(),
            ): List<DayMetricEntity> {
                /** If. */
                if (dimensionRows.isEmpty()) return emptyList()

                /** By day. */
                val byDay = dimensionRows.groupBy { it.dayKey }
                /** All days. */
                val allDays = byDay.keys.sorted().filter { !LocalDate.parse(it).isBefore(fromDay) }
                /** If. */
                if (allDays.isEmpty()) return emptyList()

                /** Rows. */
                val rows = mutableListOf<DayMetricEntity>()
                /** Sum scores. */
                var sumScores = baseline.sumScores
                /** Count. */
                var count = baseline.count
                /** Prev avg. */
                var prevAvg = baseline.prevAvg
                /** Streak pos. */
                var streakPos = baseline.streakPos
                /** Streak net. */
                var streakNet = baseline.streakNet
                /** Pos continue. */
                var posContinue = baseline.posContinue

                /** For. */
                for (dayKey in allDays) {
                    /** Dims. */
                    val dims = byDay[dayKey].orEmpty()
                    /** Day score. */
                    val dayScore = if (dims.isNotEmpty()) {
                        // C2: weighted average of dimension scores. Unknown dims
                        // (no weight row) fall back to 1.0 — equal-weights legacy.
                        /** Weighted sum. */
                        val weightedSum = dims.sumOf { it.score * (dimWeights[it.dimensionId] ?: 1.0) }
                        /** Weight sum. */
                        val weightSum = dims.sumOf { dimWeights[it.dimensionId] ?: 1.0 }
                        /** If. */
                        if (weightSum <= 0.0) 0.0 else weightedSum / weightSum
                    } else {
                        /** Continue. */
                        continue // skip days with no dimension rows at all
                    }
                    sumScores += dayScore
                    count++
                    /** Running avg. */
                    val runningAvg = sumScores / count
                    /** Progress. */
                    val progress = if (prevAvg == null) dayScore else runningAvg - prevAvg
                    /** Streaks. */
                    val streaks = computeStreaks(progress, runningAvg, streakPos, streakNet, posContinue)
                    streakPos = streaks[0]
                    streakNet = streaks[1]
                    posContinue = streaks[2]
                    rows += DayMetricEntity(
                        dayKey = dayKey,
                        dayScore = dayScore,
                        runningAvg = runningAvg,
                        progress = progress,
                        streakPos = streakPos,
                        streakNet = streakNet,
                        posContinue = posContinue,
                    )
                    prevAvg = runningAvg
                }
                return rows
            }
        }

        /** Runs the backfill once. No-op when already done or DB not open. */
        suspend fun runIfNeeded() {
            /** If. */
            if (!sessionManager.isOpen.value) return
            /** Db. */
            val db = sessionManager.requireDatabase()
            /** Settings dao. */
            val settingsDao = db.appSettingsDao()
            /** If. */
            if (settingsDao.getSetting(BACKFILL_DONE_KEY) != null) {
                // Backfill already done — run the daily catch-up instead:
                // extend every habit's L1 through yesterday (missed gap → 0.0,
                // logged → 1.0), then refresh L2/L3 tails.
                logger.d("ScoreRollupBackfillService.runIfNeeded", "Backfill already done; running catch-up")
                cascadeService.catchUpTail()
                /** Return. */
                return
            }

            /** Log tag. */
            val logTag = "ScoreRollupBackfillService.runIfNeeded"
            /** Started. */
            val started = System.currentTimeMillis()
            logger.i(logTag, "SCORE_ROLLUP_BACKFILL_START")

            // ── Fresh-install / no-legacy-data fast path ─────────────────
            // Backfill exists ONLY to convert legacy num/den rules and
            // reconstruct metric history from old occurrences. A fresh
            // install (no num/den rules, no occurrences) has nothing to do:
            // new habits are written in CONFIG format by the UI and metrics
            // flow through the live cascade (Inc 3), not the backfill.
            /** Task dao. */
            val taskDao = db.taskDao()
            /** Occ dao. */
            val occDao = db.taskOccurrenceDao()
            /** Recurring. */
            val recurring = taskDao.getRecurringTasks()
            /** Has legacy num den rules. */
            val hasLegacyNumDenRules = recurring.any { task ->
                !task.recurrenceRule.isNullOrBlank() && !task.recurrenceRule.startsWith("CONFIG:")
            }
            /** Has any occurrences. */
            val hasAnyOccurrences = occDao.countAllOccurrences() > 0
            /** If. */
            if (!hasLegacyNumDenRules && !hasAnyOccurrences) {
                settingsDao.insertSetting(
                    /** App setting entity. */
                    AppSettingEntity(
                        key = BACKFILL_DONE_KEY,
                        value = "1",
                        updatedAt = LocalDateTime.now().toString(),
                    ),
                )
                logger.i(
                    /** Log tag. */
                    logTag,
                    "SCORE_ROLLUP_BACKFILL_FRESH_INSTALL_SKIP",
                    /** Map of. */
                    mapOf(
                        "elapsedMs" to (System.currentTimeMillis() - started),
                        "reason" to "no legacy num/den rules and no occurrences; fresh install has nothing to backfill",
                    ),
                )
                /** Return. */
                return
            }

            try {
                /** Backfill. */
                backfill(
                    taskDao = taskDao,
                    occDao = occDao,
                    habitDao = db.habitMetricDao(),
                    dimDao = db.dimensionMetricDao(),
                    dayDao = db.dayMetricDao(),
                    settingsDao = settingsDao,
                )
                settingsDao.insertSetting(
                    /** App setting entity. */
                    AppSettingEntity(
                        key = BACKFILL_DONE_KEY,
                        value = "1",
                        updatedAt = LocalDateTime.now().toString(),
                    ),
                )
                logger.i(
                    /** Log tag. */
                    logTag,
                    "SCORE_ROLLUP_BACKFILL_END",
                    /** Map of. */
                    mapOf(
                        "elapsedMs" to (System.currentTimeMillis() - started),
                        "done" to true,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(logTag, "SCORE_ROLLUP_BACKFILL_FAILED", e, mapOf("error" to (e.message ?: "unknown")))
                // Do NOT set the guard — next launch retries.
            }
        }

        private suspend fun backfill(
            /** Task dao. */
            taskDao: TaskDao,
            /** Occ dao. */
            occDao: TaskOccurrenceDao,
            /** Habit dao. */
            habitDao: HabitMetricDao,
            /** Dim dao. */
            dimDao: DimensionMetricDao,
            /** Day dao. */
            dayDao: DayMetricDao,
            /** Settings dao. */
            settingsDao: AppSettingsDao,
        ) {
            /** Recurring. */
            val recurring = taskDao.getRecurringTasks()
            logger.i("ScoreRollupBackfillService.backfill", "Recurring habits found", mapOf("count" to recurring.size))

            // ── L1: per-habit sparse metrics ──────────────────────────────
            /** Habit rows. */
            val habitRows = mutableListOf<HabitMetricEntity>()
            /** First due per habit. */
            val firstDuePerHabit = mutableMapOf<String, LocalDate>()
            /** For. */
            for (task in recurring) {
                /** If. */
                if (task.status == "archived") {
                    logger.d(
                        "ScoreRollupBackfillService.backfill",
                        "HABIT_ARCHIVED_SKIPPED",
                        /** Map of. */
                        mapOf("habitId" to task.id),
                    )
                    /** Continue. */
                    continue
                }
                /** Occurrences. */
                val occurrences = occDao.getOccurrencesForTaskForBackfill(task.id)
                /** Val. */
                val (rows, firstDue, convertedRule) = buildHabitMetrics(task, occurrences)
                habitRows += rows
                /** If. */
                if (firstDue != null) firstDuePerHabit[task.id] = firstDue
                /** If. */
                if (convertedRule != null) {
                    taskDao.updateRecurrenceRule(task.id, convertedRule)
                    logger.i(
                        "ScoreRollupBackfillService.backfill",
                        "HABIT_RULE_CONVERTED",
                        /** Map of. */
                        mapOf(
                            "habitId" to task.id,
                            "oldRule" to (task.recurrenceRule ?: "null"),
                            "newRule" to convertedRule,
                            "firstDue" to (firstDue?.toString() ?: "null"),
                        ),
                    )
                }
                logger.i(
                    "ScoreRollupBackfillService.backfill",
                    "HABIT_L1_BUILT",
                    /** Map of. */
                    mapOf(
                        "habitId" to task.id,
                        "dueRows" to rows.size,
                        "firstDue" to (firstDue?.toString() ?: "null"),
                        "ruleConverted" to (convertedRule != null),
                    ),
                )
            }
            /** If. */
            if (habitRows.isNotEmpty()) habitDao.upsertAll(habitRows)
            logger.i("ScoreRollupBackfillService.backfill", "L1 habit_metrics written", mapOf("rows" to habitRows.size))

            // ── L2: per-dimension dense metrics (from L1 rows) ─────────────
            /** Dim rows. */
            val dimRows = buildDimensionMetrics(recurring, firstDuePerHabit, habitRows)
            /** If. */
            if (dimRows.isNotEmpty()) dimDao.upsertAll(dimRows)
            logger.i("ScoreRollupBackfillService.backfill", "L2 dimension_metrics written", mapOf("rows" to dimRows.size))

            // ── L3: per-day dense metrics (from L2 rows) ──────────────────
            /** Dim weights. */
            val dimWeights = sessionManager.requireDatabase().lifeDimensionDao()
                .allWeights().associate { it.id to it.weight }
            /** Day rows. */
            val dayRows = buildDayMetrics(dimRows, dimWeights)
            /** If. */
            if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
            logger.i("ScoreRollupBackfillService.backfill", "L3 day_metrics written", mapOf("rows" to dayRows.size))
        }
    }
