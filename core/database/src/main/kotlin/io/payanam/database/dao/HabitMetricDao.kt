//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.payanam.database.entity.HabitMetricEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitMetricDao {

    /** Projection for MAX(dayKey) GROUP BY habitId — avoids loading all rows. */
    data class HabitIdDayKey(
        val habitId: String,
        val maxDayKey: String,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<HabitMetricEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: HabitMetricEntity)

    @Query("DELETE FROM habit_metrics WHERE habitId = :habitId AND dayKey >= :fromDay")
    suspend fun deleteFrom(habitId: String, fromDay: String)

    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId ORDER BY dayKey ASC")
    suspend fun getForHabit(habitId: String): List<HabitMetricEntity>

    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId ORDER BY dayKey ASC")
    fun observeForHabit(habitId: String): Flow<List<HabitMetricEntity>>

    /** Window query for the activity detail view (Part C). */
    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId AND dayKey >= :start AND dayKey <= :end ORDER BY dayKey ASC")
    suspend fun getForHabitRange(habitId: String, start: String, end: String): List<HabitMetricEntity>

    @Query("SELECT * FROM habit_metrics")
    suspend fun getAll(): List<HabitMetricEntity>

    /** Max dayKey per habit — O(rows) GROUP BY instead of loading every row. */
    @Query("SELECT habitId, MAX(dayKey) AS maxDayKey FROM habit_metrics GROUP BY habitId")
    suspend fun maxDayKeyPerHabit(): List<HabitIdDayKey>

    /**
     * Latest metric row per habit (one row per habitId — the current L1 state).
     * SQLite picks an arbitrary row per group; since runningAvg/progress/streaks
     * are cumulative, the MAX(dayKey) row IS the latest state. Use a subquery
     * to make it deterministic.
     */
    @Query(
        """
        SELECT hm.* FROM habit_metrics hm
        INNER JOIN (
            SELECT habitId, MAX(dayKey) AS maxDay FROM habit_metrics GROUP BY habitId
        ) latest ON latest.habitId = hm.habitId AND latest.maxDay = hm.dayKey
        """,
    )
    suspend fun getLatestPerHabit(): List<HabitMetricEntity>

    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId AND dayKey < :dayKey ORDER BY dayKey DESC LIMIT 1")
    suspend fun latestBefore(habitId: String, dayKey: String): HabitMetricEntity?

    @Query("SELECT * FROM habit_metrics WHERE dayKey = :dayKey")
    suspend fun forDay(dayKey: String): List<HabitMetricEntity>

    @Query("SELECT COUNT(*) FROM habit_metrics")
    suspend fun count(): Int
}
