//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.ScoringConfigEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.mapper.ScoringConfigMapper
import io.payanam.database.port.ScoreRollupStore
import io.payanam.domain.model.ScoringConfig
import io.payanam.shared.logging.AppLogger
import java.sql.ResultSet

/**
 * JDBC implementation of [ScoreRollupStore] over the encrypted database.
 *
 * The score roll-up compute (L1/L2/L3 metric cascade) reads and writes six tables:
 * `tasks`, `task_occurrences`, `habit_metrics`, `dimension_metrics`, `day_metrics`,
 * `scoring_config`, and `app_settings`. This store is the single port through which
 * that compute touches them.
 *
 * ## Read-only sessions
 *
 * The desktop's opener sets `PRAGMA query_only = ON` for a [DatabaseOpenMode.READ_ONLY]
 * session. The 8 reads are all plain SELECTs and are fine. The 5 writes MUST refuse a
 * read-only session explicitly rather than silently failing — the import path opens
 * the file read-only, so this is a real case. Detection: [requireWritable] checks
 * [PayanamDatabaseSession.mode] and throws [IllegalStateException] when it is
 * [DatabaseOpenMode.READ_ONLY]. This is a guard, not a substitute for the pragma —
 * the pragma is the engine-level enforcement; this is the port-level contract that
 * gives a clear error instead of a driver exception.
 *
 * ## Style
 *
 * Copies the sibling repositories exactly: [AppLogger.current] for logging,
 * [JdbcQueries.read] / [JdbcQueries.write] for SQL, SQL constants in a private
 * companion object, entity mappings as top-level [ResultSet] extension functions.
 *
 * @param session an already-open session owned by the caller.
 */
