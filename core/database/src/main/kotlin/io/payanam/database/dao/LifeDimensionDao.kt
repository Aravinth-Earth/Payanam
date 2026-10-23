//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /**
     * Replaces the entire catalog in one transaction.
     *
     * `@Transaction` is the load-bearing part, not decoration: a delete followed by a loop of inserts
     * leaves the table EMPTY in between, so a concurrent reader — or a crash halfway through — sees a
     * database with no life dimensions and scoring weights everything by nothing. Room runs the whole
     * method inside one database transaction, so no reader ever observes the empty state and a failure
     * rolls back to the rows that were there before.
     *
     * REPLACE-on-conflict would silently overwrite an existing row if a caller passed a duplicate `key`,
     * turning a caller bug into a quietly wrong catalog with no error anywhere. The delete makes the table
     * empty first, so a plain insert cannot collide with a pre-existing row anyway, and a duplicate WITHIN
     * the batch is a real error that must roll the whole replace back.
     */
    @Transaction
    suspend fun replaceAll(entities: List<LifeDimensionEntity>): Int {
        deleteAll()
        if (entities.isEmpty()) return 0
        insertAll(entities)
        return entities.size
    }

    /**
     * Removes every row. Not called on its own — [replaceAll] is the entry point, and calling this
     * separately is the delete-without-the-inserts state that leaves scoring with no dimensions.
     */
    @Query("DELETE FROM life_dimensions")
    suspend fun deleteAll()

    /**
     * Inserts [entities] as given, aborting on any conflict. Abort rather than replace, so a duplicate
     * `key` in the caller's list surfaces as an error and rolls [replaceAll] back instead of silently
     * overwriting a dimension.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<LifeDimensionEntity>)
}
