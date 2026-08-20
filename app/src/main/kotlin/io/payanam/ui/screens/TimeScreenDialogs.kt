//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.ui.components.DimensionDropdownBadge
import io.payanam.ui.components.DimensionDropdownBadgeLabelRow
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.viewmodel.DimensionOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartTrackingDialog(
    tasks: List<Task>,
    dimensionOptions: List<DimensionOption>,
    onStart: (DimensionOption, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    /** Fallback dimension definition. */
    val fallbackDimensionDefinition = DimensionTaxonomyCatalog.WORK_LIVELIHOOD
    /** Default dimension. */
    val defaultDimension = dimensionOptions.firstOrNull()
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
    /** Selected label. */
    val selectedLabel = selectedDimension.label
    /** Filtered tasks. */
    val filteredTasks = tasks.filter { taskMatchesDimension(it, selectedDimension) }

    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_start_tracking)) },
        text = {
            /** Column. */
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                /** Text. */
                Text(
                    androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_life_dimension),
                    style = MaterialTheme.typography.labelMedium,
                )
                /** Exposed dropdown menu box. */
                ExposedDropdownMenuBox(
                    expanded = dimensionExpanded,
                    onExpandedChange = { dimensionExpanded = it },
                ) {
                    /** Outlined text field. */
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        leadingIcon = {
                            /** Dimension dropdown badge. */
                            DimensionDropdownBadge(
                                label = selectedDimension.label,
                                color = selectedDimension.color,
                                iconOption = DimensionIconCatalog.resolve(selectedDimension.iconKey, selectedDimension.id),
                                size = 22.dp,
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dimensionExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    /** Exposed dropdown menu. */
                    ExposedDropdownMenu(
                        expanded = dimensionExpanded,
                        onDismissRequest = { dimensionExpanded = false },
                    ) {
                        dimensionOptions.forEach { dim ->
                            /** Dropdown menu item. */
                            DropdownMenuItem(
                                text = {
                                    /** Dimension dropdown badge label row. */
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

                /** If. */
                if (filteredTasks.isNotEmpty()) {
                    /** Text. */
                    Text(
                        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_optional),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    /** Exposed dropdown menu box. */
                    ExposedDropdownMenuBox(
                        expanded = taskExpanded,
                        onExpandedChange = { taskExpanded = it },
                    ) {
                        /** Outlined text field. */
                        OutlinedTextField(
                            value = filteredTasks.find { it.id == selectedTaskId }?.title
                                ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_none),
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(taskExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        /** Exposed dropdown menu. */
                        ExposedDropdownMenu(
                            expanded = taskExpanded,
                            onDismissRequest = { taskExpanded = false },
                        ) {
                            /** Dropdown menu item. */
                            DropdownMenuItem(
                                text = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_none)) },
                                onClick = {
                                    selectedTaskId = null
                                    taskExpanded = false
                                },
                            )
                            filteredTasks.forEach { task ->
                                /** Task type. */
                                val taskType = if (task.recurrenceEnabled) {
                                    androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_habit)
                                } else {
                                    androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_database_tasks)
                                }
                                /** Dropdown menu item. */
                                DropdownMenuItem(
                                    text = {
                                        /** Text. */
                                        Text(
                                            androidx.compose.ui.res.stringResource(
                                                id = io.payanam.R.string.loc_tagged_title,
                                                /** Task type. */
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
            /** Text button. */
            TextButton(onClick = { onStart(selectedDimension, selectedTaskId) }) {
                /** Text. */
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_start))
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

internal fun taskMatchesDimension(task: Task, selectedDimension: DimensionOption): Boolean {
    /** Selected canonical id. */
    val selectedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(selectedDimension.id)?.id
        ?: selectedDimension.canonicalId
    /** Task canonical id. */
    val taskCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(task.dimensionId)?.id
    return task.dimensionId == selectedDimension.id ||
        (!selectedCanonicalId.isNullOrBlank() && taskCanonicalId == selectedCanonicalId) ||
        task.lifeIntentionCategory == selectedDimension.label
}
