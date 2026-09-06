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
/**
 * Room DAO for the `habit_metrics` time-series table: one [HabitMetricEntity]
 * per habit per day holding cumulative L1 state (running average, progress,
 * streaks). Because the values are cumulative, the row with the maximum
 * `dayKey` for a habit is its current state.
 */
interface HabitMetricDao {

    /** Projection for MAX(dayKey) GROUP BY habitId — avoids loading all rows. */
    data class HabitIdDayKey(
        val habitId: String,
        val maxDayKey: String,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a batch of habit-metric rows.
     */
    suspend fun upsertAll(rows: List<HabitMetricEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a single habit-metric row.
     */
    suspend fun upsert(row: HabitMetricEntity)

    @Query("DELETE FROM habit_metrics WHERE habitId = :habitId AND dayKey >= :fromDay")
    /**
     * Deletes metric rows for [habitId] whose `dayKey` is [fromDay] or later.
     * Used to truncate a corrupted tail before recomputing.
     */
    suspend fun deleteFrom(habitId: String, fromDay: String)

    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId ORDER BY dayKey ASC")
    /**
     * Returns every metric row for [habitId], ordered chronologically.
     */
    suspend fun getForHabit(habitId: String): List<HabitMetricEntity>

    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId ORDER BY dayKey ASC")
    /**
     * Emits every metric row for [habitId], chronologically, as a [Flow].
     */
    fun observeForHabit(habitId: String): Flow<List<HabitMetricEntity>>

    /** Window query for the activity detail view (Part C). */
    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId AND dayKey >= :start AND dayKey <= :end ORDER BY dayKey ASC")
    /**
     * Returns metric rows for [habitId] within the inclusive [start]..[end]
     * window, ordered chronologically — used by the activity detail view.
     */
    suspend fun getForHabitRange(habitId: String, start: String, end: String): List<HabitMetricEntity>

    @Query("SELECT * FROM habit_metrics")
    /**
     * Returns every habit-metric row across all habits.
     */
    suspend fun getAll(): List<HabitMetricEntity>

    /** Max dayKey per habit — O(rows) GROUP BY instead of loading every row. */
    @Query("SELECT habitId, MAX(dayKey) AS maxDayKey FROM habit_metrics GROUP BY habitId")
    /**
     * Returns the latest `dayKey` for each habit, used to locate each habit's
     * current (cumulative) state without loading the full history.
     */
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
    /**
     * Returns the most recent metric row for every habit (its current L1 state).
     * The join against MAX(dayKey) per habit makes the single row per habit
     * deterministic rather than relying on SQLite's arbitrary group pick.
     */
    suspend fun getLatestPerHabit(): List<HabitMetricEntity>

    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId AND dayKey < :dayKey ORDER BY dayKey DESC LIMIT 1")
    /**
     * Returns the most recent metric row for [habitId] strictly before
     * [dayKey], or null. Used to compute deltas against the prior day.
     */
    suspend fun latestBefore(habitId: String, dayKey: String): HabitMetricEntity?

    @Query("SELECT * FROM habit_metrics WHERE dayKey = :dayKey")
    /**
     * Returns every habit's metric row for [dayKey] (all habits that recorded
     * that day).
     */
    suspend fun forDay(dayKey: String): List<HabitMetricEntity>

    @Query("SELECT COUNT(*) FROM habit_metrics")
    /**
     * Returns the total number of habit-metric rows.
     */
    suspend fun count(): Int
}
