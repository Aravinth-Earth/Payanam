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
 * Defines the contract for daily insight dao.
 */
interface DailyInsightDao {
    @Query("SELECT * FROM daily_insights WHERE day_key = :dayKey AND module = :module AND dimension_id IS NULL LIMIT 1")
    /**
     * Returns the summary for day.
     */
    suspend fun getSummaryForDay(
        dayKey: String,
        module: String,
    ): DailyInsightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the upsert.
     */
    suspend fun upsert(entity: DailyInsightEntity)

    @Query("SELECT * FROM daily_insights WHERE module = :module AND dimension_id IS NULL AND day_key IN (:dayKeys)")
    /**
     * Returns the summaries for days.
     */
    suspend fun getSummariesForDays(
        dayKeys: List<String>,
        module: String,
    ): List<DailyInsightEntity>

    @Query("DELETE FROM daily_insights WHERE day_key = :dayKey AND module = :module")
    /**
     * Removes the delete summary for day.
     */
    suspend fun deleteSummaryForDay(
        dayKey: String,
        module: String,
    )
}
