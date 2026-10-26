//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint")

package io.payanam.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.payanam.backup.BackupCoordinatorCore
import io.payanam.core.ui.day.DayScreen
import io.payanam.core.ui.day.DayScreenMode
import io.payanam.core.ui.flags.LocalFeatureFlags
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.notes.NotesScreen
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.desktop.database.DesktopDatabaseInitCollaborators
import io.payanam.desktop.database.DesktopDatabaseOpenerAdapter
import io.payanam.desktop.database.DesktopDatabaseSessionController
import io.payanam.desktop.database.DesktopDatabaseTransferController
import io.payanam.desktop.database.DesktopDatabaseTransferGateway
import io.payanam.desktop.database.DesktopPassphraseChangeCollaborators
import io.payanam.desktop.database.DesktopPassphraseUnlockCollaborators
import io.payanam.desktop.database.DesktopSettingsCollaborators
import io.payanam.desktop.database.DesktopTransferMessages
import io.payanam.desktop.database.DesktopTransferOutcome
import io.payanam.desktop.database.pruneOldBackupDirectories
import io.payanam.desktop.database.rotationPolicyFromBackupRows
import io.payanam.desktop.database.scheduledBackupArtifactName
import io.payanam.desktop.platform.AwtDocumentSource
import io.payanam.desktop.platform.DesktopDebugFlags
import io.payanam.desktop.platform.DesktopPlatformSeams
import io.payanam.desktop.platform.desktopPlatformSeams
import io.payanam.domain.port.BackupCheckpointPort
import io.payanam.domain.port.BackupTrigger
import io.payanam.domain.port.retryPolicyFor
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.day.dayViewModel
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.presentation.notes.notesViewModel
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.logging.autoBackupDirName
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.FoundationArea
import io.payanam.shared.settings.FoundationReadiness
import io.payanam.shared.settings.SettingsFoundationContracts
import io.payanam.shared.settings.SettingsFoundationSnapshot
import io.payanam.shared.startup.StartupGateInputs
import io.payanam.shared.startup.withLandingBackedBySession
import io.payanam.shared.transfer.DataModuleSelection
import io.payanam.shared.transfer.TransferOutcome
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun desktopApp(onQuitRequested: () -> Unit) {
    val sessionLogger = remember { UnifiedLogger.getInstance() }
    val stores = rememberDesktopPersistenceStores(sessionLogger)
    // The debug-logging switch: the shared `debug_logging_enabled` row is the source, and the logger's own
    // gate follows it. Built here so the flags live exactly as long as the shell that owns them.
    val debugFlags = remember { DesktopDebugFlags() }
    val rememberedState =
        rememberDesktopMutableState(
            stores = stores,
            sessionLogger = sessionLogger,
            onDebugLoggingChanged = { enabled -> debugFlags.setDebugLoggingEnabled(enabled) },
            // The REAL `life_dimensions` rows, read through the installed layer's catalog repository — the
            // same repository and the same shared mapper Android's `AppPreferencesViewModel` uses. This is a
            // lambda, not a value, because there are no rows to read until a session is open: `LocalDataLayer`
            // is un-installed before unlock and THROWS on any read, so evaluating this at composition time
            // would crash the shell before the user has typed a passphrase. The source calls it from inside a
            // load, under its own write mutex, where a session is in force.
            //
            // `.first()` on the observing flow takes the one emission the JDBC poller makes immediately and
            // then stops collecting — the catalog is read per load, not held open, so this cannot keep a
            // polling loop alive after the load returns.
            dimensionRows = {
                LocalDataLayer.current.lifeDimensions
                    .observeAllDimensions()
                    .first()
            },
        )
    var activeRoute by remember {
        androidx.compose.runtime.mutableStateOf(DesktopTopLevelRoute.SETTINGS)
    }
    // A rail navigation request, consumed by the graph and never by composition. Null means "no pending
    // request", not "navigate nowhere".
    var railRouteRequest by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val models = rememberDesktopAppModels(stores = stores, rememberedState = rememberedState, activeRoute = activeRoute)
    val startupLogState =
        remember(models.startupSnapshot, models.startupGate) {
            mapOf(
                "launchRoute" to models.startupSnapshot.launchRoute.storageKey,
                "readyChecks" to models.startupSnapshot.readyChecks(),
                "totalChecks" to models.startupSnapshot.checks.size,
                "requiresAttention" to models.startupSnapshot.requiresAttention(),
                "startupMode" to models.startupGate.gateName,
            )
        }
    // Pre-unlock there are no rows to read, so the source reports the absent-row defaults and says why. It
    // does not touch the data layer: an uninstalled layer throws, and that throw must stay visible.
    LaunchedEffect(Unit) {
        rememberedState.preferencesSource.reportNoSession()
    }
    LaunchedEffect(startupLogState) {
        sessionLogger.i(
            source = "DesktopApp.desktopStartupState",
            message = "Desktop startup state resolved",
            data = startupLogState,
        )
    }

    desktopStateEffects(
        snapshot = models.shellRenderState.foundationSnapshot,
        activeRoute = activeRoute,
        startupGate = models.startupGate,
        lifecycleState = models.desktopLifecycleState,
        navigationModel = models.navigationModel,
        onRouteReset = { route ->
            activeRoute = route
            railRouteRequest = route.storageKey
        },
        sessionLogger = sessionLogger,
    )
    // Opening the encrypted database and putting the shared data layer in force. Built here because this is
    // where a coroutine scope exists: the open is suspend, and it must happen on ACCEPTANCE of the
    // passphrase, before anything reads through the holder.
    val databaseScope = rememberCoroutineScope()
    val databaseSessionController =
        remember(sessionLogger) {
            DesktopDatabaseSessionController(
                databaseFilePath = {
                    DesktopAppPaths
                        .resolveDatabaseDirectory()
                        .resolve(DesktopAppPaths.DATABASE_FILE_NAME)
                        .toString()
                },
                open = { path, passphrase -> DesktopDatabaseOpenerAdapter().openSession(path, passphrase) },
                logger = sessionLogger,
            )
        }
    // The gate's session answer comes from the session itself, exactly as Android's does, and the shared
    // preference rows are read on the same event. Without this the flag was written only by the desktop's
    // legacy gate callbacks, and the rows were never read at all - so a session opened by the database-init
    // gate's import was invisible to the gate AND every stored preference showed as its default.
    // (The LaunchedEffect itself lives below platformSeams, after both exist - see the reconcile wiring.)
    // Export and import of the encrypted database. The gateway moves the file; the controller owns the two
    // orderings around it (fold before copy, release before replace). Both are built here because this is
    // where the scope and the session controller live.
    val documentSource: DocumentSource = remember { AwtDocumentSource() }
    val transferController =
        remember(databaseSessionController, sessionLogger) {
            DesktopDatabaseTransferController(
                sessionController = databaseSessionController,
                gateway =
                    DesktopDatabaseTransferGateway(
                        liveDatabasePath = {
                            DesktopAppPaths
                                .resolveDatabaseDirectory()
                                .resolve(DesktopAppPaths.DATABASE_FILE_NAME)
                        },
                        // The CONTRACT, satisfied by the same adapter the open path uses - so verification
                        // and opening cannot drift apart.
                        opener = DesktopDatabaseOpenerAdapter(),
                        // Folds the OPEN session's log, and answers whether it did: the export must not
                        // proceed on a log it could not fold. The decision is the shared rule's, so this
                        // site cannot drift from the export gateway's own precondition below.
                        checkpoint = { WalCheckpointRule.walIsEmpty(databaseSessionController.checkpoint()) },
                    ),
                logger = sessionLogger,
            )
        }
    // ONE reader for the shared `app_settings` rows, used by BOTH the backup schedule and the rotation
    // that follows it. Declared here, above the seams that consume it, because the two policies are two
    // readings of the same table: a schedule reconciled from one reader while retention was decided by
    // another is how the desktop ends up honouring a cadence and ignoring a retention, or the reverse.
    // Before the session is open the data layer is UNINSTALLED and this throws; every caller treats a
    // failed read as "no policy" and logs it, so the window is never taken down by a read that ran early.
    val backupRows: suspend () -> Map<String, String?> = {
        LocalDataLayer.current.appSettings
            .getAllSettings()
            .first()
    }
    // The desktop's platform seams, CONSTRUCTED for as long as this shell lives: the in-process backup
    // schedule. It was implemented and unit-tested and reachable from nowhere, so the settings screen's
    // auto-backup toggle wrote `auto_backup_enabled` and the row stopped there - a Linux user could
    // turn auto-backup on, pick 15 minutes, and receive no backups at all.
    //
    // Built HERE, immediately after the transfer controller, because that is where everything it needs
    // already exists: the scope, the session controller (the fold), the export gateway (the copy) and
    // the session logger.
    val platformSeams =
        remember(databaseSessionController, transferController, sessionLogger) {
            desktopPlatformSeams(
                scope = databaseScope,
                logger = sessionLogger,
                runBackup = { runScheduledDesktopBackup(sessionLogger, databaseSessionController, backupRows, stores) },
                // The shared `app_settings` rows, read the same way every other desktop read reads them.
                // Before a session is open the data layer is UNINSTALLED and this throws; the seams
                // class treats a failed read as "no schedule" and logs it, so the window is never taken
                // down by a reconcile that ran too early.
                backupRows = backupRows,
            )
        }
    // The gate's session answer comes from the session itself, exactly as Android's does, and the shared
    // preference rows are read on the same event - plus the backup schedule is reconciled on that same
    // event, because the shared unlock screen opens through DesktopSessionControllerOpenerAdapter and
    // NEVER fires the shell's onSessionEstablished below, so its reconcile never ran on a normal unlock
    // (measured 2026-10-08: zero 'Auto-backup schedule applied from shared rows' lines in every real run
    // log, while sqlcipher read auto_backup_enabled=true). Placed after platformSeams so the callback can
    // capture it; the seams' reconcileMutex keeps a duplicate call (should one path ever fire both)
    // idempotent.
    LaunchedEffect(databaseSessionController) {
        followDesktopSessionOpenedState(
            sessionController = databaseSessionController,
            gateFlag = rememberedState.sessionOpenState,
            preferencesSource = rememberedState.preferencesSource,
            onOpened = { platformSeams.reconcileBackupSchedule() },
        )
    }
    val callbacks =
        buildDesktopShellCallbacks(
            stores = stores,
            rememberedState = rememberedState,
            sessionLogger = sessionLogger,
            onQuitRequested = onQuitRequested,
            // The session controller's own path, so the create cannot land somewhere the open and
            // transfer paths do not look.
            firstRunDatabasePath = { desktopDatabaseFilePath() },
            openSessionPassphrase = { databaseSessionController.passphraseForTransfer() },
            // The import gate's session read, through the SHARED contract member
            // (`DesktopDatabaseSessionPort.isSessionOpen()`, answered by the controller's `isOpen`) -
            // the desktop's counterpart to Android's `sessionManager.isDbOpen()`, asked in the same place
            // so the two platforms' restart decisions come from the same question.
            sessionOpen = { databaseSessionController.isOpen },
            // The create returns the app's session as its proof and closes it again, so the app's OWN
            // session is established here - otherwise the gate would move past "database ready" with an
            // uninstalled data layer and every read would throw.
            onFirstRunDatabaseCreated = { passphrase ->
                databaseScope.launch {
                    databaseSessionController.openAndInstall(passphrase)
                }
            },
            // The open-and-install runs INSIDE the preferences swap, so the rows are re-read from the new
            // session before the write lock is released - and a settings write that was in flight against the
            // old layer is discarded rather than landing on the new database.
            onSessionEstablished = { passphrase ->
                databaseScope.launch {
                    rememberedState.preferencesSource.runDatabaseSwap {
                        databaseSessionController.openAndInstall(passphrase)
                    }
                    // FIRST point the schedule can be read: the rows only exist once a session is in
                    // force, and the reconcile before this one necessarily found nothing.
                    platformSeams.reconcileBackupSchedule()
                }
            },
            onSettingChanged = { key, value ->
                databaseScope.launch {
                    rememberedState.preferencesSource.setSetting(key, value)
                    reconcileAfterSettingChange(platformSeams, key)
                }
            },
            // The focus preset is NINE rows written as one unit, under the source's own mutex - the same
            // path, launched the same way, because the callback this function builds is plain and the write
            // is suspend. DesktopApp owns databaseScope for exactly this reason.
            onFocusModePresetSelected = { preset ->
                databaseScope.launch { rememberedState.preferencesSource.setFocusModePreset(preset) }
            },
            // The pickers own their own threading: `AwtDocumentSource` hops off this coroutine's dispatcher
            // before showing its blocking modal (see its DIALOG_DISPATCHER for the crash that proved why),
            // so launching on the Compose scope is safe and the UI thread never waits on the dialog.
            onExportDatabase = {
                databaseScope.launch {
                    val destination = documentSource.requestDestination("Payanam_backup.db") ?: return@launch
                    val outcome = transferController.exportTo(destination)
                    rememberedState.transferStatusState.value =
                        DesktopTransferMessages.describe(
                            outcome,
                            DesktopTransferMessages.TransferDirection.EXPORT,
                            destination,
                        )
                }
            },
            onImportDatabase = {
                databaseScope.launch {
                    val source = documentSource.requestSource()
                    val passphrase = databaseSessionController.passphraseForTransfer()
                    when {
                        source == null -> {
                            Unit
                        }

                        // The import verifies the passphrase+file pair, so it needs the one the user unlocked
                        // with. Never prompted for a second time: asking again would mean two ways to be wrong.
                        passphrase == null -> {
                            rememberedState.transferStatusState.value =
                                "Unlock the app first, then import: the import verifies your passphrase against the file."
                        }

                        else -> {
                            // The same swap guard the unlock path uses: the import replaces the layer, so the
                            // rows must be re-read inside the lock and a racing write must be discarded.
                            val outcome =
                                rememberedState.preferencesSource.runDatabaseSwap {
                                    transferController.importFrom(source, passphrase)
                                }
                            // The passphrase copy was handed to the transfer; clearing it here keeps it out of
                            // this frame once the work is done.
                            passphrase.fill('\u0000')
                            rememberedState.transferStatusState.value =
                                DesktopTransferMessages.describe(
                                    outcome,
                                    DesktopTransferMessages.TransferDirection.IMPORT,
                                )
                        }
                    }
                }
            },
            // Named, not trailing: `onSessionEstablished` now follows it, so a trailing lambda would bind to
            // the wrong parameter.
            onRouteSelected = { route -> activeRoute = route },
            // The rail publishes a request; this holder owns where it goes. The graph reads it and the
            // route policy inside `PayanamNavHost` decides whether it is allowed.
            onRailRouteRequested = { key -> railRouteRequest = key },
        )
    // The ONE startup decision, read straight off the resolved mode. There is no projection type between the
    // resolver and the graph any more: every mount site asks THIS value which gate it is, so the answer is made
    // in exactly one place, in shared code, and translated in none.
    //
    // AND THE LANDING IS CONJOINED WITH THE SESSION OWNER'S OWN STATE - the belt to this fix's braces. See
    // [withLandingBackedBySession]: the resolver's `Ready` is fed by a snapshot the platform supplies, and the
    // shared session contract forbids feeding that from a UI/Compose flag.
    // `DesktopDatabaseSessionController.isOpen` is the owner's state and is published only AFTER
    // `LocalDataLayer.install(...)`, so this makes the app route unreachable while no data layer is installed.
    // The owner's build-405 crash was precisely `LocalDataLayer.lenses was read before a data layer was
    // installed`, reached because the gate let the landing route compose; the reset fix stops that state
    // arising, and this stops it being reachable if it ever does.
    val resolvedStartupGate = models.startupGate
    val startupGate = withLandingBackedBySession(resolvedStartupGate, sessionOpen = databaseSessionController.isOpen)
    val landingWithheld = resolvedStartupGate.isLandingReady && !startupGate.isLandingReady
    LaunchedEffect(startupGate) {
        sessionLogger.i(
            source = "DesktopApp.surfaceMounted",
            message = "Startup surface chosen",
            data =
                mapOf(
                    "startupMode" to startupGate.gateName,
                    "shouldShowDatabaseInit" to startupGate.shouldShowDatabaseInit,
                    "shouldShowPassphraseSetup" to startupGate.shouldShowPassphraseSetup,
                    "shouldShowPassphraseUnlock" to startupGate.shouldShowPassphraseUnlock,
                    // Says out loud that the app route was withheld and why, rather than leaving a run whose
                    // landing never appeared to be diagnosed from the absence of a line.
                    "landingWithheld" to landingWithheld,
                ),
        )
    }
    run {
        // The gate-bearing modes mount the SHELL, so the shared graph's own `DatabasePassphraseSetupScreen`
        // and `DatabaseInitScreen` are what a fresh install shows. The four modes with no graph gate keep the
        // desktop's own surface below, or the app's tabs would be on screen with nothing in front of them.
        //
        // Latched on the SESSION, not on first composition: the shell now composes before a session exists,
        // and the preferences source reports absent-row defaults until one does, so latching on composition
        // would freeze the default landing route for the whole run.
        val sessionLandingRoute = remember(models.startupRuntimeState.sessionOpen) { models.navigationModel.launchRoute }
        // One-time sync, not a binding: from here the graph is the source of truth for navigation.
        LaunchedEffect(sessionLandingRoute) {
            activeRoute = sessionLandingRoute
        }
        // The settings screen's collaborators, built HERE for the same reason the other three are: this
        // screen writes the shell's own `app_settings` rows and `life_dimensions` catalog, runs its backup on
        // the shell's scope, and reads the shell's database file. None of those live below this point.
        //
        // `preferences = state.preferences` is the load-bearing line. The shared appearance and default-
        // landing sections early-return on `AppPreferencesState.isLoading`, which DEFAULTS TO TRUE, so a
        // destination that built its own default-constructed state - or read `LocalAppPreferences` without
        // the shell having installed it - would render those two sections permanently and silently empty.
        // Passing the shell's own instance is also what makes the writes visible: `DesktopPreferencesSource`
        // republishes from the rows it wrote, and this is the instance that republishes.
        val settingsCollaborators =
            remember(
                models.shellRenderState.preferences,
                models.shellRenderState.unlockTimeoutMinutes,
                models.shellRenderState.desktopLifecycleState.databaseFilePath,
                callbacks.onSettingChanged,
                callbacks.onExportDatabase,
                callbacks.onImportDatabase,
                callbacks.onDismissBackupFailure,
                platformSeams,
            ) {
                DesktopSettingsCollaborators(
                    preferences = models.shellRenderState.preferences,
                    // The shell's own derive-and-clamp rule over the rows it read, rather than the shared
                    // state's own `unlockSessionTimeoutMinutes` default of ten minutes.
                    unlockTimeoutMinutes = models.shellRenderState.unlockTimeoutMinutes,
                    databaseFile = File(models.shellRenderState.desktopLifecycleState.databaseFilePath),
                    // The SHELL's scope, so a write is not abandoned when this destination leaves the
                    // composition - and a settings write followed immediately by a rail tap is the ordinary
                    // case, not an edge.
                    launchWrite = { block -> databaseScope.launch { block() } },
                    setSetting = { key, value -> callbacks.onSettingChanged(key, value) },
                    // The shell's OWN export and import flows, not new pickers: both already run on the scope
                    // that owns the transfer controller, and the import already verifies the session
                    // passphrase against the file being copied and republishes the rows inside the swap lock.
                    onExportDatabase = callbacks.onExportDatabase,
                    onImportDatabase = callbacks.onImportDatabase,
                    // One invocation of the shell's in-process backup runner, which is the same `runBackup`
                    // the scheduled loop calls - so "back up now" is a real backup on this platform and not a
                    // request to a scheduler.
                    onManualBackupRequested = { databaseScope.launch { platformSeams.backupScheduler.runNow() } },
                    onDismissBackupFailure = callbacks.onDismissBackupFailure,
                )
            }
        desktopShellSurface(
            landingRoute = sessionLandingRoute.storageKey,
            startupGate = startupGate,
            state = models.shellRenderState,
            sessionLogger = sessionLogger,
            // The rail and the graph are SIBLINGS - the rail cannot reach the graph's controller - so the
            // rail's intent is stored here and the graph navigates when it can.
            topLevelNavigationRequest = railRouteRequest,
            onTopLevelNavigationRequestConsumed = { railRouteRequest = null },
            callbacks = callbacks,
            databaseInitCollaborators =
                remember(databaseSessionController, documentSource, stores) {
                    DesktopDatabaseInitCollaborators(
                        sessionController = databaseSessionController,
                        documentSource = documentSource,
                        // The shell's OWN vault, over the one security store every gate shares - a second
                        // vault here would report a lockout the unlock gate does not apply.
                        passphraseVault = stores.passphraseVault,
                        // The shell's OWN onboarding marker: device-level, so the reset's own delete cannot
                        // make it claim this installation has never seen the tour.
                        onboardingStore = stores.onboardingStore,
                    )
                },
            passphraseUnlockCollaborators =
                remember(databaseSessionController, stores) {
                    DesktopPassphraseUnlockCollaborators(
                        // The shell's OWN security collaborators, not a second set: the desktop's startup
                        // gate, this unlock gate and the forgot-passphrase reset all read the same store, so
                        // a lockout counted in one is a lockout in all three.
                        vault = stores.passphraseVault,
                        biometricPrompt = stores.biometricUnlockPrompt,
                        forgottenPassphraseReset = stores.forgottenPassphraseReset,
                        // The shell's OWN session controller, so an unlock here installs the data layer the
                        // shell reads through instead of opening a second session and dropping its handle.
                        sessionController = databaseSessionController,
                        // The SAME path the reset was built with, taken from the store that already resolves
                        // it - so the file this gate opens is the file that reset deletes.
                        databaseFile = stores.databaseProbe.getDatabaseFilePath().toFile(),
                    )
                },
            passphraseChangeCollaborators =
                remember(databaseSessionController, stores) {
                    DesktopPassphraseChangeCollaborators(
                        // The SAME vault the unlock gate verifies against. This screen writes the new
                        // passphrase through it, so a second vault would persist a credential the unlock
                        // gate cannot check - and would keep its own failure counter.
                        vault = stores.passphraseVault,
                        // The shell's OWN session controller, so the close-and-re-open this flow performs
                        // is the one whose handle the shell still holds afterwards.
                        sessionController = databaseSessionController,
                    )
                },
            settingsCollaborators = settingsCollaborators,
        )
    }
}

