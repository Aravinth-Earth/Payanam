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
 * Data access object for scoring configuration.
 */
@Dao
/**
 * Defines the contract for scoring config dao.
 */
interface ScoringConfigDao {
    @Query("SELECT * FROM scoring_config WHERE id = 1")
    /**
     * Returns the config.
     */
    suspend fun getConfig(): ScoringConfigEntity?

    @Query("SELECT * FROM scoring_config WHERE id = 1")
    /**
     * Registers the observe config.
     */
    fun observeConfig(): Flow<ScoringConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the upsert config.
     */
    suspend fun upsertConfig(config: ScoringConfigEntity)

    @Query("DELETE FROM scoring_config WHERE id = 1")
    /**
     * Removes the delete config.
     */
    suspend fun deleteConfig()
}
