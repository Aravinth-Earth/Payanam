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
 * DailyInsightDao.
 */
interface DailyInsightDao {
    @Query("SELECT * FROM daily_insights WHERE day_key = :dayKey AND module = :module AND dimension_id IS NULL LIMIT 1")
    /**
     * Get summary for day.
     */
    suspend fun getSummaryForDay(
        /** Day key. */
        dayKey: String,
        /** Module. */
        module: String,
    ): DailyInsightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Upsert.
     */
    suspend fun upsert(entity: DailyInsightEntity)

    @Query("SELECT * FROM daily_insights WHERE module = :module AND dimension_id IS NULL AND day_key IN (:dayKeys)")
    /**
     * Get summaries for days.
     */
    suspend fun getSummariesForDays(
        dayKeys: List<String>,
        /** Module. */
        module: String,
    ): List<DailyInsightEntity>

    @Query("DELETE FROM daily_insights WHERE day_key = :dayKey AND module = :module")
    /**
     * Delete summary for day.
     */
    suspend fun deleteSummaryForDay(
        /** Day key. */
        dayKey: String,
        /** Module. */
        module: String,
    )
}
