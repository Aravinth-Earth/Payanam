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
 * DAO for the `lens_reflections` table: "lens" planning-vs-reality reflection
 * cards the user writes per day/dimension. `day_key` is YYYY-MM-DD.
 */
@Dao
interface LensReflectionDao {
    @Query("SELECT * FROM lens_reflections WHERE day_key = :dayKey ORDER BY created_at DESC")
    /**
     * Emits all reflections for [dayKey], newest first, as a [Flow].
     */
    fun observeReflectionsForDay(dayKey: String): Flow<List<LensReflectionEntity>>

    @Query("SELECT * FROM lens_reflections WHERE day_key = :dayKey AND dimension_id = :dimensionId")
    /**
     * Emits reflections for [dayKey] scoped to [dimensionId] (null = overall
     * reflections), as a [Flow].
     */
    fun getReflectionsForDimension(
        dayKey: String,
        dimensionId: String?,
    ): Flow<List<LensReflectionEntity>>

    @Query("SELECT * FROM lens_reflections WHERE day_key = :dayKey ORDER BY created_at DESC")
    /**
     * Returns all reflections for [dayKey], newest first, as a one-shot list
     * (used by repository-side scoring calculations).
     */
    suspend fun getReflectionsForDaySync(dayKey: String): List<LensReflectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a single reflection.
     */
    suspend fun insertReflection(reflection: LensReflectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a batch of reflections.
     */
    suspend fun insertReflections(reflections: List<LensReflectionEntity>)

    @Update
    /**
     * Updates all columns of an existing reflection.
     */
    suspend fun updateReflection(reflection: LensReflectionEntity)

    @Query("UPDATE lens_reflections SET is_addressed = 1, user_note = :note WHERE id = :id")
    /**
     * Marks the reflection with [id] as addressed and records the optional
     * [note] the user left.
     */
    suspend fun markReflectionAddressed(
        id: String,
        note: String?,
    )

    @Query("DELETE FROM lens_reflections WHERE day_key < :cutoffDate")
    /**
     * Deletes reflections whose `day_key` is before [cutoffDate]. Returns the
     * number of rows removed (periodic cleanup of old cards).
     */
    suspend fun deleteOldReflections(cutoffDate: String): Int

    @Query("DELETE FROM lens_reflections WHERE day_key = :dayKey")
    /**
     * Deletes every reflection for [dayKey]. Returns the row count — called
     * before regenerating that day's reflections.
     */
    suspend fun deleteReflectionsForDay(dayKey: String): Int
}
