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

    /**
     * Replaces the whole catalog with [dimensions] in ONE transaction, and returns how many rows it wrote.
     *
     * This exists because seeding a brand-new database is not a series of edits. The Android path used to
     * reach through the session to raw Room for `DELETE FROM life_dimensions` followed by a loop of
     * `INSERT`s, which is two things this interface must not do: it puts SQL in a ViewModel, and a
     * delete-then-insert loop leaves the catalog EMPTY in between — a reader on another thread, or a crash
     * halfway through, sees a database with no life dimensions at all, and scoring weights everything by
     * nothing. A read-copy-write would be worse: it would clobber an edit made between the read and the
     * write, which is exactly the race the single-row `updateDimension*` methods exist to avoid.
     *
     * So this is one atomic replace, and it is deliberately NOT expressible as N calls to the methods
     * above. Implementations must make the delete and the inserts one unit of work.
     */
    suspend fun replaceAllDimensions(dimensions: List<ConfiguredLifeDimension>): Int
}
