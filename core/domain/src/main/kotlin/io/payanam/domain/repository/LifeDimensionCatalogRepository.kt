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
     * Emits all five life dimensions (with user-edited label/color/icon/
     * weight/active state) as a [Flow], for settings + scoring consumers.
     */
    fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>>
    /**
     * Persists the user-visible [label] for [dimensionId].
     */
    suspend fun updateDimensionLabel(dimensionId: String, label: String)
    /**
     * Persists the display [colorHex] for [dimensionId].
     */
    suspend fun updateDimensionColor(dimensionId: String, colorHex: String)
    /**
     * Persists the [iconKey] for [dimensionId].
     */
    suspend fun updateDimensionIcon(dimensionId: String, iconKey: String)
    /**
     * Toggles whether [dimensionId] counts toward scoring.
     */
    suspend fun updateDimensionActiveState(dimensionId: String, isActive: Boolean)

    /** C2: user-editable dimension weight → L3-only recalc downstream. */
    suspend fun updateDimensionWeight(dimensionId: String, weight: Double)
}