/**
 * The READY shell: nav rail + content pane under one root surface.
 *
 * The language derivation does NOT live here - it lives in [desktopShellRootSurface], the composable this
 * function calls, because that is the seam a test can actually render (see there). Everything below the
 * root is handed the derived value rather than deriving again.
 */
@Composable
private fun desktopShellSurface(
    state: DesktopShellRenderState,
    sessionLogger: UnifiedLogger,
    /** The latched start destination. See the latch in `desktopApp`. */
    landingRoute: String,
    /**
     * The shared graph's four gate flags, projected from the one startup decision.
     *
     * Handed down rather than re-derived: this shell is mounted for every gate-bearing mode, so passing the
     * real decision is what puts Android's own gate screens in front of a fresh Linux install.
     *
     * It is the resolved mode itself, not a projection of it: one value crosses this boundary, and each mount
     * site below reads the gate it names.
     */
    startupGate: StartupGateInputs,
    /** The rail's pending top-level route, or null; owned by `desktopApp`. */
    topLevelNavigationRequest: String?,
    /** Called once the graph has honoured or refused the request; clears the owner's holder. */
    onTopLevelNavigationRequestConsumed: () -> Unit,
    callbacks: DesktopShellCallbacks,
    databaseInitCollaborators: DesktopDatabaseInitCollaborators,
    /** The unlock screen's collaborators, threaded down for the same reason as the init screen's. */
    passphraseUnlockCollaborators: DesktopPassphraseUnlockCollaborators,
    /**
     * The passphrase-change screen's collaborators, threaded for the same reason: the graph mutates this
     * vault and closes this session controller, so it has to reach the shell's own instances.
     */
    passphraseChangeCollaborators: DesktopPassphraseChangeCollaborators,
    /**
     * The settings screen's collaborators, built in `desktopApp` for the same reason as the other three:
     * this destination writes the shell's own rows, runs its backup on the shell's scope and reads the
     * shell's database file, none of which `desktopShellSurface` can reach.
     */
    settingsCollaborators: DesktopSettingsCollaborators,
) {
    desktopShellRootSurface(
        preferences = state.preferences,
    ) { language ->
        // The shipped shape is phone-shaped at EVERY window size, so the layout is held at the phone width
        // and centred rather than being allowed to grow. Without this the content pane lays itself out as a
        // desktop surface once the window is resized - which is the "different shape at a different size"
        // the sizing rule forbids, and it is not cosmetic: controls that are absent at the phone width
        // (the shared screens' floating action button among them) appear only in that second shape.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(PHONE_WINDOW_WIDTH_DP.dp)
                        .fillMaxHeight(),
            ) {
                desktopContentPane(
                    state = state,
                    language = language,
                    landingRoute = landingRoute,
                    startupGate = startupGate,
                    topLevelNavigationRequest = topLevelNavigationRequest,
                    onTopLevelNavigationRequestConsumed = onTopLevelNavigationRequestConsumed,
                    callbacks = callbacks,
                    databaseInitCollaborators = databaseInitCollaborators,
                    passphraseUnlockCollaborators = passphraseUnlockCollaborators,
                    passphraseChangeCollaborators = passphraseChangeCollaborators,
                    settingsCollaborators = settingsCollaborators,
                )
            }
        }
    }
}

