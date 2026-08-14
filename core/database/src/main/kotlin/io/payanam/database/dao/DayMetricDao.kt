//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.payanam.database.entity.DayMetricEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DayMetricDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DayMetricEntity>)

    @Query("DELETE FROM day_metrics WHERE dayKey >= :fromDay")
    suspend fun deleteFrom(fromDay: String)

    @Query("SELECT * FROM day_metrics ORDER BY dayKey ASC")
    fun observeAll(): Flow<List<DayMetricEntity>>

    @Query("SELECT * FROM day_metrics ORDER BY dayKey ASC")
    suspend fun getAll(): List<DayMetricEntity>

    @Query("SELECT MIN(dayKey) FROM day_metrics")
    suspend fun earliestDayKey(): String?

    @Query("SELECT * FROM day_metrics WHERE dayKey = :dayKey")
    suspend fun forDay(dayKey: String): DayMetricEntity?

    @Query("SELECT * FROM day_metrics WHERE dayKey BETWEEN :start AND :end ORDER BY dayKey ASC")
    suspend fun getForWindow(start: String, end: String): List<DayMetricEntity>

    @Query("SELECT * FROM day_metrics WHERE dayKey < :dayKey ORDER BY dayKey DESC LIMIT 1")
    suspend fun latestBefore(dayKey: String): DayMetricEntity?

    @Query("SELECT COUNT(*) FROM day_metrics")
    suspend fun count(): Int
}
