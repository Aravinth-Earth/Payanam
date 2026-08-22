//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import java.time.LocalTime

@Composable
internal fun DurationMinutesPickerField(
    label: String,
    minutes: Int?,
    enabled: Boolean,
    onMinutesChange: (Int?) -> Unit,
) {
    val logger = remember { UnifiedLogger.getInstance() }
    val focusManager = LocalFocusManager.current
    var showPicker by remember { mutableStateOf(false) }
    val safeMinutes = (minutes ?: 0).coerceIn(0, 23 * 60 + 59)
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = formatDurationForPickerValue(safeMinutes),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(enabled, safeMinutes, label) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onTap = {
                            logger.d(
                                "DurationMinutesPickerField",
                                "Opening duration picker",
                                mapOf("label" to label, "currentMinutes" to safeMinutes.toString()),
                            )
                            showPicker = true
                        },
                    )
                }
                .onFocusChanged { focusState ->
                    logger.d(
                        "DurationMinutesPickerField",
                        "Duration field focus changed",
                        mapOf(
                            "label" to label,
                            "isFocused" to focusState.isFocused.toString(),
                            "hasFocus" to focusState.hasFocus.toString(),
                            "showPicker" to showPicker.toString(),
                        ),
                    )
                    if (enabled && focusState.isFocused && !showPicker) {
                        logger.d(
                            "DurationMinutesPickerField",
                            "Opening duration picker from focus fallback",
                            mapOf("label" to label, "currentMinutes" to safeMinutes.toString()),
                        )
                        showPicker = true
                    }
                },
            enabled = enabled,
            singleLine = true,
        )
    }
    LaunchedEffect(showPicker) {
        logger.d(
            "DurationMinutesPickerField",
            "Duration picker visibility changed",
            mapOf("label" to label, "showPicker" to showPicker.toString()),
        )
    }
    if (showPicker) {
        TimePickerAlertDialog(
            initialTime = LocalTime.of(safeMinutes / 60, safeMinutes % 60),
            onConfirm = { time ->
                val nextMinutes = time.hour * 60 + time.minute
                onMinutesChange(nextMinutes.takeIf { it > 0 })
                logger.d(
                    "DurationMinutesPickerField",
                    "Selected duration minutes",
                    mapOf("minutes" to nextMinutes.toString(), "label" to label),
                )
                focusManager.clearFocus(force = true)
                showPicker = false
            },
            onDismiss = {
                logger.d(
                    "DurationMinutesPickerField",
                    "Duration picker dismissed",
                    mapOf("label" to label),
                )
                focusManager.clearFocus(force = true)
                showPicker = false
            },
        )
    }
}

@Composable
private fun formatDurationForPickerValue(totalMinutes: Int): String {
    val safeMinutes = totalMinutes.coerceAtLeast(0)
    val hours = safeMinutes / 60
    val minutes = safeMinutes % 60
    return if (hours > 0) {
        stringResource(
            id = R.string.loc_duration_hours_minutes_compact,
            hours,
            minutes,
        )
    } else {
        stringResource(id = R.string.loc_duration_minutes_compact, minutes)
    }
}
