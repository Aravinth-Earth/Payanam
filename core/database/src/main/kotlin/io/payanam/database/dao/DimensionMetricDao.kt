//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.payanam.database.entity.DimensionMetricEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DimensionMetricDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DimensionMetricEntity>)

    @Query("SELECT MIN(dayKey) FROM dimension_metrics WHERE dimensionId = :dimensionId")
    suspend fun earliestDayKey(dimensionId: String): String?

    @Query("SELECT MIN(dayKey) FROM dimension_metrics")
    suspend fun earliestDayKeyGlobal(): String?

    @Query("DELETE FROM dimension_metrics WHERE dimensionId = :dimensionId AND dayKey >= :fromDay")
    suspend fun deleteFrom(dimensionId: String, fromDay: String)

    @Query("SELECT * FROM dimension_metrics ORDER BY dayKey ASC")
    fun observeAll(): Flow<List<DimensionMetricEntity>>

    @Query("SELECT * FROM dimension_metrics")
    suspend fun getAll(): List<DimensionMetricEntity>

    @Query("SELECT * FROM dimension_metrics WHERE dimensionId = :dimensionId ORDER BY dayKey ASC")
    fun observeForDimension(dimensionId: String): Flow<List<DimensionMetricEntity>>

    @Query("SELECT * FROM dimension_metrics WHERE dimensionId = :dimensionId AND dayKey < :dayKey ORDER BY dayKey DESC LIMIT 1")
    suspend fun latestBefore(dimensionId: String, dayKey: String): DimensionMetricEntity?

    @Query("SELECT * FROM dimension_metrics WHERE dayKey = :dayKey")
    suspend fun forDay(dayKey: String): List<DimensionMetricEntity>

    @Query("SELECT * FROM dimension_metrics WHERE dayKey BETWEEN :start AND :end ORDER BY dayKey ASC")
    suspend fun getForWindow(start: String, end: String): List<DimensionMetricEntity>

    @Query("SELECT COUNT(*) FROM dimension_metrics")
    suspend fun count(): Int
}
