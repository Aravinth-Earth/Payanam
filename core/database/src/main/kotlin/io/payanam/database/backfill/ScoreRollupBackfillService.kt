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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cumulative rolling state before a change date — C1 baseline (self-gov ss/cs/pa).
 * Seeded from existing metric rows strictly BEFORE a change day so tail builds
 * (O(gap days)) reproduce the same math as a full rebuild (O(history)).
 */
internal data class MetricBaseline(
    val sumScores: Double,
    val count: Int,
    val prevAvg: Double?,
    val streakPos: Int,
    val streakNet: Int,
    val posContinue: Int,
) {
    companion object {
        /**
         * Zeroed baseline (no prior rows), used to seed a full rebuild
         * from the first due day.
         */
        fun empty(): MetricBaseline = MetricBaseline(0.0, 0, null, 0, 0, 0)

        /** Derive from rows strictly BEFORE the change day (L1 shape). */
        fun fromHabitRows(rows: List<HabitMetricEntity>): MetricBaseline =
            if (rows.isEmpty()) {
                empty()
            } else {
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
            if (rows.isEmpty()) {
                empty()
            } else {
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
            if (rows.isEmpty()) {
                empty()
            } else {
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
class ScoreRollupBackfillService
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
        private val cascadeService: ScoreRollupCascadeService,
    ) {
        private val logger = UnifiedLogger.getInstance()

        companion object {
            // Lazy so pure static helpers (computeStreaks et al.) stay usable in
            // plain JVM tests without UnifiedLogger.initialize().
            private val logger by lazy { UnifiedLogger.getInstance() }

            const val BACKFILL_DONE_KEY = "score_rollup_backfill_v1_done"

            /** Self-gov `_compute_streaks` port incl. ceiling fix (spec §9). */
            internal fun computeStreaks(
                progress: Double,
                runningAvg: Double,
                streakPos: Int,
                streakNet: Int,
                posContinue: Int,
            ): IntArray {
                val atCeiling = Math.abs(progress) < 1e-10 && Math.abs(runningAvg - 1.0) < 1e-10
                val newPos = if ((progress > 1e-10) || atCeiling) streakPos + 1 else 0
                val newNet = streakNet + when {
                    progress > 1e-10 -> 1
                    progress < -1e-10 -> -1
                    else -> 0
                }
                val newContinue = if (progress > 1e-10) posContinue + 1 else posContinue
                return intArrayOf(newPos, newNet, newContinue)
            }

            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            private fun parseLocalDate(s: String): LocalDate? =
                try {
                    LocalDate.parse(s.take(10))
                } catch (e: Exception) {
                    logger.w("ScoreRollupBackfillService", "Skipping occurrence with unparseable date", mapOf("date" to s))
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
                task: TaskEntity,
                occurrences: List<TaskOccurrenceEntity>,
                includeToday: Boolean = false,
            ): Triple<List<HabitMetricEntity>, LocalDate?, String?> {
                // Full build = tail build from the first due day with an empty baseline.
                val (firstDue, convertedRule) = habitTimelineStart(task, occurrences)
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
                task: TaskEntity,
                occurrences: List<TaskOccurrenceEntity>,
                fromDay: LocalDate,
                includeToday: Boolean = false,
                baseline: MetricBaseline = MetricBaseline.empty(),
            ): Triple<List<HabitMetricEntity>, LocalDate?, String?> {
                if (task.recurrenceEnabled != 1) return Triple(emptyList(), null, null)
                val occByDate = occurrences
                    .filter { it.status == "completed" || it.status == "skipped" }
                    .associateBy { it.dueDate.take(10) }
                // Any occurrence today (any status) — controls whether today
                // gets a row in cascade mode (includeToday=true).
                val todayLogged = occurrences.any { it.dueDate.take(10) == LocalDate.now().toString() }
                val firstDue = occurrences
                    .mapNotNull { parseLocalDate(it.dueDate) }
                    .minOrNull()

                // Anchor for interval grids: first occurrence, else createdAt.
                val anchor = firstDue ?: parseLocalDate(task.createdAt) ?: LocalDate.now()
                val convertedRule = convertRule(task, anchor)
                val firstDueDay = firstDue
                    ?: return Triple(emptyList(), null, convertedRule) // no history — rule still converted
                val config = RecurrenceConfig.parse(convertedRule)
                val today = LocalDate.now()
                val rows = mutableListOf<HabitMetricEntity>()
                var sumScores = baseline.sumScores
                var count = baseline.count
                var prevAvg = baseline.prevAvg
                var streakPos = baseline.streakPos
                var streakNet = baseline.streakNet
                var posContinue = baseline.posContinue
                var day = if (fromDay.isAfter(firstDueDay)) fromDay else firstDueDay
                val loopEnd = if (includeToday && todayLogged) today.plusDays(1) else today
                while (day.isBefore(loopEnd)) { // today stays pending unless logged (cascade)
                    if (config.isScheduledDay(day)) {
                        val score = if (occByDate[day.toString()] != null) 1.0 else 0.0
                        sumScores += score
                        count++
                        val runningAvg = sumScores / count
                        val progress = if (prevAvg == null) score else runningAvg - prevAvg
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
                task: TaskEntity,
                occurrences: List<TaskOccurrenceEntity>,
            ): Pair<LocalDate?, String?> {
                if (task.recurrenceEnabled != 1) return null to null
                val firstDue = occurrences
                    .mapNotNull { parseLocalDate(it.dueDate) }
                    .minOrNull()
                val anchor = firstDue ?: parseLocalDate(task.createdAt) ?: LocalDate.now()
                val convertedRule = convertRule(task, anchor)
                return firstDue to convertedRule
            }

            /** num/den → CONFIG with anchor = first due date (fallback: createdAt).
             *  Already-canonical rules (CONFIG: / FREQ= RRULE) pass through untouched —
             *  both are deterministic and must never be re-mapped. */
            private fun convertRule(task: TaskEntity, firstDue: LocalDate): String {
                val rule = task.recurrenceRule
                if (rule.isNullOrBlank()) return NumDenToConfigConverter.convert(null, firstDue)
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
                fromDay: LocalDate,
                includeToday: Boolean = false,
                baseline: MetricBaseline = MetricBaseline.empty(),
                lastScores: Map<String, Double> = emptyMap(),
            ): List<DimensionMetricEntity> {
                if (habitRows.isEmpty()) return emptyList()
                val byDim = recurring
                    .filter { firstDuePerHabit.containsKey(it.id) }
                    .groupBy { it.dimensionId ?: "dim_unassigned" }

                // Per habit: sorted (dayKey -> score) timeline for carry-forward
                val habitTimelines = habitRows
                    .groupBy { it.habitId }
                    .mapValues { (_, rows) -> rows.sortedBy { it.dayKey } }
                val rows = mutableListOf<DimensionMetricEntity>()
                for ((dimId, habits) in byDim) {
                    if (habits.isEmpty()) continue
                    val weight = 1.0 / habits.size
                    val earliest = habits.mapNotNull { firstDuePerHabit[it.id] }.minOrNull() ?: continue
                    val today = LocalDate.now()
                    val loopEnd = if (includeToday) today.plusDays(1) else today

                    // C1: start at the later of (fromDay, earliest); seed rolling state
                    // from the dimension baseline and the carry-forward lastScores map.
                    var day = if (fromDay.isAfter(earliest)) fromDay else earliest
                    var sumScores = baseline.sumScores
                    var count = baseline.count
                    var prevAvg = baseline.prevAvg
                    var streakPos = baseline.streakPos
                    var streakNet = baseline.streakNet
                    var posContinue = baseline.posContinue
                    val carryScores = lastScores.toMutableMap()
                    while (day.isBefore(loopEnd)) {
                        var dimScoreNumerator = 0.0
                        var weightSum = 0.0
                        for (habit in habits) {
                            val timeline = habitTimelines[habit.id] ?: continue
                            if (timeline.isEmpty()) continue
                            // carry-forward: last L1 row on-or-before this day
                            val idx = timeline.binarySearchBy(day.toString()) { it.dayKey }
                            val last = if (idx >= 0) timeline[idx] else timeline.getOrNull(-idx - 2)
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
                        if (weightSum > 0.0) {
                            val dimScore = dimScoreNumerator / weightSum
                            // ── SOURCE TRACE: how this dimension score was reached ──
                            // Exposes numerator/weightSum + each member's contributed
                            // score so a missing/swallowed L1 row is visible in logs.
                            if (UnifiedLogger.isInitialized()) {
                                val memberScores = habits.mapNotNull { habit ->
                                    val timeline = habitTimelines[habit.id] ?: return@mapNotNull null
                                    val idx = timeline.binarySearchBy(day.toString()) { it.dayKey }
                                    val last = if (idx >= 0) timeline[idx] else timeline.getOrNull(-idx - 2)
                                    val score = when {
                                        idx >= 0 -> {
                                            last!!.score
                                        }
                                        carryScores.containsKey(habit.id) -> carryScores.getValue(habit.id)
                                        last != null -> last.score
                                        else -> return@mapNotNull null
                                    }
                                    habit.id to "%.5f".format(Locale.US, score)
                                }
                                logger.d(
                                    "ScoreRollupBackfillService.buildDimensionMetricsFrom",
                                    "L2_SOURCE | dim=${dimId.hashCode().and(0xFFFF).toString(16)} d=$day | numerator=%.5f weightSum=%.5f dimScore=%.5f | members=${memberScores.size} | scores=$memberScores"
                                        .format(Locale.US, dimScoreNumerator, weightSum, dimScore),
                                )
                            }
                            sumScores += dimScore
                            count++
                            val runningAvg = sumScores / count
                            val progress = if (prevAvg == null) dimScore else runningAvg - prevAvg
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
                if (dimensionRows.isEmpty()) return emptyList()
                val byDay = dimensionRows.groupBy { it.dayKey }
                val allDays = byDay.keys.sorted()
                if (allDays.isEmpty()) return emptyList()
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
                fromDay: LocalDate,
                baseline: MetricBaseline = MetricBaseline.empty(),
                dimWeights: Map<String, Double> = emptyMap(),
            ): List<DayMetricEntity> {
                if (dimensionRows.isEmpty()) return emptyList()
                val byDay = dimensionRows.groupBy { it.dayKey }
                val allDays = byDay.keys.sorted().filter { !LocalDate.parse(it).isBefore(fromDay) }
                if (allDays.isEmpty()) return emptyList()
                val rows = mutableListOf<DayMetricEntity>()
                var sumScores = baseline.sumScores
                var count = baseline.count
                var prevAvg = baseline.prevAvg
                var streakPos = baseline.streakPos
                var streakNet = baseline.streakNet
                var posContinue = baseline.posContinue
                for (dayKey in allDays) {
                    val dims = byDay[dayKey].orEmpty()
                    val dayScore = if (dims.isNotEmpty()) {
                        // C2: weighted average of dimension scores. Unknown dims
                        // (no weight row) fall back to 1.0 — equal-weights legacy.
                        val weightedSum = dims.sumOf { it.score * (dimWeights[it.dimensionId] ?: 1.0) }
                        val weightSum = dims.sumOf { dimWeights[it.dimensionId] ?: 1.0 }
                        // ── SOURCE TRACE: how the day score was reached ──
                        // Exposes the weighted numerator/sum + each dimension's
                        // contributing score so a missing/swallowed dim row is visible.
                        if (UnifiedLogger.isInitialized()) {
                            val dimContrib = dims.mapIndexed { idx, d ->
                                "dim${idx}=%.5f".format(Locale.US, d.score)
                            }
                            logger.d(
                                "ScoreRollupBackfillService.buildDayMetricsFrom",
                                "L3_SOURCE | d=$dayKey | dayScore=%.5f | weightedSum=%.5f weightSum=%.5f | dims=${dimContrib.size} | $dimContrib"
                                    .format(Locale.US, if (weightSum <= 0.0) 0.0 else weightedSum / weightSum, weightedSum, weightSum),
                            )
                        }
                        if (weightSum <= 0.0) 0.0 else weightedSum / weightSum
                    } else {
                        continue // skip days with no dimension rows at all
                    }
                    sumScores += dayScore
                    count++
                    val runningAvg = sumScores / count
                    val progress = if (prevAvg == null) dayScore else runningAvg - prevAvg
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
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        suspend fun runIfNeeded() {
            if (!sessionManager.isOpen.value) return
            val db = sessionManager.requireDatabase()
            val settingsDao = db.appSettingsDao()
            if (settingsDao.getSetting(BACKFILL_DONE_KEY) != null) {
                // Backfill already done — run the daily catch-up instead:
                // extend every habit's L1 through yesterday (missed gap → 0.0,
                // logged → 1.0), then refresh L2/L3 tails.
                logger.d("ScoreRollupBackfillService.runIfNeeded", "Backfill already done; running catch-up")
                cascadeService.catchUpTail()
                cascadeService.prefillToday()
                return
            }
            val logTag = "ScoreRollupBackfillService.runIfNeeded"
            val started = System.currentTimeMillis()
            logger.i(logTag, "SCORE_ROLLUP_BACKFILL_START")

            // ── Fresh-install / no-legacy-data fast path ─────────────────
            // Backfill exists ONLY to convert legacy num/den rules and
            // reconstruct metric history from old occurrences. A fresh
            // install (no num/den rules, no occurrences) has nothing to do:
            // new habits are written in CONFIG format by the UI and metrics
            // flow through the live cascade (Inc 3), not the backfill.
            val taskDao = db.taskDao()
            val occDao = db.taskOccurrenceDao()
            val recurring = taskDao.getRecurringTasks()
            val hasLegacyNumDenRules = recurring.any { task ->
                !task.recurrenceRule.isNullOrBlank() && !task.recurrenceRule.startsWith("CONFIG:")
            }
            val hasAnyOccurrences = occDao.countAllOccurrences() > 0
            if (!hasLegacyNumDenRules && !hasAnyOccurrences) {
                settingsDao.insertSetting(
                    AppSettingEntity(
                        key = BACKFILL_DONE_KEY,
                        value = "1",
                        updatedAt = LocalDateTime.now().toString(),
                    ),
                )
                logger.i(
                    logTag,
                    "SCORE_ROLLUP_BACKFILL_FRESH_INSTALL_SKIP",
                    mapOf(
                        "elapsedMs" to (System.currentTimeMillis() - started),
                        "reason" to "no legacy num/den rules and no occurrences; fresh install has nothing to backfill",
                    ),
                )
                return
            }

            try {
                backfill(
                    taskDao = taskDao,
                    occDao = occDao,
                    habitDao = db.habitMetricDao(),
                    dimDao = db.dimensionMetricDao(),
                    dayDao = db.dayMetricDao(),
                    settingsDao = settingsDao,
                )
                settingsDao.insertSetting(
                    AppSettingEntity(
                        key = BACKFILL_DONE_KEY,
                        value = "1",
                        updatedAt = LocalDateTime.now().toString(),
                    ),
                )
                logger.i(
                    logTag,
                    "SCORE_ROLLUP_BACKFILL_END",
                    mapOf(
                        "elapsedMs" to (System.currentTimeMillis() - started),
                        "done" to true,
                    ),
                )
            } catch (e: Exception) {
                logger.e(logTag, "SCORE_ROLLUP_BACKFILL_FAILED", e, mapOf("error" to (e.message ?: "unknown")))
                // Do NOT set the guard — next launch retries.
            }
        }

        private suspend fun backfill(
            taskDao: TaskDao,
            occDao: TaskOccurrenceDao,
            habitDao: HabitMetricDao,
            dimDao: DimensionMetricDao,
            dayDao: DayMetricDao,
            settingsDao: AppSettingsDao,
        ) {
            val recurring = taskDao.getRecurringTasks()
            logger.i("ScoreRollupBackfillService.backfill", "Recurring habits found", mapOf("count" to recurring.size))

            // ── L1: per-habit sparse metrics ──────────────────────────────
            val habitRows = mutableListOf<HabitMetricEntity>()
            val firstDuePerHabit = mutableMapOf<String, LocalDate>()
            for (task in recurring) {
                if (task.status == "archived") {
                    logger.d(
                        "ScoreRollupBackfillService.backfill",
                        "HABIT_ARCHIVED_SKIPPED",
                        mapOf("habitId" to task.id),
                    )
                    continue
                }
                val occurrences = occDao.getOccurrencesForTaskForBackfill(task.id)
                val (rows, firstDue, convertedRule) = buildHabitMetrics(task, occurrences)
                habitRows += rows
                if (firstDue != null) firstDuePerHabit[task.id] = firstDue
                if (convertedRule != null) {
                    taskDao.updateRecurrenceRule(task.id, convertedRule)
                    logger.i(
                        "ScoreRollupBackfillService.backfill",
                        "HABIT_RULE_CONVERTED",
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
                    mapOf(
                        "habitId" to task.id,
                        "dueRows" to rows.size,
                        "firstDue" to (firstDue?.toString() ?: "null"),
                        "ruleConverted" to (convertedRule != null),
                    ),
                )
            }
            if (habitRows.isNotEmpty()) habitDao.upsertAll(habitRows)
            logger.i("ScoreRollupBackfillService.backfill", "L1 habit_metrics written", mapOf("rows" to habitRows.size))

            // ── L2: per-dimension dense metrics (from L1 rows) ─────────────
            val dimRows = buildDimensionMetrics(recurring, firstDuePerHabit, habitRows)
            if (dimRows.isNotEmpty()) dimDao.upsertAll(dimRows)
            logger.i("ScoreRollupBackfillService.backfill", "L2 dimension_metrics written", mapOf("rows" to dimRows.size))

            // ── L3: per-day dense metrics (from L2 rows) ──────────────────
            val dimWeights = sessionManager.requireDatabase().lifeDimensionDao()
                .allWeights().associate { it.id to it.weight }
            val dayRows = buildDayMetrics(dimRows, dimWeights)
            if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
            logger.i("ScoreRollupBackfillService.backfill", "L3 day_metrics written", mapOf("rows" to dayRows.size))
        }
    }