/**
 * The shell root: theme from the shared rows, and the ONE language derivation for the whole shell.
 *
 * ONE derivation, from the SHARED rows - never the machine locale. `desktopContentPane` mounts the shared
 * navigation graph, and that graph installs its OWN `LocalUiStrings` provider from the `language` it is
 * handed, while `desktopRootSurface` installs another for everything rendered outside the graph. Deriving
 * at both sites would let a preference write landing between the two reads give each site a different
 * language, so the value is computed once HERE and handed to both.
 *
 * Why this is a function of its own rather than two lines inside [desktopShellSurface]: it is the smallest
 * composable the shell root calls that decides the language, so a test can render it with a `preferences`
 * row and a contradicting JVM default locale without mounting the shared navigation graph beneath it.
 *
 * @param preferences the SHARED rows. `effectiveLanguageTag` is the input, so the row decides - and so does
 *   the theme, which [desktopRootSurface] derives from these same rows.
 * @param content receives the derived language, so the graph below is handed the value this root installed
 *   instead of deriving a second one.
 */
@Composable
internal fun desktopShellRootSurface(
    preferences: AppPreferencesState,
    content: @Composable (UiLanguage) -> Unit,
) {
    val language = desktopUiLanguageForTag(preferences.effectiveLanguageTag)
    // The JVM default locale is what every SHARED formatter reads for dates, weekday names and the "last
    // updated" timestamps (`Locale.getDefault()` in the shared screens). Android applies the app language to
    // it; the desktop used to leave it at the MACHINE locale, so an English app on a Tamil machine rendered
    // Tamil dates. Installed here, once per language change and BEFORE the graph below composes, so no
    // formatter can read the previous locale for a frame.
    val locale = desktopLocaleForTag(preferences.effectiveLanguageTag)
    remember(locale) { Locale.setDefault(locale) }
    desktopRootSurface(
        language = language,
        preferences = preferences,
    ) {
        content(language)
    }
}

