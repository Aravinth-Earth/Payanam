//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.service.AutoBackupWorker
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.AppPreferencesViewModel
import io.payanam.ui.viewmodel.BackupInterval
import io.payanam.ui.viewmodel.labelResId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsAutoBackupSection(
    /** Prefs state. */
    prefsState: AppPreferencesState,
    /** Prefs view model. */
    prefsViewModel: AppPreferencesViewModel,
    /** Logger. */
    logger: UnifiedLogger,
    /** Context. */
    context: Context,
    /** Manual backup in progress. */
    manualBackupInProgress: Boolean,
) {
    /** Column. */
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        /** Row. */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.settings_auto_backup_enable),
                    style = MaterialTheme.typography.bodyMedium,
                )
                /** Text. */
                Text(
                    text = if (prefsState.autoBackupEnabled) {
                        /** String resource. */
                        stringResource(id = R.string.settings_auto_backup_enabled_hint)
                    } else {
                        /** String resource. */
                        stringResource(id = R.string.settings_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            /** Switch. */
            Switch(
                checked = prefsState.autoBackupEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setAutoBackupEnabled(enabled)
                    /** If. */
                    if (enabled) {
                        AutoBackupWorker.schedule(context, prefsState.autoBackupInterval.minutes)
                    } else {
                        AutoBackupWorker.cancel(context)
                    }
                    logger.d("SettingsScreen.autoBackup", "Auto-backup toggled", mapOf("enabled" to enabled))
                },
            )
        }
        /** Auto backup failure message card. */
        autoBackupFailureMessageCard(
            errorMessage = prefsState.autoBackupLastErrorMessage,
            errorAt = prefsState.autoBackupLastErrorAt,
            onDismiss = prefsViewModel::dismissAutoBackupFailureMessage,
        )
        /** If. */
        if (prefsState.autoBackupEnabled) {
            var intervalExpanded by remember { mutableStateOf(false) }
            /** Text. */
            Text(
                text = stringResource(id = R.string.settings_auto_backup_interval),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            /** Exposed dropdown menu box. */
            ExposedDropdownMenuBox(
                expanded = intervalExpanded,
                onExpandedChange = { intervalExpanded = it },
            ) {
                /** Outlined text field. */
                OutlinedTextField(
                    value = stringResource(id = prefsState.autoBackupInterval.labelResId),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                /** Dropdown menu. */
                DropdownMenu(
                    expanded = intervalExpanded,
                    onDismissRequest = { intervalExpanded = false },
                ) {
                    BackupInterval.entries.forEach { interval ->
                        /** Dropdown menu item. */
                        DropdownMenuItem(
                            text = { Text(stringResource(id = interval.labelResId)) },
                            onClick = {
                                prefsViewModel.setAutoBackupInterval(interval)
                                AutoBackupWorker.schedule(context, interval.minutes)
                                intervalExpanded = false
                                logger.d(
                                    "SettingsScreen.autoBackupInterval",
                                    "Interval updated",
                                    /** Map of. */
                                    mapOf("interval" to interval.key),
                                )
                            },
                        )
                    }
                }
            }
            prefsState.autoBackupLastRun?.let { lastRun ->
                /** Text. */
                Text(
                    text = stringResource(id = R.string.settings_auto_backup_last_run, lastRun),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            /** Outlined button. */
            OutlinedButton(
                onClick = {
                    prefsViewModel.triggerManualBackupNow()
                    logger.i("SettingsScreen.autoBackup", "Manual backup triggered")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !manualBackupInProgress,
            ) {
                /** Text. */
                Text(
                    /** String resource. */
                    stringResource(
                        id = if (manualBackupInProgress) {
                            R.string.settings_manual_backup_in_progress
                        } else {
                            R.string.settings_action_run_backup_now
                        },
                    ),
                )
            }
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /** Column. */
                Column(modifier = Modifier.fillMaxWidth(0.78f)) {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.settings_backup_rotation_enable),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    /** Text. */
                    Text(
                        text = if (prefsState.backupRotationEnabled) {
                            /** String resource. */
                            stringResource(
                                id = R.string.settings_backup_rotation_enabled_hint,
                                prefsState.backupRotationCount,
                            )
                        } else {
                            /** String resource. */
                            stringResource(id = R.string.settings_backup_rotation_disabled_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                /** Switch. */
                Switch(
                    checked = prefsState.backupRotationEnabled,
                    onCheckedChange = { enabled ->
                        prefsViewModel.setBackupRotationEnabled(enabled)
                        logger.d("SettingsScreen.backupRotation", "Backup rotation toggled", mapOf("enabled" to enabled))
                    },
                )
            }
            /** If. */
            if (prefsState.backupRotationEnabled) {
                var rotationCountText by remember(prefsState.backupRotationCount) {
                    /** Mutable state of. */
                    mutableStateOf(prefsState.backupRotationCount.toString())
                }
                /** Text. */
                Text(
                    text = stringResource(id = R.string.settings_backup_rotation_count),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                /** Outlined text field. */
                OutlinedTextField(
                    value = rotationCountText,
                    onValueChange = { input ->
                        rotationCountText = input.filter { it.isDigit() }.take(3)
                        logger.d("SettingsScreen.backupRotationCountChanged", "Backup rotation count changed")
                        rotationCountText.toIntOrNull()?.let { count ->
                            /** If. */
                            if (count in 1..999) {
                                prefsViewModel.setBackupRotationCount(count)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
    }
}
