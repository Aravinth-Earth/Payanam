//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.database.buildDimensionSeedRows
import io.payanam.core.ui.database.canonicalizeDefaultSeedLabel
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.presentation.database.NewDatabaseDimensionInput

/**
 * Writes the mandatory life-dimension seed for a brand-new database.
 *
 * The types and the pure builders moved to `:core:ui` because a shared screen needs them
 * and they hold no platform code at all. What stayed behind is this one writer, and it no longer contains
 * SQL: it used to reach through the session to `db.openHelper.writableDatabase` and issue
 * `DELETE FROM life_dimensions` plus a loop of `INSERT`s — a delete-then-insert loop that leaves the catalog
 * EMPTY in between, so a concurrent reader or a crash halfway through would see a database with no life
 * dimensions and score nothing. It now calls
 * [LifeDimensionCatalogRepository.replaceAllDimensions], the one atomic swap both platforms implement.
 */
internal suspend fun persistNewDatabaseDimensionSetup(
    lifeDimensionCatalogRepository: LifeDimensionCatalogRepository,
    appSettingsRepository: AppSettingsRepository,
    dimensionInputs: List<NewDatabaseDimensionInput>,
) {
    val logger = UnifiedLogger.getInstance()
    val rows = buildDimensionSeedRows(dimensionInputs)
    logger.i(
        "DatabaseInitDimensionSetupWriter.persistNewDatabaseDimensionSetup",
        "Persisting new database dimension setup",
        mapOf("rowCount" to rows.size),
    )
    val written =
        lifeDimensionCatalogRepository.replaceAllDimensions(
            rows.map { row ->
                ConfiguredLifeDimension(
                    id = row.id,
                    key = row.key,
                    label =
                    canonicalizeDefaultSeedLabel(
                        dimensionId = row.id,
                        candidateLabel = row.label,
                    ),
                    description = row.description,
                    colorHex = row.color,
                    iconKey = row.icon,
                    sortOrder = row.sortOrder,
                    isActive = row.isActive,
                    // The seed has always weighted every dimension equally; the user-editable weight is a
                    // later settings change, so a fresh database starts uniform rather than inheriting one.
                    weight = 1.0,
                )
            },
        )
    logger.i(
        "DatabaseInitDimensionSetupWriter.persistNewDatabaseDimensionSetup",
        "Life dimension catalog replaced",
        mapOf("written" to written, "requested" to rows.size),
    )
    appSettingsRepository.setSetting("database_init_completed", "true")
    logger.i(
        "DatabaseInitDimensionSetupWriter.persistNewDatabaseDimensionSetup",
        "Completed mandatory dimension setup persistence",
        mapOf("persistedRows" to rows.size),
    )
}
