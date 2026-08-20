//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.BulkHabitMappingResult
import io.payanam.feature.settings.ExportResult
import io.payanam.feature.settings.ImportResult
import io.payanam.feature.settings.SettingsUiState
import io.payanam.feature.settings.SettingsViewModel
import io.payanam.feature.settings.UhabitsImportResult
import io.payanam.ui.viewmodel.DimensionPreference
import kotlin.system.exitProcess

@Composable
internal fun ImportDatabaseConfirmDialog(
    /** Show encrypted mode warning. */
    showEncryptedModeWarning: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_import_dialog_title)) },
        text = {
            /** Column. */
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.settings_import_dialog_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                /** Text. */
                Text(
                    text = stringResource(id = R.string.settings_import_dialog_data_loss),
                    style = MaterialTheme.typography.bodyMedium,
                )
                /** Text. */
                Text(
                    text = stringResource(id = R.string.settings_import_dialog_backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                /** If. */
                if (showEncryptedModeWarning) {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.settings_import_dialog_encrypted_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            /** Button. */
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_delete_import))
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(onClick = onDismiss) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_cancel))
            }
        },
    )
}

@Composable
internal fun DeleteAllDataConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_delete_dialog_title)) },
        text = {
            /** Text. */
            Text(
                text = stringResource(id = R.string.settings_delete_dialog_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            /** Button. */
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_delete_all_data))
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(onClick = onDismiss) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_cancel))
            }
        },
    )
}

@Composable
internal fun DeleteExportPromptDialog(
    onBackUpFirst: () -> Unit,
    onSkipAndDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_delete_export_prompt_title)) },
        text = {
            /** Text. */
            Text(
                text = stringResource(id = R.string.settings_delete_export_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            /** Button. */
            Button(onClick = onBackUpFirst) {
                /** Text. */
                Text(stringResource(id = R.string.settings_delete_export_prompt_yes))
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(
                onClick = onSkipAndDelete,
            ) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.settings_delete_export_prompt_no),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
internal fun SettingsImportFeedbackEffects(
    /** Ui state. */
    uiState: SettingsUiState,
    /** Snackbar host state. */
    snackbarHostState: SnackbarHostState,
    /** Context. */
    context: Context,
    dimensionPreferences: List<DimensionPreference>,
    /** View model. */
    viewModel: SettingsViewModel,
) {
    /** Launched effect. */
    LaunchedEffect(uiState.exportResult) {
        uiState.exportResult?.let { result ->
            /** When. */
            when (result) {
                is ExportResult.Success -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_export_success,
                            result.fileName,
                        ),
                    )
                    viewModel.clearExportResult()
                }

                is ExportResult.Error -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_export_failed,
                            result.message,
                        ),
                    )
                    viewModel.clearExportResult()
                }
            }
        }
    }

    /** Launched effect. */
    LaunchedEffect(uiState.importResult) {
        uiState.importResult?.let { result ->
            /** When. */
            when (result) {
                is ImportResult.Success -> {
                    /** If. */
                    if (result.requiresAppRestart) {
                        snackbarHostState.showSnackbar(
                            context.getString(
                                R.string.settings_snackbar_import_success_restart,
                                result.tasksImported,
                                result.timeEntriesImported,
                                result.notesImported,
                            ),
                        )
                        viewModel.clearImportResult()
                        /** Restart app after database import. */
                        restartAppAfterDatabaseImport(context)
                        return@let
                    }
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_import_success,
                            result.tasksImported,
                            result.timeEntriesImported,
                            result.notesImported,
                        ),
                    )
                    viewModel.clearImportResult()
                }

                is ImportResult.Error -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_import_failed,
                            result.message,
                        ),
                    )
                    viewModel.clearImportResult()
                }
            }
        }
    }

    /** Launched effect. */
    LaunchedEffect(uiState.uhabitsImportResult) {
        uiState.uhabitsImportResult?.let { result ->
            /** When. */
            when (result) {
                is UhabitsImportResult.Success -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_uhabits_import_success,
                            result.habitsUpserted,
                            result.repetitionsUpserted,
                        ),
                    )
                    viewModel.clearUhabitsImportResult()
                }

                is UhabitsImportResult.Error -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_uhabits_import_failed,
                            result.message,
                        ),
                    )
                    viewModel.clearUhabitsImportResult()
                }
            }
        }
    }

    /** Launched effect. */
    LaunchedEffect(uiState.bulkHabitMappingResult) {
        uiState.bulkHabitMappingResult?.let { result ->
            /** When. */
            when (result) {
                is BulkHabitMappingResult.Success -> {
                    /** Resolved label. */
                    val resolvedLabel = dimensionPreferences
                        .firstOrNull { it.id == result.dimensionId || it.canonicalId == result.dimensionId }
                        ?.label
                        ?: result.dimensionId
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_habit_mapping_success,
                            result.mappedCount,
                            /** Resolved label. */
                            resolvedLabel,
                        ),
                    )
                    viewModel.clearBulkHabitMappingResult()
                }

                is BulkHabitMappingResult.Error -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_habit_mapping_failed,
                            result.message,
                        ),
                    )
                    viewModel.clearBulkHabitMappingResult()
                }
            }
        }
    }
}

