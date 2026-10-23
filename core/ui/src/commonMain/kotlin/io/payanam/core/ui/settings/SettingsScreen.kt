//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.IMPORT_PICKER_MIME
import io.payanam.core.ui.components.toDimensionHexString
import io.payanam.core.ui.focus.focusModeSettingsContent
import io.payanam.shared.settings.FocusModePreset
import io.payanam.presentation.tasks.TaskFilter
import androidx.compose.ui.graphics.Color
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.DimensionOption
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.shared.logging.AppLogger

/**
 * The settings screen itself: one collapsible card per section, in the order the sections appear, with the
 * confirmations and the busy flags wired to the state it is handed.
 *
 * WHY NOTHING HERE BRANCHES ON A PLATFORM: the file pickers, the biometric prompt, the log export and the
 * "the user asked to see the project's page" intent are all OS work, so each arrives as a member of
 * [SettingsScreenActions] rather than being called. The shape follows the one this module already uses for
 * the same problem - [io.payanam.core.ui.database.DatabaseInitScreen] reports `onImportFileSelected(MIME)`
 * and receives the document back - so a screen here reports an intent to pick and the platform holds the
 * handle, which is the only way an Android `Uri` and a desktop path can both arrive at one button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    prefsState: AppPreferencesState,
    uiState: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    actions: SettingsScreenActions,
    /**
     * The about section's content. Its rows open Android's own package pages and read the update
     * channel's resource ids, so the slot is a composable the platform fills - the same shape the
     * debug-log exporter already takes, and the same reason: the OS work stays outside, the card that
     * frames it does not.
     */
    aboutContent: (@Composable () -> Unit)? = null,
    /**
     * True once the platform has a picked database file in hand. The confirmation therefore lives in
     * shared code while the document itself stays platform-side - the only arrangement that lets an
     * Android `Uri` and a desktop path drive the same dialog.
     */
    importSourceSelected: Boolean = false,
    /** The debug-log row, whose exporter writes to Android's own storage. Absent renders no row. */
    debugLogExport: (@Composable () -> Unit)? = null,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    var expandedSection by remember { mutableStateOf<SettingsSection?>(null) }
    var fontFamilyExpanded by remember { mutableStateOf(false) }
    var intervalExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showBulkMapDialog by remember { mutableStateOf(false) }
    var selectedBulkMapDimensionId by remember { mutableStateOf(prefsState.dimensionPreferences.firstOrNull()?.id.orEmpty()) }
    LaunchedEffect(prefsState.dimensionPreferences, selectedBulkMapDimensionId) {
        if (selectedBulkMapDimensionId.isBlank()) {
            selectedBulkMapDimensionId = prefsState.dimensionPreferences.firstOrNull()?.id.orEmpty()
            return@LaunchedEffect
        }
        if (prefsState.dimensionPreferences.none { it.id == selectedBulkMapDimensionId }) {
            selectedBulkMapDimensionId = prefsState.dimensionPreferences.firstOrNull()?.id.orEmpty()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(strings.settingsTitle) })
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard(
                title = strings.settingsAppearanceTitle,
                icon = Icons.Default.Palette,
                expanded = expandedSection == SettingsSection.APPEARANCE,
                onToggleExpanded = {
                    logSectionToggle(logger, expandedSection, "appearance", SettingsSection.APPEARANCE)
                    expandedSection = expandedSection.toggle(SettingsSection.APPEARANCE)
                },
            ) {
                SettingsAppearanceSection(
                    prefsState = prefsState,
                    onAppLanguageChange = actions.onAppLanguageChange,
                    onThemeModeChange = actions.onThemeModeChange,
                    onFontFamilyChange = actions.onFontFamilyChange,
                    onTimeFormatChange = actions.onTimeFormatChange,
                    fontFamilyExpanded = fontFamilyExpanded,
                    onFontFamilyExpandedChange = { fontFamilyExpanded = it },
                )
            }
            SettingsCard(
                title = strings.settingsDefaultLandingTitle,
                icon = Icons.Default.Timer,
                expanded = expandedSection == SettingsSection.DEFAULT_LANDING,
                onToggleExpanded = {
                    logSectionToggle(logger, expandedSection, "default_landing", SettingsSection.DEFAULT_LANDING)
                    expandedSection = expandedSection.toggle(SettingsSection.DEFAULT_LANDING)
                },
            ) {
                SettingsDefaultLandingSection(
                    prefsState = prefsState,
                    onLaunchDestinationRouteChange = actions.onLaunchDestinationRouteChange,
                    onLaunchDestinationTimeChange = actions.onLaunchDestinationTimeChange,
                    onLaunchDestinationTasksChange = actions.onLaunchDestinationTasksChange,
                )
            }
            if (actions.focusModeSettingsEnabled) {
                SettingsCard(
                    title = strings.focusModeTitle,
                    icon = Icons.Default.Visibility,
                    expanded = expandedSection == SettingsSection.FOCUS_MODE,
                    onToggleExpanded = {
                        logSectionToggle(logger, expandedSection, "focus_mode", SettingsSection.FOCUS_MODE)
                        expandedSection = expandedSection.toggle(SettingsSection.FOCUS_MODE)
                    },
                ) {
                    focusModeSettingsContent(
                        prefsState = prefsState,
                        onSetActivePreset = actions.onSetActivePreset,
                        onSetTabVisibility = actions.onSetTabVisibility,
                    )
                }
            }
            SettingsCard(
                title = strings.settingsLifeDimensionsTitle,
                icon = Icons.Default.Category,
                expanded = expandedSection == SettingsSection.DIMENSIONS,
                onToggleExpanded = {
                    logSectionToggle(logger, expandedSection, "dimensions", SettingsSection.DIMENSIONS)
                    expandedSection = expandedSection.toggle(SettingsSection.DIMENSIONS)
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        text = strings.settingsLifeDimensionsDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    (
                        prefsState.dimensionPreferences.map {
                            DimensionOption(
                                id = it.id,
                                canonicalId = it.canonicalId,
                                label = it.label,
                                description = it.description,
                                color = it.color,
                                isVisible = it.isVisible,
                                iconKey = it.iconKey,
                                weight = it.weight,
                                hasCustomLabelOverride = it.hasCustomLabelOverride,
                            )
                        } + prefsState.dynamicDimensionOptions
                        ).distinctBy { it.id }.forEach { preference ->
                        DimensionPreferenceCard(
                            preference = preference,
                            usedColorHexes = (
                                prefsState.dimensionPreferences.map { it.color.toDimensionHexString() } +
                                    prefsState.dynamicDimensionOptions.map { it.color.toDimensionHexString() }
                                )
                                .filterNot { it == preference.color.toDimensionHexString() }
                                .map { it.uppercase() }
                                .toSet(),
                            usedIconKeys = (prefsState.dimensionPreferences.map { it.iconKey } + prefsState.dynamicDimensionOptions.map { it.iconKey })
                                .filterNot { it == preference.iconKey }
                                .toSet(),
                            onLabelCommit = { label ->
                                actions.onDimensionLabelChanged(preference.id, label.ifBlank { preference.label })
                                logger.d(
                                    "SettingsScreen.dimensionLabel",
                                    "Dimension label updated",
                                    mapOf("dimensionId" to preference.id),
                                )
                            },
                            onLabelReset = {
                                actions.onDimensionLabelReset(preference.id)
                                logger.i(
                                    "SettingsScreen.dimensionLabel",
                                    "Dimension label reset",
                                    mapOf("dimensionId" to preference.id),
                                )
                            },
                            onColorSelected = { color ->
                                actions.onDimensionColorChanged(preference.id, color)
                                logger.d(
                                    "SettingsScreen.dimensionColor",
                                    "Dimension color updated",
                                    mapOf("dimensionId" to preference.id),
                                )
                            },
                            onIconSelected = { iconKey ->
                                actions.onDimensionIconChanged(preference.id, iconKey)
                                logger.d(
                                    "SettingsScreen.dimensionIcon",
                                    "Dimension icon updated",
                                    mapOf("dimensionId" to preference.id, "iconKey" to iconKey),
                                )
                            },
                            onWeightCommit = { weight ->
                                actions.onDimensionWeightChanged(preference.id, weight)
                                logger.i(
                                    "SettingsScreen.dimensionWeight",
                                    "Dimension weight updated",
                                    mapOf("dimensionId" to preference.id, "weight" to weight),
                                )
                            },
                            onVisibilityToggleRequested = {
                                actions.onDimensionVisibilityChanged(preference.id, !preference.isVisible)
                                logger.i(
                                    "SettingsScreen.dimensionVisibility",
                                    "Dimension visibility updated",
                                    mapOf("dimensionId" to preference.id, "visible" to !preference.isVisible),
                                )
                            },
                        )
                    }
                }
            }
            if (!actions.minimalModeEnabled) {
                SettingsCard(
                    title = strings.settingsAutoTrackHabitTimeTitle,
                    icon = Icons.Default.Timer,
                    expanded = expandedSection == SettingsSection.AUTO_TRACK_HABIT_TIME,
                    onToggleExpanded = {
                        logSectionToggle(logger, expandedSection, "auto_track_habit_time", SettingsSection.AUTO_TRACK_HABIT_TIME)
                        expandedSection = expandedSection.toggle(SettingsSection.AUTO_TRACK_HABIT_TIME)
                    },
                ) {
                    SettingsAutoTrackingSection(
                        prefsState = prefsState,
                        onAutoTrackHabitTimeGlobalChange = actions.onAutoTrackHabitTimeGlobalChanged,
                        onAutoTrackDimensionChange = actions.onAutoTrackDimensionPreferenceChanged,
                    )
                }
            }
            SettingsCard(
                title = strings.settingsInsightsChartsVisibilityTitle,
                icon = Icons.Default.TrendingUp,
                expanded = expandedSection == SettingsSection.TIME_INSIGHTS,
                onToggleExpanded = {
                    logSectionToggle(logger, expandedSection, "time_insights", SettingsSection.TIME_INSIGHTS)
                    expandedSection = expandedSection.toggle(SettingsSection.TIME_INSIGHTS)
                },
            ) {
                SettingsTimeInsightsSection(
                    prefsState = prefsState,
                    onChartVisibilityChange = actions.onChartVisibilityChanged,
                )
            }
            SettingsCard(
                title = strings.settingsAutoBackupTitle,
                icon = Icons.Default.Backup,
                expanded = expandedSection == SettingsSection.AUTO_BACKUP,
                onToggleExpanded = {
                    logSectionToggle(logger, expandedSection, "auto_backup", SettingsSection.AUTO_BACKUP)
                    expandedSection = expandedSection.toggle(SettingsSection.AUTO_BACKUP)
                },
            ) {
                SettingsAutoBackupSection(
                    prefsState = prefsState,
                    onAutoBackupEnabledChange = { enabled ->
                        actions.onAutoBackupEnabledChanged(enabled)
                        // Scheduling a worker is the platform's job: the worker is an Android component,
                        // so the shared screen reports the toggle and the interval it was set to.
                        actions.onAutoBackupScheduleRequested(enabled, prefsState.autoBackupInterval)
                    },
                    onAutoBackupIntervalChange = { interval ->
                        actions.onAutoBackupIntervalChanged(interval)
                        actions.onAutoBackupScheduleRequested(prefsState.autoBackupEnabled, interval)
                    },
                    onManualBackupNow = actions.onManualBackupRequested,
                    onBackupRotationEnabledChange = actions.onBackupRotationEnabledChanged,
                    onBackupRotationCountChange = actions.onBackupRotationCountChanged,
                    onDismissAutoBackupFailure = actions.onAutoBackupFailureDismissed,
                    manualBackupInProgress = actions.manualBackupInProgress,
                    intervalExpanded = intervalExpanded,
                    onIntervalExpandedChange = { intervalExpanded = it },
                )
            }
            if (actions.scoreSettingsEnabled) {
                SettingsCard(
                    title = strings.settingsScoringTitle,
                    icon = Icons.Default.Category,
                    expanded = expandedSection == SettingsSection.SCORING,
                    onToggleExpanded = {
                        logSectionToggle(logger, expandedSection, "scoring", SettingsSection.SCORING)
                        expandedSection = expandedSection.toggle(SettingsSection.SCORING)
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = strings.settingsScoringDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = actions.onNavigateToScoringConfig,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(strings.settingsActionConfigureScoring)
                        }
                    }
                }
            }
            SettingsCard(
                title = strings.settingsDebugTitle,
                icon = Icons.Default.Info,
                expanded = expandedSection == SettingsSection.DEBUG,
                onToggleExpanded = {
                    logSectionToggle(logger, expandedSection, "debug", SettingsSection.DEBUG)
                    expandedSection = expandedSection.toggle(SettingsSection.DEBUG)
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = strings.settingsDebugEnable,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (prefsState.debugLoggingEnabled) {
                                    strings.settingsDebugEnabledHint
                                } else {
                                    strings.settingsDebugDisabledHint
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = prefsState.debugLoggingEnabled,
                            onCheckedChange = { enabled ->
                                logger.d("SettingsScreen.debugLoggingToggled", "Debug logging toggled", mapOf("enabled" to enabled))
                                actions.onDebugLoggingEnabledChanged(enabled)
                            },
                        )
                    }
                    debugLogExport?.invoke()
                }
            }
            SettingsCard(
                title = strings.settingsSecurityTitle,
                icon = Icons.Default.Timer,
                expanded = expandedSection == SettingsSection.SECURITY,
                onToggleExpanded = {
                    logSectionToggle(logger, expandedSection, "security", SettingsSection.SECURITY)
                    expandedSection = expandedSection.toggle(SettingsSection.SECURITY)
                },
            ) {
                SettingsDatabaseSecuritySection(
                    uiState = uiState,
                    onUpdateUnlockTimeout = actions.onUnlockTimeoutChanged,
                    onDisableBiometricEnabled = actions.onBiometricUnlockDisabled,
                    onEnableBiometricRequested = actions.onEnableBiometricRequested,
                    biometricActions = actions.biometricActions,
                )
            }
            DatabaseStatsSettingsSection(
                expanded = expandedSection == SettingsSection.DATABASE,
                onToggleExpanded = {
                    logSectionToggle(logger, expandedSection, "database", SettingsSection.DATABASE)
                    expandedSection = expandedSection.toggle(SettingsSection.DATABASE)
                },
                uiState = uiState,
                onDeleteArtifact = actions.onDeleteArtifact,
                onCleanStaleArtifacts = actions.onCleanStaleArtifacts,
            )
            if (actions.minimalModeEnabled) {
                SettingsCard(
                    title = strings.settingsDataManagementTitle,
                    icon = Icons.Default.CloudUpload,
                    expanded = expandedSection == SettingsSection.DATA_MANAGEMENT,
                    onToggleExpanded = {
                        logSectionToggle(logger, expandedSection, "data_management", SettingsSection.DATA_MANAGEMENT)
                        expandedSection = expandedSection.toggle(SettingsSection.DATA_MANAGEMENT)
                    },
                ) {
                    MinimalDataManagementSection(
                        onChangePassphraseClick = {
                            logDataAction(logger, "change_passphrase")
                            actions.onNavigateToPassphraseChange()
                        },
                        onDeleteAllDataClick = {
                            logDataAction(logger, "delete_all")
                            actions.onDeleteAllDataRequested()
                        },
                        isExporting = uiState.isExporting,
                        isImporting = uiState.isImporting,
                    )
                }
            } else {
                SettingsCard(
                    title = strings.settingsDataManagementTitle,
                    icon = Icons.Default.CloudUpload,
                    expanded = expandedSection == SettingsSection.DATA_MANAGEMENT,
                    onToggleExpanded = {
                        logSectionToggle(logger, expandedSection, "data_management", SettingsSection.DATA_MANAGEMENT)
                        expandedSection = expandedSection.toggle(SettingsSection.DATA_MANAGEMENT)
                    },
                ) {
                    DataManagementSettingsSection(
                        uiState = uiState,
                        onExportClick = {
                            logDataAction(logger, "export")
                            actions.onExportDestinationRequested(actions.exportFileName)
                        },
                        onImportClick = {
                            // Kept alongside the shared button's own i-level click line: this one is the
                            // d-level data-action breadcrumb, the button logs the tap itself.
                            logDataAction(logger, "import")
                            actions.onImportSourceRequested(IMPORT_PICKER_MIME)
                        },
                        onImportUhabitsClick = {
                            logDataAction(logger, "import_uhabits")
                            actions.onUhabitsSourceRequested(UHABITS_IMPORT_MIME_TYPES)
                        },
                        onMapImportedHabitsClick = {
                            logDataAction(logger, "map_habits")
                            showBulkMapDialog = true
                        },
                        onChangePassphraseClick = {
                            logDataAction(logger, "change_passphrase")
                            actions.onNavigateToPassphraseChange()
                        },
                        onDeleteAllDataClick = {
                            logDataAction(logger, "delete_all")
                            actions.onDeleteAllDataRequested()
                        },
                    )
                }
            }
            SettingsCard(
                title = strings.settingsAboutTitle,
                icon = Icons.Default.Info,
                expanded = expandedSection == SettingsSection.ABOUT,
                onToggleExpanded = {
                    logSectionToggle(logger, expandedSection, "about", SettingsSection.ABOUT)
                    expandedSection = expandedSection.toggle(SettingsSection.ABOUT)
                },
            ) {
                aboutContent?.invoke()
            }
            if (importSourceSelected) {
                ImportDatabaseConfirmDialog(
                    showEncryptedModeWarning = actions.encryptionEnabled,
                    onConfirm = {
                        actions.onImportConfirmed()
                    },
                    onDismiss = actions.onImportDismissed,
                )
            }
            if (uiState.showDeleteExportPrompt) {
                DeleteExportPromptDialog(
                    onBackUpFirst = {
                        actions.onDeleteExportPromptDismissed()
                        actions.onExportDestinationRequested(actions.exportFileName)
                    },
                    onSkipAndDelete = {
                        actions.onDeleteExportPromptDismissed()
                        showDeleteConfirmDialog = true
                    },
                    onDismiss = { actions.onDeleteExportPromptDismissed() },
                )
            }
            if (showDeleteConfirmDialog) {
                DeleteAllDataConfirmDialog(onConfirm = {
                    actions.onDeleteAllDataConfirmed()
                    showDeleteConfirmDialog = false
                }, onDismiss = { showDeleteConfirmDialog = false })
            }
            if (uiState.awaitingImportPassphrase) {
                ImportEncryptedDbPassphraseDialog(
                    passphraseError = uiState.importPassphraseError,
                    isVerifying = uiState.isImporting,
                    onConfirm = actions.onImportPassphraseSubmitted,
                    onDismiss = actions.onImportPassphraseDismissed,
                )
            }
            if (showBulkMapDialog) {
                BulkMapImportedHabitsDialog(
                    selectedDimensionId = selectedBulkMapDimensionId,
                    dimensionPreferences = prefsState.dimensionPreferences,
                    onSelectedDimensionChange = { selectedBulkMapDimensionId = it },
                    onConfirm = {
                        val selectedDimension = prefsState.dimensionPreferences.firstOrNull { it.id == selectedBulkMapDimensionId }
                        if (selectedDimension != null) {
                            actions.onBulkMapImportedHabitsConfirmed(selectedDimension.id, selectedDimension.label)
                        }
                        showBulkMapDialog = false
                    },
                    onDismiss = { showBulkMapDialog = false },
                )
            }
        }
    }
}

