//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Query
import io.payanam.database.entity.LifeDimensionEntity
import kotlinx.coroutines.flow.Flow

@Dao
/**
 * Room DAO for the `life_dimensions` table: the five fixed life-intention
 * dimensions (Health, Work, etc.) whose label, color, icon, active flag, and
 * scoring weight the user can customize.
 */
interface LifeDimensionDao {
    @Query(
        """
        SELECT * FROM life_dimensions
        ORDER BY sortOrder ASC, id ASC
        """,
    )
    /**
     * Emits all dimensions ordered by their display order, then id, as a
     * [Flow].
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
     * Updates the user-visible [label] of one dimension and stamps
     * [updatedAt].
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
     * Updates the [colorHex] used to render one dimension and stamps
     * [updatedAt].
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
     * Updates the [iconKey] (icon identifier) for one dimension and stamps
     * [updatedAt].
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
     * Toggles whether one dimension is [isActive] (counts toward scoring) and
     * stamps [updatedAt].
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
     * Updates the scoring [weight] of one dimension and stamps [updatedAt].
     * Weight influences how much this dimension contributes to the overall
     * life score.
     */
    suspend fun updateWeight(
        dimensionId: String,
        weight: Double,
        updatedAt: String,
    )

    @Query("SELECT weight FROM life_dimensions WHERE id = :dimensionId")
    /**
     * Returns the scoring weight of one dimension, or null when the row is
     * missing.
     */
    suspend fun weightFor(dimensionId: String): Double?

    @Query("SELECT id, weight FROM life_dimensions")
    /**
     * Returns every dimension's id and weight — the minimal projection needed
     * to compute a weighted overall score.
     */
    suspend fun allWeights(): List<WeightRow>

    /**
     * Minimal projection of (dimension id, weight) used when computing the
     * weighted life score without loading full entities.
     */
    data class WeightRow(
        val id: String,
        val weight: Double,
    )
}