private fun restartAppAfterDatabaseImport(context: Context) {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Launch intent. */
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    /** If. */
    if (launchIntent == null) {
        logger.e(
            "SettingsImportFeedbackEffects.restartAppAfterDatabaseImport",
            "Failed to relaunch app after DB import: launch intent unavailable",
        )
        /** Return. */
        return
    }
    logger.i(
        "SettingsImportFeedbackEffects.restartAppAfterDatabaseImport",
        "Restarting app to reopen imported database with fresh Room handles",
    )
    CrashSafeBreadcrumbs.record(
        context = context,
        source = "SettingsImportFeedbackEffects.restartAppAfterDatabaseImport",
        stage = "kill_process_for_restart",
    )
    logger.flush()
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(launchIntent)
    Process.killProcess(Process.myPid())
    /** Exit process. */
    exitProcess(0)
}

@Composable
internal fun ImportEncryptedDbPassphraseDialog(
    passphraseError: String?,
    /** Is verifying. */
    isVerifying: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.db_import_passphrase_prompt_title)) },
        text = {
            /** Column. */
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.db_import_passphrase_prompt_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                /** Outlined text field. */
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(id = R.string.db_passphrase_input_label)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            /** If. */
                            if (passphrase.isNotBlank() && !isVerifying) {
                                /** On confirm. */
                                onConfirm(passphrase)
                            }
                        },
                    ),
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        /** Icon button. */
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            /** Icon. */
                            Icon(
                                imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    id = if (showPassphrase) R.string.db_passphrase_hide_toggle else R.string.db_passphrase_show_toggle,
                                ),
                            )
                        }
                    },
                    singleLine = true,
                    enabled = !isVerifying,
                )
                passphraseError?.let { err ->
                    /** Text. */
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            /** Button. */
            Button(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotBlank() && !isVerifying,
            ) {
                /** If. */
                if (isVerifying) {
                    /** Circular progress indicator. */
                    CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    /** Text. */
                    Text(stringResource(id = R.string.db_import_passphrase_prompt_action))
                }
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(onClick = onDismiss, enabled = !isVerifying) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_cancel))
            }
        },
    )
}

@Composable
internal fun BulkMapImportedHabitsDialog(
    /** Selected dimension id. */
    selectedDimensionId: String,
    dimensionPreferences: List<DimensionPreference>,
    onSelectedDimensionChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }

    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_bulk_map_dialog_title)) },
        text = {
            /** Column. */
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                /** Text. */
                Text(stringResource(id = R.string.settings_bulk_map_dialog_message))
                dimensionPreferences.forEach { dimension ->
                    /** Label. */
                    val label = dimension.label
                    /** Outlined button. */
                    OutlinedButton(
                        onClick = {
                            logger.d(
                                "BulkMapImportedHabitsDialog",
                                "Bulk map dimension selected",
                                /** Map of. */
                                mapOf("dimensionId" to dimension.id),
                            )
                            /** On selected dimension change. */
                            onSelectedDimensionChange(dimension.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        /** Text. */
                        Text(
                            text = if (selectedDimensionId == dimension.id) {
                                /** String resource. */
                                stringResource(
                                    id = R.string.settings_bulk_map_dimension_selected,
                                    /** Label. */
                                    label,
                                )
                            } else {
                                /** Label. */
                                label
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            /** Text button. */
            TextButton(onClick = onConfirm) {
                /** Text. */
                Text(stringResource(id = R.string.loc_apply))
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(onClick = onDismiss) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_cancel))
            }
        },
    )
}
