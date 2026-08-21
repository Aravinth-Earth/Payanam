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
/**
 * Defines the contract for dimension metric dao.
 */
interface DimensionMetricDao {

    /** Insert or replace all [rows] (conflict = REPLACE). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the upsert all.
     */
    suspend fun upsertAll(rows: List<DimensionMetricEntity>)

    /**
     * Earliest day key for one dimension's mapped habits.
     *
     * @param dimensionId The dimension whose earliest day is requested.
     * @return Earliest dayKey string, or null if the dimension has no rows.
     */
    @Query("SELECT MIN(dayKey) FROM dimension_metrics WHERE dimensionId = :dimensionId")
    /**
     * Performs the earliest day key.
     */
    suspend fun earliestDayKey(dimensionId: String): String?

    /** Earliest day key across all dimension rows (global, no dimension filter). */
    @Query("SELECT MIN(dayKey) FROM dimension_metrics")
    /**
     * Performs the earliest day key global.
     */
    suspend fun earliestDayKeyGlobal(): String?

    /**
     * Delete a dimension's rows from [fromDay] onward (inclusive) so they can
     * be recomputed by the cascade.
     *
     * @param dimensionId Target dimension.
     * @param fromDay Inclusive lower bound dayKey.
     */
    @Query("DELETE FROM dimension_metrics WHERE dimensionId = :dimensionId AND dayKey >= :fromDay")
    /**
     * Removes the delete from.
     */
    suspend fun deleteFrom(dimensionId: String, fromDay: String)

    /** Continuous stream of all dimension rows ordered by day (for debug/observe). */
    @Query("SELECT * FROM dimension_metrics ORDER BY dayKey ASC")
    /**
     * Registers the observe all.
     */
    fun observeAll(): Flow<List<DimensionMetricEntity>>

    /** Snapshot of every dimension row (used by cascade rebuild). */
    @Query("SELECT * FROM dimension_metrics")
    /**
     * Returns the all.
     */
    suspend fun getAll(): List<DimensionMetricEntity>

    /**
     * Continuous stream of one dimension's rows ordered by day.
     *
     * @param dimensionId The dimension to observe.
     */
    @Query("SELECT * FROM dimension_metrics WHERE dimensionId = :dimensionId ORDER BY dayKey ASC")
    /**
     * Registers the observe for dimension.
     */
    fun observeForDimension(dimensionId: String): Flow<List<DimensionMetricEntity>>

    /**
     * Most recent row strictly before [dayKey] for a dimension.
     *
     * @param dimensionId Target dimension.
     * @param dayKey Exclusive upper bound dayKey.
     * @return The preceding row, or null if none exists.
     */
    @Query("SELECT * FROM dimension_metrics WHERE dimensionId = :dimensionId AND dayKey < :dayKey ORDER BY dayKey DESC LIMIT 1")
    /**
     * Performs the latest before.
     */
    suspend fun latestBefore(dimensionId: String, dayKey: String): DimensionMetricEntity?

    /**
     * All rows for a single day across every dimension.
     *
     * @param dayKey The exact dayKey to fetch.
     */
    @Query("SELECT * FROM dimension_metrics WHERE dayKey = :dayKey")
    /**
     * Performs the for day.
     */
    suspend fun forDay(dayKey: String): List<DimensionMetricEntity>

    /**
     * All rows within an inclusive day range across every dimension.
     *
     * @param start Inclusive start dayKey.
     * @param end Inclusive end dayKey.
     */
    @Query("SELECT * FROM dimension_metrics WHERE dayKey BETWEEN :start AND :end ORDER BY dayKey ASC")
    /**
     * Returns the for window.
     */
    suspend fun getForWindow(start: String, end: String): List<DimensionMetricEntity>

    /** Total row count (used by diagnostics). */
    @Query("SELECT COUNT(*) FROM dimension_metrics")
    /**
     * Performs the count.
     */
    suspend fun count(): Int
}
