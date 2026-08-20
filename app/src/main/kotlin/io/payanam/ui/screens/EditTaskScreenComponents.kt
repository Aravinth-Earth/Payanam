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
 * EditTaskInput.
 */
data class EditTaskInput(
    /** Title. */
    val title: String,
    /** Description. */
    val description: String?,
    /** Dimension id. */
    val dimensionId: String,
    /** Dimension label. */
    val dimensionLabel: String,
    /** Due date. */
    val dueDate: LocalDateTime?,
    /** Impact level. */
    val impactLevel: String,
    /** Goal alignment. */
    val goalAlignment: String,
    /** Energy level. */
    val energyLevel: String,
    /** Control level. */
    val controlLevel: String,
    /** Duration minutes. */
    val durationMinutes: Int,
    /** Recurrence enabled. */
    val recurrenceEnabled: Boolean,
    /** Recurrence rule. */
    val recurrenceRule: String?,
    /** Notification mode. */
    val notificationMode: String,
    /** Custom notification minutes. */
    val customNotificationMinutes: Int?,
    /** Explicit urgency. */
    val explicitUrgency: Double?,
    /** Focus required. */
    val focusRequired: Double?,
    /** Blocked reason. */
    val blockedReason: String?,
    /** External dependency. */
    val externalDependency: String?,
    /** Tags. */
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
    /** Selected dimension id. */
    selectedDimensionId: String,
    options: List<DimensionOption>,
    onSelect: (DimensionOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    /** Selected dimension. */
    val selectedDimension = options.firstOrNull { it.id == selectedDimensionId }
    /** Exposed dropdown menu box. */
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        /** Outlined text field. */
        OutlinedTextField(
            value = selectedDimension?.label ?: selectedDimensionId,
            onValueChange = {},
            readOnly = true,
            leadingIcon = {
                selectedDimension?.let { dimension ->
                    /** Dimension dropdown badge. */
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
        /** Exposed dropdown menu. */
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { dimension ->
                /** Dropdown menu item. */
                DropdownMenuItem(
                    text = {
                        /** Dimension dropdown badge label row. */
                        DimensionDropdownBadgeLabelRow(
                            label = dimension.label,
                            color = dimension.color,
                            iconOption = DimensionIconCatalog.resolve(dimension.iconKey, dimension.id),
                            badgeSize = 22.dp,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        /** On select. */
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
    /** Label. */
    label: String,
    options: List<String>,
    /** Selected index. */
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        /** Text. */
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Spacer. */
        Spacer(modifier = Modifier.height(4.dp))
        /** Single choice segmented button row. */
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                /** Segmented button. */
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                    ),
                    onClick = { onSelect(index) },
                    selected = index == selectedIndex,
                ) {
                    /** Text. */
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
    /** Initial hour. */
    initialHour: Int,
    /** Initial minute. */
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    /** Time picker state. */
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_time)) },
        text = {
            /** Time picker. */
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            /** Text button. */
            TextButton(
                onClick = { onConfirm(timePickerState.hour, timePickerState.minute) },
            ) {
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
    )
}
