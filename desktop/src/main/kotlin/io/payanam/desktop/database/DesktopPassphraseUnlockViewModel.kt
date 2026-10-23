//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.database.DatabasePassphraseUnlockViewModel
import io.payanam.presentation.database.passphraseUnlockViewModel
import io.payanam.shared.database.PayanamDatabaseOpener
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.BiometricUnlockPrompt
import io.payanam.shared.security.ForgottenPassphraseReset
import io.payanam.shared.security.PassphraseVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The collaborators [databasePassphraseUnlockViewModel] needs, held together for the same reason
 * [DesktopDatabaseInitCollaborators] holds its own: they are ONE thing - the desktop's own answers for the
 * unlock gate - and passing them as a group makes it visible at the call site that the shared graph is being
 * handed a database-security context rather than a screen.
 *
 * Three of the five are supplied by [io.payanam.desktop.desktopApp], which is the only place that holds them:
 * [vault], [biometricPrompt] and [forgottenPassphraseReset] are fields of `DesktopPersistenceStores`, so
 * handing them in from there is what guarantees the unlock gate, the desktop's own startup gate and the
 * forgot-passphrase reset are all talking about ONE security store. A second vault over a second store would
 * report a lockout the others do not apply - the exact failure the field's own KDoc forbids.
 *
 * The remaining two are the desktop's own answers to a question with no platform-free answer, so constructing
 * them here IS the desktop's answer rather than a choice a caller could vary:
 *  - [databaseOpener] is [DesktopDatabaseOpenerAdapter], the same adapter `desktopApp` opens the session with
 *    and the same one the transfer gateway verifies a passphrase with, so unlock and export cannot disagree
 *    about whether a passphrase is good.
 *  - [databaseFile] is the SAME `File` the forgot-passphrase reset was built with, taken from
 *    `stores.databaseStore.getDatabaseFilePath()`. Two paths for one database would let the reset delete an
 *    artifact family the unlock screen never opened.
 */
internal data class DesktopPassphraseUnlockCollaborators(
    val vault: PassphraseVault,
    val biometricPrompt: BiometricUnlockPrompt,
    val forgottenPassphraseReset: ForgottenPassphraseReset,
    val databaseOpener: PayanamDatabaseOpener = DesktopDatabaseOpenerAdapter(),
    val databaseFile: File,
)

/**
 * Builds the shared [DatabasePassphraseUnlockViewModel] from the desktop's own collaborators.
 *
 * This is the desktop counterpart of `:app`'s `passphraseUnlockViewModel(...)` entry point, and it passes the
 * same six arguments the shared factory takes. The shared ViewModel is a plain class in `:core:presentation`
 * with no Hilt and no lifecycle, so the platform builds it at the one call site and retains it there - exactly
 * as `databaseInitViewModel(...)` and `notesViewModel()` are built and retained in
 * `DesktopPayanamNavHostScreens`.
 *
 * ## Where each collaborator comes from
 *
 * | collaborator | source |
 * |---|---|
 * | `vault`, `biometricPrompt`, `forgottenPassphraseReset` | `DesktopPersistenceStores` - the same instances the desktop's own startup gate uses |
 * | `databaseOpener` | [DesktopDatabaseOpenerAdapter] over `:core:database-jvm`, the same adapter the session open and the transfer gateway use |
 * | `databaseFile` | `stores.databaseStore.getDatabaseFilePath()`, the same value the reset was built with |
 * | `strings` | [UiStrings.of] on the caller-supplied language, so this screen's copy cannot differ from the shell's |
 *
 * ## WHY THIS RUNS NO BIOMETRIC CODE EVEN THOUGH THE PLATFORM HAS A BIOMETRIC SEAM
 *
 * The collaborators deliberately include [BiometricUnlockPrompt], because the ViewModel's constructor requires
 * it and its own KDoc explains why the prompt belongs in the shared class as a PASSPHRASE rather than a cipher.
 * On the desktop that prompt is [io.payanam.desktop.security.DesktopBiometricUnlockPrompt], which REFUSES: it
 * reports `Unavailable(UNKNOWN)` because a desktop session has no OS identity check, and a session has no
 * sensor to enumerate. That is the honest platform answer, and it is why the screen slot itself stays empty -
 * see the `biometricActions = null` at the call site. Nothing here fabricates an authenticated result.
 */
internal fun databasePassphraseUnlockViewModel(
    collaborators: DesktopPassphraseUnlockCollaborators,
    strings: UiStrings,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): DatabasePassphraseUnlockViewModel {
    AppLogger.current.i(
        "DesktopDatabasePassphraseUnlockViewModel.databasePassphraseUnlockViewModel",
        "Building the shared passphrase-unlock ViewModel from the desktop's own collaborators",
        mapOf(
            "databaseFile" to collaborators.databaseFile.name,
            "biometricPrompt" to collaborators.biometricPrompt.javaClass.simpleName,
        ),
    )
    return passphraseUnlockViewModel(
        vault = collaborators.vault,
        biometricPrompt = collaborators.biometricPrompt,
        forgottenPassphraseReset = collaborators.forgottenPassphraseReset,
        databaseOpener = collaborators.databaseOpener,
        databaseFile = collaborators.databaseFile,
        strings = strings,
        scope = scope,
    )
}
