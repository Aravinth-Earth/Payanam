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
    /** Current due date. */
    currentDueDate: LocalDateTime,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
    /** Prefs. */
    val prefs = LocalAppPreferences.current
    /** Date formatter. */
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    /** Time pattern. */
    val timePattern = if (prefs.timeFormat.use24Hour) "HH:mm" else "h:mm a"
    /** Time formatter. */
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern)
    var selectedDate by remember { mutableStateOf(currentDueDate.toLocalDate()) }
    var selectedTime by remember { mutableStateOf(currentDueDate.toLocalTime().withSecond(0).withNano(0)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reschedule_task)) },
        text = {
            /** Column. */
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_new_due_date_and_time),
                )
                /** Row. */
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    /** Outlined button. */
                    OutlinedButton(onClick = { showDatePicker = true }) {
                        /** Text. */
                        Text(selectedDate.format(dateFormatter))
                    }
                    /** Outlined button. */
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        /** Text. */
                        Text(selectedTime.format(timeFormatter))
                    }
                }
            }
        },
        confirmButton = {
            /** Text button. */
            TextButton(onClick = { onConfirm(LocalDateTime.of(selectedDate, selectedTime)) }) {
                /** Text. */
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reschedule))
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

    /** If. */
    if (showDatePicker) {
        /** Date picker state. */
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli(),
        )

        /** Date picker dialog. */
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                /** Text button. */
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
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
                }
            },
            dismissButton = {
                /** Text button. */
                TextButton(onClick = { showDatePicker = false }) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                }
            },
        ) {
            /** Date picker. */
            DatePicker(state = datePickerState)
        }
    }

    /** If. */
    if (showTimePicker) {
        /** Time picker dialog. */
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
    /** Initial time. */
    initialTime: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    /** Time picker state. */
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
    )
    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            /** Text button. */
            TextButton(onClick = {
                /** On confirm. */
                onConfirm(LocalTime.of(timePickerState.hour, timePickerState.minute))
            }) {
                /** Text. */
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(onClick = onDismiss) {
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
