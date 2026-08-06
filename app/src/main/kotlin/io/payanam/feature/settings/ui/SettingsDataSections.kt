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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.R
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
    legacyDimensionDiagnosticsInProgress: Boolean,
    onRunLegacyDimensionDiagnostics: () -> Unit,
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
                    "SettingsScreen.legacyDimensionDiagnosticsTapped",
                    "Legacy dimension diagnostics tapped",
                )
                onRunLegacyDimensionDiagnostics()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !legacyDimensionDiagnosticsInProgress,
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(
                    id = if (legacyDimensionDiagnosticsInProgress) {
                        R.string.settings_action_run_legacy_dimension_diagnostics_in_progress
                    } else {
                        R.string.settings_action_run_legacy_dimension_diagnostics
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

@Composable
internal fun AboutSettingsSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    uiState: SettingsUiState,
    onViewGithub: () -> Unit,
) {
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
    }
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
