//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements", "LongMethod", "TooManyFunctions")

package io.payanam.database.jvm.backfill

import io.payanam.database.entity.AppSettingEntity
import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.jvm.JdbcFlows
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.database.jvm.readOccurrence
import io.payanam.database.jvm.readTask
import io.payanam.scoring.MetricBaseline
import io.payanam.scoring.ScoreRollupCompute
import io.payanam.shared.logging.AppLogger
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Outcome of one [ScoreRollupBackfillJdbc.runIfNeeded] call.
 *
 * [ran] false means nothing was computed: either [guardAlreadySet] (a previous run, or a previous
 * Android run — the guard lives in `app_settings`, shared by both platforms) or
 * [freshInstallFastPath] (nothing legacy to convert). Per-table counts are rows handed to the
 * upsert, so they describe what this run wrote, not what is in the tables.
 *
 * [failureMessage] non-null means the transaction was rolled back and the guard deliberately NOT
 * written, so the next call retries from scratch — same contract as the Android service.
 */
data class ScoreRollupBackfillJdbcResult(
    val ran: Boolean,
    val guardAlreadySet: Boolean,
    val freshInstallFastPath: Boolean = false,
    val recurrenceRulesConverted: Int = 0,
    val habitMetricRows: Int = 0,
    val dimensionMetricRows: Int = 0,
    val dayMetricRows: Int = 0,
    val failureMessage: String? = null,
)

/**
 * JDBC twin of Android's `ScoreRollupBackfillService` — the one-time score roll-up backfill
 * (Inc 2). Builds, over an already-open encrypted session:
 *
 *  1. every habit's num/den `recurrenceRule` → canonical `CONFIG:` form, via
 *     [NumDenToConfigConverter], persisted back onto `tasks`.
 *  2. L1 (`habit_metrics`, sparse): due-date scan from each habit's first occurrence up to
 *     yesterday (today stays pending), binary score from occurrences, running avg, progress and
 *     self-gov streaks.
 *  3. L2 (`dimension_metrics`, dense): equal-weighted mean of member habit scores per day, with
 *     carry-forward of each habit's last known L1 score.
 *  4. L3 (`day_metrics`, dense): dimension-weighted mean per day, iterating only the days that
 *     have dimension rows.
 *
 * Guarded by the same `app_settings` key as Android, written LAST, inside the same transaction.
 *
 * ### What this twin does better than the service it ports
 *
 * The Android `backfill()` runs outside any transaction: rule updates, L1, L2 and L3 are separate
 * Room calls, so a crash midway leaves converted rules with no L1 rows and no guard — the next
 * launch retries and re-converts, which is survivable, but the intermediate state is a database
 * the cascade service will read. Here the whole build is ONE unit of work: `autoCommit = false`,
 * explicit commit, explicit rollback, guard written last. Failure leaves the tables exactly as
 * they were and the guard unset, so the retry is a clean full rebuild.
 *
 * ### What it deliberately does NOT do
 *
 * No cascade. The service's already-done path calls `catchUpTail()` + `prefillToday()`, and its
 * fresh-install path calls `prefillToday()`. Both are cascade behaviours and `ScoreRollupCascadeService`
 * is Android-only, so this twin returns a report instead of pretending the work happened. Today's
 * rows are therefore absent until a cascade equivalent exists on this platform.
 *
 * @param session an already-open session owned by the caller.
 */
