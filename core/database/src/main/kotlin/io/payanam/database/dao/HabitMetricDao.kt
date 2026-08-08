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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<HabitMetricEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: HabitMetricEntity)

    @Query("DELETE FROM habit_metrics WHERE habitId = :habitId AND dayKey >= :fromDay")
    suspend fun deleteFrom(habitId: String, fromDay: String)

    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId ORDER BY dayKey ASC")
    fun observeForHabit(habitId: String): Flow<List<HabitMetricEntity>>

    @Query("SELECT * FROM habit_metrics WHERE habitId = :habitId AND dayKey < :dayKey ORDER BY dayKey DESC LIMIT 1")
    suspend fun latestBefore(habitId: String, dayKey: String): HabitMetricEntity?

    @Query("SELECT * FROM habit_metrics WHERE dayKey = :dayKey")
    suspend fun forDay(dayKey: String): List<HabitMetricEntity>

    @Query("SELECT COUNT(*) FROM habit_metrics")
    suspend fun count(): Int
}
