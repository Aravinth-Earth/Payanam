//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.payanam.ui.viewmodel.LocalAppPreferences
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RescheduleDialog(
    currentDueDate: LocalDateTime,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val prefs = LocalAppPreferences.current
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    val timePattern = if (prefs.timeFormat.use24Hour) "HH:mm" else "h:mm a"
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern)
    var selectedDate by remember { mutableStateOf(currentDueDate.toLocalDate()) }
    var selectedTime by remember { mutableStateOf(currentDueDate.toLocalTime().withSecond(0).withNano(0)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reschedule_task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_new_due_date_and_time),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDatePicker = true }) {
                        Text(selectedDate.format(dateFormatter))
                    }
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        Text(selectedTime.format(timeFormatter))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalDateTime.of(selectedDate, selectedTime)) }) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reschedule))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
    )

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli(),
        )

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialTime = selectedTime,
            onConfirm = { newTime ->
                selectedTime = newTime
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialTime: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(LocalTime.of(timePickerState.hour, timePickerState.minute))
            }) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
        text = {
            TimePicker(state = timePickerState)
        },
    )
}
