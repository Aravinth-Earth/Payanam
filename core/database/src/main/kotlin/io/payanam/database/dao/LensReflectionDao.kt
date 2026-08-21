//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.payanam.database.entity.LensReflectionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing lens reflection cards (planning/reality gaps).
 */
@Dao
/**
 * LensReflectionDao.
 */
interface LensReflectionDao {
    /**
     * Observe all reflections for a specific day.
     * @param dayKey The day in YYYY-MM-DD format
     * @return Flow of reflection entities ordered by creation time
     */
    @Query("SELECT * FROM lens_reflections WHERE day_key = :dayKey ORDER BY created_at DESC")
    /**
     * Observe reflections for day.
     */
    fun observeReflectionsForDay(dayKey: String): Flow<List<LensReflectionEntity>>

    /**
     * Get reflections for a specific dimension on a specific day.
     * @param dayKey The day in YYYY-MM-DD format
     * @param dimensionId The dimension ID (or null for overall reflections)
     * @return Flow of filtered reflection entities
     */
    @Query("SELECT * FROM lens_reflections WHERE day_key = :dayKey AND dimension_id = :dimensionId")
    /**
     * Get reflections for dimension.
     */
    fun getReflectionsForDimension(
        dayKey: String,
        dimensionId: String?,
    ): Flow<List<LensReflectionEntity>>

    /**
     * Get reflections for a specific day synchronously (for repository calculations).
     * @param dayKey The day in YYYY-MM-DD format
     * @return List of reflection entities
     */
    @Query("SELECT * FROM lens_reflections WHERE day_key = :dayKey ORDER BY created_at DESC")
    /**
     * Get reflections for day sync.
     */
    suspend fun getReflectionsForDaySync(dayKey: String): List<LensReflectionEntity>

    /**
     * Insert a new reflection (or replace if exists).
     * @param reflection The reflection entity to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert reflection.
     */
    suspend fun insertReflection(reflection: LensReflectionEntity)

    /**
     * Insert multiple reflections.
     * @param reflections List of reflection entities to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert reflections.
     */
    suspend fun insertReflections(reflections: List<LensReflectionEntity>)

    /**
     * Update an existing reflection.
     * @param reflection The reflection entity to update
     */
    @Update
    /**
     * Update reflection.
     */
    suspend fun updateReflection(reflection: LensReflectionEntity)

    /**
     * Mark a reflection as addressed with an optional note.
     * @param id The reflection ID
     * @param note Optional user note
     */
    @Query("UPDATE lens_reflections SET is_addressed = 1, user_note = :note WHERE id = :id")
    /**
     * Mark reflection addressed.
     */
    suspend fun markReflectionAddressed(
        id: String,
        note: String?,
    )

    /**
     * Delete old reflections before a cutoff date (cleanup).
     * @param cutoffDate The cutoff date in YYYY-MM-DD format
     * @return Number of deleted rows
     */
    @Query("DELETE FROM lens_reflections WHERE day_key < :cutoffDate")
    /**
     * Delete old reflections.
     */
    suspend fun deleteOldReflections(cutoffDate: String): Int

    /**
     * Delete all reflections for a specific day (for recalculation).
     * @param dayKey The day in YYYY-MM-DD format
     * @return Number of deleted rows
     */
    @Query("DELETE FROM lens_reflections WHERE day_key = :dayKey")
    /**
     * Delete reflections for day.
     */
    suspend fun deleteReflectionsForDay(dayKey: String): Int
}
