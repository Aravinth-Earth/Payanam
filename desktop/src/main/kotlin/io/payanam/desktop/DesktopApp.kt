//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
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
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
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
import io.payanam.core.ui.day.DayScreen
import io.payanam.core.ui.day.DayScreenMode
import io.payanam.core.ui.navigation.PayanamNavHost
import io.payanam.core.ui.notes.NotesScreen
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
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
import io.payanam.desktop.platform.desktopPlatformSeams
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.day.dayViewModel
import io.payanam.presentation.notes.notesViewModel
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.FoundationArea
import io.payanam.shared.settings.FoundationReadiness
import io.payanam.shared.settings.SettingsFoundationContracts
import io.payanam.shared.settings.SettingsFoundationSnapshot
import io.payanam.shared.transfer.DataModuleSelection
import io.payanam.shared.transfer.TransferOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
        remember(models.startupSnapshot, models.startupMode) {
            mapOf(
                "launchRoute" to models.startupSnapshot.launchRoute.storageKey,
                "readyChecks" to models.startupSnapshot.readyChecks(),
                "totalChecks" to models.startupSnapshot.checks.size,
                "requiresAttention" to models.startupSnapshot.requiresAttention(),
                "startupMode" to models.startupMode.name,
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
        startupMode = models.startupMode,
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
    // schedule and the reminder sink. Both were implemented and unit-tested and reachable from nowhere,
    // so the settings screen's auto-backup toggle wrote `auto_backup_enabled` and the row stopped there -
    // a Linux user could turn auto-backup on, pick 15 minutes, and receive no backups at all.
    //
    // Built HERE, immediately after the transfer controller, because that is where everything it needs
    // already exists: the scope, the session controller (the fold), the export gateway (the copy) and
    // the session logger.
    val platformSeams =
        remember(databaseSessionController, transferController, sessionLogger) {
            desktopPlatformSeams(
                scope = databaseScope,
                logger = sessionLogger,
                runBackup = backup@{
                    // A scheduled run is a REAL backup, through the SAME gateway the export button uses:
                    // fold the open session's write-ahead log, then copy the encrypted file. The fold is
                    // the gateway's own precondition as well, so a backup can never be a snapshot missing
                    // the user's most recent writes - and a fold that failed must not produce a file that
                    // looks like a backup.
                    if (!WalCheckpointRule.walIsEmpty(databaseSessionController.checkpoint())) {
                        sessionLogger.w(
                            source = "DesktopApp.scheduledBackup",
                            message = "Scheduled backup skipped: the open session's log could not be folded",
                        )
                        return@backup
                    }
                    val destination = desktopScheduledBackupDestination()
                    val outcome = transferController.exportTo(destination.toString())
                    val data = mapOf("fileName" to destination.fileName.toString())
                    val summary =
                        DesktopTransferMessages.describe(
                            outcome,
                            DesktopTransferMessages.TransferDirection.EXPORT,
                            destination.toString(),
                        )
                    if (outcome.succeededForBackup()) {
                        sessionLogger.i("DesktopApp.scheduledBackup", summary, data)
                        // ROTATION, on the path that actually runs. It belongs here, immediately after a
                        // SUCCESSFUL backup, for the reason Android puts it in its coordinator: a backup
                        // nobody prunes is a folder that grows without bound, and the user's retention
                        // setting is then a control over nothing. Rotation used to live only in
                        // `DesktopDatabaseBackupPort`, which the running app never constructs - so the
                        // only path that wrote a backup artifact was also the only path that never pruned
                        // one, and every test of it was a test of a class nothing called. The rows are
                        // read the same way the schedule reads them, so the two cannot disagree.
                        pruneScheduledBackupRotation(
                            exportDirectory = DesktopAppPaths.resolveExportDirectory().toFile(),
                            backupRows = backupRows,
                            logger = sessionLogger,
                        )
                    } else {
                        sessionLogger.w("DesktopApp.scheduledBackup", summary, data)
                    }
                },
                // The shared `app_settings` rows, read the same way every other desktop read reads them.
                // Before a session is open the data layer is UNINSTALLED and this throws; the seams
                // class treats a failed read as "no schedule" and logs it, so the window is never taken
                // down by a reconcile that ran too early.
                backupRows = backupRows,
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
            // The create returns the app's session as its proof and closes it again, so the app's OWN
            // session is established here - otherwise the gate would move past "database ready" with an
            // uninstalled data layer and every read would throw.
            onFirstRunDatabaseCreated = { passphrase ->
                databaseScope.launch {
                    databaseSessionController.openAndInstall(passphrase)
                    // The same flush as the unlock path, and for the same reason: a first-run create is the
                    // moment a BRAND-NEW empty database comes into existence, so this is the write that gives
                    // it its dimension rows. Skipping it here is the whole defect - an empty table on a
                    // database the user just created.
                    rememberedState.flushPendingFirstRunDimensions(sessionLogger)
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
                    // FIRST point a dimension write is possible at all: the user confirmed their dimensions
                    // before a database existed, so the catalog is seeded here or it is never seeded - and an
                    // unseeded `life_dimensions` table is what makes every note and time entry carry a
                    // dimension that resolves to nothing. Inside the same databaseScope as the open, and
                    // AFTER it, because the layer must be installed before the repository can be reached.
                    rememberedState.flushPendingFirstRunDimensions(sessionLogger)
                    // FIRST point the schedule can be read: the rows only exist once a session is in
                    // force, and the reconcile before this one necessarily found nothing.
                    platformSeams.reconcileBackupSchedule()
                }
            },
            onSettingChanged = { key, value ->
                databaseScope.launch {
                    rememberedState.preferencesSource.setSetting(key, value)
                    // The toggle's whole job is the SCHEDULE, so the schedule follows the row - Android's
                    // `AutoBackupWorker` rule, applied to the in-process timer. Reconciling on every other
                    // key would be harmless (idempotent) and noisy, so only these two trigger it.
                    if (key == AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED ||
                        key == AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL
                    ) {
                        platformSeams.reconcileBackupSchedule()
                    }
                }
            },
            // The focus preset is NINE rows written as one unit, under the source's own mutex - the same
            // path, launched the same way, because the callback this function builds is plain and the write
            // is suspend. DesktopApp owns databaseScope for exactly this reason.
            onFocusModePresetSelected = { preset ->
                databaseScope.launch { rememberedState.preferencesSource.setFocusModePreset(preset) }
            },
            // The first-run dimension catalog, for the same reason the focus preset is: the write is REAL
            // and suspend, so it runs on the scope this function owns rather than being logged and
            // forgotten, which is what left a fresh Linux install with no dimension rows at all.
            //
            // A session is not open yet at this point in a first run - the scaffold stages resolve BEFORE
            // the passphrase and database gates - so the write is attempted anyway and the seed is kept
            // either way: a confirmation that lands while a database IS open (a second run, or an import
            // that is already unlocked) writes straight through, and one that lands before any database
            // exists stays pending for the session-open flush below. The stage advances ONLY on a write
            // that landed or a catalog that was already there, which is what the old mock got wrong: it
            // advanced over a save no database had ever seen.
            onDimensionSetupPersist = { seed ->
                databaseScope.launch {
                    val outcome = rememberedState.preferencesSource.seedFirstRunDimensions(seed)
                    if (outcome == DesktopFirstRunDimensionWriteOutcome.Written ||
                        outcome == DesktopFirstRunDimensionWriteOutcome.AlreadyPresent
                    ) {
                        rememberedState.pendingDimensionSeedState.value = null
                        rememberedState.scaffoldOnboardingStageState.value =
                            DesktopScaffoldOnboardingStage.Finished
                    } else {
                        sessionLogger.w(
                            source = "DesktopApp.onDimensionSetupPersist",
                            message = "Dimension setup not written yet; kept pending for session open",
                            data = mapOf("outcome" to outcome.name, "requested" to seed.rows.size),
                        )
                    }
                }
            },
            // The file dialogs are blocking modals, so they run on databaseScope rather than on the
            // Compose thread - the same rule the AWT dialog's own contract states.
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
    // Projected ONCE, above the branch, so the surface that is mounted and the flags the graph is given are
    // the same decision.
    val navGateFlags = desktopNavGateFlags(models.startupMode)
    // Hoisted so the marker below and the branch that follows cannot disagree about which surface was
    // mounted: one predicate, one evaluation, logged.
    val graphOwnsGate = desktopStartupGateShownByGraph(models.startupMode)
    LaunchedEffect(models.startupMode) {
        sessionLogger.i(
            source = "DesktopApp.surfaceMounted",
            message = "Startup surface chosen",
            data =
                mapOf(
                    "graphOwnsGate" to graphOwnsGate,
                    "startupMode" to models.startupMode.name,
                    "shouldShowDatabaseInit" to navGateFlags.shouldShowDatabaseInit,
                    "shouldShowPassphraseSetup" to navGateFlags.shouldShowPassphraseSetup,
                    "shouldShowPassphraseUnlock" to navGateFlags.shouldShowPassphraseUnlock,
                ),
        )
    }
    if (graphOwnsGate) {
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
            gateFlags = navGateFlags,
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
                        databaseFile = stores.databaseStore.getDatabaseFilePath().toFile(),
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
    } else {
        desktopStartupGateSurface(
            snapshot = models.startupSnapshot,
            startupMode = models.startupMode,
            runtimeState = models.startupRuntimeState,
            databaseSnapshot = models.databaseSnapshot,
            dimensionInputs = models.dimensionInputs,
            callbacks = callbacks,
            sessionLogger = sessionLogger,
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
     * Handed down rather than re-derived: this shell is now mounted for every gate-bearing mode, so passing
     * the real flags is what puts Android's own gate screens in front of a fresh Linux install.
     */
    gateFlags: DesktopNavGateFlags,
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
        darkTheme = state.preferences.themeMode == ThemeModeOption.DARK,
    ) { language ->
        // Whether the desktop's OWN chrome is on screen, read from the ONE startup projection rather than
        // from a second decision here. `showsNoGate` is true for `Ready` and for the three onboarding
        // scaffold modes - the only two groups that reach this function - so it separates exactly the
        // ready app (rail, header, inset: the behaviour that must not change) from the four gate-bearing
        // modes, where the graph's own gate screen IS the window.
        val showsDesktopChrome = gateFlags.showsNoGate
        Row(modifier = Modifier.fillMaxSize()) {
            if (showsDesktopChrome) {
                desktopNavigationRail(
                    activeRoute = state.activeRoute,
                    routes = state.navigationModel.primaryRoutes,
                    onNavigate = { route ->
                        callbacks.onRouteSelected(route)
                        // The graph is a sibling, not a child: this is the only channel to it.
                        callbacks.onRailRouteRequested(route.storageKey)
                        sessionLogger.i(
                            source = "DesktopApp.desktopNavigationRail",
                            message = "Desktop route selected",
                            data = mapOf("route" to route.storageKey),
                        )
                    },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                desktopContentPane(
                    state = state,
                    language = language,
                    landingRoute = landingRoute,
                    gateFlags = gateFlags,
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
 * @param preferences the SHARED rows. `effectiveLanguageTag` is the input, so the row decides.
 * @param darkTheme from the same rows; undefaulted for the reason [desktopRootSurface] gives.
 * @param content receives the derived language, so the graph below is handed the value this root installed
 *   instead of deriving a second one.
 */
@Composable
internal fun desktopShellRootSurface(
    preferences: AppPreferencesState,
    darkTheme: Boolean,
    content: @Composable (UiLanguage) -> Unit,
) {
    val language = desktopUiLanguageForTag(preferences.effectiveLanguageTag)
    desktopRootSurface(
        darkTheme = darkTheme,
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
    startupMode: DesktopStartupMode,
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
                    "startupGateActive" to (startupMode != DesktopStartupMode.Ready),
                    "startupMode" to startupMode.name,
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
    LaunchedEffect(lifecycleState.bootstrapSnapshot) {
        sessionLogger.i(
            source = "DesktopApp.desktopBootstrap",
            message = "Desktop bootstrap state active",
            data = mapOf("schemaVersion" to lifecycleState.bootstrapSnapshot.schemaVersion),
        )
    }
    LaunchedEffect(startupMode) {
        sessionLogger.i(
            source = "DesktopApp.desktopStartupMode",
            message = "Desktop startup mode active",
            data = mapOf("startupMode" to startupMode.name),
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
 * @param darkTheme the theme mode to render in. Undefaulted on purpose: a default is a second theme
 *   decision that could win over the preference the caller just read.
 * @param language the copy language for every shared screen below this root. Also undefaulted, and for the
 *   same reason - a default here would silently reinstate a language the caller did not choose (the machine
 *   locale, most likely). Requiring it makes each call site state where its answer comes from: the shared
 *   rows after unlock, the machine locale in the pre-unlock gate.
 */
@Composable
internal fun desktopRootSurface(
    darkTheme: Boolean,
    language: UiLanguage,
    preferences: AppPreferencesState,
    content: @Composable () -> Unit,
) {
    val desktopColors = desktopColorPalette()
    // The SHARED theme, so anything rendered from :core:ui gets the same Material 3 tokens Android does -
    // and the dynamic-colour seam runs here too (the desktop's actual answers null, selecting the fallback).
    io.payanam.core.ui.theme.PayanamTheme(darkTheme = darkTheme) {
        // Material 2 stays for the desktop's own routes, which V1-V5 replace with shared screens.
        CompositionLocalProvider(
            LocalDesktopDarkTheme provides darkTheme,
            // The shared screens read their copy from this ambient table - they replaced their
            // stringResource(...) calls with lookups against it. Android installs it once inside
            // PayanamNavHost, but this desktop renders shared screens without that graph (the
            // startup flow and, until every screen lands, individual routes), so the root installs
            // it. Reading it with no provider calls error() and takes the whole surface down.
            LocalUiStrings provides UiStrings.of(language),
            // The SHARED preference rows, from the same `app_settings` table Android's
            // `MainActivity` reads. Undefaulted for a sharper reason than the two above: this local
            // DEFAULTS rather than failing, so a route that forgot to provide it would render a real
            // screen at the default zoom with fallback dimension labels and none of the user's timeline
            // settings - a wrong screen, not a crash. Every entry point therefore states which rows it
            // is holding, and the pre-unlock ones say so by naming the defaults they pass.
            LocalAppPreferences provides preferences,
        ) {
            MaterialTheme(colors = desktopColors.materialColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = desktopColors.background,
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun desktopNavigationRail(
    activeRoute: DesktopTopLevelRoute,
    routes: List<DesktopTopLevelRoute>,
    onNavigate: (DesktopTopLevelRoute) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(desktopChromeBackgroundColor())
                .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Payanam",
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Local-first desktop",
            style = MaterialTheme.typography.body2,
            color = desktopMutedTextColor(),
        )
        routes.forEach { route ->
            Card(
                backgroundColor =
                    if (route == activeRoute) {
                        desktopSelectedCardColor()
                    } else {
                        desktopCardColor()
                    },
                shape = RoundedCornerShape(16.dp),
                elevation = 0.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics {
                                role = Role.Button
                                contentDescription = "Navigate to ${route.displayName}"
                                selected = route == activeRoute
                                stateDescription =
                                    if (route == activeRoute) {
                                        "${route.displayName} route selected"
                                    } else {
                                        "${route.displayName} route not selected"
                                    }
                            }.clickable { onNavigate(route) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = route.displayName,
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = route.summary(),
                        style = MaterialTheme.typography.caption,
                        color = desktopMutedTextColor(),
                    )
                }
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
    gateFlags: DesktopNavGateFlags,
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
    // A gate that OWNS the window gets the whole of it. The pane is then a bare `fillMaxSize` box with
    // no header above the graph and no 28.dp inset around it, so the shared screen is measured against the
    // real window constraints instead of a padded, header-shifted pane it cannot lay out in.
    //
    // `Ready` (and the scaffold modes, which never reach this function) take the `else` branch unchanged:
    // the rail, the header, the inset and the semantics label are all still exactly as they were.
    val paneModifier =
        if (gateFlags.showsNoGate) {
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Desktop content pane for ${state.activeRoute.displayName}" }
                .padding(28.dp)
        } else {
            Modifier.fillMaxSize()
        }
    Column(
        modifier = paneModifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (gateFlags.showsNoGate) {
            desktopHeader(activeRoute = state.activeRoute)
        }
        // The shared navigation graph, mounted. It owns the routes, the back stack and the
        // parameterised detail routes the desktop's flat 7-tab enum could not express.
        //
        // The gate flags arrive as this function's `gateFlags` parameter, projected once at the mount from
        // the one startup decision. They were previously re-derived here from a hardcoded `Ready`, which is
        // what left a fresh Linux install showing the desktop's own gate instead of the shared graph's
        // `DatabasePassphraseSetupScreen` and `DatabaseInitScreen`.
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
            ) {
                DesktopPayanamNavHostScreens(
                    databaseInitCollaborators = databaseInitCollaborators,
                    passphraseUnlockCollaborators = passphraseUnlockCollaborators,
                    passphraseChangeCollaborators = passphraseChangeCollaborators,
                    language = language,
                    settingsCollaborators = settingsCollaborators,
                )
            }
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
            // Minimal mode is a BUILD-TIME flag on Android (BuildConfig.MINIMAL_MODE via FeatureFlags),
            // not a preference row, and the desktop has no equivalent knob - so this is false here rather
            // than a copy of a build constant that could drift. Tab visibility, which IS a shared row and
            // is what focus preset actually drives, is passed below and does the same job at runtime.
            // TODO(V5): if minimal mode is ever exposed on the desktop, read it from one shared source.
            minimalModeEnabled = false,
            tabVisibility = state.preferences.tabVisibility,
            // The LATCHED landing route, not the rail's selection: a start destination that keeps tracking
            // the rail would be a second, racing answer to "where is the graph" alongside the request
            // channel below, and the graph would reinstall on every rail tap.
            landingRoute = landingRoute,
            isLandingReady = gateFlags.showsNoGate,
            // The rail's channel: the graph navigates when it can and reports the request honoured or
            // refused, so this holder can be cleared.
            topLevelNavigationRequest = topLevelNavigationRequest,
            onTopLevelNavigationRequestConsumed = onTopLevelNavigationRequestConsumed,
            shouldShowPassphraseSetup = gateFlags.shouldShowPassphraseSetup,
            shouldShowPassphraseUnlock = gateFlags.shouldShowPassphraseUnlock,
            shouldShowDatabaseInit = gateFlags.shouldShowDatabaseInit,
            shouldShowFocusModeOnboarding = gateFlags.shouldShowFocusModeOnboarding,
            onFocusModePresetSelected = callbacks.onCompleteFocusModeOnboarding,
            onRestartAfterDelete = callbacks.onQuitRequested,
            // The passphrase-change destination's restart duty, and the same exit path the delete flow
            // already uses: it ends this process through the path that also releases the single-instance
            // lease, which is what a spawned replacement needs before it can acquire one. Supplied now so
            // the promotion has the seam wired; nothing fires it yet, because the desktop's own
            // passphrase-change destination is still unwired.
            onRestartAfterPassphraseChange = callbacks.onQuitRequested,
            onDatabaseReady = callbacks.onGraphDatabaseReady,
        )
    }
}

@Composable
private fun desktopHeader(activeRoute: DesktopTopLevelRoute) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = activeRoute.displayName,
            style = MaterialTheme.typography.h4,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = activeRoute.summary(),
            style = MaterialTheme.typography.body1,
            color = desktopBodyTextColor(),
        )
    }
}

@Composable
internal fun statusBanner(snapshot: SettingsFoundationSnapshot) {
    Card(
        backgroundColor = desktopBannerCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Shared desktop foundation v${snapshot.schemaVersion}",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    "${DesktopBuildInfo.VERSION_DISPLAY_NAME} | " +
                        "${snapshot.areasWithStatus(FoundationReadiness.SharedReady)} shared-ready, " +
                        "${snapshot.areasWithStatus(FoundationReadiness.ExtractionNext)} extraction-next, " +
                        "${snapshot.areasWithStatus(FoundationReadiness.AndroidOnly)} Android-only areas",
                style = MaterialTheme.typography.body2,
            )
        }
    }
}

@Composable
internal fun featureGrid(cards: List<FoundationArea>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.forEach { card ->
            Card(
                backgroundColor = desktopSurfaceColor(),
                shape = RoundedCornerShape(18.dp),
                elevation = 0.dp,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .background(card.status.swatch(), RoundedCornerShape(50)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = card.title,
                            style = MaterialTheme.typography.h6,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = card.summary,
                            style = MaterialTheme.typography.body2,
                            color = desktopMutedTextColor(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun selectionPreview(
    selection: DataModuleSelection,
    sessionLogger: UnifiedLogger,
    onSelectionChanged: (DataModuleSelection) -> Unit,
) {
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Desktop-safe shared model check",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            desktopToggleRow(
                label = "Tasks",
                enabled = selection.tasks,
                onToggle = {
                    val nextSelection = selection.copy(tasks = !selection.tasks)
                    sessionLogger.i(
                        source = "DesktopApp.selectionPreview",
                        message = "Toggled module selection",
                        data = mapOf("module" to "tasks", "enabled" to nextSelection.tasks),
                    )
                    onSelectionChanged(nextSelection)
                },
            )
            desktopToggleRow(
                label = "Time entries",
                enabled = selection.timeEntries,
                onToggle = {
                    val nextSelection = selection.copy(timeEntries = !selection.timeEntries)
                    sessionLogger.i(
                        source = "DesktopApp.selectionPreview",
                        message = "Toggled module selection",
                        data = mapOf("module" to "timeEntries", "enabled" to nextSelection.timeEntries),
                    )
                    onSelectionChanged(nextSelection)
                },
            )
            desktopToggleRow(
                label = "Notes",
                enabled = selection.notes,
                onToggle = {
                    val nextSelection = selection.copy(notes = !selection.notes)
                    sessionLogger.i(
                        source = "DesktopApp.selectionPreview",
                        message = "Toggled module selection",
                        data = mapOf("module" to "notes", "enabled" to nextSelection.notes),
                    )
                    onSelectionChanged(nextSelection)
                },
            )
        }
    }
}

@Composable
internal fun desktopToggleRow(
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    interactive: Boolean = true,
) {
    Card(
        backgroundColor = if (enabled) desktopSelectedCardColor() else desktopSurfaceColor(),
        shape = RoundedCornerShape(14.dp),
        elevation = 0.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        contentDescription = "$label toggle"
                        stateDescription = if (enabled) "$label enabled" else "$label disabled"
                        // NOTE: interactivity is driven by [interactive], NOT by [enabled]. [enabled] is the
                        // toggle's STATE (on/off), so gating .clickable on it would make every Off toggle
                        // permanently un-clickable - a one-way switch. The biometric row is the only caller
                        // that passes interactive = false.
                    }.then(if (interactive) Modifier.clickable(onClick = onToggle) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.body1)
            Text(
                text = if (enabled) "On" else "Off",
                style = MaterialTheme.typography.button,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
 * The directory is created here because nothing else does: the manual export's destination is chosen by
 * a file dialog, so the exports folder never exists until something writes into it.
 */
internal fun desktopScheduledBackupDestination(exportDirectory: Path = DesktopAppPaths.resolveExportDirectory()): Path {
    // A failure to create it is not swallowed: the gateway reports `DESTINATION_UNWRITABLE` and the
    // scheduler logs it, which is the honest answer - a backup that has nowhere to go did not happen.
    Files.createDirectories(exportDirectory)
    val stamp = LocalDateTime.now().format(BACKUP_FILE_TIMESTAMP)
    // The NAME comes from the shared prefix the rotation matcher prunes with, so an artifact this
    // app writes is one rotation can recognise; a name assembled only here would be invisible to it.
    return exportDirectory.resolve(scheduledBackupArtifactName(stamp))
}

/** The backup file's timestamp, to the second: sortable, and no two runs in one second collide. */
private val BACKUP_FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * Prunes old backup artifacts in the folder a scheduled backup just wrote into.
 *
 * [backupRows] and [exportDirectory] are parameters so this is exercisable without an open session and
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
    exportDirectory: java.io.File,
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
    return pruneOldBackupDirectories(exportDirectory, policy, logger)
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