/**
 * [SettingsScreenContent] with the one piece of state a caller would otherwise have to own.
 *
 * WHY THIS EXISTS: `SettingsScreenContent` takes a [SnackbarHostState] so that a platform can report a
 * write outcome on the screen's own snackbar, which is the arrangement Android uses to surface its
 * ViewModel's result flows. Building that state is a [remember] over a Material 3 type, and `:core:ui`
 * declares Material 3 as `implementation` rather than `api` - so a platform that calls the content
 * function directly cannot name the type at all. This wrapper is where the dependency stays: the platform
 * calls one function with primitive parameters and the shared layer keeps the state it owns.
 *
 * The state is `remember`ed, not hoisted from the caller, because there is nothing outside this screen to
 * show a snackbar in: on both platforms the outcome is reported about this screen, and a caller that wanted
 * its own host would render [SettingsScreenContent] directly.
 */
@Composable
fun SettingsScreen(
    prefsState: AppPreferencesState,
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    /** The about section's content. See [SettingsScreenContent]'s parameter of the same name. */
    aboutContent: (@Composable () -> Unit)? = null,
    importSourceSelected: Boolean = false,
    debugLogExport: (@Composable () -> Unit)? = null,
) {
    SettingsScreenContent(
        prefsState = prefsState,
        uiState = uiState,
        // Named local rather than an inline `remember { SnackbarHostState() }`: the parameter is the
        // screen's own, so it belongs in a name a reader can find, not inside a call.
        snackbarHostState = remember { SnackbarHostState() },
        actions = actions,
        aboutContent = aboutContent,
        importSourceSelected = importSourceSelected,
        debugLogExport = debugLogExport,
    )
}

/**
 * Reports that a section header was tapped, with the state that is about to replace it.
 *
 * Exists so each of the thirteen headers logs the same line with the same shape, rather than thirteen
 * hand-written ones that drift: the previous per-header lambdas all carried the same tag, message and key
 * set, and a difference between any two of them was a silent divergence in the log.
 */
private fun logSectionToggle(
    logger: io.payanam.shared.logging.UnifiedLogger,
    current: SettingsSection?,
    section: String,
    target: SettingsSection,
) {
    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to section, "expanded" to (current != target)))
}

/** The d-level data-action breadcrumb the export/import rows add beside the shared button's own tap line. */
private fun logDataAction(logger: io.payanam.shared.logging.UnifiedLogger, action: String) {
    logger.d("SettingsScreen.dataActionTapped", "Data management action tapped", mapOf("action" to action))
}