class ScoreRollupBackfillJdbc(
    private val session: PayanamDatabaseSession,
) {
    private val logger = AppLogger.current

    /**
     * Runs the backfill once. Blocking JDBC work is dispatched through [JdbcFlows.onIo]; the
     * outcome is captured in a local rather than returned from the helper, so this compiles whether
     * or not `onIo` propagates its block's value.
     */
    suspend fun runIfNeeded(): ScoreRollupBackfillJdbcResult {
        var outcome: ScoreRollupBackfillJdbcResult? = null
        JdbcFlows.onIo { outcome = runOnIo() }
        return requireNotNull(outcome) { "ScoreRollupBackfillJdbc.runIfNeeded produced no outcome" }
    }

    private fun runOnIo(): ScoreRollupBackfillJdbcResult =
        session.withSerializedAccess { connection ->
            val tag = "ScoreRollupBackfillJdbc.runIfNeeded"
            val started = System.currentTimeMillis()

            // ── Guard read, before any write ──────────────────────────────
            // Presence of the row is the whole signal: Android treats ANY row as done and never
            // inspects the value, so a row written by a previous Android run skips this too. The
            // guard key is shared state in `app_settings`, not per-platform.
            if (isGuardSet(connection)) {
                logger.d(
                    tag,
                    "SCORE_ROLLUP_BACKFILL_ALREADY_DONE",
                    mapOf(
                        "action" to "skipped; no cascade on this platform (catchUpTail/prefillToday are Android-only)",
                    ),
                )
                return@withSerializedAccess ScoreRollupBackfillJdbcResult(ran = false, guardAlreadySet = true)
            }

            logger.i(tag, "SCORE_ROLLUP_BACKFILL_START")
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                // Re-read inside the transaction. The service reads the guard once and then spends
                // unguarded time writing; here the whole build is atomic, so closing the
                // check-then-write window costs one cheap indexed SELECT.
                if (isGuardSet(connection)) {
                    connection.rollback()
                    logger.d(tag, "SCORE_ROLLUP_BACKFILL_ALREADY_DONE", mapOf("phase" to "recheck-in-transaction"))
                    return@withSerializedAccess ScoreRollupBackfillJdbcResult(ran = false, guardAlreadySet = true)
                }

                val recurring = readRecurringTasks(connection)
                val occurrencesByTask = readOccurrencesByTask(connection)

                // ── Fresh-install / no-legacy-data fast path ───────────────
                // The backfill exists only to convert legacy num/den rules and reconstruct history
                // from old occurrences. Nothing legacy → nothing to build; only the guard is written,
                // and it goes in inside this transaction like everything else.
                // Local vals for the rule: `recurrenceRule` is declared in another module, so Kotlin
                // cannot smart-cast the property itself after a null check.
                val hasLegacyNumDenRules =
                    recurring.any { task ->
                        val rule = task.recurrenceRule
                        !rule.isNullOrBlank() && !rule.startsWith("CONFIG:")
                    }
                val hasAnyOccurrences = occurrencesByTask.isNotEmpty()
                if (!hasLegacyNumDenRules && !hasAnyOccurrences) {
                    writeGuardRow(connection)
                    connection.commit()
                    logger.i(
                        tag,
                        "SCORE_ROLLUP_BACKFILL_FRESH_INSTALL_SKIP",
                        mapOf(
                            "elapsedMs" to (System.currentTimeMillis() - started),
                            "reason" to "no legacy num/den rules and no occurrences; fresh install has nothing to backfill",
                            "cascaded" to false,
                        ),
                    )
                    return@withSerializedAccess ScoreRollupBackfillJdbcResult(
                        ran = false,
                        guardAlreadySet = false,
                        freshInstallFastPath = true,
                    )
                }

                logger.i(
                    "ScoreRollupBackfillJdbc.backfill",
                    "Recurring habits found",
                    mapOf("count" to recurring.size),
                )

                // ── L1: per-habit sparse metrics ─────────────────────────
                val habitRows = mutableListOf<HabitMetricEntity>()
                val firstDuePerHabit = mutableMapOf<String, LocalDate>()
                var rulesConverted = 0
                for (task in recurring) {
                    if (task.status == "archived") {
                        logger.d(
                            "ScoreRollupBackfillJdbc.backfill",
                            "HABIT_ARCHIVED_SKIPPED",
                            mapOf("habitId" to task.id),
                        )
                        continue
                    }
                    val occurrences = occurrencesByTask[task.id].orEmpty()
                    val (rows, firstDue, convertedRule) = ScoreRollupCompute.buildHabitMetrics(task, occurrences)
                    habitRows += rows
                    if (firstDue != null) firstDuePerHabit[task.id] = firstDue
                    if (convertedRule != null) {
                        updateRecurrenceRule(connection, task.id, convertedRule)
                        rulesConverted++
                        logger.i(
                            "ScoreRollupBackfillJdbc.backfill",
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
                        "ScoreRollupBackfillJdbc.backfill",
                        "HABIT_L1_BUILT",
                        mapOf(
                            "habitId" to task.id,
                            "dueRows" to rows.size,
                            "firstDue" to (firstDue?.toString() ?: "null"),
                            "ruleConverted" to (convertedRule != null),
                        ),
                    )
                }
                val habitWritten = upsertHabitMetrics(connection, habitRows)
                logger.i(
                    "ScoreRollupBackfillJdbc.backfill",
                    "L1 habit_metrics written",
                    mapOf("rows" to habitWritten, "rulesConverted" to rulesConverted),
                )

                // ── L2: per-dimension dense metrics (from L1 rows) ───────
                val dimRows = ScoreRollupCompute.buildDimensionMetrics(recurring, firstDuePerHabit, habitRows)
                val dimensionWritten = upsertDimensionMetrics(connection, dimRows)
                logger.i(
                    "ScoreRollupBackfillJdbc.backfill",
                    "L2 dimension_metrics written",
                    mapOf("rows" to dimensionWritten),
                )

                // ── L3: per-day dense metrics (from L2 rows) ──────────────
                val dimWeights = readDimensionWeights(connection)
                val dayRows = ScoreRollupCompute.buildDayMetrics(dimRows, dimWeights)
                val dayWritten = upsertDayMetrics(connection, dayRows)
                logger.i(
                    "ScoreRollupBackfillJdbc.backfill",
                    "L3 day_metrics written",
                    mapOf("rows" to dayWritten),
                )

                // Guard LAST, inside this transaction: nothing marks the backfill done until every
                // metric row and every converted rule above is committed with it.
                writeGuardRow(connection)
                connection.commit()

                logger.i(
                    tag,
                    "SCORE_ROLLUP_BACKFILL_END",
                    mapOf(
                        "elapsedMs" to (System.currentTimeMillis() - started),
                        "done" to true,
                        "habitMetrics" to habitWritten,
                        "dimensionMetrics" to dimensionWritten,
                        "dayMetrics" to dayWritten,
                        "recurrenceRulesConverted" to rulesConverted,
                    ),
                )
                return@withSerializedAccess ScoreRollupBackfillJdbcResult(
                    ran = true,
                    guardAlreadySet = false,
                    recurrenceRulesConverted = rulesConverted,
                    habitMetricRows = habitWritten,
                    dimensionMetricRows = dimensionWritten,
                    dayMetricRows = dayWritten,
                )
            } catch (error: SQLException) {
                // Narrow on purpose: every statement in this block is JDBC, so SQLException is the whole
                // surface. A broad catch would also swallow a cancellation and commit a half-built
                // roll-up on the way out.
                // Rolled back explicitly, because autoCommit is still false. The guard is therefore
                // never written and the next call retries from scratch — same contract as the service,
                // which swallows, logs and leaves the guard unset.
                runCatching { connection.rollback() }
                    .onFailure { rollbackError ->
                        logger.e(
                            "ScoreRollupBackfillJdbc.runIfNeeded",
                            "Rollback failed after a failed score roll-up backfill",
                            rollbackError,
                            mapOf("rollbackError" to (rollbackError.message ?: "Unknown error")),
                        )
                    }
                logger.e(
                    tag,
                    "SCORE_ROLLUP_BACKFILL_FAILED",
                    error,
                    mapOf(
                        "error" to (error.message ?: "Unknown error"),
                        "elapsedMs" to (System.currentTimeMillis() - started),
                        "guardWritten" to false,
                    ),
                )
                return@withSerializedAccess ScoreRollupBackfillJdbcResult(
                    ran = false,
                    guardAlreadySet = false,
                    failureMessage = error.message ?: "Unknown error",
                )
            } finally {
                runCatching { connection.autoCommit = previousAutoCommit }
            }
        }

    // ────────────────────────────────────────────────────────────────────
    // SQL — reads
    // ────────────────────────────────────────────────────────────────────

    /**
     * The key alone is read: the service checks the row, not the value, and a row written by a
     * previous Android run must skip this platform too.
     */
    private fun isGuardSet(connection: Connection): Boolean =
        connection.prepareStatement(SELECT_GUARD_SQL).use { prepared ->
            prepared.setString(COLUMN_KEY, BACKFILL_DONE_KEY)
            prepared.executeQuery().use { resultSet -> resultSet.next() }
        }

    /**
     * `SELECT *`, as the DAO issues it. Read by column name rather than position so a future
     * column addition to `tasks` cannot shift a value into the wrong field.
     *
     * No `ORDER BY`, matching the DAO's shape: it does not change any stored value except
     * possibly the last ULP of a L2 float sum, because the dimension numerator is accumulated in
     * member order and this query returns whatever order SQLite chooses.
     */
    private fun readRecurringTasks(connection: Connection): List<TaskEntity> =
        connection.prepareStatement(RECURRING_TASKS_SQL).use { prepared ->
            prepared.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.readTask())
                    }
                }
            }
        }

    /**
     * One pass for every task's occurrences, ascending per task. The service issues this per task
     * through `getOccurrencesForTaskForBackfill`; grouping one ordered read is the same data in
     * the same per-task order, and it keeps the row count inside one transaction instead of N.
     */
    private fun readOccurrencesByTask(connection: Connection): Map<String, List<TaskOccurrenceEntity>> =
        connection.prepareStatement(ALL_OCCURRENCES_SQL).use { prepared ->
            prepared.executeQuery().use { resultSet ->
                val byTask = LinkedHashMap<String, MutableList<TaskOccurrenceEntity>>()
                while (resultSet.next()) {
                    val taskId = resultSet.getString("taskId")
                    if (taskId == null) continue
                    byTask.getOrPut(taskId) { mutableListOf() } += resultSet.readOccurrence()
                }
                byTask
            }
        }

    /** C2 weights: dimensionId → weight, matching `LifeDimensionDao.allWeights()`. */
    private fun readDimensionWeights(connection: Connection): Map<String, Double> =
        connection.prepareStatement(DIMENSION_WEIGHTS_SQL).use { prepared ->
            prepared.executeQuery().use { resultSet ->
                val weights = LinkedHashMap<String, Double>()
                while (resultSet.next()) {
                    val id = resultSet.getString("id") ?: continue
                    weights[id] = resultSet.getDouble("weight")
                }
                weights
            }
        }

    // ────────────────────────────────────────────────────────────────────
    // SQL — writes
    // ────────────────────────────────────────────────────────────────────

    /**
     * Guard row. `INSERT OR REPLACE` on the `key` primary key, value the literal `"1"`, timestamp
     * `LocalDateTime.now().toString()` — the exact triple the service persists, so a database
     * written by either platform is byte-comparable in the other. `AppSettingEntity.value` is
     * nullable while the written value is not: the column is nullable, this writer is not.
     */
    private fun writeGuardRow(connection: Connection) {
        val entity = AppSettingEntity(
            key = BACKFILL_DONE_KEY,
            value = BACKFILL_DONE_VALUE,
            updatedAt = LocalDateTime.now().toString(),
        )
        connection.prepareStatement(UPSERT_SETTING_SQL).use { prepared ->
            prepared.setString(COLUMN_KEY, entity.key)
            prepared.setString(COLUMN_VALUE, entity.value)
            prepared.setString(COLUMN_UPDATED_AT, entity.updatedAt)
            prepared.executeUpdate()
        }
    }

    /**
     * Only the rule column is written. The backfill rewrites a stored value in place; it is not a
     * user edit, so no `updatedAt` bump — otherwise this platform would be the one that keeps
     * touching task modification times during a migration Android leaves alone.
     */
    private fun updateRecurrenceRule(
        connection: Connection,
        taskId: String,
        rule: String,
    ) {
        connection.prepareStatement(UPDATE_RECURRENCE_RULE_SQL).use { prepared ->
            prepared.setString(1, rule)
            prepared.setString(2, taskId)
            prepared.executeUpdate()
        }
    }

    private fun upsertHabitMetrics(
        connection: Connection,
        rows: List<HabitMetricEntity>,
    ): Int {
        if (rows.isEmpty()) return 0
        connection.prepareStatement(UPSERT_HABIT_METRIC_SQL).use { prepared ->
            rows.forEach { entity ->
                prepared.setString(COLUMN_HABIT_ID, entity.habitId)
                prepared.setString(COLUMN_DAY_KEY, entity.dayKey)
                prepared.setDouble(COLUMN_SCORE, entity.score)
                prepared.setDouble(COLUMN_RUNNING_AVG, entity.runningAvg)
                prepared.setDouble(COLUMN_PROGRESS, entity.progress)
                prepared.setInt(COLUMN_STREAK_POS, entity.streakPos)
                prepared.setInt(COLUMN_STREAK_NET, entity.streakNet)
                prepared.setInt(COLUMN_POS_CONTINUE, entity.posContinue)
                prepared.addBatch()
            }
            prepared.executeBatch()
        }
        // rows.size, not the batch update counts: drivers may report SUCCESS_NO_INFO per batch
        // entry, and Room's `upsertAll` counts the entities handed to it anyway.
        return rows.size
    }

    private fun upsertDimensionMetrics(
        connection: Connection,
        rows: List<DimensionMetricEntity>,
    ): Int {
        if (rows.isEmpty()) return 0
        connection.prepareStatement(UPSERT_DIMENSION_METRIC_SQL).use { prepared ->
            rows.forEach { entity ->
                prepared.setString(COLUMN_DIMENSION_ID, entity.dimensionId)
                prepared.setString(COLUMN_DAY_KEY, entity.dayKey)
                prepared.setDouble(COLUMN_SCORE, entity.score)
                prepared.setDouble(COLUMN_RUNNING_AVG, entity.runningAvg)
                prepared.setDouble(COLUMN_PROGRESS, entity.progress)
                prepared.setInt(COLUMN_STREAK_POS, entity.streakPos)
                prepared.setInt(COLUMN_STREAK_NET, entity.streakNet)
                prepared.setInt(COLUMN_POS_CONTINUE, entity.posContinue)
                prepared.addBatch()
            }
            prepared.executeBatch()
        }
        return rows.size
    }

    private fun upsertDayMetrics(
        connection: Connection,
        rows: List<DayMetricEntity>,
    ): Int {
        if (rows.isEmpty()) return 0
        connection.prepareStatement(UPSERT_DAY_METRIC_SQL).use { prepared ->
            rows.forEach { entity ->
                // The day insert's own numbering: dayKey leads at 1, because day_metrics has no id column.
                prepared.setString(COLUMN_DAY_KEY_DAY_METRIC, entity.dayKey)
                prepared.setDouble(COLUMN_DAY_SCORE_DAY_METRIC, entity.dayScore)
                prepared.setDouble(COLUMN_DAY_RUNNING_AVG, entity.runningAvg)
                prepared.setDouble(COLUMN_DAY_PROGRESS, entity.progress)
                prepared.setInt(COLUMN_DAY_STREAK_POS, entity.streakPos)
                prepared.setInt(COLUMN_DAY_STREAK_NET, entity.streakNet)
                prepared.setInt(COLUMN_DAY_POS_CONTINUE, entity.posContinue)
                prepared.addBatch()
            }
            prepared.executeBatch()
        }
        return rows.size
    }

    private companion object {
        /** Verbatim from `ScoreRollupBackfillService.BACKFILL_DONE_KEY` — one key, both platforms. */
        const val BACKFILL_DONE_KEY = "score_rollup_backfill_v1_done"

        /** Verbatim from the service: the written value is the literal `"1"`, not `"true"`. */
        const val BACKFILL_DONE_VALUE = "1"

        /**
         * Presence only. `LIMIT 1` because the answer is a boolean, and no `value` column is
         * selected so this cannot accidentally start depending on what was written.
         */
        const val SELECT_GUARD_SQL = "SELECT key FROM app_settings WHERE key = ? LIMIT 1"

        /**
         * Plain `INSERT OR REPLACE` on the `key` primary key, matching Room's
         * `@Insert(onConflict = REPLACE)` for `AppSettingEntity`.
         */
        const val UPSERT_SETTING_SQL = "INSERT OR REPLACE INTO app_settings (key, value, updatedAt) VALUES (?, ?, ?)"

        /** Verbatim from `TaskDao.getRecurringTasks`. */
        const val RECURRING_TASKS_SQL = "SELECT * FROM tasks WHERE recurrenceEnabled = 1"

        /**
         * Every occurrence, grouped in memory. Ascending within a task is the load-bearing part:
         * the L1 builder keeps the LAST completed/skipped occurrence per day.
         */
        const val ALL_OCCURRENCES_SQL = "SELECT * FROM task_occurrences ORDER BY taskId ASC, dueDate ASC"

        const val UPDATE_RECURRENCE_RULE_SQL = "UPDATE tasks SET recurrenceRule = ? WHERE id = ?"

        /** Verbatim from `LifeDimensionDao.allWeights` (id + weight is all the scoring reads). */
        const val DIMENSION_WEIGHTS_SQL = "SELECT id, weight FROM life_dimensions"

        /**
         * `INSERT OR REPLACE`, deliberately: all three metric tables are keyed on a composite
         * primary key (`habit_metrics` on `(habitId, dayKey)`, `dimension_metrics` on
         * `(dimensionId, dayKey)`, `day_metrics` on `dayKey`), which is what makes replace the
         * correct upsert and matches Room's `@Insert(REPLACE)` on the same entities. A plain
         * INSERT would fail the second time a day is rebuilt.
         */
        const val UPSERT_HABIT_METRIC_SQL =
            "INSERT OR REPLACE INTO habit_metrics " +
                "(habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        const val UPSERT_DIMENSION_METRIC_SQL =
            "INSERT OR REPLACE INTO dimension_metrics " +
                "(dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        const val UPSERT_DAY_METRIC_SQL =
            "INSERT OR REPLACE INTO day_metrics " +
                "(dayKey, dayScore, runningAvg, progress, streakPos, streakNet, posContinue) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"

        // 1-based JDBC parameter positions, named because they are POSITIONAL: a value pasted into
        // the wrong slot writes a dayKey into a score column and still compiles. Each group must
        // stay in the exact order of its INSERT's column list.
        private const val COLUMN_KEY = 1
        private const val COLUMN_VALUE = 2
        private const val COLUMN_UPDATED_AT = 3

        private const val COLUMN_HABIT_ID = 1
        private const val COLUMN_DIMENSION_ID = 1

        /**
         * Shared by the habit and dimension inserts, which both lead with an id column: habitId/
         * dimensionId (1), dayKey (2), then the tail.
         */
        private const val COLUMN_DAY_KEY = 2
        private const val COLUMN_SCORE = 3
        private const val COLUMN_RUNNING_AVG = 4
        private const val COLUMN_PROGRESS = 5
        private const val COLUMN_STREAK_POS = 6
        private const val COLUMN_STREAK_NET = 7
        private const val COLUMN_POS_CONTINUE = 8

        /**
         * The DAY insert numbers one LOWER throughout, because `day_metrics` has no id column: it leads
         * with dayKey and every other value moves down a slot. Binding the shared constants above into it
         * leaves position 1 unwritten and binds position 8 where the statement has only seven
         * placeholders, which SQLite answers with `column index out of range` (SQLITE_RANGE) - a failure
         * that rolls the whole backfill transaction back, so no metric row is written and the done-guard
         * is never set. Kept separate rather than derived, so a change to either insert's column list
         * cannot silently re-align the other.
         */
        private const val COLUMN_DAY_KEY_DAY_METRIC = 1
        private const val COLUMN_DAY_SCORE_DAY_METRIC = 2
        private const val COLUMN_DAY_RUNNING_AVG = 3
        private const val COLUMN_DAY_PROGRESS = 4
        private const val COLUMN_DAY_STREAK_POS = 5
        private const val COLUMN_DAY_STREAK_NET = 6
        private const val COLUMN_DAY_POS_CONTINUE = 7
    }
}
