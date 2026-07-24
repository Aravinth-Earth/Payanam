//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.DatabasePassphraseSetupViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DatabasePassphraseSetupScreen(
    onPassphraseConfigured: () -> Unit,
    viewModel: DatabasePassphraseSetupViewModel = hiltViewModel(),
) {
    val logger = UnifiedLogger.getInstance()
    val uiState by viewModel.uiState.collectAsState()
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmPassphrase by rememberSaveable { mutableStateOf("") }
    var isPassphraseVisible by rememberSaveable { mutableStateOf(false) }
    var isConfirmPassphraseVisible by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestLogPathPlaceholder = "__LATEST_LOG_PATH__"
    val latestLogExportedTemplate = stringResource(
        id = R.string.settings_snackbar_latest_log_exported,
        latestLogPathPlaceholder,
    )
    val latestLogExportFailedMessage = stringResource(id = R.string.settings_snackbar_latest_log_export_failed)
    val allLogsPathPlaceholder = "__ALL_LOGS_PATH__"
    val allLogsExportedTemplate = stringResource(
        id = R.string.settings_snackbar_all_logs_exported,
        allLogsPathPlaceholder,
    )
    val allLogsExportFailedMessage = stringResource(id = R.string.settings_snackbar_all_logs_export_failed)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(id = R.string.db_passphrase_setup_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(id = R.string.db_passphrase_setup_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(id = R.string.db_passphrase_setup_encryption_default),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (uiState.hasExistingLocalDatabase) {
            val lastUpdated = if (uiState.databaseLastModifiedMs > 0L) {
                SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    .format(Date(uiState.databaseLastModifiedMs))
            } else {
                "-"
            }
            Text(
                text = stringResource(id = R.string.db_passphrase_setup_existing_data_migration),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    id = R.string.db_passphrase_db_summary_storage_mode,
                    stringResource(id = R.string.db_passphrase_storage_mode_plaintext),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(id = R.string.settings_database_size_value, uiState.databaseSizeKb),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_last_updated, lastUpdated),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_tasks, uiState.taskCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_time_entries, uiState.timeEntryCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_journal, uiState.journalCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_notes, uiState.noteCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(id = R.string.db_passphrase_no_recovery_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        OutlinedTextField(
            value = passphrase,
            onValueChange = {
                passphrase = it
                viewModel.clearError()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(id = R.string.db_passphrase_input_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (isPassphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isPassphraseVisible = !isPassphraseVisible }) {
                    Icon(
                        imageVector = if (isPassphraseVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (isPassphraseVisible) {
                            stringResource(id = R.string.db_passphrase_hide_toggle)
                        } else {
                            stringResource(id = R.string.db_passphrase_show_toggle)
                        },
                    )
                }
            },
        )
        OutlinedTextField(
            value = confirmPassphrase,
            onValueChange = {
                confirmPassphrase = it
                viewModel.clearError()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(id = R.string.db_passphrase_confirm_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (isConfirmPassphraseVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { isConfirmPassphraseVisible = !isConfirmPassphraseVisible }) {
                    Icon(
                        imageVector = if (isConfirmPassphraseVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (isConfirmPassphraseVisible) {
                            stringResource(id = R.string.db_passphrase_hide_toggle)
                        } else {
                            stringResource(id = R.string.db_passphrase_show_toggle)
                        },
                    )
                }
            },
        )

        uiState.errorReasonCode?.let { reasonCode ->
            val errorText = when (reasonCode) {
                "min_length" -> stringResource(id = R.string.db_passphrase_error_min_length)
                "missing_uppercase" -> stringResource(id = R.string.db_passphrase_error_uppercase)
                "missing_lowercase" -> stringResource(id = R.string.db_passphrase_error_lowercase)
                "missing_digit" -> stringResource(id = R.string.db_passphrase_error_digit)
                "missing_symbol" -> stringResource(id = R.string.db_passphrase_error_symbol)
                "mismatch" -> stringResource(id = R.string.db_passphrase_error_mismatch)
                "migration_incompatible" -> stringResource(id = R.string.db_passphrase_error_migration_incompatible)
                "migration_failed" -> stringResource(id = R.string.db_passphrase_error_migration_failed)
                "persist_failed" -> stringResource(id = R.string.db_passphrase_error_persist_failed)
                "reset_failed" -> stringResource(id = R.string.db_passphrase_unlock_reset_failed)
                else -> stringResource(id = R.string.db_passphrase_error_generic)
            }
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (uiState.errorReasonCode == "migration_incompatible" || uiState.errorReasonCode == "migration_failed") {
            TextButton(
                onClick = { showResetConfirmDialog = true },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.db_passphrase_setup_reset_action))
            }
        }
        Text(
            text = stringResource(id = R.string.db_passphrase_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(id = R.string.db_passphrase_diagnostics_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val exportedFile = logger.exportLatestLog()
                        debugExportMessage = if (exportedFile != null) {
                            latestLogExportedTemplate.replace(latestLogPathPlaceholder, exportedFile.absolutePath)
                        } else {
                            latestLogExportFailedMessage
                        }
                    }
                },
                enabled = !uiState.isSaving,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(id = R.string.settings_action_export_latest_log))
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val exportedFile = logger.exportAllLogs()
                        debugExportMessage = if (exportedFile != null) {
                            allLogsExportedTemplate.replace(allLogsPathPlaceholder, exportedFile.absolutePath)
                        } else {
                            allLogsExportFailedMessage
                        }
                    }
                },
                enabled = !uiState.isSaving,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(id = R.string.settings_action_export_all_logs))
            }
        }
        if (debugExportMessage != null) {
            Text(
                text = debugExportMessage.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = {
                logger.i("DatabasePassphraseSetupScreen", "Submitting passphrase setup")
                viewModel.configurePassphrase(
                    passphrase = passphrase,
                    confirmPassphrase = confirmPassphrase,
                    onSuccess = onPassphraseConfigured,
                )
            },
            enabled = !uiState.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(
                text = if (uiState.isSaving) {
                    stringResource(id = R.string.db_passphrase_setup_saving)
                } else {
                    stringResource(id = R.string.db_passphrase_setup_action)
                },
            )
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text(stringResource(id = R.string.db_passphrase_setup_reset_title)) },
            text = { Text(stringResource(id = R.string.db_passphrase_setup_reset_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmDialog = false
                        viewModel.resetLocalDataAndConfigurePassphrase(
                            passphrase = passphrase,
                            confirmPassphrase = confirmPassphrase,
                            onSuccess = onPassphraseConfigured,
                        )
                    },
                ) {
                    Text(stringResource(id = R.string.db_passphrase_unlock_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text(stringResource(id = R.string.settings_action_cancel))
                }
            },
        )
    }
}
