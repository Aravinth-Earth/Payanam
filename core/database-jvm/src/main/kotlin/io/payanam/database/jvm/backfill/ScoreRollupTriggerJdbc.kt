//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements", "LongMethod", "TooManyFunctions")

package io.payanam.database.jvm.backfill

import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.jvm.JdbcFlows
import io.payanam.database.jvm.PayanamDatabaseSession
import io.payanam.database.jvm.event.ScoreChangeEventBusJvm
import io.payanam.database.jvm.readOccurrence
import io.payanam.database.jvm.readTask
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.scoring.MetricBaseline
import io.payanam.scoring.ScoreRollupCompute
import io.payanam.shared.logging.AppLogger
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate

/**
 * JDBC implementation of [ScoreRollupTrigger] — recomputes score roll-up metrics when
 * a task's status or recurrence rule changes, or when a day-level recalculation is needed.
 *
 * Each method follows the same transaction idiom as [ScoreRollupBackfillJdbc]:
 * [JdbcFlows.onIo] → [PayanamDatabaseSession.withSerializedAccess] → `autoCommit = false`
 * → explicit commit/rollback.
 *
 * The cascade is L1 → L2 → L3:
 * - L1 (`habit_metrics`): per-habit sparse due-day rows
 * - L2 (`dimension_metrics`): per-dimension dense rows (equal-weighted mean of member habits)
 * - L3 (`day_metrics`): per-day dense rows (dimension-weighted mean)
 *
 * @param session an already-open session owned by the caller.
 */
