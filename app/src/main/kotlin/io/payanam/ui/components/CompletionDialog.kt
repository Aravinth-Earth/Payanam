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
 * Completion dialog.
 */
fun CompletionDialog(
    /** Task title. */
    taskTitle: String,
    /** Planned duration minutes. */
    plannedDurationMinutes: Int,
    plannedCompletedAt: LocalDateTime? = null,
    onDismiss: () -> Unit,
    onSave: (LocalDateTime?, Int?) -> Unit,
) {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Current time. */
    val currentTime = LocalDateTime.now()
    /** Default time. */
    val defaultTime = plannedCompletedAt ?: currentTime
    /** Default duration. */
    val defaultDuration = plannedDurationMinutes
    /** Default time label. */
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

    /** Time formatter. */
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    /** Time picker state. */
    val timePickerState = rememberTimePickerState(
        initialHour = selectedTime?.hour ?: defaultTime.hour,
        initialMinute = selectedTime?.minute ?: defaultTime.minute,
    )

    // Time Picker Dialog
    /** If. */
    if (showTimePicker) {
        /** Alert dialog. */
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                /** Text button. */
                TextButton(onClick = {
                    selectedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
                }
            },
            dismissButton = {
                /** Text button. */
                TextButton(onClick = { showTimePicker = false }) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                }
            },
            text = {
                /** Time picker. */
                TimePicker(state = timePickerState)
            },
        )
    }

    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            /** Text. */
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mark_done),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            /** Column. */
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                /** Text. */
                Text(
                    text = taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )

                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))

                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_specify_actual_completion_details),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                /** Spacer. */
                Spacer(modifier = Modifier.height(16.dp))

                // Completion Time Section
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_completion_time),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )

                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))

                // Default time display
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        id = io.payanam.R.string.loc_default_time_with_label,
                        defaultTime.format(timeFormatter),
                        /** Default time label. */
                        defaultTimeLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))

                // Override time checkbox
                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /** Checkbox. */
                    Checkbox(
                        checked = overrideTime,
                        onCheckedChange = { overrideTime = it },
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(8.dp))
                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_override_with_custom_time),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))

                /** If. */
                if (overrideTime) {
                    // Show default time as striked out
                    /** Text. */
                    Text(
                        text = defaultTime.format(timeFormatter),
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.LineThrough,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    /** Spacer. */
                    Spacer(modifier = Modifier.height(4.dp))

                    // Time picker button
                    /** Outlined button. */
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        /** Text. */
                        Text(
                            text = selectedTime?.format(timeFormatter)
                                ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_time),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                /** Spacer. */
                Spacer(modifier = Modifier.height(16.dp))

                // Duration Section
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )

                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))

                // Default duration display
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        id = io.payanam.R.string.loc_default_duration_minutes_planned,
                        /** Default duration. */
                        defaultDuration,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))

                // Override duration checkbox
                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /** Checkbox. */
                    Checkbox(
                        checked = overrideDuration,
                        onCheckedChange = { overrideDuration = it },
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(8.dp))
                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_override_with_custom_duration),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                /** Spacer. */
                Spacer(modifier = Modifier.height(8.dp))

                /** If. */
                if (overrideDuration) {
                    // Show default duration as striked out
                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            id = io.payanam.R.string.loc_duration_minutes_plain,
                            /** Default duration. */
                            defaultDuration,
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.LineThrough,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    /** Spacer. */
                    Spacer(modifier = Modifier.height(4.dp))

                    // Custom duration input
                    /** Outlined text field. */
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
            /** Text button. */
            TextButton(
                onClick = {
                    /** Actual time. */
                    val actualTime = if (overrideTime) {
                        selectedTime?.let { LocalDateTime.of(defaultTime.toLocalDate(), it) }
                    } else {
                        /** Default time. */
                        defaultTime
                    }
                    /** Actual duration. */
                    val actualDuration = if (overrideDuration) {
                        durationMinutes.toIntOrNull()
                    } else {
                        /** Default duration. */
                        defaultDuration
                    }
                    logger.i("CompletionDialog", "Marking task as done", mapOf("taskTitle" to taskTitle, "actualTime" to actualTime?.toString(), "actualDuration" to actualDuration))
                    /** On save. */
                    onSave(actualTime, actualDuration)
                },
            ) {
                /** Text. */
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mark_done))
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(onClick = onDismiss) {
                /** Text. */
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
    )
}
