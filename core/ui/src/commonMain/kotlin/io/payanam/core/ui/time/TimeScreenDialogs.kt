//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.DimensionDropdownBadge
import io.payanam.core.ui.components.DimensionDropdownBadgeLabelRow
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.model.DimensionIconCatalog
import io.payanam.core.ui.preferences.DimensionOption
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartTrackingDialog(
    tasks: List<Task>,
    dimensionOptions: List<DimensionOption>,
    onStart: (DimensionOption, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    val fallbackDimensionDefinition = DimensionTaxonomyCatalog.WORK_LIVELIHOOD
    val defaultDimension =
        dimensionOptions.firstOrNull()
            ?: DimensionOption(
                id = fallbackDimensionDefinition.id,
                canonicalId = fallbackDimensionDefinition.id,
                label = fallbackDimensionDefinition.fallbackLabel,
                color = MaterialTheme.colorScheme.primary,
                isVisible = true,
                iconKey = fallbackDimensionDefinition.defaultIconKey,
            )
    var selectedDimension by remember { mutableStateOf(defaultDimension) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var dimensionExpanded by remember { mutableStateOf(false) }
    var taskExpanded by remember { mutableStateOf(false) }
    val selectedLabel = selectedDimension.label
    val filteredTasks = tasks.filter { taskMatchesDimension(it, selectedDimension) }
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.locStartTracking) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    strings.locLifeDimension,
                    style = MaterialTheme.typography.labelMedium,
                )
                ExposedDropdownMenuBox(
                    expanded = dimensionExpanded,
                    onExpandedChange = { dimensionExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        leadingIcon = {
                            DimensionDropdownBadge(
                                label = selectedDimension.label,
                                color = selectedDimension.color,
                                iconOption = DimensionIconCatalog.resolve(selectedDimension.iconKey, selectedDimension.id),
                                size = 22.dp,
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dimensionExpanded) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = dimensionExpanded,
                        onDismissRequest = { dimensionExpanded = false },
                    ) {
                        dimensionOptions.forEach { dim ->
                            DropdownMenuItem(
                                text = {
                                    DimensionDropdownBadgeLabelRow(
                                        label = dim.label,
                                        color = dim.color,
                                        iconOption = DimensionIconCatalog.resolve(dim.iconKey, dim.id),
                                        badgeSize = 22.dp,
                                    )
                                },
                                onClick = {
                                    selectedDimension = dim
                                    selectedTaskId = null
                                    dimensionExpanded = false
                                },
                            )
                        }
                    }
                }
                if (filteredTasks.isNotEmpty()) {
                    Text(
                        strings.locTaskOptional,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    ExposedDropdownMenuBox(
                        expanded = taskExpanded,
                        onExpandedChange = { taskExpanded = it },
                    ) {
                        OutlinedTextField(
                            value =
                                filteredTasks.find { it.id == selectedTaskId }?.title
                                    ?: strings.locNone,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(taskExpanded) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = taskExpanded,
                            onDismissRequest = { taskExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(strings.locNone) },
                                onClick = {
                                    selectedTaskId = null
                                    taskExpanded = false
                                },
                            )
                            filteredTasks.forEach { task ->
                                val taskType =
                                    if (task.recurrenceEnabled) {
                                        strings.locHabit
                                    } else {
                                        strings.settingsDatabaseTasks
                                    }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            strings.locTaggedTitle.format(
                                                taskType,
                                                task.title,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        selectedTaskId = task.id
                                        taskExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(selectedDimension, selectedTaskId) }) {
                Text(strings.locStart)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}

/**
 * Whether a task belongs to the dimension the user has selected, for the task picker's filter.
 *
 * Deliberately permissive, because the three id fields in play disagree by design: a task may carry a
 * concrete id, a legacy alias, or only a category name. So a match is any one of: the task's id equals
 * the selection's id outright; the two resolve to the same canonical dimension, which is what makes a
 * task stored under a legacy alias still appear under its current dimension; or the task's stored
 * category name equals the selection's label, which is the only signal left for a task with no id at
 * all. A task with no id and no category name matches nothing.
 *
 * Canonical comparison is skipped rather than treated as a match when the selection does not resolve,
 * so an unrecognised selection falls back to the exact-id and name checks instead of silently
 * matching every task.
 */
fun taskMatchesDimension(
    task: Task,
    selectedDimension: DimensionOption,
): Boolean {
    val selectedCanonicalId =
        DimensionTaxonomyCatalog.fromCanonicalId(selectedDimension.id)?.id
            ?: selectedDimension.canonicalId
    val taskCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(task.dimensionId)?.id
    return task.dimensionId == selectedDimension.id ||
        (!selectedCanonicalId.isNullOrBlank() && taskCanonicalId == selectedCanonicalId) ||
        task.lifeIntentionCategory == selectedDimension.label
}
