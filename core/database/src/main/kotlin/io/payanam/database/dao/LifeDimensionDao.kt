//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Query
import io.payanam.database.entity.LifeDimensionEntity
import kotlinx.coroutines.flow.Flow

@Dao
/**
 * LifeDimensionDao.
 */
interface LifeDimensionDao {
    @Query(
        """
        SELECT * FROM life_dimensions
        ORDER BY sortOrder ASC, id ASC
        """,
    )
    /**
     * Observe all dimensions.
     */
    fun observeAllDimensions(): Flow<List<LifeDimensionEntity>>

    @Query(
        """
        UPDATE life_dimensions
        SET label = :label,
            updatedAt = :updatedAt
        WHERE id = :dimensionId
        """,
    )
    /**
     * Update label.
     */
    suspend fun updateLabel(
        dimensionId: String,
        label: String,
        updatedAt: String,
    )

    @Query(
        """
        UPDATE life_dimensions
        SET color = :colorHex,
            updatedAt = :updatedAt
        WHERE id = :dimensionId
        """,
    )
    /**
     * Update color.
     */
    suspend fun updateColor(
        dimensionId: String,
        colorHex: String,
        updatedAt: String,
    )

    @Query(
        """
        UPDATE life_dimensions
        SET icon = :iconKey,
            updatedAt = :updatedAt
        WHERE id = :dimensionId
        """,
    )
    /**
     * Update icon.
     */
    suspend fun updateIcon(
        dimensionId: String,
        iconKey: String,
        updatedAt: String,
    )

    @Query(
        """
        UPDATE life_dimensions
        SET isActive = :isActive,
            updatedAt = :updatedAt
        WHERE id = :dimensionId
        """,
    )
    /**
     * Update active state.
     */
    suspend fun updateActiveState(
        dimensionId: String,
        isActive: Int,
        updatedAt: String,
    )

    @Query(
        """
        UPDATE life_dimensions
        SET weight = :weight,
            updatedAt = :updatedAt
        WHERE id = :dimensionId
        """,
    )
    /**
     * Update weight.
     */
    suspend fun updateWeight(
        dimensionId: String,
        weight: Double,
        updatedAt: String,
    )

    @Query("SELECT weight FROM life_dimensions WHERE id = :dimensionId")
    /**
     * Weight for.
     */
    suspend fun weightFor(dimensionId: String): Double?

    @Query("SELECT id, weight FROM life_dimensions")
    /**
     * All weights.
     */
    suspend fun allWeights(): List<WeightRow>

    /**
     * WeightRow.
     */
    data class WeightRow(
        val id: String,
        val weight: Double,
    )
}
