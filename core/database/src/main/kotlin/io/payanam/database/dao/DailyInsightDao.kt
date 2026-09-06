//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.payanam.database.entity.DailyInsightEntity

@Dao
/**
 * Room DAO for the `daily_insights` table: generated per-day summary insights.
 * Rows with `dimension_id IS NULL` are the overall day summary (this DAO only
 * touches those; dimension-scoped insights are written elsewhere).
 */
interface DailyInsightDao {
    @Query("SELECT * FROM daily_insights WHERE day_key = :dayKey AND module = :module AND dimension_id IS NULL LIMIT 1")
    /**
     * Returns the overall (non-dimension) insight summary for [dayKey] and
     * [module], or null.
     */
    suspend fun getSummaryForDay(
        dayKey: String,
        module: String,
    ): DailyInsightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a daily-insight row.
     */
    suspend fun upsert(entity: DailyInsightEntity)

    @Query("SELECT * FROM daily_insights WHERE module = :module AND dimension_id IS NULL AND day_key IN (:dayKeys)")
    /**
     * Returns the overall insight summaries for every [dayKeys] under [module].
     * Used to load a multi-day window without per-day round-trips.
     */
    suspend fun getSummariesForDays(
        dayKeys: List<String>,
        module: String,
    ): List<DailyInsightEntity>

    @Query("DELETE FROM daily_insights WHERE day_key = :dayKey AND module = :module")
    /**
     * Deletes every insight (overall and dimension-scoped) for [dayKey] under
     * [module] — used before regenerating that day's insights.
     */
    suspend fun deleteSummaryForDay(
        dayKey: String,
        module: String,
    )
}
