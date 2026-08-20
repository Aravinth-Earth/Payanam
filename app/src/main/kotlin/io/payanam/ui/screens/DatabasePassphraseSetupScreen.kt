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
/**
 * Database passphrase setup screen.
 */
fun DatabasePassphraseSetupScreen(
    onPassphraseConfigured: () -> Unit,
    viewModel: DatabasePassphraseSetupViewModel = hiltViewModel(),
) {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    val uiState by viewModel.uiState.collectAsState()
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmPassphrase by rememberSaveable { mutableStateOf("") }
    var isPassphraseVisible by rememberSaveable { mutableStateOf(false) }
    var isConfirmPassphraseVisible by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    /** Context. */
    val context = LocalContext.current
    /** Scope. */
    val scope = rememberCoroutineScope()
    /** Latest log path placeholder. */
    val latestLogPathPlaceholder = "__LATEST_LOG_PATH__"
    /** Latest log exported template. */
    val latestLogExportedTemplate = stringResource(
        id = R.string.settings_snackbar_latest_log_exported,
        /** Latest log path placeholder. */
        latestLogPathPlaceholder,
    )
    /** Latest log export failed message. */
    val latestLogExportFailedMessage = stringResource(id = R.string.settings_snackbar_latest_log_export_failed)
    /** All logs path placeholder. */
    val allLogsPathPlaceholder = "__ALL_LOGS_PATH__"
    /** All logs exported template. */
    val allLogsExportedTemplate = stringResource(
        id = R.string.settings_snackbar_all_logs_exported,
        /** All logs path placeholder. */
        allLogsPathPlaceholder,
    )
    /** All logs export failed message. */
    val allLogsExportFailedMessage = stringResource(id = R.string.settings_snackbar_all_logs_export_failed)

    /** Column. */
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        /** Text. */
        Text(
            text = stringResource(id = R.string.db_passphrase_setup_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        /** Text. */
        Text(
            text = stringResource(id = R.string.db_passphrase_setup_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        /** Text. */
        Text(
            text = stringResource(id = R.string.db_passphrase_setup_encryption_default),
            style = MaterialTheme.typography.bodyMedium,
        )
        /** If. */
        if (uiState.hasExistingLocalDatabase) {
            /** Last updated. */
            val lastUpdated = if (uiState.databaseLastModifiedMs > 0L) {
                /** Simple date format. */
                SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    .format(Date(uiState.databaseLastModifiedMs))
            } else {
                "-"
            }
            /** Text. */
            Text(
                text = stringResource(id = R.string.db_passphrase_setup_existing_data_migration),
                style = MaterialTheme.typography.bodyMedium,
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_title),
                style = MaterialTheme.typography.titleSmall,
            )
            /** Text. */
            Text(
                text = stringResource(
                    id = R.string.db_passphrase_db_summary_storage_mode,
                    /** String resource. */
                    stringResource(id = R.string.db_passphrase_storage_mode_plaintext),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.settings_database_size_value, uiState.databaseSizeKb),
                style = MaterialTheme.typography.bodySmall,
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_last_updated, lastUpdated),
                style = MaterialTheme.typography.bodySmall,
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_tasks, uiState.taskCount),
                style = MaterialTheme.typography.bodySmall,
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_time_entries, uiState.timeEntryCount),
                style = MaterialTheme.typography.bodySmall,
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_journal, uiState.journalCount),
                style = MaterialTheme.typography.bodySmall,
            )
            /** Text. */
            Text(
                text = stringResource(id = R.string.db_passphrase_db_summary_notes, uiState.noteCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        /** Text. */
        Text(
            text = stringResource(id = R.string.db_passphrase_no_recovery_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        /** Outlined text field. */
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
                /** Icon button. */
                IconButton(onClick = { isPassphraseVisible = !isPassphraseVisible }) {
                    /** Icon. */
                    Icon(
                        imageVector = if (isPassphraseVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (isPassphraseVisible) {
                            /** String resource. */
                            stringResource(id = R.string.db_passphrase_hide_toggle)
                        } else {
                            /** String resource. */
                            stringResource(id = R.string.db_passphrase_show_toggle)
                        },
                    )
                }
            },
        )
        /** Outlined text field. */
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
                /** Password visual transformation. */
                PasswordVisualTransformation()
            },
            trailingIcon = {
                /** Icon button. */
                IconButton(onClick = { isConfirmPassphraseVisible = !isConfirmPassphraseVisible }) {
                    /** Icon. */
                    Icon(
                        imageVector = if (isConfirmPassphraseVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (isConfirmPassphraseVisible) {
                            /** String resource. */
                            stringResource(id = R.string.db_passphrase_hide_toggle)
                        } else {
                            /** String resource. */
                            stringResource(id = R.string.db_passphrase_show_toggle)
                        },
                    )
                }
            },
        )

        uiState.errorReasonCode?.let { reasonCode ->
            /** Error text. */
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
            /** Text. */
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        /** If. */
        if (uiState.errorReasonCode == "migration_incompatible" || uiState.errorReasonCode == "migration_failed") {
            /** Text button. */
            TextButton(
                onClick = { showResetConfirmDialog = true },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                /** Text. */
                Text(text = stringResource(id = R.string.db_passphrase_setup_reset_action))
            }
        }
        /** Text. */
        Text(
            text = stringResource(id = R.string.db_passphrase_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
        )
        /** Text. */
        Text(
            text = stringResource(id = R.string.db_passphrase_diagnostics_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Row. */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /** Outlined button. */
            OutlinedButton(
                onClick = {
                    scope.launch {
                        /** Exported file. */
                        val exportedFile = logger.exportLatestLog()
                        debugExportMessage = if (exportedFile != null) {
                            latestLogExportedTemplate.replace(latestLogPathPlaceholder, exportedFile.absolutePath)
                        } else {
                            /** Latest log export failed message. */
                            latestLogExportFailedMessage
                        }
                    }
                },
                enabled = !uiState.isSaving,
                modifier = Modifier.weight(1f),
            ) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_export_latest_log))
            }
            /** Outlined button. */
            OutlinedButton(
                onClick = {
                    scope.launch {
                        /** Exported file. */
                        val exportedFile = logger.exportAllLogs()
                        debugExportMessage = if (exportedFile != null) {
                            allLogsExportedTemplate.replace(allLogsPathPlaceholder, exportedFile.absolutePath)
                        } else {
                            /** All logs export failed message. */
                            allLogsExportFailedMessage
                        }
                    }
                },
                enabled = !uiState.isSaving,
                modifier = Modifier.weight(1f),
            ) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_export_all_logs))
            }
        }
        /** If. */
        if (debugExportMessage != null) {
            /** Text. */
            Text(
                text = debugExportMessage.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        /** Button. */
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
            /** Text. */
            Text(
                text = if (uiState.isSaving) {
                    /** String resource. */
                    stringResource(id = R.string.db_passphrase_setup_saving)
                } else {
                    /** String resource. */
                    stringResource(id = R.string.db_passphrase_setup_action)
                },
            )
        }
    }

    /** If. */
    if (showResetConfirmDialog) {
        /** Alert dialog. */
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text(stringResource(id = R.string.db_passphrase_setup_reset_title)) },
            text = { Text(stringResource(id = R.string.db_passphrase_setup_reset_message)) },
            confirmButton = {
                /** Button. */
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
                    /** Text. */
                    Text(stringResource(id = R.string.db_passphrase_unlock_reset_confirm))
                }
            },
            dismissButton = {
                /** Text button. */
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    /** Text. */
                    Text(stringResource(id = R.string.settings_action_cancel))
                }
            },
        )
    }
}
