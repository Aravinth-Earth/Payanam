//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.FeatureFlags
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.feature.settings.SettingsViewModel
import io.payanam.feature.settings.cancelImportPassphrase
import io.payanam.feature.settings.resumeImportWithPassphrase
import io.payanam.ui.components.toDimensionHexString
import io.payanam.ui.viewmodel.AppPreferencesViewModel
import io.payanam.ui.viewmodel.TaskFilter
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel(), onNavigateToPassphraseChange: () -> Unit = {}, onNavigateToScoringConfig: () -> Unit = {}, onNavigateToDatabaseInit: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val activity = checkNotNull(LocalActivity.current)
    val prefsViewModel: AppPreferencesViewModel = hiltViewModel()
    val prefsState by prefsViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = activity
    val logger = remember { UnifiedLogger.getInstance() }
    val encryptionManager = remember(context) { DatabaseEncryptionManager(context) }
    val scope = rememberCoroutineScope()
    var expandedSection by remember { mutableStateOf<SettingsSection?>(null) }
    var fontFamilyExpanded by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
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
    LaunchedEffect(Unit) { prefsViewModel.refreshAutoBackupStatusFromStorage() }
    LaunchedEffect(viewModel.navigateToDatabaseInit) { viewModel.navigateToDatabaseInit.collect { onNavigateToDatabaseInit() } }
    val manualBackupInProgress by prefsViewModel.manualBackupInProgress.collectAsState()
    val habitScoreDiagnosticsInProgress by prefsViewModel.habitScoreDiagnosticsInProgress.collectAsState()
    LaunchedEffect(Unit) { prefsViewModel.habitScoreDiagnosticsMessage.collect { snackbarHostState.showSnackbar(it) } }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let {
            viewModel.exportData(destinationUri = it)
        }
    }
    val onDatabaseImportSourceSelected: (Uri?) -> Unit = { uri ->
        uri?.let {
            pendingImportUri = it
            showImportConfirmDialog = true
        }
    }
    val importFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), onDatabaseImportSourceSelected)
    val importUhabitsLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importUhabitsData(it) }
    }
    SettingsImportFeedbackEffects(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        context = context,
        dimensionPreferences = prefsState.dimensionPreferences,
        viewModel = viewModel,
    )
    if (showImportConfirmDialog) {
        ImportDatabaseConfirmDialog(
            showEncryptedModeWarning = encryptionManager.isEncryptionEnabled(),
            onConfirm = {
                pendingImportUri?.let {
                    viewModel.importData(sourceUri = it)
                }
                showImportConfirmDialog = false
                pendingImportUri = null
            },
            onDismiss = {
                showImportConfirmDialog = false
                pendingImportUri = null
            },
        )
    }
    if (uiState.showDeleteExportPrompt) {
        DeleteExportPromptDialog(
            onBackUpFirst = {
                viewModel.dismissDeleteExportPrompt()
                exportLauncher.launch(viewModel.generateExportFileName())
            },
            onSkipAndDelete = {
                viewModel.dismissDeleteExportPrompt()
                showDeleteConfirmDialog = true
            },
            onDismiss = { viewModel.dismissDeleteExportPrompt() },
        )
    }
    if (showDeleteConfirmDialog) {
        DeleteAllDataConfirmDialog(onConfirm = {
            viewModel.deleteDatabase()
            showDeleteConfirmDialog = false
        }, onDismiss = { showDeleteConfirmDialog = false })
    }
    if (uiState.awaitingImportPassphrase) {
        ImportEncryptedDbPassphraseDialog(
            passphraseError = uiState.importPassphraseError,
            isVerifying = uiState.isImporting,
            onConfirm = { viewModel.resumeImportWithPassphrase(it) },
            onDismiss = { viewModel.cancelImportPassphrase() },
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
                    viewModel.bulkMapImportedHabitsToDimension(
                        targetDimensionId = selectedDimension.id,
                        targetDimensionLabel = selectedDimension.label,
                    )
                }
                showBulkMapDialog = false
            },
            onDismiss = { showBulkMapDialog = false },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(id = R.string.settings_title)) })
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
                title = stringResource(id = R.string.settings_appearance_title),
                icon = Icons.Default.Palette,
                expanded = expandedSection == SettingsSection.APPEARANCE,
                onToggleExpanded = {
                    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "appearance", "expanded" to (expandedSection != SettingsSection.APPEARANCE)))
                    expandedSection = expandedSection.toggle(SettingsSection.APPEARANCE)
                },
            ) {
                settingsAppearanceSection(
                    prefsState = prefsState,
                    prefsViewModel = prefsViewModel,
                    logger = logger,
                    fontFamilyExpanded = fontFamilyExpanded,
                    onFontFamilyExpandedChange = { fontFamilyExpanded = it },
                )
            }
            SettingsCard(
                title = stringResource(id = R.string.settings_default_landing_title),
                icon = Icons.Default.Timer,
                expanded = expandedSection == SettingsSection.DEFAULT_LANDING,
                onToggleExpanded = {
                    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "default_landing", "expanded" to (expandedSection != SettingsSection.DEFAULT_LANDING)))
                    expandedSection = expandedSection.toggle(SettingsSection.DEFAULT_LANDING)
                },
            ) {
                SettingsDefaultLandingSection(
                    prefsState = prefsState,
                    prefsViewModel = prefsViewModel,
                )
            }
            if (FeatureFlags.focusModeSettingsEnabled) {
                SettingsCard(
                    title = stringResource(id = R.string.focus_mode_title),
                    icon = Icons.Default.Visibility,
                    expanded = expandedSection == SettingsSection.FOCUS_MODE,
                    onToggleExpanded = {
                        logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "focus_mode", "expanded" to (expandedSection != SettingsSection.FOCUS_MODE)))
                        expandedSection = expandedSection.toggle(SettingsSection.FOCUS_MODE)
                    },
                ) {
                    focusModeSettingsContent(
                        prefsState = prefsState,
                        onSetActivePreset = { preset -> prefsViewModel.setActivePreset(preset) },
                        onSetTabVisibility = { tabRoute, visible -> prefsViewModel.setTabVisibility(tabRoute, visible) },
                    )
                }
            }
            SettingsCard(
                title = stringResource(id = R.string.settings_life_dimensions_title),
                icon = Icons.Default.Category,
                expanded = expandedSection == SettingsSection.DIMENSIONS,
                onToggleExpanded = {
                    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "dimensions", "expanded" to (expandedSection != SettingsSection.DIMENSIONS)))
                    expandedSection = expandedSection.toggle(SettingsSection.DIMENSIONS)
                },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text(
                        text = stringResource(id = R.string.settings_life_dimensions_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    (
                        prefsState.dimensionPreferences.map {
                            io.payanam.ui.viewmodel.DimensionOption(
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
                                val resolved = label.ifBlank { preference.label }
                                prefsViewModel.setDimensionLabel(preference.id, resolved)
                                logger.d(
                                    "SettingsScreen.dimensionLabel",
                                    "Dimension label updated",
                                    mapOf("dimensionId" to preference.id),
                                )
                            },
                            onLabelReset = {
                                prefsViewModel.resetDimensionLabel(preference.id)
                                logger.i(
                                    "SettingsScreen.dimensionLabel",
                                    "Dimension label reset",
                                    mapOf("dimensionId" to preference.id),
                                )
                            },
                            onColorSelected = { color ->
                                prefsViewModel.setDimensionColor(preference.id, color)
                                logger.d(
                                    "SettingsScreen.dimensionColor",
                                    "Dimension color updated",
                                    mapOf("dimensionId" to preference.id),
                                )
                            },
                            onIconSelected = { iconKey ->
                                prefsViewModel.setDimensionIcon(preference.id, iconKey)
                                logger.d(
                                    "SettingsScreen.dimensionIcon",
                                    "Dimension icon updated",
                                    mapOf("dimensionId" to preference.id, "iconKey" to iconKey),
                                )
                            },
                            onWeightCommit = { weight ->
                                prefsViewModel.setDimensionWeight(preference.id, weight)
                                logger.i(
                                    "SettingsScreen.dimensionWeight",
                                    "Dimension weight updated",
                                    mapOf("dimensionId" to preference.id, "weight" to weight),
                                )
                            },
                            onVisibilityToggleRequested = {
                                prefsViewModel.setDimensionVisibility(preference.id, !preference.isVisible)
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
            if (!FeatureFlags.minimalModeEnabled) {
                SettingsCard(
                    title = stringResource(id = R.string.settings_auto_track_habit_time_title),
                    icon = Icons.Default.Timer,
                    expanded = expandedSection == SettingsSection.AUTO_TRACK_HABIT_TIME,
                    onToggleExpanded = {
                        logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "auto_track_habit_time", "expanded" to (expandedSection != SettingsSection.AUTO_TRACK_HABIT_TIME)))
                        expandedSection = expandedSection.toggle(SettingsSection.AUTO_TRACK_HABIT_TIME)
                    },
                ) {
                    AutoTrackingSection(
                        prefsState = prefsState,
                        prefsViewModel = prefsViewModel,
                        logger = logger,
                    )
                }
            }
            InsightsChartsVisibilitySettingsCard(
                expanded = expandedSection == SettingsSection.TIME_INSIGHTS,
                onToggleExpanded = {
                    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "time_insights", "expanded" to (expandedSection != SettingsSection.TIME_INSIGHTS)))
                    expandedSection = expandedSection.toggle(SettingsSection.TIME_INSIGHTS)
                },
                prefsState = prefsState,
                prefsViewModel = prefsViewModel,
                logger = logger,
            )
            SettingsCard(
                title = stringResource(id = R.string.settings_auto_backup_title),
                icon = Icons.Default.Backup,
                expanded = expandedSection == SettingsSection.AUTO_BACKUP,
                onToggleExpanded = {
                    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "auto_backup", "expanded" to (expandedSection != SettingsSection.AUTO_BACKUP)))
                    expandedSection = expandedSection.toggle(SettingsSection.AUTO_BACKUP)
                },
            ) {
                SettingsAutoBackupSection(
                    prefsState = prefsState,
                    prefsViewModel = prefsViewModel,
                    logger = logger,
                    context = context,
                    manualBackupInProgress = manualBackupInProgress,
                )
            }
            if (FeatureFlags.scoreSettingsEnabled) {
                SettingsCard(
                    title = stringResource(id = R.string.settings_scoring_title),
                    icon = Icons.Default.Category,
                    expanded = expandedSection == SettingsSection.SCORING,
                    onToggleExpanded = {
                        logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "scoring", "expanded" to (expandedSection != SettingsSection.SCORING)))
                        expandedSection = expandedSection.toggle(SettingsSection.SCORING)
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(id = R.string.settings_scoring_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onNavigateToScoringConfig,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(id = R.string.settings_action_configure_scoring))
                        }
                    }
                }
            }
            SettingsCard(
                title = stringResource(id = R.string.settings_debug_title),
                icon = Icons.Default.Info,
                expanded = expandedSection == SettingsSection.DEBUG,
                onToggleExpanded = {
                    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "debug", "expanded" to (expandedSection != SettingsSection.DEBUG)))
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
                                text = stringResource(id = R.string.settings_debug_enable),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (prefsState.debugLoggingEnabled) {
                                    stringResource(id = R.string.settings_debug_enabled_hint)
                                } else {
                                    stringResource(id = R.string.settings_debug_disabled_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = prefsState.debugLoggingEnabled,
                            onCheckedChange = { enabled ->
                                logger.d("SettingsScreen.debugLoggingToggled", "Debug logging toggled", mapOf("enabled" to enabled))
                                prefsViewModel.setDebugLoggingEnabled(enabled)
                            },
                        )
                    }
                    DebugLogExportActions(
                        logger = logger,
                        scope = scope,
                        snackbarHostState = snackbarHostState,
                        context = context,
                        habitScoreDiagnosticsInProgress = habitScoreDiagnosticsInProgress,
                        onRunHabitScoreDiagnostics = prefsViewModel::runHabitScoreDiagnostics,
                    )
                }
            }
            SettingsCard(
                title = stringResource(id = R.string.settings_security_title),
                icon = Icons.Default.Timer,
                expanded = expandedSection == SettingsSection.SECURITY,
                onToggleExpanded = {
                    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "security", "expanded" to (expandedSection != SettingsSection.SECURITY)))
                    expandedSection = expandedSection.toggle(SettingsSection.SECURITY)
                },
            ) {
                DatabaseSecurityPreferencesSection(
                    uiState = uiState,
                    context = context,
                    onUpdateUnlockTimeout = viewModel::updateUnlockSessionTimeoutMinutes,
                    onDisableBiometricEnabled = viewModel::disableBiometricUnlock,
                    onEnableBiometricRequested = viewModel::enableBiometricUnlockWithVerification,
                )
            }
            DatabaseStatsSettingsSection(
                expanded = expandedSection == SettingsSection.DATABASE,
                onToggleExpanded = {
                    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "database", "expanded" to (expandedSection != SettingsSection.DATABASE)))
                    expandedSection = expandedSection.toggle(SettingsSection.DATABASE)
                },
                uiState = uiState,
                onDeleteArtifact = viewModel::deleteDatabaseArtifact,
                onCleanStaleArtifacts = viewModel::cleanStaleArtifacts,
            )
            if (FeatureFlags.minimalModeEnabled) {
                MinimalDataManagementSection(
                    expanded = expandedSection == SettingsSection.DATA_MANAGEMENT,
                    onToggleExpanded = {
                        logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "data_management", "expanded" to (expandedSection != SettingsSection.DATA_MANAGEMENT)))
                        expandedSection = expandedSection.toggle(SettingsSection.DATA_MANAGEMENT)
                    },
                    onChangePassphraseClick = {
                        logger.d("SettingsScreen.dataActionTapped", "Data management action tapped", mapOf("action" to "change_passphrase"))
                        onNavigateToPassphraseChange()
                    },
                    onDeleteAllDataClick = {
                        logger.d("SettingsScreen.dataActionTapped", "Data management action tapped", mapOf("action" to "delete_all"))
                        viewModel.requestDeleteDatabase()
                    },
                    isExporting = uiState.isExporting,
                    isImporting = uiState.isImporting,
                )
            } else {
                DataManagementSettingsSection(
                    expanded = expandedSection == SettingsSection.DATA_MANAGEMENT,
                    onToggleExpanded = {
                        logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "data_management", "expanded" to (expandedSection != SettingsSection.DATA_MANAGEMENT)))
                        expandedSection = expandedSection.toggle(SettingsSection.DATA_MANAGEMENT)
                    },
                    uiState = uiState,
                    onExportClick = {
                        logger.d("SettingsScreen.dataActionTapped", "Data management action tapped", mapOf("action" to "export"))
                        exportLauncher.launch(viewModel.generateExportFileName())
                    },
                    onImportClick = {
                        logger.d("SettingsScreen.dataActionTapped", "Data management action tapped", mapOf("action" to "import"))
                        importFolderLauncher.launch(null)
                    },
                    onImportUhabitsClick = {
                        logger.d("SettingsScreen.dataActionTapped", "Data management action tapped", mapOf("action" to "import_uhabits"))
                        importUhabitsLauncher.launch(arrayOf("application/x-sqlite3", "application/vnd.sqlite3", "application/octet-stream", "*/*"))
                    },
                    onMapImportedHabitsClick = {
                        logger.d("SettingsScreen.dataActionTapped", "Data management action tapped", mapOf("action" to "map_habits"))
                        showBulkMapDialog = true
                    },
                    onChangePassphraseClick = {
                        logger.d("SettingsScreen.dataActionTapped", "Data management action tapped", mapOf("action" to "change_passphrase"))
                        onNavigateToPassphraseChange()
                    },
                    onDeleteAllDataClick = {
                        logger.d("SettingsScreen.dataActionTapped", "Data management action tapped", mapOf("action" to "delete_all"))
                        viewModel.requestDeleteDatabase()
                    },
                )
            }
            AboutSettingsSection(
                expanded = expandedSection == SettingsSection.ABOUT,
                onToggleExpanded = {
                    logger.d("SettingsScreen.sectionToggled", "Settings section toggled", mapOf("section" to "about", "expanded" to (expandedSection != SettingsSection.ABOUT)))
                    expandedSection = expandedSection.toggle(SettingsSection.ABOUT)
                },
                uiState = uiState,
                onViewGithub = {
                    logger.d("SettingsScreen.aboutActionTapped", "About action tapped", mapOf("action" to "github"))
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Aravinth-Earth/Payanam")))
                },
                onCheckForUpdate = viewModel::checkForUpdate,
                onUpdateChannelSelected = viewModel::onUpdateChannelSelected,
                onAutoDownloadToggled = viewModel::onAutoDownloadToggled,
                onPromptInstallToggled = viewModel::onPromptInstallToggled,
                onInstallNow = viewModel::onInstallNow,
                onInstallLater = viewModel::onInstallLater,
            )
        }
    }
}
