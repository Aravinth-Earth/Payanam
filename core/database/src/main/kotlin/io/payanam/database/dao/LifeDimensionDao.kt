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
        /** Dimension id. */
        dimensionId: String,
        /** Label. */
        label: String,
        /** Updated at. */
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
        /** Dimension id. */
        dimensionId: String,
        /** Color hex. */
        colorHex: String,
        /** Updated at. */
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
        /** Dimension id. */
        dimensionId: String,
        /** Icon key. */
        iconKey: String,
        /** Updated at. */
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
        /** Dimension id. */
        dimensionId: String,
        /** Is active. */
        isActive: Int,
        /** Updated at. */
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
        /** Dimension id. */
        dimensionId: String,
        /** Weight. */
        weight: Double,
        /** Updated at. */
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
        /** Id. */
        val id: String,
        /** Weight. */
        val weight: Double,
    )
}
