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
    /** Selected date. */
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** Selected date millis. */
    val selectedDateMillis = remember(selectedDate) {
        /** Selected date. */
        selectedDate
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
    /** Date picker state. */
    val datePickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = selectedDateMillis,
    )

    /** Date picker dialog. */
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            /** Text button. */
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        /** Resolved date. */
                        val resolvedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                        logger.d(
                            "TimeScreenDatePickerDialog.onConfirm",
                            "Selected date for time screen",
                            /** Map of. */
                            mapOf("selectedDate" to resolvedDate.toString()),
                        )
                        /** On date selected. */
                        onDateSelected(resolvedDate)
                    }
                    /** On dismiss. */
                    onDismiss()
                },
            ) {
                /** Text. */
                Text(stringResource(id = R.string.loc_ok))
            }
        },
        dismissButton = {
            /** Text button. */
            TextButton(onClick = onDismiss) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_cancel))
            }
        },
    ) {
        /** Date picker. */
        DatePicker(state = datePickerState)
    }
}
