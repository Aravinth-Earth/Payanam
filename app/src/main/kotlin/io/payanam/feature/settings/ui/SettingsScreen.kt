//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.EntryPointAccessors
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.settings.SettingsScreenActions
import io.payanam.core.ui.settings.SettingsScreenContent
import io.payanam.core.ui.settings.UHABITS_IMPORT_MIME_TYPES
import io.payanam.core.ui.text.uiStringsFor
import io.payanam.feature.settings.SettingsViewModel
import io.payanam.feature.settings.cancelImportPassphrase
import io.payanam.feature.settings.resumeImportWithPassphrase
import io.payanam.platform.AndroidDatabaseEncryptionProbe
import io.payanam.platform.AndroidDocumentSource
import io.payanam.platform.SettingsEntryPoint
import io.payanam.platform.appDatabaseInitWalConsolidator
import io.payanam.service.AutoBackupWorker
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.ui.retainSharedViewModel
import io.payanam.ui.screens.AndroidDatabaseSecurityBiometricActions
import io.payanam.ui.viewmodel.AppPreferencesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The Android half of the settings screen: the three activity-result pickers, the backup worker, the
 * biometric prompt's host activity, and the import/export feedback host.
 *
 * Everything that draws is shared now - `io.payanam.core.ui.settings.SettingsScreenContent` owns the card
 * list, the confirmations and the section state, and this file is left holding only the four things that
 * are genuinely OS-bound:
 *
 *  - the pickers, because `ActivityResultContracts` is an activity contract and the returned `Uri` is an
 *    Android type. Each one is the seam `DatabaseInitScreen` already uses: the shared screen reports an
 *    intent to pick together with the MIME filter, and the document is held here.
 *  - [AutoBackupWorker], which is an Android component the shared screen can only ask for.
 *  - [AndroidDatabaseSecurityBiometricActions], which resolves the `FragmentActivity` the prompt needs.
 *  - [SettingsImportFeedbackEffects], which needs a `Context` to read the snackbar copy. It no longer
 *    restarts anything: the import carries on smoothly (owner ruling, 2026-10-08), so there is no
 *    `restartAppAfterDatabaseImport` and no `Process.killProcess` on any import path.
 *
 * `isFDroidBuild` is passed as a measured value rather than read from `BuildConfig` here and never reaches
 * shared code as a flag: it arrives in the about content the platform fills, which is where the F-Droid
 * branch of the update UI already lived.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = retainedSettingsViewModel(),
    onNavigateToPassphraseChange: () -> Unit = {},
    onNavigateToScoringConfig: () -> Unit = {},
    onNavigateToDatabaseInit: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val activity = checkNotNull(LocalActivity.current)
    val prefsViewModel: AppPreferencesViewModel = hiltViewModel()
    val prefsState by prefsViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = activity
    val manualBackupInProgress by prefsViewModel.manualBackupInProgress.collectAsState()
    val habitScoreDiagnosticsInProgress by prefsViewModel.habitScoreDiagnosticsInProgress.collectAsState()
    val logger = remember { io.payanam.common.logging.UnifiedLogger.getInstance() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(Unit) { prefsViewModel.refreshAutoBackupStatusFromStorage() }
    LaunchedEffect(viewModel.navigateToDatabaseInit) { viewModel.navigateToDatabaseInit.collect { onNavigateToDatabaseInit() } }
    LaunchedEffect(Unit) { prefsViewModel.habitScoreDiagnosticsMessage.collect { snackbarHostState.showSnackbar(it) } }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            viewModel.exportData(destinationUri = it)
        }
    }
    val importDatabaseLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingImportUri = uri }
    val importUhabitsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importUhabitsData(it) }
    }
    val biometricActions = biometricActionsHolder(context, viewModel::enableBiometricUnlockWithVerification)
    SettingsImportFeedbackEffects(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        context = context,
        dimensionPreferences = prefsState.dimensionPreferences,
        viewModel = viewModel,
    )
    SettingsScreenContent(
        prefsState = prefsState,
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        // The picked document is the platform's to hold: the shared screen only needs to know that one
        // is in hand, and takes the handle back through onImportConfirmed.
        importSourceSelected = pendingImportUri != null,
        debugLogExport = {
            DebugLogExportActions(
                logger = logger,
                scope = scope,
                snackbarHostState = snackbarHostState,
                context = context,
                habitScoreDiagnosticsInProgress = habitScoreDiagnosticsInProgress,
                onRunHabitScoreDiagnostics = prefsViewModel::runHabitScoreDiagnostics,
            )
        },
        aboutContent = {
            AboutSettingsContent(
                uiState = uiState,
                logger = logger,
                onViewGithub = {
                    logger.d("SettingsScreen.aboutActionTapped", "About action tapped", mapOf("action" to "github"))
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Aravinth-Earth/Payanam"),
                            ),
                        )
                    }.onFailure { e ->
                        logger.w("SettingsScreen.openGithub", "No browser available for GitHub intent", mapOf("error" to (e.message ?: "unknown")))
                    }
                },
                onCheckForUpdate = viewModel::checkForUpdate,
                onUpdateChannelSelected = viewModel::onUpdateChannelSelected,
                onAutoDownloadToggled = viewModel::onAutoDownloadToggled,
                onPromptInstallToggled = viewModel::onPromptInstallToggled,
                onWifiOnlyToggled = viewModel::onWifiOnlyToggled,
                onAutoCheckToggled = viewModel::onAutoCheckToggled,
                onDownloadOrRetry = viewModel::downloadOrRetry,
                onCancelDownload = viewModel::onCancelDownload,
                onInstallNow = viewModel::onInstallNow,
                onInstallLater = viewModel::onInstallLater,
                isFDroidBuild = viewModel.isFDroidBuild,
            )
        },
        actions = SettingsScreenActions(
            onAppLanguageChange = { prefsViewModel.setAppLanguage(it) },
            onThemeModeChange = { prefsViewModel.setThemeMode(it) },
            onFontFamilyChange = { prefsViewModel.setFontFamily(it) },
            onTimeFormatChange = { prefsViewModel.setTimeFormat(it) },
            onLaunchDestinationRouteChange = { prefsViewModel.setLaunchDestination(it) },
            onLaunchDestinationTimeChange = { prefsViewModel.setLaunchDestinationTime() },
            onLaunchDestinationTasksChange = { prefsViewModel.setLaunchDestinationTasks(it) },
            focusModeSettingsEnabled = FeatureFlags.focusModeSettingsEnabled,
            minimalModeEnabled = FeatureFlags.minimalModeEnabled,
            onSetActivePreset = { preset -> prefsViewModel.setActivePreset(preset) },
            onSetTabVisibility = { tabRoute, visible -> prefsViewModel.setTabVisibility(tabRoute, visible) },
            onDimensionLabelChanged = { id, label -> prefsViewModel.setDimensionLabel(id, label) },
            onDimensionLabelReset = { id -> prefsViewModel.resetDimensionLabel(id) },
            onDimensionColorChanged = { id, color -> prefsViewModel.setDimensionColor(id, color) },
            onDimensionIconChanged = { id, iconKey -> prefsViewModel.setDimensionIcon(id, iconKey) },
            onDimensionWeightChanged = { id, weight -> prefsViewModel.setDimensionWeight(id, weight) },
            onDimensionVisibilityChanged = { id, visible -> prefsViewModel.setDimensionVisibility(id, visible) },
            onAutoTrackHabitTimeGlobalChanged = { enabled -> prefsViewModel.setAutoTrackHabitTimeGlobal(enabled) },
            onDayBoundaryHourChanged = { hour -> prefsViewModel.setDayBoundaryHour(hour) },
            onAutoTrackDimensionPreferenceChanged = { id, enabled -> prefsViewModel.setAutoTrackDimensionPreference(id, enabled) },
            onChartVisibilityChanged = { key, enabled -> prefsViewModel.setChartVisibility(key, enabled) },
            onAutoBackupEnabledChanged = { enabled -> prefsViewModel.setAutoBackupEnabled(enabled) },
            onAutoBackupIntervalChanged = { interval -> prefsViewModel.setAutoBackupInterval(interval) },
            onAutoBackupScheduleRequested = { enabled, interval ->
                if (enabled) {
                    AutoBackupWorker.schedule(context, interval.minutes)
                } else {
                    AutoBackupWorker.cancel(context)
                }
            },
            onManualBackupRequested = { prefsViewModel.triggerManualBackupNow() },
            onBackupRotationEnabledChanged = { enabled -> prefsViewModel.setBackupRotationEnabled(enabled) },
            onBackupRotationCountChanged = { count -> prefsViewModel.setBackupRotationCount(count) },
            onAutoBackupFailureDismissed = { prefsViewModel.dismissAutoBackupFailureMessage() },
            manualBackupInProgress = manualBackupInProgress,
            scoreSettingsEnabled = FeatureFlags.scoreSettingsEnabled,
            onNavigateToScoringConfig = onNavigateToScoringConfig,
            onDebugLoggingEnabledChanged = { enabled -> prefsViewModel.setDebugLoggingEnabled(enabled) },
            onUnlockTimeoutChanged = { minutes -> viewModel.updateUnlockSessionTimeoutMinutes(minutes) },
            onBiometricUnlockDisabled = { viewModel.disableBiometricUnlock() },
            // Routed through the platform half so the FragmentActivity the prompt needs is resolved
            // the same way the availability check resolves it, rather than by a cast at the call site.
            onEnableBiometricRequested = biometricActions::requestEnable,
            biometricActions = biometricActions,
            onDeleteArtifact = { fileName -> viewModel.deleteDatabaseArtifact(fileName) },
            onCleanStaleArtifacts = { viewModel.cleanStaleArtifacts() },
            exportFileName = viewModel.generateExportFileName(),
            onExportDestinationRequested = { fileName -> exportLauncher.launch(fileName) },
            onImportSourceRequested = { mimeTypes -> importDatabaseLauncher.launch(mimeTypes) },
            onUhabitsSourceRequested = { mimeTypes -> importUhabitsLauncher.launch(mimeTypes) },
            onImportConfirmed = {
                pendingImportUri?.let { viewModel.importData(sourceUri = it) }
                pendingImportUri = null
            },
            onImportDismissed = { pendingImportUri = null },
            onDeleteExportPromptDismissed = { viewModel.dismissDeleteExportPrompt() },
            onDeleteAllDataRequested = { viewModel.requestDeleteDatabase() },
            onDeleteAllDataConfirmed = { viewModel.deleteDatabase() },
            onImportPassphraseSubmitted = { passphrase ->
                viewModel.resumeImportWithPassphrase(
                    passphrase,
                    context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME),
                    AndroidDatabaseEncryptionProbe(context),
                    uiStringsFor(context),
                    AndroidDocumentSource(context),
                    appDatabaseInitWalConsolidator(context, UnifiedLogger.getInstance()),
                )
            },
            onImportPassphraseDismissed = { viewModel.cancelImportPassphrase() },
            onBulkMapImportedHabitsConfirmed = { id, label -> viewModel.bulkMapImportedHabitsToDimension(id, label) },
            onNavigateToPassphraseChange = onNavigateToPassphraseChange,
        ),
    )
}

