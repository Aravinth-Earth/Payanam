//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.feature.settings.DownloadUiState
import io.payanam.feature.settings.UpdateChannel
import io.payanam.feature.settings.UpdateCheckError
import io.payanam.feature.settings.buildNumberFromFileName
import io.payanam.feature.settings.labelResId
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun DataManagementSettingsSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    uiState: SettingsUiState,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onImportUhabitsClick: () -> Unit,
    onMapImportedHabitsClick: () -> Unit,
    onChangePassphraseClick: () -> Unit,
    onDeleteAllDataClick: () -> Unit,
) {
    SettingsCard(
        title = stringResource(id = R.string.settings_data_management_title),
        icon = Icons.Default.CloudUpload,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        Text(
            text = stringResource(id = R.string.settings_data_management_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onExportClick,
                enabled = !uiState.isExporting && !uiState.isImporting,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(id = R.string.settings_action_export))
            }
            OutlinedButton(
                onClick = onImportClick,
                enabled = !uiState.isExporting && !uiState.isImporting,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(id = R.string.settings_action_import))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onImportUhabitsClick,
                enabled = !uiState.isExporting && !uiState.isImporting && !uiState.isUhabitsImporting,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isUhabitsImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(id = R.string.settings_action_import_uhabits))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onMapImportedHabitsClick,
                enabled = uiState.importedUhabitsHabitCount > 0 &&
                    !uiState.isUhabitsImporting &&
                    !uiState.isBulkMappingImportedHabits,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isBulkMappingImportedHabits) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(id = R.string.settings_action_map_imported_habits))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                id = R.string.settings_imported_habits_count,
                uiState.importedUhabitsHabitCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onChangePassphraseClick,
            enabled = !uiState.isExporting && !uiState.isImporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(id = R.string.settings_action_change_passphrase))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDeleteAllDataClick,
            enabled = !uiState.isExporting && !uiState.isImporting,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(id = R.string.settings_action_delete_all_data))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.settings_imported_habits_count, uiState.importedUhabitsHabitCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DebugLogExportActions(
    logger: UnifiedLogger,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    context: Context,
    habitScoreDiagnosticsInProgress: Boolean,
    onRunHabitScoreDiagnostics: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    logger.d("SettingsScreen.exportLogsButtonTapped", "Export logs button tapped")
                    scope.launch {
                        val exportedFile = logger.exportLatestLog()
                        if (exportedFile != null) {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.settings_snackbar_latest_log_exported,
                                    exportedFile.absolutePath,
                                ),
                            )
                        } else {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.settings_snackbar_latest_log_export_failed),
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(id = R.string.settings_action_export_latest_log))
            }
            OutlinedButton(
                onClick = {
                    logger.d("SettingsScreen.exportLogsButtonTapped", "Export logs button tapped")
                    scope.launch {
                        val exportedFile = logger.exportAllLogs()
                        if (exportedFile != null) {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.settings_snackbar_all_logs_exported,
                                    exportedFile.absolutePath,
                                ),
                            )
                        } else {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.settings_snackbar_all_logs_export_failed),
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(id = R.string.settings_action_export_all_logs))
            }
        }
        OutlinedButton(
            onClick = {
                logger.i(
                    "SettingsScreen.habitScoreDiagnosticsTapped",
                    "Habit score diagnostics tapped",
                )
                onRunHabitScoreDiagnostics()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !habitScoreDiagnosticsInProgress,
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(
                    id = if (habitScoreDiagnosticsInProgress) {
                        R.string.settings_action_run_habit_score_diagnostics_in_progress
                    } else {
                        R.string.settings_action_run_habit_score_diagnostics
                    },
                ),
            )
        }
    }
}

