//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.payanam.core.ui.components.DimensionDropdownBadge
import io.payanam.core.ui.components.DimensionDropdownBadgeLabelRow
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.model.DimensionIconCatalog
import io.payanam.core.ui.preferences.DimensionOption
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.shared.logging.AppLogger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTimeEntryDialog(
    tasks: List<io.payanam.domain.model.Task>,
    dimensionOptions: List<DimensionOption>,
    initialDate: LocalDate,
    use24Hour: Boolean,
    onAdd: (DimensionOption, String?, LocalDate, LocalTime, LocalDate, LocalTime, Double?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    val fallbackDimensionDefinition = DimensionTaxonomyCatalog.WORK_LIVELIHOOD
    val defaultDimension =
        dimensionOptions.firstOrNull() ?: DimensionOption(
            id = fallbackDimensionDefinition.id,
            canonicalId = fallbackDimensionDefinition.id,
            label = fallbackDimensionDefinition.fallbackLabel,
            color = MaterialTheme.colorScheme.primary,
            isVisible = true,
            iconKey = fallbackDimensionDefinition.defaultIconKey,
        )
    var selectedDimension by remember { mutableStateOf(defaultDimension) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var startDate by remember(initialDate) { mutableStateOf(initialDate) }
    var startTime by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var endDate by remember(initialDate) { mutableStateOf(initialDate) }
    var endTime by remember { mutableStateOf(LocalTime.of(10, 0)) }
    var focusRating by remember { mutableStateOf(0f) }
    var focusNote by remember { mutableStateOf("") }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var dimensionExpanded by remember { mutableStateOf(false) }
    var taskExpanded by remember { mutableStateOf(false) }
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val timeFormatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a")
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    val selectedLabel = selectedDimension.label
    val canAdd = LocalDateTime.of(startDate, startTime) < LocalDateTime.of(endDate, endTime)
    val filteredTasks = tasks.filter { taskMatchesDimension(it, selectedDimension) }
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.locAddTimeEntry) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(strings.locLifeDimension, style = MaterialTheme.typography.labelMedium)
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
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .testTag("time_entry_dimension_field"),
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
                    Text(strings.locTaskOptional, style = MaterialTheme.typography.labelMedium)
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
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
                Text(strings.locStart, style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.locStartDate, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { showStartDatePicker = true }) {
                            Text(startDate.format(dateFormatter))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.locStartTime, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { showStartTimePicker = true }) {
                            Text(startTime.format(timeFormatter))
                        }
                    }
                }
                Text(strings.locEnd, style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.locEndDate, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { showEndDatePicker = true }) {
                            Text(endDate.format(dateFormatter))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.locEndTime, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { showEndTimePicker = true }) {
                            Text(endTime.format(timeFormatter))
                        }
                    }
                }
                Text(
                    text = strings.locFocusRating01,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = strings.locFocusRatingValue.format(String.format(Locale.US, "%.2f", focusRating.toDouble())),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = focusRating,
                    onValueChange = { focusRating = it },
                    valueRange = 0f..1f,
                )
                OutlinedTextField(
                    value = focusNote,
                    onValueChange = { focusNote = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.locFocusNoteOptional) },
                    placeholder = { Text(strings.locAddANote) },
                    minLines = 2,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    logger.i(
                        "AddTimeEntryDialog",
                        "Adding manual time entry",
                        mapOf(
                            "dimensionId" to selectedDimension.id,
                            "taskId" to (selectedTaskId ?: "none"),
                            "startDateTime" to LocalDateTime.of(startDate, startTime).toString(),
                            "endDateTime" to LocalDateTime.of(endDate, endTime).toString(),
                            "focusRating" to focusRating.toString(),
                            "hasFocusNote" to focusNote.isNotBlank().toString(),
                        ),
                    )
                    onAdd(
                        selectedDimension,
                        selectedTaskId,
                        startDate,
                        startTime,
                        endDate,
                        endTime,
                        focusRating.toDouble(),
                        focusNote.takeIf { it.isNotBlank() },
                    )
                },
                enabled = canAdd,
            ) {
                Text(strings.locAdd)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
    if (showStartDatePicker) {
        DatePickerAlertDialog(
            initialDate = startDate,
            onConfirm = {
                startDate = it
                if (endDate < it) {
                    endDate = it
                }
                showStartDatePicker = false
            },
            onDismiss = { showStartDatePicker = false },
        )
    }
    if (showEndDatePicker) {
        DatePickerAlertDialog(
            initialDate = endDate,
            onConfirm = {
                endDate = it
                showEndDatePicker = false
            },
            onDismiss = { showEndDatePicker = false },
        )
    }
    if (showStartTimePicker) {
        TimePickerAlertDialog(
            initialTime = startTime,
            onConfirm = {
                startTime = it
                if (endDate == startDate && endTime < it) {
                    endDate = startDate.plusDays(1)
                }
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false },
        )
    }
    if (showEndTimePicker) {
        TimePickerAlertDialog(
            initialTime = endTime,
            onConfirm = {
                endTime = it
                if (endDate == startDate && it < startTime) {
                    endDate = startDate.plusDays(1)
                }
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerAlertDialog(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    val initialMillis =
        remember(initialDate) {
            initialDate
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
        )
    PayanamAlertDialog(
        modifier = Modifier.fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text(strings.locSelectDate) },
        text = {
            DatePicker(
                state = datePickerState,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val selected =
                        datePickerState.selectedDateMillis?.let { millis ->
                            Instant
                                .ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        } ?: initialDate
                    onConfirm(selected)
                },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerAlertDialog(
    initialTime: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    val timePickerState =
        rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
        )
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.locSelectTime) },
        text = {
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(LocalTime.of(timePickerState.hour, timePickerState.minute))
                },
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
