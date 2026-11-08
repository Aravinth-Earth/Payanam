//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

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
import io.payanam.core.ui.components.DimensionDropdownBadge
import io.payanam.core.ui.components.DimensionDropdownBadgeLabelRow
import io.payanam.core.ui.model.DimensionIconCatalog
import io.payanam.core.ui.preferences.DimensionOption
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.tasks.EditTaskInput
import java.time.LocalDateTime

/**
 * Maps a task's stored impact level onto its index in the edit screen's three-button row.
 *
 * The row offers exactly three steps, but the database stores five gradations, so the stored string
 * is folded down: `Critical`, `High` and `Major` share the top button, `Moderate` the middle, and
 * `Low`, `Minor` and `Minimal` the bottom. Folding rather than indexing is deliberate - a five-way
 * control would not fit the row, and the top and bottom steps are the only ones a user chooses
 * between in practice.
 *
 * The scale is a STORED value, not a localized one, so the comparison is against the canonical
 * English strings that the scoring config and the seed write. The user-facing button labels are the
 * translated `impactOptions` in [io.payanam.core.ui.tasks.EditTaskScreen]; the index is the bridge
 * between the two, and only this function knows the mapping.
 *
 * An unrecognized or blank level - an older row, an import, a custom value - resolves to `1`, the
 * middle step. Not `0`: defaulting to the bottom step would silently record a major task as a
 * minimal one and let the user save that, whereas the middle is the one choice that is neither
 * claim.
 *
 * @param level the task's stored `impactLevel`; matching is exact and case-sensitive.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun resolveImpactIndex(level: String): Int =
    when (level) {
        "Critical Impact", "High Impact", "Major Impact" -> 2
        "Moderate Impact" -> 1
        "Low Impact", "Minor Impact", "Minimal Impact" -> 0
        else -> 1
    }

/**
 * Maps a task's stored goal alignment onto its index in the edit screen's three-button row.
 *
 * The same fold as [resolveImpactIndex], over the alignment scale instead of the impact one: the
 * row has three steps and the database stores five, so `Perfect`, `Strong` and `High` share the top
 * button, `Moderate` the middle, and `Weak`, `No` and `Low` the bottom.
 *
 * Matching is against the canonical English strings the scoring config persists, not against the
 * translated `alignmentOptions` the row renders; the index is what bridges the stored value and
 * the localized label, and the value written back on save is again the canonical English string.
 *
 * An unrecognized or blank alignment resolves to `1`, the middle step, for the same reason as
 * [resolveImpactIndex] - an unmatched value must not be reported as either extreme.
 *
 * @param level the task's stored `goalAlignment`; matching is exact and case-sensitive.
 */
fun resolveAlignmentIndex(level: String): Int =
    when (level) {
        "Perfect Alignment", "Strong Alignment", "High Alignment" -> 2
        "Moderate Alignment" -> 1
        "Weak Alignment", "No Alignment", "Low Alignment" -> 0
        else -> 1
    }

private const val ENERGY_INDEX_LOW = 0
private const val ENERGY_INDEX_MODERATE = 1
private const val ENERGY_INDEX_HIGH = 2

private const val CONTROL_INDEX_FULL = 0
private const val CONTROL_INDEX_MOSTLY = 1
private const val CONTROL_INDEX_OFFICE = 2
private const val CONTROL_INDEX_EXTERNAL = 3

/**
 * Maps a task's stored energy level onto its index in the edit screen's three-button row.
 *
 * The row's order is `Low`, `Moderate`, `High`; the scoring config stores the same three labels,
 * so a legacy synonym is folded onto its canonical label first. An unrecognized or blank level
 * resolves to `1` (moderate), never `0`: the top step would silently rewrite the task as the
 * lowest-energy option on the next save.
 *
 * @param level the task's stored `energyLevel`; matching is exact and case-sensitive.
 */
fun resolveEnergyIndex(level: String): Int =
    when (level) {
        "High", "High Energy" -> ENERGY_INDEX_HIGH
        "Moderate", "Medium", "Moderate Energy" -> ENERGY_INDEX_MODERATE
        "Low", "Low Energy" -> ENERGY_INDEX_LOW
        else -> ENERGY_INDEX_MODERATE
    }

/**
 * Maps a task's stored control level onto its index in the edit screen's four-button row.
 *
 * The row's order is `Full Control`, `Mostly Controllable`, `Office/Colleagues Dependent`,
 * `External Dependent`, which covers four of the five labels the scoring config stores; `No
 * Control` folds onto the last step. Legacy synonyms are folded onto their canonical label first.
 * An unrecognized or blank level resolves to `2` (`Office/Colleagues Dependent`), the value the
 * task repository already writes as its own default, rather than to `0`.
 *
 * @param level the task's stored `controlLevel`; matching is exact and case-sensitive.
 */
fun resolveControlIndex(level: String): Int =
    when (level) {
        "Full Control", "Self", "Self Controlled", "Fully Independent" -> CONTROL_INDEX_FULL
        "Mostly Controllable" -> CONTROL_INDEX_MOSTLY
        "Office/Colleagues Dependent" -> CONTROL_INDEX_OFFICE
        "External Dependent", "Fully Dependent", "No Control" -> CONTROL_INDEX_EXTERNAL
        else -> CONTROL_INDEX_OFFICE
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditLifeDimensionDropdown(
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
            modifier =
                Modifier
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
fun EditScoringSegmentedRow(
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
                    shape =
                        SegmentedButtonDefaults.itemShape(
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
fun EditTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val strings = LocalUiStrings.current
    val timePickerState =
        rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
        )
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.locSelectTime) },
        text = {
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(timePickerState.hour, timePickerState.minute) },
            ) {
                Text(strings.locOk)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}
