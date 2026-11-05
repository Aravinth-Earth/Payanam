//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger
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
fun CompletionDialog(
    taskTitle: String,
    plannedDurationMinutes: Int,
    plannedCompletedAt: LocalDateTime? = null,
    onDismiss: () -> Unit,
    onSave: (LocalDateTime?, Int?) -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = AppLogger.current
    val currentTime = LocalDateTime.now()
    val defaultTime = plannedCompletedAt ?: currentTime
    val defaultDuration = plannedDurationMinutes
    val defaultTimeLabel =
        if (plannedCompletedAt != null) {
            strings.locPlannedDueTime
        } else {
            strings.locNowLowercase
        }

    var overrideTime by remember { mutableStateOf(false) }
    var selectedTime by remember { mutableStateOf<LocalTime?>(defaultTime.toLocalTime()) }
    var showTimePicker by remember { mutableStateOf(false) }
    var overrideDuration by remember { mutableStateOf(false) }
    var durationMinutes by remember { mutableStateOf(plannedDurationMinutes.toString()) }
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val timePickerState =
        rememberTimePickerState(
            initialHour = selectedTime?.hour ?: defaultTime.hour,
            initialMinute = selectedTime?.minute ?: defaultTime.minute,
        )

    // Time Picker Dialog
    if (showTimePicker) {
        PayanamAlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                    showTimePicker = false
                }) {
                    Text(strings.locOk)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(strings.settingsActionCancel)
                }
            },
            text = {
                TimePicker(state = timePickerState)
            },
        )
    }
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.locMarkDone,
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
                    text = strings.locSpecifyActualCompletionDetails,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Completion Time Section
                Text(
                    text = strings.locCompletionTime,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Default time display
                Text(
                    text =
                        strings.locDefaultTimeWithLabel.format(
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
                        modifier = Modifier.testTag("completion_override_time"),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.locOverrideWithCustomTime,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (overrideTime) {
                    // Show default time as striked out
                    Text(
                        text = defaultTime.format(timeFormatter),
                        style =
                            MaterialTheme.typography.bodySmall.copy(
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
                            text =
                                selectedTime?.format(timeFormatter)
                                    ?: strings.locSelectTime,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Duration Section
                Text(
                    text = strings.locDuration,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Default duration display
                Text(
                    text = strings.locDefaultDurationMinutesPlanned.format(defaultDuration),
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
                        modifier = Modifier.testTag("completion_override_duration"),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.locOverrideWithCustomDuration,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (overrideDuration) {
                    // Show default duration as striked out
                    Text(
                        text = strings.locDurationMinutesPlain.format(defaultDuration),
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                textDecoration = TextDecoration.LineThrough,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Custom duration input
                    OutlinedTextField(
                        value = durationMinutes,
                        onValueChange = { durationMinutes = it },
                        label = { Text(strings.locCustomDurationMinutes) },
                        placeholder = { Text(strings.locHowLongDidItTake) },
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
                    val actualTime =
                        if (overrideTime) {
                            selectedTime?.let { LocalDateTime.of(defaultTime.toLocalDate(), it) }
                        } else {
                            defaultTime
                        }
                    val actualDuration =
                        if (overrideDuration) {
                            durationMinutes.toIntOrNull()
                        } else {
                            defaultDuration
                        }
                    logger.i(
                        "CompletionDialog",
                        "Marking task as done",
                        mapOf(
                            "taskTitle" to taskTitle,
                            "actualTime" to actualTime?.toString(),
                            "actualDuration" to actualDuration,
                        ),
                    )
                    onSave(actualTime, actualDuration)
                },
            ) {
                Text(strings.locMarkDone)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}
