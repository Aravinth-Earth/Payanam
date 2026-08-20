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
/**
 * DayMetricDao.
 */
interface DayMetricDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Upsert all.
     */
    suspend fun upsertAll(rows: List<DayMetricEntity>)

    @Query("DELETE FROM day_metrics WHERE dayKey >= :fromDay")
    /**
     * Delete from.
     */
    suspend fun deleteFrom(fromDay: String)

    @Query("SELECT * FROM day_metrics ORDER BY dayKey ASC")
    /**
     * Observe all.
     */
    fun observeAll(): Flow<List<DayMetricEntity>>

    @Query("SELECT * FROM day_metrics ORDER BY dayKey ASC")
    /**
     * Get all.
     */
    suspend fun getAll(): List<DayMetricEntity>

    @Query("SELECT MIN(dayKey) FROM day_metrics")
    /**
     * Earliest day key.
     */
    suspend fun earliestDayKey(): String?

    @Query("SELECT * FROM day_metrics WHERE dayKey = :dayKey")
    /**
     * For day.
     */
    suspend fun forDay(dayKey: String): DayMetricEntity?

    @Query("SELECT * FROM day_metrics WHERE dayKey BETWEEN :start AND :end ORDER BY dayKey ASC")
    /**
     * Get for window.
     */
    suspend fun getForWindow(start: String, end: String): List<DayMetricEntity>

    @Query("SELECT * FROM day_metrics WHERE dayKey < :dayKey ORDER BY dayKey DESC LIMIT 1")
    /**
     * Latest before.
     */
    suspend fun latestBefore(dayKey: String): DayMetricEntity?

    @Query("SELECT COUNT(*) FROM day_metrics")
    /**
     * Count.
     */
    suspend fun count(): Int
}
