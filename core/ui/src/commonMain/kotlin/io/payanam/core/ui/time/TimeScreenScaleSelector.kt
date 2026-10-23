//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.payanam.shared.logging.AppLogger
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.LocalUiStrings

private val logger = AppLogger.current

@Composable
fun TimeScalePresetSelector(
    selectedPreset: TimeScalePreset,
    onApplyPreset: (TimeScalePreset) -> Unit,
) {
    val strings = LocalUiStrings.current
    var showScaleMenu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(
            onClick = { showScaleMenu = true },
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(selectedPreset.label(strings))
        }
        DropdownMenu(
            expanded = showScaleMenu,
            onDismissRequest = { showScaleMenu = false },
        ) {
            TIME_SCALE_PRESETS.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label(strings)) },
                    onClick = {
                        logger.d(
                            "TimeScaleSelector.apply",
                            "User selected explicit time scale preset",
                            mapOf("slotMinutes" to preset.slotMinutes.toString()),
                        )
                        onApplyPreset(preset)
                        showScaleMenu = false
                    },
                )
            }
        }
    }
}
