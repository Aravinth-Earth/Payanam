//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.domain.model.DayMetricRow
import io.payanam.domain.model.DimensionMetricRow
import io.payanam.domain.model.MetricWindowRow
import io.payanam.domain.repository.ScoreWindowRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet

/**
 * JDBC implementation of [ScoreWindowRepository] over the encrypted database.
 *
 * Follows the pattern established by [HabitMetricRepositoryJvm]: the SQL is the Android `@Query`
 * text verbatim (all five port exactly — no `IN (:list)`, no bound `LIMIT`), blocking JDBC is wrapped
 * in [Dispatchers.IO], and rows are read through the shared entity layer.
 *
 * Dimension rows and the DAY row carry the same six self-governance metrics and surface through the
 * shared [MetricWindowRow] contract, which is why one detail layout can render either.
 *
 * @param session an already-open session owned by the caller.
 */
class ScoreWindowRepositoryJvm(
    private val session: PayanamDatabaseSession,
) : ScoreWindowRepository {
    /** Dimension rows for every day in the inclusive window, ascending. */
    override suspend fun getDimensionWindow(
        start: String,
        end: String,
    ): List<MetricWindowRow> =
        withContext(Dispatchers.IO) {
            val rows =
                session.read(DIMENSION_WINDOW_SQL, start, end) { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                DimensionMetricRow(
                                    dimensionId = resultSet.getString("dimensionId"),
                                    dayKey = resultSet.getString("dayKey"),
                                    score = resultSet.getDouble("score"),
                                    runningAvg = resultSet.getDouble("runningAvg"),
                                    progress = resultSet.getDouble("progress"),
                                    streakPos = resultSet.getInt("streakPos"),
                                    streakNet = resultSet.getInt("streakNet"),
                                    posContinue = resultSet.getInt("posContinue"),
                                ),
                            )
                        }
                    }
                }
            AppLogger.current.d(SOURCE, "Loaded dimension window", mapOf("rows" to rows.size))
            rows
        }

    /** DAY rows (one per day) for the inclusive window, ascending. */
    override suspend fun getDayWindow(
        start: String,
        end: String,
    ): List<MetricWindowRow> =
        withContext(Dispatchers.IO) {
            val rows =
                session.read(DAY_WINDOW_SQL, start, end) { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                DayMetricRow(
                                    dayKey = resultSet.getString("dayKey"),
                                    // The column is `dayScore`; DayMetricRow calls the field `score`.
                                    score = resultSet.getDouble("dayScore"),
                                    runningAvg = resultSet.getDouble("runningAvg"),
                                    progress = resultSet.getDouble("progress"),
                                    streakPos = resultSet.getInt("streakPos"),
                                    streakNet = resultSet.getInt("streakNet"),
                                    posContinue = resultSet.getInt("posContinue"),
                                ),
                            )
                        }
                    }
                }
            AppLogger.current.d(SOURCE, "Loaded day window", mapOf("rows" to rows.size))
            rows
        }

    /** Earliest logged day across all habits, or null when the table is empty. */
    override suspend fun earliestDayKey(): String? =
        withContext(Dispatchers.IO) {
            session.read(EARLIEST_DAY_SQL) { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    /** Earliest logged day for one dimension's mapped habits, or null. */
    override suspend fun earliestDimensionDayKey(dimensionId: String): String? =
        withContext(Dispatchers.IO) {
            session.read(EARLIEST_DIMENSION_SQL, dimensionId) { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    /** Earliest logged day across all dimensions, or null. */
    override suspend fun earliestDimensionDayKey(): String? =
        withContext(Dispatchers.IO) {
            session.read(EARLIEST_DIMENSION_GLOBAL_SQL) { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    /**
     * Runs one query and maps its rows through the shared helper.
     *
     * `MIN(dayKey)` over an empty table returns a single row holding SQL NULL, so the earliest-day
     * readers ask for `getString(1)` and receive null — they must not be written as "no rows means
     * empty", which would be a different case.
     */
    private fun <T> PayanamDatabaseSession.read(
        sql: String,
        vararg parameters: String,
        map: (ResultSet) -> T,
    ): T = JdbcQueries.read(this, sql, parameters, map)

    private companion object {
        const val SOURCE = "ScoreWindowRepositoryJvm"

        /** Verbatim from `DimensionMetricDao.getForWindow`. */
        const val DIMENSION_WINDOW_SQL =
            "SELECT * FROM dimension_metrics WHERE dayKey BETWEEN ? AND ? ORDER BY dayKey ASC"

        /** Verbatim from `DayMetricDao.getForWindow`. */
        const val DAY_WINDOW_SQL =
            "SELECT * FROM day_metrics WHERE dayKey BETWEEN ? AND ? ORDER BY dayKey ASC"

        /** Verbatim from `DayMetricDao.earliestDayKey`. */
        const val EARLIEST_DAY_SQL = "SELECT MIN(dayKey) FROM day_metrics"

        /** Verbatim from `DimensionMetricDao.earliestDayKey`. */
        const val EARLIEST_DIMENSION_SQL =
            "SELECT MIN(dayKey) FROM dimension_metrics WHERE dimensionId = ?"

        /** Verbatim from `DimensionMetricDao.earliestDayKeyGlobal`. */
        const val EARLIEST_DIMENSION_GLOBAL_SQL = "SELECT MIN(dayKey) FROM dimension_metrics"
    }
}