/**
 * The biometric platform half, remembered per [context] so the prompt's activity lookup happens once.
 *
 * Two [SettingsScreenActions] members take the same instance - the request seam and the availability
 * probe - because the section must resolve them consistently: a caller that built two objects would let
 * availability come from one activity and the prompt from another.
 */
@Composable
private fun biometricActionsHolder(
    context: android.content.Context,
    onEnableBiometricRequested: (androidx.fragment.app.FragmentActivity, String, (Boolean) -> Unit) -> Unit,
): AndroidDatabaseSecurityBiometricActions = remember(context, onEnableBiometricRequested) {
    AndroidDatabaseSecurityBiometricActions(
        context = context,
        onEnableBiometricRequested = onEnableBiometricRequested,
    )
}

/**
 * The settings ViewModel, constructed here because [SettingsViewModel] is a plain class now.
 *
 * It stopped extending `androidx.lifecycle.ViewModel`, so `hiltViewModel()` can no longer resolve it, and
 * it takes its collaborators as constructor parameters rather than as injected fields. The platform
 * therefore builds it - the way [biometricActionsHolder] above resolves its own Hilt-free dependencies -
 * and Hilt's bindings are reached through [SettingsEntryPoint] rather than by injection.
 *
 * It is RETAINED rather than merely remembered, and it is handed its own [kotlinx.coroutines.CoroutineScope]
 * instead of being left on the constructor's default, because both halves of that are the same user flow:
 * tapping "export database", "delete database" or "check for updates" starts work the user expects to finish
 * even though the next thing they do is leave - tap a rail card, answer a system prompt, rotate the phone.
 * A `remember` block would drop the instance on the first of those; a composition scope
 * (`rememberCoroutineScope()`, still used above for plain collaborator actions) would abandon the launch
 * mid-flight on the second. The scope here is created with the instance and cancelled in the holder's
 * `onCleared()`, so the work is deliberately long-lived and its teardown is deliberate too - the same
 * rule the desktop shell states for its settings writes.
 */
@Composable
private fun retainedSettingsViewModel(): SettingsViewModel {
    // The APPLICATION context, not [LocalContext]: this instance outlives the activity that made it, so a
    // retained ViewModel holding an Activity context would leak it. Same reason the passphrase-setup
    // screen above builds from `context.applicationContext`.
    val context = LocalContext.current.applicationContext
    return retainSharedViewModel(
        key = "settings",
        onRelease = { it.scope.cancel() },
    ) {
        val entryPoint = EntryPointAccessors.fromApplication(context, SettingsEntryPoint::class.java)
        SettingsViewModel(
            context = context,
            sessionManager = entryPoint.androidDatabaseSessionManager(),
            databaseEncryptionManager = entryPoint.databaseEncryptionManager(),
            databaseBackupCoordinator = entryPoint.databaseBackupCoordinator(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
