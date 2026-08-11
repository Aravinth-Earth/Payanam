//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.ConfiguredLifeDimension
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the runtime life dimension catalog stored in the database.
 */
interface LifeDimensionCatalogRepository {
    fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>>

    suspend fun updateDimensionLabel(dimensionId: String, label: String)

    suspend fun updateDimensionColor(dimensionId: String, colorHex: String)

    suspend fun updateDimensionIcon(dimensionId: String, iconKey: String)

    suspend fun updateDimensionActiveState(dimensionId: String, isActive: Boolean)

    /** C2: user-editable dimension weight → L3-only recalc downstream. */
    suspend fun updateDimensionWeight(dimensionId: String, weight: Double)
}
