//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.desktop.security.DesktopEncryptionStateCheckpoint
import io.payanam.presentation.database.DatabasePassphraseSetupViewModel
import io.payanam.presentation.database.passphraseSetupViewModel
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Builds the shared passphrase-setup gate's ViewModel from the desktop's own collaborators.
 *
 * The shared class has no Hilt and no lifecycle, so the platform builds it at the one call site and retains it
 * there - exactly as `databasePassphraseUnlockViewModel(...)` and `databaseInitViewModel(...)` are built and
 * retained in `DesktopPayanamNavHostScreens`.
 *
 * ## Where each collaborator comes from
 *
 * | collaborator | source |
 * |---|---|
 * | `vault` | `DesktopPassphraseUnlockCollaborators.vault`, over the one `DesktopSecurityStore` every gate shares |
 * | `databasePathResolver` | [DesktopDatabasePathResolver], the same resolver the init gate uses |
 * | `encryptionProbe` | [DesktopDatabaseInitPorts.encryptionProbe], the same probe the init gate uses |
 * | `encryptionStateCheckpoint` | [DesktopEncryptionStateCheckpoint], which reports "not applicable" rather than pretending a rollback checkpoint exists |
 *
 * The setup gate writes the passphrase that the unlock gate later verifies, so a vault built here would hold a
 * credential no other gate can check. It therefore arrives from the caller - the vault the unlock gate was built
 * over - rather than being constructed in this function.
 */
internal fun databasePassphraseSetupViewModel(
    passphraseUnlockCollaborators: DesktopPassphraseUnlockCollaborators,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): DatabasePassphraseSetupViewModel {
    AppLogger.current.i(
        "DesktopDatabasePassphraseSetupViewModel.databasePassphraseSetupViewModel",
        "Building the shared passphrase-setup ViewModel from the desktop's own collaborators",
        mapOf("vault" to passphraseUnlockCollaborators.vault.javaClass.simpleName),
    )
    return passphraseSetupViewModel(
        databasePathResolver = DesktopDatabasePathResolver(),
        encryptionProbe = DesktopDatabaseInitPorts.encryptionProbe,
        passphraseVault = passphraseUnlockCollaborators.vault,
        encryptionStateCheckpoint = DesktopEncryptionStateCheckpoint(),
        scope = scope,
    )
}