internal class ScoreRollupStoreJdbc(
    private val session: PayanamDatabaseSession,
) : ScoreRollupStore {
    private val logger = AppLogger.current

    // ── Reads ──────────────────────────────────────────────────────────────

    override suspend fun getRecurringTasks(): List<TaskEntity> =
        JdbcFlows.onIo {
            val rows = JdbcQueries.read(session, RECURRING_TASKS_SQL, emptyArray()) { rs ->
                buildList {
                    while (rs.next()) add(rs.readTask())
                }
            }
            logger.d("ScoreRollupStoreJdbc.getRecurringTasks", "Read recurring tasks", mapOf("count" to rows.size))
            rows
        }

    override suspend fun getOccurrencesForTask(taskId: String): List<TaskOccurrenceEntity> =
        JdbcFlows.onIo {
            val rows = JdbcQueries.read(session, OCCURRENCES_FOR_TASK_SQL, arrayOf(taskId)) { rs ->
                buildList {
                    while (rs.next()) add(rs.readOccurrence())
                }
            }
            logger.d(
                "ScoreRollupStoreJdbc.getOccurrencesForTask",
                "Read occurrences for task",
                mapOf("taskId" to taskId, "count" to rows.size),
            )
            rows
        }

    override suspend fun countAllOccurrences(): Int =
        JdbcFlows.onIo {
            val count = JdbcQueries.read(session, COUNT_OCCURRENCES_SQL, emptyArray()) { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
            logger.d("ScoreRollupStoreJdbc.countAllOccurrences", "Counted occurrences", mapOf("count" to count))
            count
        }

    override suspend fun getHabitMetricsForHabit(habitId: String): List<HabitMetricEntity> =
        JdbcFlows.onIo {
            val rows = JdbcQueries.read(session, HABIT_METRICS_FOR_HABIT_SQL, arrayOf(habitId)) { rs ->
                buildList {
                    while (rs.next()) add(rs.readHabitMetric())
                }
            }
            logger.d(
                "ScoreRollupStoreJdbc.getHabitMetricsForHabit",
                "Read habit metrics",
                mapOf("habitId" to habitId, "count" to rows.size),
            )
            rows
        }

    override suspend fun getDimensionMetricsForDimension(dimensionId: String): List<DimensionMetricEntity> =
        JdbcFlows.onIo {
            val rows = JdbcQueries.read(session, DIMENSION_METRICS_FOR_DIMENSION_SQL, arrayOf(dimensionId)) { rs ->
                buildList {
                    while (rs.next()) add(rs.readDimensionMetric())
                }
            }
            logger.d(
                "ScoreRollupStoreJdbc.getDimensionMetricsForDimension",
                "Read dimension metrics",
                mapOf("dimensionId" to dimensionId, "count" to rows.size),
            )
            rows
        }

    override suspend fun getDayMetrics(): List<DayMetricEntity> =
        JdbcFlows.onIo {
            val rows = JdbcQueries.read(session, ALL_DAY_METRICS_SQL, emptyArray()) { rs ->
                buildList {
                    while (rs.next()) add(rs.readDayMetric())
                }
            }
            logger.d("ScoreRollupStoreJdbc.getDayMetrics", "Read day metrics", mapOf("count" to rows.size))
            rows
        }

    /**
     * Reads the singleton `scoring_config` row (id = 1) and maps its nine `dimensionX`
     * weight columns to dimension ids through [ScoringConfigMapper] — the same pattern
     * [ScoringConfigRepositoryJvm] uses. Returns [ScoringConfig.defaults().dimensionWeightsById]
     * when no row has been saved.
     */
    override suspend fun getDimensionWeights(): Map<String, Double> =
        JdbcFlows.onIo {
            val entity = JdbcQueries.read(session, CONFIG_SQL, emptyArray()) { rs ->
                if (rs.next()) rs.readConfig() else null
            }
            val weights = if (entity != null) {
                ScoringConfigMapper.toDomain(entity).dimensionWeightsById
            } else {
                ScoringConfig.defaults().dimensionWeightsById
            }
            logger.d(
                "ScoreRollupStoreJdbc.getDimensionWeights",
                "Read dimension weights",
                mapOf("count" to weights.size),
            )
            weights
        }

    override suspend fun getSetting(key: String): String? =
        JdbcFlows.onIo {
            val value = JdbcQueries.read(session, SETTING_BY_KEY_SQL, arrayOf(key)) { rs ->
                if (rs.next()) rs.getString("value") else null
            }
            logger.d("ScoreRollupStoreJdbc.getSetting", "Read setting", mapOf("key" to key, "found" to (value != null)))
            value
        }

    // ── Writes ─────────────────────────────────────────────────────────────

    override suspend fun upsertHabitMetrics(rows: List<HabitMetricEntity>) {
        requireWritable()
        JdbcFlows.onIo {
            val count = rows.sumOf { row ->
                    JdbcQueries.write(session, UPSERT_HABIT_METRIC_SQL, row.toHabitMetricBindings())
                }
            logger.i("ScoreRollupStoreJdbc.upsertHabitMetrics", "Upserted habit metrics", mapOf("rows" to count))
        }
    }

    override suspend fun upsertDimensionMetrics(rows: List<DimensionMetricEntity>) {
        requireWritable()
        JdbcFlows.onIo {
            val count = rows.sumOf { row ->
                    JdbcQueries.write(session, UPSERT_DIMENSION_METRIC_SQL, row.toDimensionMetricBindings())
                }
            logger.i("ScoreRollupStoreJdbc.upsertDimensionMetrics", "Upserted dimension metrics", mapOf("rows" to count))
        }
    }

    override suspend fun upsertDayMetrics(rows: List<DayMetricEntity>) {
        requireWritable()
        JdbcFlows.onIo {
            val count = rows.sumOf { row ->
                    JdbcQueries.write(session, UPSERT_DAY_METRIC_SQL, row.toDayMetricBindings())
                }
            logger.i("ScoreRollupStoreJdbc.upsertDayMetrics", "Upserted day metrics", mapOf("rows" to count))
        }
    }

    override suspend fun updateRecurrenceRule(taskId: String, rule: String) {
        requireWritable()
        JdbcFlows.onIo {
            val count = JdbcQueries.write(session, UPDATE_RECURRENCE_RULE_SQL, arrayOf(rule, taskId))
            logger.i(
                "ScoreRollupStoreJdbc.updateRecurrenceRule",
                "Updated recurrence rule",
                mapOf("taskId" to taskId, "rows" to count),
            )
        }
    }

    override suspend fun insertSetting(key: String, value: String) {
        requireWritable()
        JdbcFlows.onIo {
            val count = JdbcQueries.write(session, UPSERT_SETTING_SQL, arrayOf(key, value, java.time.LocalDateTime.now().toString()))
            logger.i("ScoreRollupStoreJdbc.insertSetting", "Inserted setting", mapOf("key" to key, "rows" to count))
        }
    }

    // ── Read-only guard ────────────────────────────────────────────────────

    /**
     * Refuses a write on a read-only session.
     *
     * The desktop's opener sets `PRAGMA query_only = ON` for a [DatabaseOpenMode.READ_ONLY]
     * session. The engine would reject the write with a SQLException, but the port's contract
     * is to refuse explicitly with a clear message — the import path opens the file
     * read-only, so this is a real case that must not surface as a driver exception.
     */
    private fun requireWritable() {
        if (session.mode == DatabaseOpenMode.READ_ONLY) {
            throw IllegalStateException(
                "ScoreRollupStoreJdbc: write attempted on a READ_ONLY session (mode=${session.mode})"
            )
        }
    }

    // ── SQL ────────────────────────────────────────────────────────────────

    private companion object {
        const val SOURCE = "ScoreRollupStoreJdbc"

        // Reads
        const val RECURRING_TASKS_SQL = "SELECT * FROM tasks WHERE recurrenceEnabled = 1"
        const val OCCURRENCES_FOR_TASK_SQL = "SELECT * FROM task_occurrences WHERE taskId = ? ORDER BY dueDate ASC"
        const val COUNT_OCCURRENCES_SQL = "SELECT COUNT(*) FROM task_occurrences"
        const val HABIT_METRICS_FOR_HABIT_SQL = "SELECT * FROM habit_metrics WHERE habitId = ? ORDER BY dayKey ASC"
        const val DIMENSION_METRICS_FOR_DIMENSION_SQL = "SELECT * FROM dimension_metrics WHERE dimensionId = ? ORDER BY dayKey ASC"
        const val ALL_DAY_METRICS_SQL = "SELECT * FROM day_metrics ORDER BY dayKey ASC"
        const val CONFIG_SQL = "SELECT * FROM scoring_config WHERE id = 1"
        const val SETTING_BY_KEY_SQL = "SELECT value FROM app_settings WHERE key = ?"

        // Writes
        const val UPSERT_HABIT_METRIC_SQL =
            "INSERT OR REPLACE INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        const val UPSERT_DIMENSION_METRIC_SQL =
            "INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        const val UPSERT_DAY_METRIC_SQL =
            "INSERT OR REPLACE INTO day_metrics (dayKey, dayScore, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (?, ?, ?, ?, ?, ?, ?)"
        const val UPDATE_RECURRENCE_RULE_SQL = "UPDATE tasks SET recurrenceRule = ? WHERE id = ?"
        const val UPSERT_SETTING_SQL = "INSERT OR REPLACE INTO app_settings (key, value, updatedAt) VALUES (?, ?, ?)"
    }
}

// ── Entity mappings (top-level so they are reachable from any file) ────────

internal fun ResultSet.readHabitMetric(): HabitMetricEntity =
    HabitMetricEntity(
        habitId = getString("habitId"),
        dayKey = getString("dayKey"),
        score = getDouble("score"),
        runningAvg = getDouble("runningAvg"),
        progress = getDouble("progress"),
        streakPos = getInt("streakPos"),
        streakNet = getInt("streakNet"),
        posContinue = getInt("posContinue"),
    )

internal fun ResultSet.readDimensionMetric(): DimensionMetricEntity =
    DimensionMetricEntity(
        dimensionId = getString("dimensionId"),
        dayKey = getString("dayKey"),
        score = getDouble("score"),
        runningAvg = getDouble("runningAvg"),
        progress = getDouble("progress"),
        streakPos = getInt("streakPos"),
        streakNet = getInt("streakNet"),
        posContinue = getInt("posContinue"),
    )

internal fun ResultSet.readDayMetric(): DayMetricEntity =
    DayMetricEntity(
        dayKey = getString("dayKey"),
        dayScore = getDouble("dayScore"),
        runningAvg = getDouble("runningAvg"),
        progress = getDouble("progress"),
        streakPos = getInt("streakPos"),
        streakNet = getInt("streakNet"),
        posContinue = getInt("posContinue"),
    )

internal fun ResultSet.readConfig(): ScoringConfigEntity =
    ScoringConfigEntity(
        id = getInt("id"),
        dimensionWeight = getDouble("dimensionWeight"),
        impactWeight = getDouble("impactWeight"),
        alignmentWeight = getDouble("alignmentWeight"),
        energyWeight = getDouble("energyWeight"),
        controlWeight = getDouble("controlWeight"),
        durationWeight = getDouble("durationWeight"),
        impactCritical = getDouble("impactCritical"),
        impactHigh = getDouble("impactHigh"),
        impactModerate = getDouble("impactModerate"),
        impactLow = getDouble("impactLow"),
        impactMinimal = getDouble("impactMinimal"),
        alignmentPerfect = getDouble("alignmentPerfect"),
        alignmentStrong = getDouble("alignmentStrong"),
        alignmentModerate = getDouble("alignmentModerate"),
        alignmentWeak = getDouble("alignmentWeak"),
        alignmentNone = getDouble("alignmentNone"),
        energyHigh = getDouble("energyHigh"),
        energyModerate = getDouble("energyModerate"),
        energyLow = getDouble("energyLow"),
        controlFull = getDouble("controlFull"),
        controlMostly = getDouble("controlMostly"),
        controlOffice = getDouble("controlOffice"),
        controlExternal = getDouble("controlExternal"),
        controlNone = getDouble("controlNone"),
        dimensionCareerWork = getDouble("dimensionCareerWork"),
        dimensionHealthWellness = getDouble("dimensionHealthWellness"),
        dimensionRelationships = getDouble("dimensionRelationships"),
        dimensionPersonalGrowth = getDouble("dimensionPersonalGrowth"),
        dimensionFinancial = getDouble("dimensionFinancial"),
        dimensionSpiritual = getDouble("dimensionSpiritual"),
        dimensionRecreation = getDouble("dimensionRecreation"),
        dimensionLearning = getDouble("dimensionLearning"),
        dimensionContribution = getDouble("dimensionContribution"),
        updatedAt = getString("updatedAt"),
    )

// ── Write helpers ─────────────────────────────────────────────────────────

private fun HabitMetricEntity.toHabitMetricBindings(): Array<String?> =
    arrayOf<String?>(
        habitId.toString(),
        dayKey.toString(),
        score.toString(),
        runningAvg.toString(),
        progress.toString(),
        streakPos.toString(),
        streakNet.toString(),
        posContinue.toString(),
    )

private fun DimensionMetricEntity.toDimensionMetricBindings(): Array<String?> =
    arrayOf<String?>(
        dimensionId.toString(),
        dayKey.toString(),
        score.toString(),
        runningAvg.toString(),
        progress.toString(),
        streakPos.toString(),
        streakNet.toString(),
        posContinue.toString(),
    )

private fun DayMetricEntity.toDayMetricBindings(): Array<String?> =
    arrayOf<String?>(
        dayKey.toString(),
        dayScore.toString(),
        runningAvg.toString(),
        progress.toString(),
        streakPos.toString(),
        streakNet.toString(),
        posContinue.toString(),
    )
