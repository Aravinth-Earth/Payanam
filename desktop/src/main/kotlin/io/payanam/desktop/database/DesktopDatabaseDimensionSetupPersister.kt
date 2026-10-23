//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.core.ui.database.buildDimensionSeedRows
import io.payanam.core.ui.database.canonicalizeDefaultSeedLabel
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.presentation.database.DatabaseDimensionSetupPersister
import io.payanam.presentation.database.NewDatabaseDimensionInput
import io.payanam.shared.logging.AppLogger

/**
 * The desktop's [DatabaseDimensionSetupPersister]: writes a brand-new database's mandatory life-dimension
 * seed, then records that the setup finished.
 *
 * A REAL implementation, and deliberately not a [io.payanam.desktop.port.DesktopUnsupportedPorts] refusal.
 * The two reasons the `:app` version could not be reused are both gone here: `:app` is not on this classpath,
 * and the seed-row builders it used (`buildDimensionSeedRows`, `canonicalizeDefaultSeedLabel`) live in
 * `:core:ui`, which this module DOES depend on. The brief prefers real over stub for this port, and a stub
 * would be actively harmful rather than merely incomplete: the ViewModel records the setup complete and
 * navigates on, leaving a new database whose catalog holds no rows — a database that scores nothing and
 * whose first launch reports success.
 *
 * The behaviour matches Android's `persistNewDatabaseDimensionSetup` step for step:
 *
 *  - The seed rows are built by the SAME shared builders, so both platforms seed the same dimensions with the
 *    same labels, the same colours and the same order rather than two conventions to reconcile.
 *  - The write is ONE atomic `replaceAllDimensions` swap. A delete-then-insert loop leaves the catalog empty
 *    in between, so a concurrent reader — or a crash halfway through — sees a database with no life
 *    dimensions. The contract requires the atomic swap and this is it.
 *  - Every dimension is weighted `1.0`. The seed has always been uniform; the user-editable weight is a
 *    later settings change, so a fresh database starts uniform rather than inheriting one.
 *  - The `database_init_completed` flag is written only AFTER the catalog is in place, and through
 *    [AppSettingsRepository] so the row is the one a session-backed write would produce.
 *
 * The repositories are parameters rather than fields for the same reason they are on the contract: the
 * ViewModel already holds the installed data layer's, and this class has no session of its own to build
 * them from.
 */
internal val desktopDatabaseDimensionSetupPersister: DatabaseDimensionSetupPersister =
    DatabaseDimensionSetupPersister { lifeDimensionCatalogRepository, appSettingsRepository, dimensionInputs ->
        val rows = buildDimensionSeedRows(dimensionInputs)
        AppLogger.current.i(
            "DesktopDatabaseDimensionSetupPersister.persist",
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
                        // Uniform on a fresh database, as on Android — see the class KDoc.
                        weight = 1.0,
                    )
                },
            )
        AppLogger.current.i(
            "DesktopDatabaseDimensionSetupPersister.persist",
            "Life dimension catalog replaced",
            mapOf("written" to written, "requested" to rows.size),
        )
        appSettingsRepository.setSetting(
            io.payanam.domain.repository.AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY,
            io.payanam.domain.repository.AppSettingsRepository.INIT_COMPLETED_VALUE,
        )
        AppLogger.current.i(
            "DesktopDatabaseDimensionSetupPersister.persist",
            "Completed mandatory dimension setup persistence",
            mapOf("persistedRows" to rows.size),
        )
    }