@Composable
internal fun MinimalDataManagementSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onChangePassphraseClick: () -> Unit,
    onDeleteAllDataClick: () -> Unit,
    isExporting: Boolean,
    isImporting: Boolean,
) {
    SettingsCard(
        title = stringResource(id = R.string.settings_data_management_title),
        icon = Icons.Default.CloudUpload,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        OutlinedButton(
            onClick = onChangePassphraseClick,
            enabled = !isExporting && !isImporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(id = R.string.settings_action_change_passphrase))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDeleteAllDataClick,
            enabled = !isExporting && !isImporting,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(id = R.string.settings_action_delete_all_data))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSettingsSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    uiState: SettingsUiState,
    logger: UnifiedLogger,
    onViewGithub: () -> Unit,
    onCheckForUpdate: () -> Unit = {},
    onUpdateChannelSelected: (UpdateChannel) -> Unit = {},
    onAutoDownloadToggled: (Boolean) -> Unit = {},
    onPromptInstallToggled: (Boolean) -> Unit = {},
    onWifiOnlyToggled: (Boolean) -> Unit = {},
    onAutoCheckToggled: (Boolean) -> Unit = {},
    onDownloadOrRetry: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onInstallNow: () -> Unit = {},
    onInstallLater: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingsCard(
        title = stringResource(id = R.string.settings_about_title),
        icon = Icons.Default.Info,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        StatRow(
            label = stringResource(id = R.string.settings_about_version_label),
            value = stringResource(
                id = R.string.settings_about_version_value,
                uiState.appVersion,
            ),
        )
        StatRow(
            label = stringResource(id = R.string.settings_about_codename_label),
            value = stringResource(id = R.string.settings_about_codename_value),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(id = R.string.settings_about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.settings_about_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onViewGithub) {
            Text(stringResource(id = R.string.settings_action_view_on_github))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.feedback_contact_email_value, stringResource(id = R.string.feedback_contact_email)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(id = R.string.feedback_contact_signal_value, stringResource(id = R.string.feedback_contact_signal)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Update channel selector
        Text(
            text = stringResource(id = R.string.settings_update_channel_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var channelMenuExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = channelMenuExpanded,
            onExpandedChange = { channelMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = stringResource(id = uiState.updateChannel.labelResId()),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelMenuExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            DropdownMenu(
                expanded = channelMenuExpanded,
                onDismissRequest = { channelMenuExpanded = false },
            ) {
                UpdateChannel.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(id = option.labelResId())) },
                        onClick = {
                            channelMenuExpanded = false
                            onUpdateChannelSelected(option)
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Auto-download opt-in + check button
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = uiState.autoDownloadEnabled,
                onCheckedChange = { onAutoDownloadToggled(it) },
                enabled = !uiState.isCheckingForUpdate,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.settings_update_auto_download),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = uiState.promptInstallEnabled,
                onCheckedChange = { onPromptInstallToggled(it) },
                enabled = uiState.autoDownloadEnabled && !uiState.isCheckingForUpdate,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.settings_update_prompt_install),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = uiState.wifiOnlyEnabled,
                onCheckedChange = { onWifiOnlyToggled(it) },
                enabled = !uiState.isCheckingForUpdate,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.settings_update_wifi_only),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = uiState.autoCheckEnabled,
                onCheckedChange = { onAutoCheckToggled(it) },
                enabled = !uiState.isCheckingForUpdate,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.settings_update_auto_check),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        // Update check
        // Single state-driven action button (never two actions at once):
        //   Idle           → Check for update
        //   Checking       → disabled spinner "Checking…"
        //   Downloading    → disabled "Downloading… N%"
        //   Downloaded     → Install now
        //   Failed         → Retry
        //   Up to date     → Check for update (re-check allowed, cooldown guards)
        val buttonLabel: String
        val buttonEnabled: Boolean
        val buttonAction: () -> Unit
        when {
            uiState.isCheckingForUpdate -> {
                buttonLabel = stringResource(id = R.string.settings_update_checking)
                buttonEnabled = false
                buttonAction = onCheckForUpdate
            }
            uiState.downloadState is DownloadUiState.Downloading -> {
                buttonLabel = stringResource(
                    id = R.string.settings_update_downloading,
                    (uiState.downloadState as DownloadUiState.Downloading).progressPercent,
                )
                buttonEnabled = false
                buttonAction = onCheckForUpdate
            }
            uiState.downloadState is DownloadUiState.Downloaded -> {
                buttonLabel = stringResource(id = R.string.settings_update_install_now_button)
                buttonEnabled = true
                buttonAction = onInstallNow
            }
            uiState.downloadState is DownloadUiState.Failed -> {
                buttonLabel = stringResource(id = R.string.settings_update_retry_button)
                buttonEnabled = true
                buttonAction = onDownloadOrRetry
            }
            // Update available but not downloading yet (manual download path).
            // A stale result (>15 min) reverts to "Check for update" so the
            // user always has a fresh-check exit from a stale state.
            uiState.updateCheckResult?.isUpdateAvailable == true && !uiState.autoDownloadEnabled &&
                !uiState.isUpdateResultStale() -> {
                buttonLabel = stringResource(id = R.string.settings_update_download_button)
                buttonEnabled = true
                buttonAction = onDownloadOrRetry
            }
            else -> {
                buttonLabel = stringResource(id = R.string.settings_update_check_button)
                buttonEnabled = true
                buttonAction = onCheckForUpdate
            }
        }
        Button(
            onClick = {
                logger.d(
                    "SettingsScreen.updateButtonTapped",
                    "Update button tapped",
                    mapOf(
                        "label" to buttonLabel,
                        "action" to when (buttonAction) {
                            onCheckForUpdate -> "checkForUpdate"
                            onDownloadOrRetry -> "downloadOrRetry"
                            onInstallNow -> "installNow"
                            else -> "unknown"
                        },
                        "checking" to uiState.isCheckingForUpdate,
                        "updateAvailable" to (uiState.updateCheckResult?.isUpdateAvailable ?: false),
                        "autoDownload" to uiState.autoDownloadEnabled,
                        "downloadState" to uiState.downloadState::class.simpleName,
                    ),
                )
                buttonAction()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = buttonEnabled,
        ) {
            if (uiState.isCheckingForUpdate) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = buttonLabel)
        }

        // Secondary "Check again" affordance: shown whenever a manual download
        // path is active, so a fresh check is always one tap away.
        if (buttonAction == onDownloadOrRetry) {
            TextButton(
                onClick = {
                    logger.d("SettingsScreen.updateCheckAgainTapped", "Check again tapped", mapOf("label" to buttonLabel))
                    onCheckForUpdate()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(id = R.string.settings_update_check_again_button),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val result = uiState.updateCheckResult
        if (result != null && !uiState.isCheckingForUpdate) {
            Spacer(modifier = Modifier.height(8.dp))
            when {
                result.error != null -> {
                    val errorText = when (result.error) {
                        UpdateCheckError.NO_INTERNET, UpdateCheckError.TIMEOUT ->
                            stringResource(id = R.string.settings_update_error_network)
                        UpdateCheckError.RATE_LIMITED ->
                            stringResource(id = R.string.settings_update_error_rate_limited)
                        UpdateCheckError.GITHUB_UNAVAILABLE ->
                            stringResource(id = R.string.settings_update_error_github)
                        UpdateCheckError.PARSE_ERROR, UpdateCheckError.UNKNOWN ->
                            stringResource(id = R.string.settings_update_error_parse)
                    }
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                result.isUpdateAvailable -> {
                    Text(
                        text = stringResource(id = R.string.settings_update_available, result.latestBuildNumber ?: 0),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = {
                        result.releaseUrl?.let { url ->
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }
                    }) {
                        Text(stringResource(id = R.string.settings_update_view_release))
                    }
                }
                else -> {
                    Text(
                        text = stringResource(id = R.string.settings_update_up_to_date, uiState.buildNumber),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // All-channel status rows (populated from the same list fetch)
        val statuses = result?.channelStatuses
        if (statuses != null && statuses.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.settings_update_channel_statuses_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UpdateChannel.entries.forEach { channel ->
                val status = statuses.firstOrNull { it.channel == channel }
                val isSelected = channel == uiState.updateChannel
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(id = channel.labelResId()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = status?.buildNumber?.let { stringResource(id = R.string.settings_update_channel_build, it) }
                            ?: stringResource(id = R.string.settings_update_channel_no_build),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status?.buildNumber != null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        }

        // Auto-download progress/state
        when (val dl = uiState.downloadState) {
            is DownloadUiState.Downloading -> {
                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Line 1: channel · build label
                    Text(
                        text = stringResource(
                            id = R.string.settings_update_downloading_header,
                            dl.channelName.ifEmpty { "dev" },
                            downloadBuildLabel(dl.buildName.ifEmpty { dl.fileName }),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Line 2: full build name (small, muted)
                    Text(
                        text = dl.buildName.ifEmpty { dl.fileName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            progress = { dl.progressPercent / 100f },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.loc_percent_value, dl.progressPercent),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancelDownload) {
                            Text(
                                text = stringResource(id = R.string.settings_action_cancel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            is DownloadUiState.Paused -> {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(id = pausedMessageRes(dl.message)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is DownloadUiState.Downloaded -> {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(id = R.string.settings_update_downloaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            is DownloadUiState.Failed -> {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(id = failedMessageRes(dl.message)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            DownloadUiState.Idle -> Unit
        }

        // Update-install popup (shown when a download finished and prompt-install is ON)
        val pendingPath = uiState.pendingInstallPath
        if (pendingPath != null) {
            AlertDialog(
                onDismissRequest = onInstallLater,
                title = { Text(stringResource(id = R.string.settings_update_install_title)) },
                text = { Text(stringResource(id = R.string.settings_update_install_message)) },
                confirmButton = {
                    TextButton(onClick = onInstallNow) {
                        Text(stringResource(id = R.string.settings_update_install_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onInstallLater) {
                        Text(stringResource(id = R.string.settings_update_install_later))
                    }
                },
            )
        }
    }
}

/** Extract "Payanam #1568" from a DownloadManager title/filename. */
private fun downloadBuildLabel(fileName: String): String = "Payanam #${buildNumberFromFileName(fileName)}"

/** Map a DownloadManager failure key to a user-friendly string resource. */
private fun failedMessageRes(key: String): Int = when (key) {
    "no_download_url" -> R.string.settings_update_error_no_url
    "enqueue_failed" -> R.string.settings_update_error_enqueue
    "file_missing" -> R.string.settings_update_error_file_missing
    "install_launch_failed" -> R.string.settings_update_error_install_launch
    "retry_available" -> R.string.settings_update_error_retry_later
    "download_error_file" -> R.string.settings_update_error_file
    "download_error_http" -> R.string.settings_update_error_http
    "download_error_http_data" -> R.string.settings_update_error_http
    "download_error_redirects" -> R.string.settings_update_error_http
    "download_error_space" -> R.string.settings_update_error_space
    "download_error_device" -> R.string.settings_update_error_network
    "download_error_resume" -> R.string.settings_update_error_retry_later
    "download_error_exists" -> R.string.settings_update_error_exists
    else -> R.string.settings_update_download_failed
}

/** Map a DownloadManager paused key to a user-friendly string resource. */
private fun pausedMessageRes(key: String): Int = when (key) {
    "download_paused_wifi" -> R.string.settings_update_paused_wifi
    "download_paused_retry" -> R.string.settings_update_paused_retry
    else -> R.string.settings_update_paused
}

@Composable
internal fun DatabaseStatsSettingsSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    uiState: SettingsUiState,
    onDeleteArtifact: (String) -> Unit,
    onCleanStaleArtifacts: () -> Unit = {},
) {
    SettingsCard(
        title = stringResource(id = R.string.settings_database_title),
        icon = Icons.Default.Storage,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        StatRow(label = stringResource(id = R.string.settings_database_tasks), value = uiState.taskCount.toString())
        StatRow(label = stringResource(id = R.string.settings_database_time_entries), value = uiState.timeEntryCount.toString())
        StatRow(label = stringResource(id = R.string.settings_database_notes), value = uiState.noteCount.toString())
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        StatRow(
            label = stringResource(id = R.string.settings_database_size),
            value = stringResource(id = R.string.settings_database_size_value, uiState.databaseSizeKb),
        )
        StatRow(
            label = stringResource(id = R.string.settings_database_current_schema),
            value = uiState.currentDatabaseSchemaVersion.toString(),
        )
        StatRow(
            label = stringResource(id = R.string.settings_database_min_supported_schema),
            value = uiState.minimumSupportedSchemaVersion.toString(),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        DatabaseArtifactsSection(
            artifacts = uiState.databaseArtifacts,
            onDeleteArtifact = onDeleteArtifact,
            onCleanStaleArtifacts = onCleanStaleArtifacts,
        )
    }
}
