//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.ConfiguredLifeDimension
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the runtime life dimension catalog stored in the database.
 */
interface LifeDimensionCatalogRepository {
    /**
     * Registers the observe all dimensions.
     */
    fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>>
    /**
     * Updates the update dimension label.
     */
    suspend fun updateDimensionLabel(dimensionId: String, label: String)
    /**
     * Updates the update dimension color.
     */
    suspend fun updateDimensionColor(dimensionId: String, colorHex: String)
    /**
     * Updates the update dimension icon.
     */
    suspend fun updateDimensionIcon(dimensionId: String, iconKey: String)
    /**
     * Updates the update dimension active state.
     */
    suspend fun updateDimensionActiveState(dimensionId: String, isActive: Boolean)

    /** C2: user-editable dimension weight → L3-only recalc downstream. */
    suspend fun updateDimensionWeight(dimensionId: String, weight: Double)
}
