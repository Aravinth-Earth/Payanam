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
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.labelFor
import io.payanam.ui.viewmodel.labelForDimensionId
import kotlin.system.exitProcess

@Composable
internal fun ImportDatabaseConfirmDialog(
    showEncryptedModeWarning: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_import_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(id = R.string.settings_import_dialog_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(id = R.string.settings_import_dialog_data_loss),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(id = R.string.settings_import_dialog_backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showEncryptedModeWarning) {
                    Text(
                        text = stringResource(id = R.string.settings_import_dialog_encrypted_mode_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(id = R.string.settings_action_delete_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_delete_dialog_title)) },
        text = {
            Text(
                text = stringResource(id = R.string.settings_delete_dialog_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(id = R.string.settings_action_delete_all_data))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_delete_export_prompt_title)) },
        text = {
            Text(
                text = stringResource(id = R.string.settings_delete_export_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(onClick = onBackUpFirst) {
                Text(stringResource(id = R.string.settings_delete_export_prompt_yes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSkipAndDelete,
            ) {
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
    uiState: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    context: Context,
    dimensionPreferences: List<DimensionPreference>,
    viewModel: SettingsViewModel,
) {
    LaunchedEffect(uiState.exportResult) {
        uiState.exportResult?.let { result ->
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

    LaunchedEffect(uiState.importResult) {
        uiState.importResult?.let { result ->
            when (result) {
                is ImportResult.Success -> {
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

    LaunchedEffect(uiState.uhabitsImportResult) {
        uiState.uhabitsImportResult?.let { result ->
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

    LaunchedEffect(uiState.bulkHabitMappingResult) {
        uiState.bulkHabitMappingResult?.let { result ->
            when (result) {
                is BulkHabitMappingResult.Success -> {
                    val resolvedLabel = dimensionPreferences
                        .firstOrNull { it.id == result.dimensionId || it.canonicalId == result.dimensionId }
                        ?.label
                        ?: result.dimensionId
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_habit_mapping_success,
                            result.mappedCount,
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
    val logger = UnifiedLogger.getInstance()
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (launchIntent == null) {
        logger.e(
            "SettingsImportFeedbackEffects.restartAppAfterDatabaseImport",
            "Failed to relaunch app after DB import: launch intent unavailable",
        )
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
    exitProcess(0)
}

@Composable
internal fun ImportEncryptedDbPassphraseDialog(
    passphraseError: String?,
    isVerifying: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.db_import_passphrase_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(id = R.string.db_import_passphrase_prompt_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                            if (passphrase.isNotBlank() && !isVerifying) {
                                onConfirm(passphrase)
                            }
                        },
                    ),
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
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
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotBlank() && !isVerifying,
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(id = R.string.db_import_passphrase_prompt_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isVerifying) {
                Text(stringResource(id = R.string.settings_action_cancel))
            }
        },
    )
}

@Composable
internal fun BulkMapImportedHabitsDialog(
    selectedDimensionId: String,
    dimensionPreferences: List<DimensionPreference>,
    onSelectedDimensionChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val logger = remember { UnifiedLogger.getInstance() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.settings_bulk_map_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(id = R.string.settings_bulk_map_dialog_message))
                dimensionPreferences.forEach { dimension ->
                    val label = dimension.label
                    OutlinedButton(
                        onClick = {
                            logger.d(
                                "BulkMapImportedHabitsDialog",
                                "Bulk map dimension selected",
                                mapOf("dimensionId" to dimension.id),
                            )
                            onSelectedDimensionChange(dimension.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (selectedDimensionId == dimension.id) {
                                stringResource(
                                    id = R.string.settings_bulk_map_dimension_selected,
                                    label,
                                )
                            } else {
                                label
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(id = R.string.loc_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.settings_action_cancel))
            }
        },
    )
}
