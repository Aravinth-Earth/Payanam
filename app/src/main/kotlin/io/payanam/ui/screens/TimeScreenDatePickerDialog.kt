//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * Time screen date picker dialog.
 */
fun TimeScreenDatePickerDialog(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val logger = remember { UnifiedLogger.getInstance() }
    val selectedDateMillis = remember(selectedDate) {
        selectedDate
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
    val datePickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = selectedDateMillis,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val resolvedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        logger.d(
                            "TimeScreenDatePickerDialog.onConfirm",
                            "Selected date for time screen",
                            mapOf("selectedDate" to resolvedDate.toString()),
                        )
                        onDateSelected(resolvedDate)
                    }
                    onDismiss()
                },
            ) {
                Text(stringResource(id = R.string.loc_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.settings_action_cancel))
            }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}
