//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.service.DatabaseBackupCoordinator

/**
 * Hilt entry point for the settings screen's collaborators.
 *
 * The [io.payanam.feature.settings.SettingsViewModel] is a plain class now - it no longer extends
 * `androidx.lifecycle.ViewModel` and therefore cannot be resolved by `hiltViewModel()`. So the platform
 * builds the object at the one call site, and this is how it gets the collaborators Hilt already binds.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    /**
     * The Android session manager, for reading entity counts.
     *
     * Named for the implementation rather than `databaseSessionManager()` on purpose: `DatabaseInitEntryPoint`
     * and `PassphraseChangeEntryPoint` already declare that getter for the shared PORT
     * (`io.payanam.domain.port.DatabaseSessionManager`), and Hilt implements a component getter once, so two
     * entry points exposing the same name for DIFFERENT binding keys is a build error ("conflicting entry
     * point declarations"). This ViewModel takes the Android implementation, so its getter says so.
     */
    fun androidDatabaseSessionManager(): DatabaseSessionManager

    /** The encryption manager, for passphrase verification and biometric unlock. */
    fun databaseEncryptionManager(): DatabaseEncryptionManager

    /** The backup coordinator, for export/import. */
    fun databaseBackupCoordinator(): DatabaseBackupCoordinator

    /**
     * The shared process-restart port, for giving the process fresh Room handles after an import
     * replaces the database file underneath the open singleton.
     *
     * Named for the shared PORT, and deliberately the same name [DatabaseInitEntryPoint] uses: both
     * entry points resolve `io.payanam.domain.port.ProcessRestart` to one binding, so the two getters
     * agree on name AND key and Hilt implements the component getter once. The hazard described on
     * [androidDatabaseSessionManager] above does not apply here - that one collided because this file
     * wanted the Android implementation while the others wanted the port.
     */
    fun processRestart(): io.payanam.domain.port.ProcessRestart
}