@Composable
private fun desktopStateEffects(
    snapshot: SettingsFoundationSnapshot,
    activeRoute: DesktopTopLevelRoute,
    startupGate: StartupGateInputs,
    lifecycleState: DesktopLifecycleState,
    navigationModel: DesktopNavigationModel,
    onRouteReset: (DesktopTopLevelRoute) -> Unit,
    sessionLogger: UnifiedLogger,
) {
    LaunchedEffect(snapshot.schemaVersion, activeRoute.storageKey) {
        sessionLogger.i(
            source = "DesktopApp.desktopApp",
            message = "Desktop app state rendered",
            data =
                mapOf(
                    "schemaVersion" to snapshot.schemaVersion,
                    "activeRoute" to activeRoute.storageKey,
                    "startupGateActive" to !startupGate.isLandingReady,
                    "startupMode" to startupGate.gateName,
                    "sharedReadyAreas" to snapshot.areasWithStatus(FoundationReadiness.SharedReady),
                    "extractionNextAreas" to snapshot.areasWithStatus(FoundationReadiness.ExtractionNext),
                    "androidOnlyAreas" to snapshot.areasWithStatus(FoundationReadiness.AndroidOnly),
                ),
        )
    }
    // From the navigation model, which is derived from the SHARED preferences - the same rows the rail
    // and the startup gate read. The desktop snapshot used to be the source here, and it was a second
    // answer to each of these questions.
    LaunchedEffect(navigationModel) {
        sessionLogger.i(
            source = "DesktopApp.navigationModel",
            message = "Shared navigation state active",
            data =
                mapOf(
                    "launchRoute" to navigationModel.launchRoute.storageKey,
                    "visibleRouteCount" to navigationModel.primaryRoutes.size,
                ),
        )
    }
    // The facts this used to log lived in the bootstrap store and are gone: the lifecycle flag is now
    // derived from the database file, and onboarding completion + the launch route are read from the
    // shared rows (logged above, from the navigation model). The bootstrap snapshot carries a schema
    // version and nothing else, so the only honest thing to log is that it is in force.
    LaunchedEffect(lifecycleState.bootstrapSchema) {
        sessionLogger.i(
            source = "DesktopApp.desktopBootstrap",
            message = "Desktop bootstrap state active",
            data = mapOf("schemaVersion" to lifecycleState.bootstrapSchema.schemaVersion),
        )
    }
    LaunchedEffect(startupGate) {
        sessionLogger.i(
            source = "DesktopApp.desktopStartupMode",
            message = "Desktop startup mode active",
            data = mapOf("startupMode" to startupGate.gateName),
        )
    }
    LaunchedEffect(navigationModel.primaryRoutes, navigationModel.launchRoute) {
        if (activeRoute !in navigationModel.primaryRoutes) {
            onRouteReset(navigationModel.launchRoute)
        }
    }
}

