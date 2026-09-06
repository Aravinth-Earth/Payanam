//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.payanam.database.entity.ScoringConfigEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for scoring configuration — a single-row table (id = 1)
 * holding the tunable weights/thresholds the scoring engine reads.
 */
@Dao
interface ScoringConfigDao {
    @Query("SELECT * FROM scoring_config WHERE id = 1")
    /**
     * Returns the scoring configuration row (always id = 1), or null when no
     * config has been seeded yet.
     */
    suspend fun getConfig(): ScoringConfigEntity?

    @Query("SELECT * FROM scoring_config WHERE id = 1")
    /**
     * Emits the scoring configuration as a [Flow] (null until seeded).
     */
    fun observeConfig(): Flow<ScoringConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces the scoring configuration row.
     */
    suspend fun upsertConfig(config: ScoringConfigEntity)

    @Query("DELETE FROM scoring_config WHERE id = 1")
    /**
     * Deletes the scoring configuration row (id = 1), resetting to unseeded.
     */
    suspend fun deleteConfig()
}
