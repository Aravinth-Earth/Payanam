//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.components.DimensionDropdownBadge
import io.payanam.ui.components.DimensionDropdownBadgeLabelRow
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.viewmodel.DimensionOption
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Edit-task form fields collected by the edit screen, converted to a
 * TaskInput on save.
 */
data class EditTaskInput(
    val title: String,
    val description: String?,
    val dimensionId: String,
    val dimensionLabel: String,
    val dueDate: LocalDateTime?,
    val impactLevel: String,
    val goalAlignment: String,
    val energyLevel: String,
    val controlLevel: String,
    val durationMinutes: Int,
    val recurrenceEnabled: Boolean,
    val recurrenceRule: String?,
    val notificationMode: String,
    val customNotificationMinutes: Int?,
    val explicitUrgency: Double?,
    val focusRequired: Double?,
    val blockedReason: String?,
    val externalDependency: String?,
    val tags: List<String> = emptyList(),
)

internal fun resolveImpactIndex(level: String): Int = when (level) {
    "Critical Impact", "High Impact", "Major Impact" -> 2
    "Moderate Impact" -> 1
    "Low Impact", "Minor Impact", "Minimal Impact" -> 0
    else -> 1
}

internal fun resolveAlignmentIndex(level: String): Int = when (level) {
    "Perfect Alignment", "Strong Alignment", "High Alignment" -> 2
    "Moderate Alignment" -> 1
    "Weak Alignment", "No Alignment", "Low Alignment" -> 0
    else -> 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditLifeDimensionDropdown(
    selectedDimensionId: String,
    options: List<DimensionOption>,
    onSelect: (DimensionOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDimension = options.firstOrNull { it.id == selectedDimensionId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedDimension?.label ?: selectedDimensionId,
            onValueChange = {},
            readOnly = true,
            leadingIcon = {
                selectedDimension?.let { dimension ->
                    DimensionDropdownBadge(
                        label = dimension.label,
                        color = dimension.color,
                        iconOption = DimensionIconCatalog.resolve(dimension.iconKey, dimension.id),
                        size = 22.dp,
                    )
                }
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { dimension ->
                DropdownMenuItem(
                    text = {
                        DimensionDropdownBadgeLabelRow(
                            label = dimension.label,
                            color = dimension.color,
                            iconOption = DimensionIconCatalog.resolve(dimension.iconKey, dimension.id),
                            badgeSize = 22.dp,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        onSelect(dimension)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditScoringSegmentedRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                    ),
                    onClick = { onSelect(index) },
                    selected = index == selectedIndex,
                ) {
                    Text(
                        text = option.split(" ").first(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_time)) },
        text = {
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(timePickerState.hour, timePickerState.minute) },
            ) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
            }
        },
    )
}
