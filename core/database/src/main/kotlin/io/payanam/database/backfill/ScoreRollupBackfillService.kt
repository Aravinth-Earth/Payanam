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
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

import io.payanam.scoring.MetricBaseline
import io.payanam.scoring.ScoreRollupCompute

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
        }

        /** Runs the backfill once. No-op when already done or DB not open. */
        @Suppress("TooGenericExceptionCaught")  // Intentional: scoring cascade must not crash app; log and continue
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
                // Local val: `recurrenceRule` is declared in another module, so Kotlin cannot
                // smart-cast the property itself after the null check.
                val rule = task.recurrenceRule
                !rule.isNullOrBlank() && !rule.startsWith("CONFIG:")
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
                // Fresh install still needs today's not-done seeds so L2/L3 have
                // full source rows from the very first launch (full backfill and
                // the live cascade exclude today by default).
                cascadeService.prefillToday()
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
                // The initial legacy backfill excludes today by default — seed
                // today's not-done rows so the first launch has full L2/L3
                // source rows too.
                cascadeService.prefillToday()
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
                val (rows, firstDue, convertedRule) = ScoreRollupCompute.buildHabitMetrics(task, occurrences)
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
            val dimRows = ScoreRollupCompute.buildDimensionMetrics(recurring, firstDuePerHabit, habitRows)
            if (dimRows.isNotEmpty()) dimDao.upsertAll(dimRows)
            logger.i("ScoreRollupBackfillService.backfill", "L2 dimension_metrics written", mapOf("rows" to dimRows.size))

            // ── L3: per-day dense metrics (from L2 rows) ──────────────────
            val dimWeights = sessionManager.requireDatabase().lifeDimensionDao()
                .allWeights().associate { it.id to it.weight }
            val dayRows = ScoreRollupCompute.buildDayMetrics(dimRows, dimWeights)
            if (dayRows.isNotEmpty()) dayDao.upsertAll(dayRows)
            logger.i("ScoreRollupBackfillService.backfill", "L3 day_metrics written", mapOf("rows" to dayRows.size))
        }
    }
