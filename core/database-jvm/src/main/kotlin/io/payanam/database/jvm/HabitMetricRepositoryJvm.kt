//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.HabitMetricEntity
import io.payanam.domain.model.HabitL1Summary
import io.payanam.domain.repository.HabitMetricRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet

/**
 * JDBC implementation of [HabitMetricRepository] over the encrypted database.
 *
 * The first of the repository interfaces to be ported, so it also establishes the pattern the rest
 * follow:
 *
 * - **The SQL is the Android `@Query` text, verbatim** where the driver can take it. This one is a
 *   read-only interface with no `IN (:list)` or `LIMIT :param` sites, so both queries port exactly.
 *   Where a query cannot port verbatim (Room expands `IN (:list)` at runtime, `LIMIT :limit` is
 *   bound as a parameter) the impl generates the placeholders and the agreement test records it.
 * - **Blocking JDBC is wrapped in [Dispatchers.IO].** The Android side gets this from Room; a raw
 *   `Connection` does not, so a suspend function that called it directly would block the caller's
 *   thread.
 * - **Rows map through the shared entity** from `:core:database-model`, then to the domain model —
 *   the same two-step the Android impl uses, so the mapping cannot drift between platforms.
 *
 * @param session an already-open session owned by the caller. Every read here runs on its single
 *   connection, which is why access is serialised by the session rather than pooled.
 */
class HabitMetricRepositoryJvm(
    private val session: PayanamDatabaseSession,
) : HabitMetricRepository {
    /**
     * The latest L1 row per habit.
     *
     * SQL ported verbatim from `HabitMetricDao.getLatestPerHabit`. The self-join against `MAX(dayKey)`
     * per habit is what makes the pick deterministic — SQLite's bare `GROUP BY` would otherwise choose
     * an arbitrary row, which for cumulative counters silently reports a stale score.
     */
    override suspend fun getLatestPerHabit(): Map<String, HabitL1Summary> =
        withContext(Dispatchers.IO) {
            val rows =
                JdbcQueries.read(session, LATEST_PER_HABIT_SQL, emptyArray()) {
                    it.readHabitMetrics()
                }
            AppLogger.current.d(SOURCE, "Read latest L1 rows", mapOf("count" to rows.size))
            rows.associate { it.habitId to it.toSummary() }
        }

    /**
     * The latest L1 row for one habit, or null.
     *
     * Deliberately implemented as a filter over [getLatestPerHabit] rather than a second query, so it
     * cannot disagree with the bulk reader about which row is "latest".
     */
    override suspend fun getLatestForHabit(habitId: String): HabitL1Summary? = getLatestPerHabit()[habitId]

    /** L1 rows for one habit in `[start]..[end]`, ascending — the activity-detail window. */
    override suspend fun getForHabitRange(
        habitId: String,
        start: String,
        end: String,
    ): List<HabitL1Summary> =
        withContext(Dispatchers.IO) {
            val rows =
                JdbcQueries.read(session, RANGE_SQL, arrayOf(habitId, start, end)) {
                    it.readHabitMetrics()
                }
            AppLogger.current.d(
                SOURCE,
                "Read L1 rows for a window",
                mapOf("rows" to rows.size),
            )
            rows.map { it.toSummary() }
        }

    private fun ResultSet.readHabitMetrics(): List<HabitMetricEntity> =
        buildList {
            while (next()) {
                add(
                    HabitMetricEntity(
                        habitId = getString("habitId"),
                        dayKey = getString("dayKey"),
                        score = getDouble("score"),
                        runningAvg = getDouble("runningAvg"),
                        progress = getDouble("progress"),
                        streakPos = getInt("streakPos"),
                        streakNet = getInt("streakNet"),
                        posContinue = getInt("posContinue"),
                    ),
                )
            }
        }

    private companion object {
        const val SOURCE = "HabitMetricRepositoryJvm"

        /** Verbatim from `HabitMetricDao.getLatestPerHabit`. */
        const val LATEST_PER_HABIT_SQL = """
            SELECT hm.* FROM habit_metrics hm
            INNER JOIN (
                SELECT habitId, MAX(dayKey) AS maxDay FROM habit_metrics GROUP BY habitId
            ) latest ON latest.habitId = hm.habitId AND latest.maxDay = hm.dayKey
        """

        /** Verbatim from `HabitMetricDao.getForHabitRange`. */
        const val RANGE_SQL =
            "SELECT * FROM habit_metrics WHERE habitId = ? AND dayKey >= ? AND dayKey <= ? ORDER BY dayKey ASC"
    }
}

/**
 * Maps a persisted habit-metric row to the domain summary.
 *
 * Mirrors the private `toSummary()` in Android's `HabitMetricRepositoryImpl` field for field. It is
 * duplicated rather than shared because the Android one is private to that class and lives in a
 * module this one cannot depend on; if a field is ever added, both must change, and the
 * cross-platform equivalence check is what catches the miss.
 */
private fun HabitMetricEntity.toSummary() =
    HabitL1Summary(
        habitId = habitId,
        dayKey = dayKey,
        score = score,
        runningAvg = runningAvg,
        progress = progress,
        streakPos = streakPos,
        streakNet = streakNet,
        posContinue = posContinue,
    )
