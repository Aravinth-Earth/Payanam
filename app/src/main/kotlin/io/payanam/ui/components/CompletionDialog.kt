//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Dialog for capturing actual completion details when marking a recurring task as done.
 * Allows specifying actual completion time and duration for behavioral learning.
 * Features separate controls for time and duration with visibility of default values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * Performs the completion dialog.
 */
fun CompletionDialog(
    taskTitle: String,
    plannedDurationMinutes: Int,
    plannedCompletedAt: LocalDateTime? = null,
    onDismiss: () -> Unit,
    onSave: (LocalDateTime?, Int?) -> Unit,
) {
    val logger = UnifiedLogger.getInstance()
    val currentTime = LocalDateTime.now()
    val defaultTime = plannedCompletedAt ?: currentTime
    val defaultDuration = plannedDurationMinutes
    val defaultTimeLabel = androidx.compose.ui.res.stringResource(
        id = if (plannedCompletedAt != null) {
            io.payanam.R.string.loc_planned_due_time
        } else {
            io.payanam.R.string.loc_now_lowercase
        },
    )

    var overrideTime by remember { mutableStateOf(false) }
    var selectedTime by remember { mutableStateOf<LocalTime?>(defaultTime.toLocalTime()) }
    var showTimePicker by remember { mutableStateOf(false) }
    var overrideDuration by remember { mutableStateOf(false) }
    var durationMinutes by remember { mutableStateOf(plannedDurationMinutes.toString()) }
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val timePickerState = rememberTimePickerState(
        initialHour = selectedTime?.hour ?: defaultTime.hour,
        initialMinute = selectedTime?.minute ?: defaultTime.minute,
    )

    // Time Picker Dialog
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) {
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                }
            },
            text = {
                TimePicker(state = timePickerState)
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mark_done),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_specify_actual_completion_details),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Completion Time Section
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_completion_time),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Default time display
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        id = io.payanam.R.string.loc_default_time_with_label,
                        defaultTime.format(timeFormatter),
                        defaultTimeLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Override time checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = overrideTime,
                        onCheckedChange = { overrideTime = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_override_with_custom_time),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (overrideTime) {
                    // Show default time as striked out
                    Text(
                        text = defaultTime.format(timeFormatter),
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.LineThrough,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Time picker button
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = selectedTime?.format(timeFormatter)
                                ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_time),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Duration Section
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Default duration display
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        id = io.payanam.R.string.loc_default_duration_minutes_planned,
                        defaultDuration,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Override duration checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = overrideDuration,
                        onCheckedChange = { overrideDuration = it },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_override_with_custom_duration),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (overrideDuration) {
                    // Show default duration as striked out
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            id = io.payanam.R.string.loc_duration_minutes_plain,
                            defaultDuration,
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.LineThrough,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Custom duration input
                    OutlinedTextField(
                        value = durationMinutes,
                        onValueChange = { durationMinutes = it },
                        label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_custom_duration_minutes)) },
                        placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_how_long_did_it_take)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val actualTime = if (overrideTime) {
                        selectedTime?.let { LocalDateTime.of(defaultTime.toLocalDate(), it) }
                    } else {
                        defaultTime
                    }
                    val actualDuration = if (overrideDuration) {
                        durationMinutes.toIntOrNull()
                    } else {
                        defaultDuration
                    }
                    logger.i("CompletionDialog", "Marking task as done", mapOf("taskTitle" to taskTitle, "actualTime" to actualTime?.toString(), "actualDuration" to actualDuration))
                    onSave(actualTime, actualDuration)
                },
            ) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mark_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
    )
}