/**
 * The desktop's root surface: the shared theme, the desktop's own palette, and the ambient copy table.
 *
 * The appearance is not a parameter: it is derived here from the SAME preference rows this surface is
 * already handed, through the shared theme's preference entry point - the one Android calls with its own
 * rows. A `darkTheme` boolean used to arrive from above with the resolution already applied, and that
 * resolution was the desktop's own: it read `DARK` and treated every other value, `SYSTEM` included, as
 * light. Deriving it here keeps one translation of the preference and gives `SYSTEM` the same meaning it
 * has on Android.
 *
 * @param language the copy language for every shared screen below this root. Undefaulted, and for the same
 *   reason a defaulted theme would be wrong - a default here would silently reinstate a language the caller
 *   did not choose (the machine locale, most likely). Requiring it makes each call site state where its
 *   answer comes from: the shared rows after unlock, the machine locale in the pre-unlock gate.
 */
@Composable
internal fun desktopRootSurface(
    language: UiLanguage,
    preferences: AppPreferencesState,
    content: @Composable () -> Unit,
) {
    // The SHARED theme, so anything rendered from :core:ui gets the same Material 3 tokens Android does -
    // and the dynamic-colour seam runs here too (the desktop's actual answers null, selecting the fallback).
    // It is now the ONLY theme installed here: the desktop's own routes were migrated off their Material 2
    // components, so the palette that used to wrap this is gone rather than kept for their sake.
    io.payanam.core.ui.theme.PayanamTheme(themeMode = preferences.themeMode, fontFamily = preferences.fontFamily) {
        CompositionLocalProvider(
            // The shared screens read their copy from this ambient table - they replaced their
            // stringResource(...) calls with lookups against it. Android installs it once inside
            // PayanamNavHost, but this desktop renders shared screens without that graph (the
            // startup flow and, until every screen lands, individual routes), so the root installs
            // it. Reading it with no provider calls error() and takes the whole surface down.
            LocalUiStrings provides UiStrings.of(language),
            // The SHARED feature flags, from the same `PlatformFeatureFlags` holder Android's
            // `MainActivity` reads. Without this, every shared screen reading `LocalFeatureFlags.current`
            // gets the DEFAULT `FeatureFlagsState()` (remindersEnabled = true), so the desktop renders
            // reminder controls even though `DesktopMain.kt` installs `remindersEnabled = false`.
            LocalFeatureFlags provides PlatformFeatureFlags.current,
            // The SHARED preference rows, from the same `app_settings` table Android's
            // `MainActivity` reads. Undefaulted for a sharper reason than the two above: this local
            // DEFAULTS rather than failing, so a route that forgot to provide it would render a real
            // screen at the default zoom with fallback dimension labels and none of the user's timeline
            // settings - a wrong screen, not a crash. Every entry point therefore states which rows it
            // is holding, and the pre-unlock ones say so by naming the defaults they pass.
            LocalAppPreferences provides preferences,
            // The desktop's text scale, the counterpart of Android's `font_scale`. Provided at the ROOT so
            // every shared screen and its copy scale together; without it the desktop drew every glyph 25%
            // larger than the phone at the same dp window (see [DESKTOP_TEXT_SCALE]).
            LocalDensity provides Density(LocalDensity.current.density, fontScale = DESKTOP_TEXT_SCALE),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                // The window's own surface. This paints the full-size root the whole shell sits on -
                // not a card, not the rail chrome - and `surface` is the matching Material 3 role for a
                // window background. `surfaceVariant` would put a second, competing surface tint on the
                // background of every screen.
                color = Material3Theme.colorScheme.surface,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun desktopContentPane(
    state: DesktopShellRenderState,
    /** The ONE language derived at the shell root, so the graph and the root cannot disagree. */
    language: UiLanguage,
    /** The latched start destination, not the rail's current selection. */
    landingRoute: String,
    /**
     * The shared graph's gate flags, threaded from [desktopShellSurface] unmodified.
     *
     * Undefaulted on purpose: a default here would be a second answer to which gate is showing, and the
     * answer the mount already made would lose.
     */
    startupGate: StartupGateInputs,
    /** The rail's pending top-level route, or null - the desktop's counterpart to Android's command channel. */
    topLevelNavigationRequest: String?,
    onTopLevelNavigationRequestConsumed: () -> Unit,
    callbacks: DesktopShellCallbacks,
    /**
     * The database-init screen's own collaborators, threaded down from [desktopApp] so the shared graph
     * reaches the same session controller, document source and vault the shell already holds.
     */
    databaseInitCollaborators: DesktopDatabaseInitCollaborators,
    /**
     * The unlock screen's own collaborators, threaded down from [desktopApp] so the shared graph reaches the
     * same vault, reset and database file the desktop's own startup gate uses.
     */
    passphraseUnlockCollaborators: DesktopPassphraseUnlockCollaborators,
    /**
     * The passphrase-change screen's collaborators, threaded for the same reason: the graph mutates this
     * vault and closes this session controller, so it has to reach the shell's own instances.
     */
    passphraseChangeCollaborators: DesktopPassphraseChangeCollaborators,
    /** The settings screen's collaborators, threaded for the same reason as the passphrase-change screen's. */
    settingsCollaborators: DesktopSettingsCollaborators,
) {
    // The desktop adds NO chrome of its own around the shared graph: no header above it and no inset around
    // it. The shared screens already render their own header, so the desktop's was a second, stale copy on
    // every screen; and the pane IS the app's surface, so a 28.dp inset here is a border the Android build
    // does not have. One convention on both platforms: the graph gets the whole pane.
    val paneStrings = LocalUiStrings.current
    val paneModifier =
        if (startupGate.isLandingReady) {
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription =
                        paneStrings.desktopContentPaneFor.formatArgs(state.activeRoute.displayName)
                }
        } else {
            Modifier.fillMaxSize()
        }
    Box(modifier = paneModifier) {
        // The shared navigation graph, mounted. It owns the routes, the back stack and the
        // parameterised detail routes the desktop's flat 7-tab enum could not express.
        //
        // The gate reaches this function as `startupGate` - the one resolved decision, handed down unmodified.
        // It was previously re-derived here from a hardcoded `Ready`, which is what left a fresh Linux install
        // showing the desktop's own gate instead of the shared graph's `DatabasePassphraseSetupScreen` and
        // `DatabaseInitScreen`.
        // Named local rather than an inline multi-line argument: `DesktopNavHostParityTest` reads this call
        // site to prove the desktop supplies the same parameters Android does, and its scanner pairs a
        // `name =` with a value on the SAME line. An inline `remember { … }` spanning several lines reads as
        // a parameter named `screens` with an empty value, which fails the parity gate for a formatting
        // reason rather than a wiring one - so the shape is chosen to be legible to the guard that checks it.
        val navHostScreens =
            remember(
                databaseInitCollaborators,
                passphraseUnlockCollaborators,
                passphraseChangeCollaborators,
                language,
                settingsCollaborators,
                callbacks.onDatabaseResetObserved,
            ) {
                DesktopPayanamNavHostScreens(
                    databaseInitCollaborators = databaseInitCollaborators,
                    passphraseUnlockCollaborators = passphraseUnlockCollaborators,
                    passphraseChangeCollaborators = passphraseChangeCollaborators,
                    language = language,
                    settingsCollaborators = settingsCollaborators,
                    // The shell's own reaction to a reset: without it the startup decision is read from
                    // facts the reset has invalidated, which is how the graph ended up composing the app
                    // route with no data layer installed.
                    onDatabaseResetObserved = callbacks.onDatabaseResetObserved,
                )
            }
        // A composition scope for the unlock callback inside this mount: the reconcile it launches must not
        // block the unlock's own navigation, and this is the composition that owns the mount. Deliberately
        // local rather than threaded in - nothing else in the pane needs a scope, and a parameter would be a
        // second place for the same handle to live.
        val unlockScope = rememberCoroutineScope()
        // The gate reaches this call as `startupGate` - the inputs the one shared decision produced, handed
        // down unmodified. Nothing here translates or re-decides them.
        PayanamNavHost(
            screens = navHostScreens,
            // The value the shell root derived from the shared rows, not a second derivation: the graph
            // installs its own LocalUiStrings provider from this parameter, so re-deriving here could
            // give the graph a different language than the root it is nested in.
            language = language,
            // The backup-failure banner, from the SAME shared rows Android reads. The desktop already
            // holds `AppPreferencesState` in `state.preferences`, so these are two field reads rather than
            // new state - the banner was absent only because nobody passed them.
            backupFailureMessage = state.preferences.autoBackupLastErrorMessage,
            backupFailureAt = state.preferences.autoBackupLastErrorAt,
            onDismissBackupFailure = callbacks.onDismissBackupFailure,
            // Android's mechanism: the graph asks for a silent unlock of the route it needs re-auth for,
            // and the host captures it to return to after. `resumeToRouteAfterUnlock` is the SAME field
            // Android keeps; Android additionally mirrors it into an intent extra, which exists only to
            // survive process death - the desktop has no activity recreation, so the field is the whole
            // mechanism and the extra has no counterpart to copy.
            resumeToRouteAfterUnlock = state.resumeToRouteAfterUnlock,
            onSilentUnlockRequested = { route -> callbacks.onSilentUnlockRequested(route) },
            onUnlockReturnRouteConsumed = { callbacks.onUnlockReturnRouteConsumed() },
            // Minimal mode, read from the same named source Android installs it into: `PlatformFeatureFlags`,
            // which `PayanamApp.onCreate` fills from `BuildConfig.MINIMAL_MODE` and `DesktopMain.main` fills
            // from this platform's own declared values, before any composition exists. The desktop takes no
            // build-time capability flag - it is a full-feature build - so the field it installs is the
            // explicit `false` this reads back rather than a generated constant.
            //
            // A READ rather than a hard-coded `false` so this mount site cannot disagree with the shared
            // ViewModels, which already take the flag from that same holder (see `TimeViewModel`,
            // `TasksViewModel`): hard-coding left the graph's answer independent of the state layer's,
            // which is exactly the drift this removes. Tab visibility, which IS a shared preference row
            // and is what focus preset actually drives, is passed below and does the same job at runtime.
            minimalModeEnabled = PlatformFeatureFlags.current.minimalModeEnabled,
            tabVisibility = state.preferences.tabVisibility,
            // The LATCHED landing route, not the rail's selection: a start destination that keeps tracking
            // the rail would be a second, racing answer to "where is the graph" alongside the request
            // channel below, and the graph would reinstall on every rail tap.
            landingRoute = landingRoute,
            isLandingReady = startupGate.isLandingReady,
            // The rail's channel: the graph navigates when it can and reports the request honoured or
            // refused, so this holder can be cleared.
            topLevelNavigationRequest = topLevelNavigationRequest,
            onTopLevelNavigationRequestConsumed = onTopLevelNavigationRequestConsumed,
            // Which gate is the window, read off the ONE resolved decision. Android feeds these same
            // parameters from the facts it holds; the desktop feeds them from its resolver's answer, so
            // neither platform has a second place where "what is the user looking at" is decided.
            shouldShowPassphraseSetup = startupGate.shouldShowPassphraseSetup,
            shouldShowPassphraseUnlock = startupGate.shouldShowPassphraseUnlock,
            shouldShowDatabaseInit = startupGate.shouldShowDatabaseInit,
            shouldShowFocusModeOnboarding = startupGate.shouldShowFocusModeOnboarding,
            shouldShowSecurityStateUnavailable = startupGate.shouldShowSecurityStateUnavailable,
            shouldShowOnboardingIntro = startupGate.shouldShowOnboardingIntro,
            // The refusal destination's two actions: the reset the dialog confirmed, and the exit path
            // every other terminal gate already uses.
            onOnboardingIntroFinished = callbacks.onOnboardingIntroAdvanced,
            onSecurityResetRequested = callbacks.onSecurityResetRequested,
            onQuitRequested = callbacks.onQuitRequested,
            onFocusModePresetSelected = callbacks.onCompleteFocusModeOnboarding,
            onRestartAfterDelete = callbacks.onQuitRequested,
            // The passphrase-change destination's restart duty, and the same exit path the delete flow
            // already uses: it ends this process through the path that also releases the single-instance
            // lease, which is what a spawned replacement needs before it can acquire one. Supplied now so
            // the promotion has the seam wired; nothing fires it yet, because the desktop's own
            // passphrase-change destination is still unwired.
            onRestartAfterPassphraseChange = callbacks.onQuitRequested,
            onDatabaseReady = callbacks.onGraphDatabaseReady,
            // Android runs its post-unlock initialisation here (`handlePostUnlockInitState()`, MainActivity
            // :360). The desktop's equivalent is the reconcile the session-open path already runs on launch -
            // same call, same guard - so what runs on the desktop is that reconcile and nothing more: this
            // platform deliberately does not recompute score roll-ups (a declared divergence, see
            // DesktopUnsupportedPorts.scoreRollupTrigger), and inventing a step it cannot perform would make
            // the log claim a reconciliation that did not happen.
            //
            // Launched rather than awaited: the unlock's own navigation must not wait on a WAL-backed
            // rollover, and the reconcile is guarded, so an overlapping resume cannot double-run it.
            onPassphraseUnlocked = {
                unlockScope.launch { DesktopDatabaseSessionController.reconcileProcessSessionOnResume() }
            },
        )
    }
}

/**
 * Foundation facts for a data module selection (used by the settings screen).
 */
fun desktopFoundationSnapshot(selection: DataModuleSelection): SettingsFoundationSnapshot = SettingsFoundationContracts.snapshot(selection)

/**
 * Where a scheduled backup is written, and why that name.
 *
 * `<root>/exports` - the SAME directory the user-initiated export already uses, so a scheduled backup and
 * a manual one land together and the exports folder holds one kind of thing. The file name is timestamped
 * rather than fixed because a fixed name would be overwritten by the next run and a user's only backup
 * would silently be their most recent, not their last known good.
 *
 * The directory is created here because nothing else does: the database directory is created on
 * demand by the first write, so the backup ensures it exists before the transfer begins.
 */
internal fun desktopScheduledBackupDestination(databaseDirectory: Path = DesktopAppPaths.resolveDatabaseDirectory()): Path {
    // A failure to create it is not swallowed: the gateway reports `DESTINATION_UNWRITABLE` and the
    // scheduler logs it, which is the honest answer - a backup that has nowhere to go did not happen.
    Files.createDirectories(databaseDirectory)
    val stamp = LocalDateTime.now().format(BACKUP_FILE_TIMESTAMP)
    // The NAME comes from the shared prefix the rotation matcher prunes with, so an artifact this
    // app writes is one rotation can recognise; a name assembled only here would be invisible to it.
    val plain = databaseDirectory.resolve(scheduledBackupArtifactName(stamp))
    if (!Files.exists(plain)) return plain
    // A SECOND run inside the same second would otherwise name the same file, and the export copies
    // with REPLACE_EXISTING - so the first backup would be silently overwritten and rotation would
    // count the survivor once. The path is only CHECKED, never created: creating it here to claim
    // the name would leave an empty artifact behind whenever the transfer fails, which rotation would
    // then prune as though it were a backup. The counter is zero-padded and fixed-width because the
    // rotation tie-break sorts by NAME descending, so `-10` must not sort before `-2`.
    var attempt = 2
    while (true) {
        val suffix = "-${attempt.toString().padStart(2, '0')}"
        val candidate = databaseDirectory.resolve(scheduledBackupArtifactName(stamp + suffix))
        if (!Files.exists(candidate)) return candidate
        attempt++
    }
}

/**
 * The backup file's timestamp, to the second: sortable, and fixed-width so name order stays
 * chronological order. Resolution to the SECOND is not uniqueness on its own - a second run in the
 * same second repeats the stamp, and [desktopScheduledBackupDestination] disambiguates those.
 */
private val BACKUP_FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * The dispatcher the backup's file work is done on - deliberately NOT the caller's.
 *
 * Both triggers reach this backup through `databaseScope`, which is `rememberCoroutineScope()` and so is
 * bound to Compose Desktop's Main dispatcher, i.e. the AWT event dispatch thread: the scheduled loop at
 * `DesktopBackupScheduler.schedule` and the settings screen's "back up now" row both land there. A backup
 * copies the whole encrypted database and then walks the exports folder deleting what retention no longer
 * keeps, so the window freezes for the length of both - with no repaint, no input handling and no
 * progress, which reads to a user as a hang rather than as a slow backup.
 *
 * `Dispatchers.IO` rather than a private thread because the work is a blocking file copy and a recursive
 * delete: that is precisely what the IO pool's threads are blocked FOR, and this is the same idiom
 * `AwtDocumentSource` uses for the same reason - hop off the caller's thread before doing slow blocking
 * work, and let the UI thread go back to painting.
 */
private val BACKUP_DISPATCHER: CoroutineDispatcher = Dispatchers.IO

/**
 * ONE scheduled backup: fold the open session's write-ahead log, copy the encrypted file, then prune.
 *
 * Hoisted out of the shell's `runBackup` lambda - and named, like [pruneScheduledBackupRotation] and
 * [desktopScheduledBackupDestination] beside it - for the reason that makes it a function at all: the hop
 * to [BACKUP_DISPATCHER] has to wrap work that lives somewhere a `withContext` can enclose, and a lambda
 * threaded through the seams constructor has nowhere to put one that a test can see.
 *
 * WHAT it does is unchanged, and that is the constraint: same gateway, same destination rule, same
 * artifact name and path, same rotation on the same success branch. Only the THREAD it happens on moved.
 */
private suspend fun runScheduledDesktopBackup(
    sessionLogger: UnifiedLogger,
    databaseSessionController: DesktopDatabaseSessionController,
    backupRows: suspend () -> Map<String, String?>,
    stores: DesktopPersistenceStores,
) {
    // A scheduled run is a REAL backup through the SHARED coordinator core - the same
    // DatabaseBackupCoordinator logic Android runs (Android parity): checkpoint/WAL gate,
    // verified snapshot with retries, status recording, and rotation all live in
    // BackupCoordinatorCore; this function only supplies the desktop's seams.
    withContext(BACKUP_DISPATCHER) {
        val tempSnapshotDir = Files.createTempDirectory("payanam_backup_").toFile()
        runCatching {
            val checkpointPort = object : BackupCheckpointPort {
                override fun isDbOpen(): Boolean = databaseSessionController.isOpen
                override fun foldWal(): WalCheckpointRule.FoldReport =
                    databaseSessionController.checkpoint() ?: WalCheckpointRule.NO_ROW
            }
            val core = BackupCoordinatorCore(
                dbFile = DesktopAppPaths.resolveDatabaseDirectory()
                    .resolve(PayanamDatabaseSchema.DATABASE_NAME).toFile(),
                tempSnapshotDir = tempSnapshotDir,
                checkpointPort = checkpointPort,
                retryPolicy = retryPolicyFor(BackupTrigger.AUTO),
                logger = sessionLogger,
                backupDirectory = DesktopAppPaths.resolveDatabaseDirectory().toFile(),
                sessionFolderName = {
                    autoBackupDirName(
                        DesktopBuildInfo.PLATFORM,
                        DesktopBuildInfo.VERSION_NAME,
                        DesktopBuildInfo.BUILD_SHORT_SHA,
                        DesktopBuildInfo.IS_DEBUG_BUILD,
                        LocalDateTime.now().format(BACKUP_FILE_TIMESTAMP),
                    )
                },
                destinationFileName = PayanamDatabaseSchema.DATABASE_NAME,
                rotationRows = backupRows,
                failureMessage = { trigger, maxAttempts, error ->
                    "Scheduled backup failed (${trigger.key}): " +
                        (error.message ?: error::class.java.simpleName)
                },
                recordSuccess = { recordedAtMillis ->
                    stores.backupStatusStore.recordSuccess(recordedAtMillis)
                },
                recordFailure = { finalMessage ->
                    stores.backupStatusStore.recordFailure(finalMessage)
                },
            )
            val result = core.backupToAppBackupDirectory(BackupTrigger.AUTO)
            sessionLogger.i(
                "DesktopApp.scheduledBackup",
                "Scheduled backup completed: ${result.destinationPath}",
                mapOf(
                    "destination" to result.destinationPath,
                    "attempts" to result.attemptsUsed,
                ),
            )
        }.onFailure { error ->
            // A cancelled backup is a stopped coroutine, not a failed backup: rethrow so the
            // hop unwinds the way Android's coordinator does.
            if (error is kotlinx.coroutines.CancellationException) throw error
            sessionLogger.w(
                "DesktopApp.scheduledBackup",
                "Scheduled backup failed: ${error.message ?: error::class.java.simpleName}",
                mapOf("error" to (error.message ?: error::class.java.simpleName)),
            )
        }
        tempSnapshotDir.deleteRecursively()
    }
}

/**
 * Prunes old backup artifacts in the folder a scheduled backup just wrote into.
 *
 * [backupRows] and [backupDirectory] are parameters so this is exercisable without an open session and
 * without the real exports folder: the reader is passed in rather than reached for, so the scheduled
 * path and the backup port can be shown to read retention through the SAME source instead of two
 * implementations of one idea.
 *
 * A read that fails is logged and nothing is pruned: retention is a policy applied after the fact, and
 * an unreadable policy must never delete a backup the user believes they have. Pruning itself reports
 * per-artifact and never fails the backup that triggered it.
 *
 * Returns what was deleted, so the caller can report it and a test can assert it.
 */
internal suspend fun pruneScheduledBackupRotation(
    backupDirectory: java.io.File,
    backupRows: suspend () -> Map<String, String?>,
    // FULLY QUALIFIED, deliberately: this file is in `io.payanam.desktop`, which declares its OWN
    // `UnifiedLogger` class, so a bare `UnifiedLogger` here would resolve to that class and the helper
    // would demand the desktop's concrete logger instead of the shared interface every other backup
    // collaborator takes. That is the same shadowing the debug-flags class documents for the same reason.
    logger: io.payanam.shared.logging.UnifiedLogger,
): List<java.io.File> {
    val rows =
        runCatching { backupRows() }
            .onFailure { error ->
                logger.w(
                    source = "DesktopApp.pruneScheduledBackupRotation",
                    message = "Backup rotation skipped: the retention rows could not be read",
                    data = mapOf("failure" to error.javaClass.simpleName),
                )
            }.getOrNull() ?: return emptyList()
    val policy = rotationPolicyFromBackupRows(rows)
    return pruneOldBackupDirectories(backupDirectory, policy, logger)
}

/**
 * Whether a scheduled export actually produced a file, so the log level matches the fact.
 *
 * A case rather than a flag argument at the call site: the two failure shapes mean different things
 * (nothing was open, versus the gateway refused) and both are a warning, so the caller does not have to
 * enumerate the refusal reasons to classify them.
 */
private fun DesktopTransferOutcome.succeededForBackup(): Boolean =
    when (this) {
        is DesktopTransferOutcome.Gateway -> outcome is TransferOutcome.Succeeded
        DesktopTransferOutcome.NothingToExport -> false
        is DesktopTransferOutcome.ImportedButUnopenable -> false
    }

/** The one answer to "where does the database live", shared by the create, the open and the transfer. */
private fun desktopDatabaseFilePath(): Path =
    DesktopAppPaths
        .resolveDatabaseDirectory()
        .resolve(DesktopAppPaths.DATABASE_FILE_NAME)

/**
 * The reconcile that follows a settings row - for the two keys the backup schedule is derived from.
 *
 * Extracted from the shell's `onSettingChanged` callback so the pair the behaviour consists of can be
 * driven by a test: the row is written first (by the caller, through the same guarded source), and only
 * the keys named here move the schedule. Reconciling on every other key would be harmless - the
 * reconcile is idempotent - and noisy, so the two-key list IS the decision, and it belongs where a test
 * can pin it rather than inline in a composable's lambda.
 *
 * The toggle's whole job is the SCHEDULE, so the schedule follows the row - Android's `AutoBackupWorker`
 * rule, applied to the in-process timer.
 */
internal suspend fun reconcileAfterSettingChange(
    platformSeams: DesktopPlatformSeams,
    key: String,
) {
    if (key == AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED ||
        key == AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL
    ) {
        platformSeams.reconcileBackupSchedule()
    }
}
