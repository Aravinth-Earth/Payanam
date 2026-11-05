//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository

/**
 * Writes the mandatory life-dimension seed for a brand-new database.
 *
 * A contract rather than a function because the seed-row builders it needs live in `:core:ui` - shared
 * types with no platform code in them, but `:core:ui` is an Android library and this module cannot depend
 * on it. The platform supplies the implementation, exactly as it does for the other handles this
 * ViewModel needs, and for the same reason: the alternative is a second copy of the seed logic.
 *
 * Implementations MUST write the catalog atomically, through
 * [LifeDimensionCatalogRepository.replaceAllDimensions], and only then record that initialisation
 * finished. A delete-then-insert loop leaves the catalog empty in between, so a concurrent reader - or a
 * crash halfway through - sees a database with no life dimensions that scores nothing.
 */
fun interface DatabaseDimensionSetupPersister {
    /**
     * Persists [dimensionInputs] as the new database's life dimensions and records that the mandatory
     * setup is complete.
     */
    suspend fun persist(
        lifeDimensionCatalogRepository: LifeDimensionCatalogRepository,
        appSettingsRepository: AppSettingsRepository,
        dimensionInputs: List<NewDatabaseDimensionInput>,
    )
}
