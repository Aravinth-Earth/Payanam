//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.shared.logging.AppLogger

/**
 * Auto-backup settings section: the cadence, the last run, a manual run, and backup rotation.
 *
 * WHY THE SCHEDULING IS A CALLBACK AND NOT A WORKER: the two places that changed this section's behaviour
 * both reach for Android's `WorkManager` - turning the toggle on enqueues a periodic job, and changing the
 * cadence replaces it. That scheduling belongs to the platform, and the seam for it already exists:
 * `io.payanam.shared.platform.BackupScheduler` declares `schedule(intervalMinutes)`/`cancel()` and is
 * implemented by `AndroidBackupScheduler` on the app and an in-process timer on the desktop, whose only
 * honest difference is the `BackupBackgroundCapability` it reports. Reaching past that seam to name
 * `AutoBackupWorker` from a composable would put `androidx.work` and a `Context` into `commonMain` and give
 * the desktop the one thing it cannot do. So the section reports the intent and the caller reconciles the
 * schedule, which is what `BackupScheduler`'s idempotency contract already anticipates - it is documented as
 * something the settings screen does routinely with an interval already in force.
 *
 * WHY SIX WRITES RATHER THAN ONE: they are six different operations, and the section's job is to say which
 * one the user asked for. Enabling writes a preference AND reconciles a schedule; the interval writes a
 * different preference AND reconciles the schedule again; "Run Backup Now" writes no preference at all, it
 * performs a backup and reports a result; the two rotation rows each write a preference and a second store
 * (`payanam_backup_meta`) that the backup sweeper reads; and dismissing the failure banner clears a third
 * store. Collapsing these into one `onBackupChanged` would push a `when` over the row identity onto every
 * caller - and would hide that a manual backup is not a preference write at all.
 *
 * Nothing here reads the failure banner's own copy: [autoBackupFailureMessageCard] is already shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAutoBackupSection(
    prefsState: AppPreferencesState,
    onAutoBackupEnabledChange: (Boolean) -> Unit,
    onAutoBackupIntervalChange: (BackupInterval) -> Unit,
    onManualBackupNow: () -> Unit,
    onBackupRotationEnabledChange: (Boolean) -> Unit,
    onBackupRotationCountChange: (Int) -> Unit,
    onDismissAutoBackupFailure: () -> Unit,
    manualBackupInProgress: Boolean,
    intervalExpanded: Boolean,
    onIntervalExpandedChange: (Boolean) -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = strings.settingsAutoBackupEnable,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text =
                        if (prefsState.autoBackupEnabled) {
                            strings.settingsAutoBackupEnabledHint
                        } else {
                            strings.settingsDisabled
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = prefsState.autoBackupEnabled,
                // The row's title is a SIBLING Text, so without this the toggle announces as its state and
                // nothing else. The description is that same visible title from the copy table, so the
                // announcement cannot disagree with the label the user can see. Deliberately no merge: a merge
                // boundary moves the toggle's own node out of the unmerged semantics tree, and this screen's
                // rendering test walks that tree by geometry.
                modifier =
                    Modifier.semantics {
                        contentDescription = strings.settingsAutoBackupEnable
                    },
                onCheckedChange = { enabled ->
                    onAutoBackupEnabledChange(enabled)
                    logger.d("SettingsScreen.autoBackup", "Auto-backup toggled", mapOf("enabled" to enabled))
                },
            )
        }
        autoBackupFailureMessageCard(
            errorMessage = prefsState.autoBackupLastErrorMessage,
            errorAt = prefsState.autoBackupLastErrorAt,
            onDismiss = onDismissAutoBackupFailure,
        )
        if (prefsState.autoBackupEnabled) {
            Text(
                text = strings.settingsAutoBackupInterval,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExposedDropdownMenuBox(
                expanded = intervalExpanded,
                onExpandedChange = onIntervalExpandedChange,
            ) {
                OutlinedTextField(
                    value = strings.backupIntervalLabel(prefsState.autoBackupInterval),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                DropdownMenu(
                    expanded = intervalExpanded,
                    onDismissRequest = { onIntervalExpandedChange(false) },
                ) {
                    BackupInterval.entries.forEach { interval ->
                        DropdownMenuItem(
                            text = { Text(strings.backupIntervalLabel(interval)) },
                            onClick = {
                                onAutoBackupIntervalChange(interval)
                                onIntervalExpandedChange(false)
                                logger.d(
                                    "SettingsScreen.autoBackupInterval",
                                    "Interval updated",
                                    mapOf("interval" to interval.key),
                                )
                            },
                        )
                    }
                }
            }
            prefsState.autoBackupLastRun?.let { lastRun ->
                Text(
                    text = strings.settingsAutoBackupLastRun.formatArgs(lastRun),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = {
                    onManualBackupNow()
                    logger.i("SettingsScreen.autoBackup", "Manual backup triggered")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !manualBackupInProgress,
            ) {
                Text(
                    if (manualBackupInProgress) {
                        strings.settingsManualBackupInProgress
                    } else {
                        strings.settingsActionRunBackupNow
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.fillMaxWidth(0.78f)) {
                    Text(
                        text = strings.settingsBackupRotationEnable,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text =
                            if (prefsState.backupRotationEnabled) {
                                strings.settingsBackupRotationEnabledHint.formatArgs(prefsState.backupRotationCount)
                            } else {
                                strings.settingsBackupRotationDisabledHint
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = prefsState.backupRotationEnabled,
                    // Same sibling-label shape as the toggle above, and the same fix: the visible title
                    // becomes the control's own description rather than a string typed a second time here.
                    modifier =
                        Modifier.semantics {
                            contentDescription = strings.settingsBackupRotationEnable
                        },
                    onCheckedChange = { enabled ->
                        onBackupRotationEnabledChange(enabled)
                        logger.d("SettingsScreen.backupRotation", "Backup rotation toggled", mapOf("enabled" to enabled))
                    },
                )
            }
            if (prefsState.backupRotationEnabled) {
                var rotationCountText by remember(prefsState.backupRotationCount) {
                    mutableStateOf(prefsState.backupRotationCount.toString())
                }
                Text(
                    text = strings.settingsBackupRotationCount,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = rotationCountText,
                    onValueChange = { input ->
                        rotationCountText = input.filter { it.isDigit() }.take(3)
                        logger.d("SettingsScreen.backupRotationCountChanged", "Backup rotation count changed")
                        rotationCountText.toIntOrNull()?.let { count ->
                            if (count in 1..999) {
                                onBackupRotationCountChange(count)
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
