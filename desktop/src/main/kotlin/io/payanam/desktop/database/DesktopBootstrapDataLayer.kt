//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.data.PayanamDataLayer
import io.payanam.presentation.data.UninstalledDataLayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The layer installed at STARTUP, before any session exists — the desktop's equivalent of Android
 * installing its repository graph in `PayanamApp.onCreate`.
 *
 * It exists because the shared database-init screen binds these two repositories BEFORE any session
 * exists: the desktop's startup order resolves the scaffold stage first, so the graph composes
 * `databaseInit` long before a passphrase or a session is in play, and `databaseInitViewModel` reads
 * this holder WHILE IT IS BEING CONSTRUCTED. Under [UninstalledDataLayer] that read throws, which is
 * why a fresh Linux install died with `IllegalStateException: LocalDataLayer.appSettings was read
 * before a data layer was installed` during composition rather than showing the init screen.
 *
 * Contract:
 *
 * - Reads forward to the LIVE layer once one is installed, so the moment `openAndInstall` replaces
 *   this layer, a repository handed out earlier still answers from the real database.
 * - Every WRITE refuses loudly before that, naming the operation and the table. A write cannot be
 *   answered, and returning success for one that never landed is the lie this exists to prevent.
 * - Every OTHER repository keeps the uninstalled refusal, exactly as before: this layer is narrow on
 *   purpose, and widening it would turn a missing database into a screen full of empty lists.
 * - [DesktopDatabaseSessionController.openAndInstall] replaces the WHOLE layer on unlock, so nothing
 *   here has to know how a real layer is built.
 */
internal object DesktopBootstrapDataLayer : PayanamDataLayer by UninstalledDataLayer {
    override val appSettings: AppSettingsRepository = PreSessionAppSettings()
    override val lifeDimensions: LifeDimensionCatalogRepository = PreSessionCatalog()
}

/**
 * The `app_settings` repository for the pre-session window: forwards to the live one when it exists,
 * and refuses every write until it does.
 */
private class PreSessionAppSettings : AppSettingsRepository {
    /** The live repository, or null when this instance IS the installed one. */
    private fun live(): AppSettingsRepository? = LocalDataLayer.current.appSettings.takeIf { it !== this }

    override suspend fun getSetting(key: String): String? = live()?.getSetting(key)

    override fun observeSetting(key: String): Flow<String?> = live()?.observeSetting(key) ?: flowOf(null)

    override fun getAllSettings(): Flow<Map<String, String?>> = live()?.getAllSettings() ?: flowOf(emptyMap())

    override suspend fun setSetting(
        key: String,
        value: String?,
    ) = live()?.setSetting(key, value) ?: noDatabase("setSetting")

    override suspend fun deleteSetting(key: String) = live()?.deleteSetting(key) ?: noDatabase("deleteSetting")

    private fun noDatabase(op: String): Nothing = error("DesktopBootstrapDataLayer: no database is open, so app_settings.$op cannot run")
}

/**
 * The `life_dimensions` catalog for the pre-session window: an empty read before a database exists,
 * a forwarding read after one is installed, and a loud refusal on every write until then.
 */
private class PreSessionCatalog : LifeDimensionCatalogRepository {
    /** The live catalog, or null when this instance IS the installed one. */
    private fun live(): LifeDimensionCatalogRepository? = LocalDataLayer.current.lifeDimensions.takeIf { it !== this }

    override fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>> = live()?.observeAllDimensions() ?: flowOf(emptyList())

    override suspend fun updateDimensionLabel(
        dimensionId: String,
        label: String,
    ) = live()?.updateDimensionLabel(dimensionId, label) ?: noDatabase("updateDimensionLabel")

    override suspend fun updateDimensionColor(
        dimensionId: String,
        colorHex: String,
    ) = live()?.updateDimensionColor(dimensionId, colorHex) ?: noDatabase("updateDimensionColor")

    override suspend fun updateDimensionIcon(
        dimensionId: String,
        iconKey: String,
    ) = live()?.updateDimensionIcon(dimensionId, iconKey) ?: noDatabase("updateDimensionIcon")

    override suspend fun updateDimensionActiveState(
        dimensionId: String,
        isActive: Boolean,
    ) = live()?.updateDimensionActiveState(dimensionId, isActive) ?: noDatabase("updateDimensionActiveState")

    override suspend fun updateDimensionWeight(
        dimensionId: String,
        weight: Double,
    ) = live()?.updateDimensionWeight(dimensionId, weight) ?: noDatabase("updateDimensionWeight")

    override suspend fun replaceAllDimensions(dimensions: List<ConfiguredLifeDimension>): Int =
        live()?.replaceAllDimensions(dimensions) ?: noDatabase("replaceAllDimensions")

    private fun noDatabase(op: String): Nothing = error("DesktopBootstrapDataLayer: no database is open, so life_dimensions.$op cannot run")
}