class ScoreRollupTriggerJdbc(
    private val session: PayanamDatabaseSession,
    private val eventBus: ScoreChangeEventBusJvm,
) : ScoreRollupTrigger {
    private val logger = AppLogger.current

    override suspend fun recalcForStatusChange(
        taskId: String,
        date: LocalDate,
    ) {
        JdbcFlows.onIo {
            session.withSerializedAccess { connection ->
                val tag = "ScoreRollupTriggerJdbc.recalcForStatusChange"
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val task = readTaskById(connection, taskId)
                    if (task == null) {
                        logger.d(tag, "TASK_NOT_FOUND", mapOf("taskId" to taskId))
                        return@withSerializedAccess
                    }

                    if (task.recurrenceEnabled != 1) {
                        // Android's cascade returns without writes for non-recurring tasks; parity.
                        return@withSerializedAccess
                    }

                    val occurrences = readOccurrencesByTaskId(connection, task.id)
                    val existingHabitRows = readHabitMetricsByHabitId(connection, task.id)
                    val baseline =
                        MetricBaseline.fromHabitRows(
                            existingHabitRows
                                .filter { it.dayKey < date.toString() }
                                // Android parity: HabitMetricDao ORDER BY dayKey ASC — baseline sorted by dayKey.
                                .sortedBy { it.dayKey },
                        )

                    val (newHabitRows, _, _) =
                        ScoreRollupCompute.buildHabitMetricsFrom(
                            task = task,
                            occurrences = occurrences,
                            fromDay = date,
                            // Android's ScoreRollupCascadeService.recalcForStatusChange passes
                            // includeToday=true ('changeDay → today' tail). Desktop must match:
                            // a status change on today must produce today's habit_metrics row.
                            includeToday = true,
                            baseline = baseline,
                        )

                    deleteHabitMetricsFrom(connection, task.id, date)
                    val habitWritten = upsertHabitMetrics(connection, newHabitRows)
                    logger.i(
                        tag,
                        "L1_REBUILT",
                        mapOf(
                            "taskId" to task.id,
                            "fromDate" to date.toString(),
                            "rows" to habitWritten,
                        ),
                    )

                    // Parity: Android's recalcForStatusChange filters L2 members to the
                    // changed task's dimension (task.dimensionId ?: "dim_unassigned").
                    cascadeL2L3(connection, date, task.dimensionId ?: "dim_unassigned")
                    connection.commit()
                    eventBus.emit(date)
                } catch (error: SQLException) {
                    runCatching { connection.rollback() }
                    logger.e(
                        tag,
                        "RECALC_FAILED",
                        error,
                        mapOf(
                            "taskId" to taskId,
                            "date" to date.toString(),
                            "error" to (error.message ?: "Unknown error"),
                        ),
                    )
                } finally {
                    runCatching { connection.autoCommit = previousAutoCommit }
                }
            }
        }
    }

    override suspend fun recalcForRuleChange(taskId: String) {
        JdbcFlows.onIo {
            session.withSerializedAccess { connection ->
                val tag = "ScoreRollupTriggerJdbc.recalcForRuleChange"
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val task = readTaskById(connection, taskId)
                    if (task == null) {
                        logger.d(tag, "TASK_NOT_FOUND", mapOf("taskId" to taskId))
                        return@withSerializedAccess
                    }

                    val occurrences = readOccurrencesByTaskId(connection, task.id)

                    // Android's cascade returns without writes for non-recurring tasks; parity.
                    if (task.recurrenceEnabled != 1) return@withSerializedAccess

                    val (newHabitRows, firstDue, _) =
                        ScoreRollupCompute.buildHabitMetrics(
                            task = task,
                            occurrences = occurrences,
                            includeToday = false,
                        )

                    deleteAllHabitMetrics(connection, task.id)
                    val habitWritten = upsertHabitMetrics(connection, newHabitRows)
                    logger.i(
                        tag,
                        "L1_REBUILT",
                        mapOf(
                            "taskId" to task.id,
                            "firstDue" to (firstDue?.toString() ?: "null"),
                            "rows" to habitWritten,
                        ),
                    )

                    val fromDay = firstDue ?: LocalDate.now()
                    // Parity: Android's recalcForRuleChange filters L2 members to the
                    // changed task's dimension.
                    cascadeL2L3(connection, fromDay, task.dimensionId ?: "dim_unassigned")
                    connection.commit()
                    eventBus.emit(fromDay)
                } catch (error: SQLException) {
                    runCatching { connection.rollback() }
                    logger.e(
                        tag,
                        "RECALC_FAILED",
                        error,
                        mapOf(
                            "taskId" to taskId,
                            "error" to (error.message ?: "Unknown error"),
                        ),
                    )
                } finally {
                    runCatching { connection.autoCommit = previousAutoCommit }
                }
            }
        }
    }

    override suspend fun recalcDayOnly(changeDate: LocalDate) {
        JdbcFlows.onIo {
            session.withSerializedAccess { connection ->
                val tag = "ScoreRollupTriggerJdbc.recalcDayOnly"
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    // Parity: Android's recalcDayOnly reads dimDao.getAll() and derives
                    // fromDay across all dimension rows — L3 spans all dimensions.
                    cascadeL2L3(connection, changeDate)
                    connection.commit()
                    eventBus.emit(changeDate)
                    logger.i(
                        tag,
                        "DAY_RECALC_DONE",
                        mapOf(
                            "changeDate" to changeDate.toString(),
                        ),
                    )
                } catch (error: SQLException) {
                    runCatching { connection.rollback() }
                    logger.e(
                        tag,
                        "RECALC_FAILED",
                        error,
                        mapOf(
                            "changeDate" to changeDate.toString(),
                            "error" to (error.message ?: "Unknown error"),
                        ),
                    )
                } finally {
                    runCatching { connection.autoCommit = previousAutoCommit }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Cascade: L2 (dimension metrics) + L3 (day metrics)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Rebuilds L2 and L3 from [fromDay] onward. L1 is assumed to be already correct
     * (either unchanged, or recomputed by the caller before this is called).
     *
     * L2 is rebuilt per-dimension, each seeded from its own baseline (rows before [fromDay]).
     * L3 is rebuilt from the updated L2 rows, seeded from the day baseline.
     *
     * When [dimensionId] is non-null, only that dimension's L2 rows are rebuilt (task-scoped
     * paths — parity with Android's ScoreRollupCascadeService.recalcForStatusChange and
     * recalcForRuleChange, which filter members to the changed task's dimension). When null,
     * all dimensions are rebuilt (day-only path — parity with Android's recalcDayOnly,
     * which reads dimDao.getAll() and derives fromDay across all dimension rows).
     */
    private fun cascadeL2L3(
        connection: Connection,
        fromDay: LocalDate,
        dimensionId: String? = null,
    ) {
        val recurring = readRecurringTasks(connection)
        val allHabitRows = readAllHabitMetrics(connection)

        val existingDimRows = readAllDimensionMetrics(connection)
        val newDimRows = mutableListOf<DimensionMetricEntity>()
        val dimIds =
            if (dimensionId != null) {
                listOf(dimensionId)
            } else {
                recurring.map { it.dimensionId ?: "dim_unassigned" }.distinct()
            }
        for (dimId in dimIds) {
            // Android parity: exclude archived tasks from L2 members.
            val dimTasks = recurring.filter { it.status != "archived" && (it.dimensionId ?: "dim_unassigned") == dimId }
            val dimHabitRows = allHabitRows.filter { row -> dimTasks.any { it.id == row.habitId } }
            val dimFirstDue =
                dimHabitRows
                    .groupBy { it.habitId }
                    .mapValues { (_, rows) -> LocalDate.parse(rows.minOf { it.dayKey }) }
            val dimBaseline =
                MetricBaseline.fromDimensionRows(
                    existingDimRows
                        .filter { it.dimensionId == dimId && it.dayKey < fromDay.toString() }
                        // Android parity: baseline sorted by dayKey.
                        .sortedBy { it.dayKey },
                )
            // Parity: Android seeds L2 rebuild with each member habit's last L1 score
            // before the change day (carry-forward seed for the rolling average).
            val lastScores =
                dimHabitRows
                    .groupBy { it.habitId }
                    .mapValues { (_, rs) -> rs.filter { it.dayKey < fromDay.toString() }.maxByOrNull { it.dayKey } }
                    .filterValues { it != null }
                    .mapValues { it.value!!.score }
            val dimNewRows =
                ScoreRollupCompute.buildDimensionMetricsFrom(
                    recurring = dimTasks,
                    firstDuePerHabit = dimFirstDue,
                    habitRows = dimHabitRows,
                    fromDay = fromDay,
                    // Android parity: includeToday=true — L2 tail includes today.
                    includeToday = true,
                    baseline = dimBaseline,
                    lastScores = lastScores,
                )
            newDimRows += dimNewRows
        }

        if (dimensionId != null) {
            deleteDimensionMetricsFrom(connection, dimensionId, fromDay)
        } else {
            deleteDimensionMetricsFrom(connection, fromDay)
        }
        val dimWritten = upsertDimensionMetrics(connection, newDimRows)
        logger.i(
            "ScoreRollupTriggerJdbc.cascadeL2L3",
            "L2_REBUILT",
            mapOf(
                "fromDate" to fromDay.toString(),
                "rows" to dimWritten,
            ),
        )

        val allDimRows = readAllDimensionMetrics(connection)
        val dimWeights = readDimensionWeights(connection)
        val existingDayRows = readAllDayMetrics(connection)
        val dayBaseline =
            MetricBaseline.fromDayRows(
                existingDayRows.filter { it.dayKey < fromDay.toString() },
            )
        val newDayRows =
            ScoreRollupCompute.buildDayMetricsFrom(
                dimensionRows = allDimRows,
                fromDay = fromDay,
                baseline = dayBaseline,
                dimWeights = dimWeights,
            )

        deleteDayMetricsFrom(connection, fromDay)
        val dayWritten = upsertDayMetrics(connection, newDayRows)
        logger.i(
            "ScoreRollupTriggerJdbc.cascadeL2L3",
            "L3_REBUILT",
            mapOf(
                "fromDate" to fromDay.toString(),
                "rows" to dayWritten,
            ),
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // SQL — reads
    // ────────────────────────────────────────────────────────────────────

    private fun readTaskById(
        connection: Connection,
        taskId: String,
    ): TaskEntity? =
        connection.prepareStatement(TASK_BY_ID_SQL).use { prepared ->
            prepared.setString(1, taskId)
            prepared.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.readTask() else null
            }
        }

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

    private fun readOccurrencesByTaskId(
        connection: Connection,
        taskId: String,
    ): List<TaskOccurrenceEntity> =
        connection.prepareStatement(OCCURRENCES_BY_TASK_ID_SQL).use { prepared ->
            prepared.setString(1, taskId)
            prepared.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.readOccurrence())
                    }
                }
            }
        }

    private fun readHabitMetricsByHabitId(
        connection: Connection,
        habitId: String,
    ): List<HabitMetricEntity> =
        connection.prepareStatement(HABIT_METRICS_BY_HABIT_ID_SQL).use { prepared ->
            prepared.setString(1, habitId)
            prepared.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(readHabitMetric(resultSet))
                    }
                }
            }
        }

    private fun readAllHabitMetrics(connection: Connection): List<HabitMetricEntity> =
        connection.prepareStatement(ALL_HABIT_METRICS_SQL).use { prepared ->
            prepared.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(readHabitMetric(resultSet))
                    }
                }
            }
        }

    private fun readAllDimensionMetrics(connection: Connection): List<DimensionMetricEntity> =
        connection.prepareStatement(ALL_DIMENSION_METRICS_SQL).use { prepared ->
            prepared.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(readDimensionMetric(resultSet))
                    }
                }
            }
        }

    private fun readAllDayMetrics(connection: Connection): List<DayMetricEntity> =
        connection.prepareStatement(ALL_DAY_METRICS_SQL).use { prepared ->
            prepared.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(readDayMetric(resultSet))
                    }
                }
            }
        }

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

    private fun readMaxDayKeyPerHabit(connection: Connection): Map<String, String> =
        connection.prepareStatement(MAX_DAY_KEY_PER_HABIT_SQL).use { prepared ->
            prepared.executeQuery().use { resultSet ->
                val result = mutableMapOf<String, String>()
                while (resultSet.next()) {
                    val habitId = resultSet.getString("habitId") ?: continue
                    val maxDayKey = resultSet.getString("dayKey") ?: continue
                    result[habitId] = maxDayKey
                }
                result
            }
        }

    // ────────────────────────────────────────────────────────────────────
    // SQL — entity readers
    // ────────────────────────────────────────────────────────────────────

    private fun readHabitMetric(resultSet: ResultSet): HabitMetricEntity =
        HabitMetricEntity(
            habitId = resultSet.getString("habitId"),
            dayKey = resultSet.getString("dayKey"),
            score = resultSet.getDouble("score"),
            runningAvg = resultSet.getDouble("runningAvg"),
            progress = resultSet.getDouble("progress"),
            streakPos = resultSet.getInt("streakPos"),
            streakNet = resultSet.getInt("streakNet"),
            posContinue = resultSet.getInt("posContinue"),
        )

    private fun readDimensionMetric(resultSet: ResultSet): DimensionMetricEntity =
        DimensionMetricEntity(
            dimensionId = resultSet.getString("dimensionId"),
            dayKey = resultSet.getString("dayKey"),
            score = resultSet.getDouble("score"),
            runningAvg = resultSet.getDouble("runningAvg"),
            progress = resultSet.getDouble("progress"),
            streakPos = resultSet.getInt("streakPos"),
            streakNet = resultSet.getInt("streakNet"),
            posContinue = resultSet.getInt("posContinue"),
        )

    private fun readDayMetric(resultSet: ResultSet): DayMetricEntity =
        DayMetricEntity(
            dayKey = resultSet.getString("dayKey"),
            dayScore = resultSet.getDouble("dayScore"),
            runningAvg = resultSet.getDouble("runningAvg"),
            progress = resultSet.getDouble("progress"),
            streakPos = resultSet.getInt("streakPos"),
            streakNet = resultSet.getInt("streakNet"),
            posContinue = resultSet.getInt("posContinue"),
        )

    // ────────────────────────────────────────────────────────────────────
    // SQL — writes
    // ────────────────────────────────────────────────────────────────────

    private fun deleteHabitMetricsFrom(
        connection: Connection,
        habitId: String,
        fromDay: LocalDate,
    ) {
        connection.prepareStatement(DELETE_HABIT_METRICS_FROM_SQL).use { prepared ->
            prepared.setString(1, habitId)
            prepared.setString(2, fromDay.toString())
            prepared.executeUpdate()
        }
    }

    private fun deleteAllHabitMetrics(
        connection: Connection,
        habitId: String,
    ) {
        connection.prepareStatement(DELETE_ALL_HABIT_METRICS_SQL).use { prepared ->
            prepared.setString(1, habitId)
            prepared.executeUpdate()
        }
    }

    private fun deleteDimensionMetricsFrom(
        connection: Connection,
        fromDay: LocalDate,
    ) {
        connection.prepareStatement(DELETE_DIMENSION_METRICS_FROM_SQL).use { prepared ->
            prepared.setString(1, fromDay.toString())
            prepared.executeUpdate()
        }
    }

    private fun deleteDimensionMetricsFrom(
        connection: Connection,
        dimensionId: String,
        fromDay: LocalDate,
    ) {
        connection.prepareStatement(DELETE_DIMENSION_METRICS_BY_DIM_FROM_SQL).use { prepared ->
            prepared.setString(1, dimensionId)
            prepared.setString(2, fromDay.toString())
            prepared.executeUpdate()
        }
    }

    private fun deleteDayMetricsFrom(
        connection: Connection,
        fromDay: LocalDate,
    ) {
        connection.prepareStatement(DELETE_DAY_METRICS_FROM_SQL).use { prepared ->
            prepared.setString(1, fromDay.toString())
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
        const val TASK_BY_ID_SQL = "SELECT * FROM tasks WHERE id = ?"

        const val RECURRING_TASKS_SQL = "SELECT * FROM tasks WHERE recurrenceEnabled = 1 AND status != 'archived'"

        const val OCCURRENCES_BY_TASK_ID_SQL = "SELECT * FROM task_occurrences WHERE taskId = ? ORDER BY dueDate ASC"

        const val HABIT_METRICS_BY_HABIT_ID_SQL = "SELECT * FROM habit_metrics WHERE habitId = ?"

        const val ALL_HABIT_METRICS_SQL = "SELECT * FROM habit_metrics"

        const val ALL_DIMENSION_METRICS_SQL = "SELECT * FROM dimension_metrics"

        const val ALL_DAY_METRICS_SQL = "SELECT * FROM day_metrics"

        const val DIMENSION_WEIGHTS_SQL = "SELECT id, weight FROM life_dimensions"

        const val MAX_DAY_KEY_PER_HABIT_SQL = "SELECT habitId, MAX(dayKey) as dayKey FROM habit_metrics GROUP BY habitId"

        const val DELETE_HABIT_METRICS_FROM_SQL = "DELETE FROM habit_metrics WHERE habitId = ? AND dayKey >= ?"

        const val DELETE_ALL_HABIT_METRICS_SQL = "DELETE FROM habit_metrics WHERE habitId = ?"

        const val DELETE_DIMENSION_METRICS_FROM_SQL = "DELETE FROM dimension_metrics WHERE dayKey >= ?"

        const val DELETE_DIMENSION_METRICS_BY_DIM_FROM_SQL = "DELETE FROM dimension_metrics WHERE dimensionId = ? AND dayKey >= ?"

        const val DELETE_DAY_METRICS_FROM_SQL = "DELETE FROM day_metrics WHERE dayKey >= ?"

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

        private const val COLUMN_HABIT_ID = 1
        private const val COLUMN_DIMENSION_ID = 1
        private const val COLUMN_DAY_KEY = 2
        private const val COLUMN_SCORE = 3
        private const val COLUMN_RUNNING_AVG = 4
        private const val COLUMN_PROGRESS = 5
        private const val COLUMN_STREAK_POS = 6
        private const val COLUMN_STREAK_NET = 7
        private const val COLUMN_POS_CONTINUE = 8

        private const val COLUMN_DAY_KEY_DAY_METRIC = 1
        private const val COLUMN_DAY_SCORE_DAY_METRIC = 2
        private const val COLUMN_DAY_RUNNING_AVG = 3
        private const val COLUMN_DAY_PROGRESS = 4
        private const val COLUMN_DAY_STREAK_POS = 5
        private const val COLUMN_DAY_STREAK_NET = 6
        private const val COLUMN_DAY_POS_CONTINUE = 7
    }
}
