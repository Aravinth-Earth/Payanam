//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.desktop.port.DesktopProcessRestartPort
import io.payanam.domain.port.DatabaseSessionManager
import io.payanam.domain.port.ProcessRestart
import io.payanam.presentation.database.DatabasePassphraseChangeViewModel
import io.payanam.presentation.database.passphraseChangeViewModel
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.PassphraseVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The collaborators the passphrase-change screen needs from the SHELL, held together so the shared graph
 * can be handed them in one value.
 *
 * Both are the shell's own instances and both are supplied rather than constructed here, for the same
 * reason [DesktopPassphraseUnlockCollaborators] is: this screen is not a viewer of the security state, it
 * MUTATES it. The vault persists the new passphrase, and the session controller owns the open database
 * the flow closes and re-opens.
 *
 * A vault built inside this screen would write the new passphrase into a second security store while the
 * unlock gate kept reading the first, so the next unlock would ask for a passphrase nothing stored - and
 * the second store would have its own failure counter, so a lockout counted in one gate would not apply
 * to the other. A session controller built here would register a second process shutdown hook and leave
 * the shell's session handle dropped. Neither is compiler-enforced, which is exactly why
 * `DesktopPassphraseChangeViewModelTest` asserts identity rather than type.
 */
internal data class DesktopPassphraseChangeCollaborators(
    /** The shell's own vault: the one store every passphrase gate on this platform shares. */
    val vault: PassphraseVault,
    /** The shell's own session controller, so a re-open here installs the data layer the shell reads. */
    val sessionController: DesktopDatabaseSessionController,
)

/**
 * Builds the shared passphrase-change screen's ViewModel from the desktop's own collaborators.
 *
 * The shared class is a plain class in `:core:presentation` with no Hilt and no lifecycle, so the platform
 * builds it at the one call site and retains it there - exactly as `databasePassphraseSetupViewModel(...)`,
 * `databasePassphraseUnlockViewModel(...)` and `databaseInitViewModel(...)` already are.
 *
 * ## Where each collaborator comes from
 *
 * | collaborator | source |
 * |---|---|
 * | `vault` | the shell's own vault, the one the unlock gate verifies against - see [DesktopPassphraseChangeCollaborators] |
 * | `sessionManager` | [DesktopDatabaseSessionPort] over the shell's session controller, so the close the flow performs is the close the shell's handle knows about |
 * | `rekey` | [DesktopDatabaseInitPorts.rekey], the same probe object the init gate uses |
 * | `databasePathResolver` | [DesktopDatabasePathResolver], the same resolver the init gate uses |
 * | `processRestart` | [DesktopProcessRestartPort], the desktop's real relaunch-or-refuse port |
 *
 * ## WHY THE RESTART PORT IS A PARAMETER RATHER THAN A CONSTRUCTED FIELD
 *
 * [DesktopProcessRestartPort] holds no per-process state of its own that a second instance would corrupt:
 * the fold it performs is guarded by the session controller's own `shutdownRun`, and the marker it writes
 * is a fixed path in the runtime directory. It is still a parameter so this factory can be driven against
 * a refusal or a spy without spawning a process - the desktop's other factories take their test seams the
 * same way. The production default is the real port, never a no-op: a restart that silently did nothing
 * would leave the user staring at a success message over a process still holding the old key in memory.
 */
internal fun databasePassphraseChangeViewModel(
    collaborators: DesktopPassphraseChangeCollaborators,
    processRestart: ProcessRestart = DesktopProcessRestartPort(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): DatabasePassphraseChangeViewModel {
    val sessionManager: DatabaseSessionManager = DesktopDatabaseSessionPort(collaborators.sessionController)
    AppLogger.current.i(
        "DesktopDatabasePassphraseChangeViewModel.databasePassphraseChangeViewModel",
        "Building the shared passphrase-change ViewModel from the desktop's own collaborators",
        mapOf(
            "vault" to collaborators.vault.javaClass.simpleName,
            "sessionManager" to sessionManager.javaClass.simpleName,
            "processRestart" to processRestart.javaClass.simpleName,
        ),
    )
    return passphraseChangeViewModel(
        databasePathResolver = DesktopDatabasePathResolver(),
        passphraseVault = collaborators.vault,
        rekey = DesktopDatabaseInitPorts.rekey,
        sessionManager = sessionManager,
        processRestart = processRestart,
        scope = scope,
    )
}
