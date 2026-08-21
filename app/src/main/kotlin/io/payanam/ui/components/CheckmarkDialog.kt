//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming", "UndocumentedPublicProperty")

package io.payanam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Dialog for editing a checkmark entry.
 * Allows selecting status (completed, skipped, missed) and adding notes.
 *
 * Similar to uHabits' CheckmarkDialog.
 */
@Composable
/**
 * Returns true when the checkmark dialog.
 */
fun CheckmarkDialog(
    date: LocalDate,
    currentStatus: CheckmarkStatus,
    currentNote: String,
    onDismiss: () -> Unit,
    onSave: (CheckmarkStatus, String) -> Unit,
) {
    val logger = UnifiedLogger.getInstance()
    var selectedStatus by remember { mutableStateOf(currentStatus) }
    var note by remember { mutableStateOf(currentNote) }
    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = date.format(dateFormatter),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_how_did_it_go),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Status selection row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatusOption(
                        status = CheckmarkStatus.COMPLETED,
                        labelRes = io.payanam.R.string.loc_done,
                        icon = Icons.Default.Check,
                        color = Color(0xFF4CAF50),
                        isSelected = selectedStatus == CheckmarkStatus.COMPLETED,
                        onClick = { selectedStatus = CheckmarkStatus.COMPLETED },
                    )
                    StatusOption(
                        status = CheckmarkStatus.SKIPPED,
                        labelRes = io.payanam.R.string.task_notification_action_skip,
                        icon = Icons.Default.Remove,
                        color = Color(0xFF9E9E9E),
                        isSelected = selectedStatus == CheckmarkStatus.SKIPPED,
                        onClick = { selectedStatus = CheckmarkStatus.SKIPPED },
                    )
                    StatusOption(
                        status = CheckmarkStatus.MISSED,
                        labelRes = io.payanam.R.string.loc_missed,
                        icon = Icons.Default.Close,
                        color = Color(0xFFF44336),
                        isSelected = selectedStatus == CheckmarkStatus.MISSED,
                        onClick = { selectedStatus = CheckmarkStatus.MISSED },
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Clear button (like uHabits) - resets to PENDING
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(
                        onClick = { selectedStatus = CheckmarkStatus.PENDING },
                    ) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_clear_not_filled),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))

                // Notes field
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_notes_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_how_was_it_any_observations)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    logger.i("CheckmarkDialog", "Saving checkmark status", mapOf("date" to date.toString(), "status" to selectedStatus.name, "note" to note))
                    onSave(selectedStatus, note)
                },
            ) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
    )
}

@Composable
private fun StatusOption(
    status: CheckmarkStatus,
    @androidx.annotation.StringRes labelRes: Int,
    icon: ImageVector,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isSelected) color else color.copy(alpha = 0.2f),
                )
                .border(
                    width = if (isSelected) 3.dp else 0.dp,
                    color = if (isSelected) color else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = androidx.compose.ui.res.stringResource(id = labelRes),
                tint = if (isSelected) Color.White else color,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(id = labelRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Skip/miss reason options for checkmark dialog.
 */
enum class CheckmarkSkipReason(@androidx.annotation.StringRes val labelRes: Int) {
    NO_TIME(io.payanam.R.string.loc_no_time_today),
    LOW_ENERGY(io.payanam.R.string.loc_low_energy),
    SICK(io.payanam.R.string.loc_sick_unwell),
    TRAVELING(io.payanam.R.string.loc_traveling),
    INTENTIONAL(io.payanam.R.string.loc_intentional_rest),
    OTHER(io.payanam.R.string.loc_other_reason),
}

/**
 * Extended dialog with skip reason selection.
 */
@Composable
/**
 * Returns true when the checkmark dialog with reason.
 */
fun CheckmarkDialogWithReason(
    date: LocalDate,
    currentStatus: CheckmarkStatus,
    currentNote: String,
    currentReason: CheckmarkSkipReason?,
    onDismiss: () -> Unit,
    onSave: (CheckmarkStatus, String, CheckmarkSkipReason?) -> Unit,
) {
    val logger = UnifiedLogger.getInstance()
    var selectedStatus by remember { mutableStateOf(currentStatus) }
    var note by remember { mutableStateOf(currentNote) }
    var selectedReason by remember { mutableStateOf(currentReason) }
    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = date.format(dateFormatter),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_how_did_it_go),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Status selection row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatusOption(
                        status = CheckmarkStatus.COMPLETED,
                        labelRes = io.payanam.R.string.loc_done,
                        icon = Icons.Default.Check,
                        color = Color(0xFF4CAF50),
                        isSelected = selectedStatus == CheckmarkStatus.COMPLETED,
                        onClick = {
                            selectedStatus = CheckmarkStatus.COMPLETED
                            selectedReason = null
                        },
                    )
                    StatusOption(
                        status = CheckmarkStatus.SKIPPED,
                        labelRes = io.payanam.R.string.task_notification_action_skip,
                        icon = Icons.Default.Remove,
                        color = Color(0xFF9E9E9E),
                        isSelected = selectedStatus == CheckmarkStatus.SKIPPED,
                        onClick = { selectedStatus = CheckmarkStatus.SKIPPED },
                    )
                    StatusOption(
                        status = CheckmarkStatus.MISSED,
                        labelRes = io.payanam.R.string.loc_missed,
                        icon = Icons.Default.Close,
                        color = Color(0xFFF44336),
                        isSelected = selectedStatus == CheckmarkStatus.MISSED,
                        onClick = { selectedStatus = CheckmarkStatus.MISSED },
                    )
                }

                // Skip reason chips (only show when skipped/missed)
                if (selectedStatus == CheckmarkStatus.SKIPPED || selectedStatus == CheckmarkStatus.MISSED) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reason_optional),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Reason chips in a flow layout
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CheckmarkSkipReason.entries.take(3).forEach { reason ->
                                ReasonChip(
                                    reason = reason,
                                    isSelected = selectedReason == reason,
                                    onClick = {
                                        selectedReason = if (selectedReason == reason) null else reason
                                    },
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CheckmarkSkipReason.entries.drop(3).forEach { reason ->
                                ReasonChip(
                                    reason = reason,
                                    isSelected = selectedReason == reason,
                                    onClick = {
                                        selectedReason = if (selectedReason == reason) null else reason
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))

                // Notes field
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_notes_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_how_was_it_any_observations)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    logger.i("CheckmarkDialogWithReason", "Saving checkmark status with reason", mapOf("date" to date.toString(), "status" to selectedStatus.name, "note" to note, "reason" to selectedReason?.name))
                    onSave(selectedStatus, note, selectedReason)
                },
            ) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
    )
}

@Composable
private fun ReasonChip(
    reason: CheckmarkSkipReason,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(id = reason.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
