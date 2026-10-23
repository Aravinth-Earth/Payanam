//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.core.ui.text.UiStrings
import io.payanam.desktop.DesktopOnboardingStore
import io.payanam.desktop.security.DesktopEncryptionStateCheckpoint
import io.payanam.domain.port.DatabasePathResolver
import io.payanam.domain.port.DatabaseSessionManager
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.database.DatabaseInitViewModel
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.security.EncryptionStateCheckpoint
import io.payanam.shared.security.PassphraseVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The collaborators [databaseInitViewModel] needs, held together so the shared graph can be handed them
 * in one value.
 *
 * They are bundled rather than listed as eight constructor parameters because they are ONE thing: the
 * desktop's own answers for the database-init screen, and passing them as a group makes it visible at the
 * call site that the graph is being given a database context rather than a screen.
 *
 * The three that need no external state are built here. [DesktopEncryptionStateCheckpoint] holds no
 * encryption state to checkpoint on this platform and [DesktopDatabasePathResolver] resolves the canonical
 * path, so constructing either is constructing the desktop's own answer, not a choice a caller could vary.
 * The three that DO need state — the session controller, the document source and the vault — are supplied
 * by [io.payanam.desktop.desktopApp], which is the only place that has them, so the init screen and the
 * shell cannot end up holding different instances of the same collaborator.
 */
internal data class DesktopDatabaseInitCollaborators(
    val sessionController: DesktopDatabaseSessionController,
    val documentSource: DocumentSource,
    val passphraseVault: PassphraseVault,
    val databasePathResolver: DatabasePathResolver = DesktopDatabasePathResolver(),
    val encryptionStateCheckpoint: EncryptionStateCheckpoint = DesktopEncryptionStateCheckpoint(),
    /**
     * This installation's onboarding marker, so the init gate can tell a FIRST RUN (show the tour) from the
     * RESET's continuation (do not) - see [DesktopOnboardingStore].
     *
     * It rides these collaborators rather than a new one because it belongs to the gate that renders the
     * tour: the database-init destination is the only reader and the only writer.
     *
     * UNDEFAULTED, and that is the point rather than a style choice. The tour's completion WRITES through
     * this store, so a default would put a test's write into whatever directory the default resolves - which
     * is the operator's real one. Measured 2026-10-14: a defaulted store here made the desktop's first-run
     * journey test create `desktop-onboarding.properties` in the real app data directory, and the cases that
     * ran after it in the same JVM then read that marker and found no tour. The path resolver is injected at
     * every call site for the same reason (see `DesktopDatabaseInitPreLayerMountTest`).
     */
    val onboardingStore: DesktopOnboardingStore,
)

/**
 * Builds the shared [DatabaseInitViewModel] from the desktop's own collaborators.
 *
 * This is the desktop counterpart of `:app`'s `databaseInitViewModel(...)`, and it resolves the SAME set of
 * arguments the ViewModel's constructor takes. The shared ViewModel is a plain class in `:core:presentation`
 * with no Hilt and no lifecycle, so the platform builds it at the one call site and retains it there — exactly
 * as `notesViewModel()` is built and retained in `DesktopPayanamNavHostScreens`.
 *
 * ## Where each of the ViewModel's collaborators comes from
 *
 * | collaborator | source |
 * |---|---|
 * | `appSettingsRepository`, `lifeDimensionCatalogRepository` | the INSTALLED [LocalDataLayer] — the same objects every other shared screen reads, so the init screen cannot write to a different database than the shell |
 * | `databaseSessionManager` | [DesktopDatabaseSessionPort] over the desktop's session controller, so an import opens and closes through the ONE place that installs the data layer |
 * | `strings` | [UiStrings.of] on the caller-supplied language: the copy table follows the graph's own language, not the JVM locale |
 * | `healthCheck`, `encryptionProbe`, the three file handles, `dimensionSetupPersister` | [DesktopDatabaseInitPorts] and [desktopDatabaseDimensionSetupPersister] — the six ports this step added |
 * | `databasePathResolver` | [DesktopDatabasePathResolver], so the path stays defined in exactly one place |
 * | `documentSource` | the desktop's AWT file picker, the same instance the shell already holds |
 * | `encryptionStateCheckpoint`, `passphraseVault` | the desktop's own honest answers; a caller that asks mid-import is told plainly that this platform holds no at-rest encryption state |
 *
 * ## WHY THE DATA LAYER IS READ THROUGH `LocalDataLayer` RATHER THAN CONSTRUCTED HERE
 *
 * The repositories are not built from a session in this function because there may be no session: this
 * screen runs BEFORE the database is open, which is the whole point of it. Reading the installed layer is
 * what the shared factories (`notesViewModel()`, `tasksViewModel()`) already do on both platforms, and the
 * holder throws loudly when nothing is installed — a loud failure at a call site that genuinely has no
 * session is correct, and silently substituting a repository over a different database is not.
 */
internal fun databaseInitViewModel(
    collaborators: DesktopDatabaseInitCollaborators,
    strings: UiStrings,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): DatabaseInitViewModel {
    val dataLayer = LocalDataLayer.current
    val databaseSessionManager: DatabaseSessionManager = DesktopDatabaseSessionPort(collaborators.sessionController)
    AppLogger.current.i(
        "DesktopDatabaseInitViewModel.databaseInitViewModel",
        "Building the shared database-init ViewModel from the installed data layer",
        mapOf("sessionManager" to databaseSessionManager.javaClass.simpleName),
    )
    return DatabaseInitViewModel(
        appSettingsRepository = dataLayer.appSettings,
        databaseSessionManager = databaseSessionManager,
        lifeDimensionCatalogRepository = dataLayer.lifeDimensions,
        strings = strings,
        healthCheck = DesktopDatabaseInitPorts.healthCheck,
        databasePathResolver = collaborators.databasePathResolver,
        encryptionProbe = DesktopDatabaseInitPorts.encryptionProbe,
        documentSource = collaborators.documentSource,
        databaseInitFlagWriter = DesktopDatabaseInitPorts.databaseInitFlagWriter,
        databaseInitFlagReader = DesktopDatabaseInitPorts.databaseInitFlagReader,
        databaseInitWalConsolidator = DesktopDatabaseInitPorts.databaseInitWalConsolidator,
        dimensionSetupPersister = desktopDatabaseDimensionSetupPersister,
        encryptionStateCheckpoint = collaborators.encryptionStateCheckpoint,
        passphraseVault = collaborators.passphraseVault,
        scope = scope,
    )
}
