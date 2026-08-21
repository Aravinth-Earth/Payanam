//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.ui.components.DimensionBadgeLabelRow
import io.payanam.ui.components.DimensionDropdownBadge
import io.payanam.ui.components.DimensionDropdownBadgeLabelRow
import io.payanam.ui.components.TagEditorField
import io.payanam.ui.components.parseTagsInput
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.viewmodel.DimensionOption
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class TaskBlockActionState(
    val task: Task,
    val isCompletedBlock: Boolean,
)

private enum class TimeBlockModalSection { CONTEXT, TIME, FOCUS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeBlockModalDialog(
    title: String,
    tasks: List<Task>,
    dimensionOptions: List<DimensionOption>,
    initialDimension: DimensionOption,
    initialTaskId: String?,
    initialStartDate: LocalDate,
    initialStartTime: LocalTime,
    initialEndDate: LocalDate?,
    initialEndTime: LocalTime?,
    initialFocusRating: Double?,
    initialFocusNote: String?,
    initialTags: List<String>,
    tagSuggestions: List<String>,
    use24Hour: Boolean,
    isExistingEntry: Boolean,
    isActiveEntry: Boolean,
    isGapCreate: Boolean,
    taskActionState: TaskBlockActionState?,
    onConfirmTimeEntry: (DimensionOption, String?, LocalDate, LocalTime, LocalDate?, LocalTime?, Double?, String?, List<String>) -> Unit,
    onDeleteEntry: (() -> Unit)?,
    onContinueEntry: (() -> Unit)?,
    onSetAndContinue: ((DimensionOption, String?, LocalDate, LocalTime) -> Unit)?,
    onStartTaskTracking: (() -> Unit)?,
    onCompleteTask: ((String?, LocalDateTime?, Int?, List<String>) -> Unit)?,
    onSkipTask: ((String?, List<String>) -> Unit)?,
    onMissTask: ((String?, List<String>) -> Unit)?,
    onArchiveTask: ((List<String>) -> Unit)?,
    onDeleteTask: ((List<String>) -> Unit)?,
    onEditTask: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val logger = remember { UnifiedLogger.getInstance() }
    var selectedDimension by remember { mutableStateOf(initialDimension) }
    var selectedTaskId by remember { mutableStateOf(initialTaskId) }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var startTime by remember { mutableStateOf(initialStartTime) }
    var endDate by remember { mutableStateOf(initialEndDate) }
    var endTime by remember { mutableStateOf(initialEndTime) }
    var focusRating by remember { mutableStateOf((initialFocusRating ?: 0.0).toFloat()) }
    var focusNote by remember { mutableStateOf(initialFocusNote.orEmpty()) }
    var tagsRaw by remember(initialTags) { mutableStateOf(initialTags.joinToString(", ")) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var dimensionExpanded by remember { mutableStateOf(false) }
    var taskExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val defaultSection = remember(isGapCreate, isActiveEntry, taskActionState, isExistingEntry) {
        when {
            isGapCreate -> TimeBlockModalSection.TIME
            isActiveEntry -> TimeBlockModalSection.FOCUS
            taskActionState != null -> TimeBlockModalSection.CONTEXT
            isExistingEntry -> TimeBlockModalSection.TIME
            else -> TimeBlockModalSection.CONTEXT
        }
    }
    var expandedSection by remember(defaultSection) { mutableStateOf(defaultSection) }
    val selectedLabel = selectedDimension.label
    val selectedTaskTitle = tasks.firstOrNull { it.id == selectedTaskId }?.title
    val timeFormatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a")
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    val liveEndDate = endDate ?: LocalDate.now()
    val liveEndTime = endTime ?: LocalTime.now()
    val liveDurationMinutes = Duration.between(
        LocalDateTime.of(startDate, startTime),
        LocalDateTime.of(liveEndDate, liveEndTime),
    ).toMinutes().coerceAtLeast(0).toInt()
    val canSave = canSaveEditedTimeEntry(startDate, startTime, endDate, endTime)
    val selectedCanonicalDimensionId = selectedDimension.canonicalId
    val filteredTasks = tasks.filter {
        taskMatchesDimension(it, selectedDimension) ||
            (
                !selectedCanonicalDimensionId.isNullOrBlank() &&
                    DimensionTaxonomyCatalog.fromCanonicalId(it.dimensionId)?.id == selectedCanonicalDimensionId
                )
    }
    val taskTags = parseTagsInput(tagsRaw)
    val isRecurringTaskBlock = taskActionState?.task?.recurrenceEnabled == true
    val isCompletedTaskBlock = taskActionState?.isCompletedBlock == true
    val deleteAction = when {
        onDeleteTask != null -> ({ onDeleteTask.invoke(taskTags) })
        onDeleteEntry != null -> onDeleteEntry
        else -> null
    }
    val primaryActionLabel = when {
        isGapCreate || isActiveEntry || taskActionState != null -> io.payanam.R.string.loc_end
        isExistingEntry -> io.payanam.R.string.loc_save
        else -> io.payanam.R.string.loc_add
    }
    /**
     * Performs the submit time entry.
     */
    fun submitTimeEntry() {
        val normalizedFocusNote = focusNote.takeIf { it.isNotBlank() }
        val resolvedEndDate = if (isActiveEntry && endDate == null && endTime == null) {
            LocalDate.now()
        } else {
            endDate
        }
        val resolvedEndTime = if (isActiveEntry && endDate == null && endTime == null) {
            LocalTime.now()
        } else {
            endTime
        }
        onConfirmTimeEntry(
            selectedDimension,
            selectedTaskId,
            startDate,
            startTime,
            resolvedEndDate,
            resolvedEndTime,
            focusRating.toDouble(),
            normalizedFocusNote,
            taskTags,
        )
    }
    val continueAction = resolveContinueAction(
        isGapCreate = isGapCreate,
        isActiveEntry = isActiveEntry,
        onSetAndContinue = onSetAndContinue,
        onContinueEntry = onContinueEntry,
        selectedDimension = selectedDimension,
        selectedTaskId = selectedTaskId,
        startDate = startDate,
        startTime = startTime,
    )
    LaunchedEffect(isGapCreate, isActiveEntry, continueAction) {
        logger.d(
            "TimeBlockModalDialog.continueAction",
            "Continue button visibility resolved",
            mapOf(
                "isGapCreate" to isGapCreate,
                "isActiveEntry" to isActiveEntry,
                "hasOnSetAndContinue" to (onSetAndContinue != null),
                "hasOnContinueEntry" to (onContinueEntry != null),
                "isExistingEntry" to isExistingEntry,
                "continueVisible" to (continueAction != null),
            ),
        )
    }
    val contextSummary = selectedTaskTitle?.let { "$selectedLabel - $it" } ?: selectedLabel
    val timeSummary = "${startTime.format(timeFormatter)} - ${liveEndTime.format(timeFormatter)}"
    val focusSummary = String.format(Locale.US, "%.2f", focusRating.toDouble())
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = stringResource(id = io.payanam.R.string.loc_duration_minutes_plain, liveDurationMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TimeBlockModalSectionCard(
                        titleRes = io.payanam.R.string.loc_life_dimension,
                        summary = contextSummary,
                        summaryContent = {
                            DimensionBadgeLabelRow(
                                label = selectedLabel,
                                color = selectedDimension.color,
                                iconOption = DimensionIconCatalog.resolve(selectedDimension.iconKey, selectedDimension.id),
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                badgeSize = 24.dp,
                            )
                            selectedTaskTitle?.let { taskTitle ->
                                Text(
                                    text = taskTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        expanded = expandedSection == TimeBlockModalSection.CONTEXT,
                        onExpand = { expandedSection = TimeBlockModalSection.CONTEXT },
                    ) {
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_life_dimension),
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            DropdownMenu(
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
                                            if (taskActionState == null) {
                                                selectedTaskId = null
                                            }
                                            dimensionExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_task_optional),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        ExposedDropdownMenuBox(
                            expanded = taskExpanded,
                            onExpandedChange = { taskExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = filteredTasks.find { it.id == selectedTaskId }?.title
                                    ?: stringResource(id = io.payanam.R.string.loc_none),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(taskExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            DropdownMenu(
                                expanded = taskExpanded,
                                onDismissRequest = { taskExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(id = io.payanam.R.string.loc_none)) },
                                    onClick = {
                                        selectedTaskId = null
                                        taskExpanded = false
                                    },
                                )
                                filteredTasks.forEach { task ->
                                    DropdownMenuItem(
                                        text = { Text(task.title) },
                                        onClick = {
                                            selectedTaskId = task.id
                                            taskExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    TimeBlockModalSectionCard(
                        titleRes = io.payanam.R.string.loc_time,
                        summary = timeSummary,
                        expanded = expandedSection == TimeBlockModalSection.TIME,
                        onExpand = { expandedSection = TimeBlockModalSection.TIME },
                    ) {
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_start),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = io.payanam.R.string.loc_start_date),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = { showStartDatePicker = true }) {
                                    Text(startDate.format(dateFormatter))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = io.payanam.R.string.loc_start_time),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = { showStartTimePicker = true }) {
                                    Text(startTime.format(timeFormatter))
                                }
                            }
                        }
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_end),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = io.payanam.R.string.loc_end_date),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = { showEndDatePicker = true }) {
                                    Text(endDate?.format(dateFormatter) ?: stringResource(id = io.payanam.R.string.loc_now))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(id = io.payanam.R.string.loc_end_time),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = { showEndTimePicker = true }) {
                                    Text(endTime?.format(timeFormatter) ?: stringResource(id = io.payanam.R.string.loc_now))
                                }
                            }
                        }
                    }
                    TimeBlockModalSectionCard(
                        titleRes = io.payanam.R.string.loc_focus,
                        summary = focusSummary,
                        expanded = expandedSection == TimeBlockModalSection.FOCUS,
                        onExpand = { expandedSection = TimeBlockModalSection.FOCUS },
                    ) {
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_focus_rating_0_1),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = stringResource(
                                id = io.payanam.R.string.loc_focus_rating_value,
                                String.format(Locale.US, "%.2f", focusRating.toDouble()),
                            ),
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
                            label = { Text(stringResource(id = io.payanam.R.string.loc_focus_note_optional)) },
                            placeholder = { Text(stringResource(id = io.payanam.R.string.loc_add_a_note)) },
                            minLines = 2,
                            maxLines = 3,
                        )
                        TagEditorField(
                            rawValue = tagsRaw,
                            onValueChange = { tagsRaw = it },
                            suggestions = tagSuggestions,
                        )

                        taskActionState?.let {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (!isCompletedTaskBlock) {
                                    OutlinedButton(
                                        onClick = {
                                            logger.i("TimeBlockModalDialog", "Task start from modal", mapOf("taskId" to it.task.id))
                                            onStartTaskTracking?.invoke()
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(id = io.payanam.R.string.loc_start))
                                    }
                                }
                                if (isRecurringTaskBlock && !isCompletedTaskBlock) {
                                    OutlinedButton(
                                        onClick = {
                                            val completionDateTime = endDate?.let { selectedEndDate ->
                                                endTime?.let { selectedEndTime ->
                                                    LocalDateTime.of(selectedEndDate, selectedEndTime)
                                                }
                                            }
                                            onCompleteTask?.invoke(
                                                focusNote.takeIf { note -> note.isNotBlank() },
                                                completionDateTime,
                                                liveDurationMinutes,
                                                taskTags,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(id = io.payanam.R.string.loc_done_2))
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (isRecurringTaskBlock && !isCompletedTaskBlock) {
                                    OutlinedButton(
                                        onClick = {
                                            onSkipTask?.invoke(focusNote.takeIf { note -> note.isNotBlank() }, taskTags)
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(id = io.payanam.R.string.task_notification_action_skip))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onMissTask?.invoke(focusNote.takeIf { note -> note.isNotBlank() }, taskTags)
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(id = io.payanam.R.string.loc_miss))
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { onEditTask?.invoke() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(id = io.payanam.R.string.loc_edit))
                                }
                                if (isRecurringTaskBlock) {
                                    OutlinedButton(
                                        onClick = { onArchiveTask?.invoke(taskTags) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(id = io.payanam.R.string.loc_archive))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(120.dp))
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    deleteAction?.let { onDelete ->
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(id = io.payanam.R.string.loc_delete))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(id = io.payanam.R.string.settings_action_cancel))
                        }
                        continueAction?.let { onContinue ->
                            OutlinedButton(
                                onClick = onContinue,
                                modifier = Modifier.weight(1f),
                                enabled = canSave,
                            ) {
                                Text(stringResource(id = io.payanam.R.string.loc_continue))
                            }
                        }
                        Button(
                            modifier = Modifier
                                .testTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG)
                                .weight(1f),
                            enabled = canSave,
                            onClick = { submitTimeEntry() },
                        ) {
                            Text(stringResource(id = primaryActionLabel))
                        }
                    }
                }
            }
        }
    }
    if (showStartDatePicker) {
        DatePickerAlertDialog(
            initialDate = startDate,
            onConfirm = {
                startDate = it
                if (endDate == null && endTime != null) {
                    endDate = it
                }
                showStartDatePicker = false
            },
            onDismiss = { showStartDatePicker = false },
        )
    }
    if (showEndDatePicker) {
        DatePickerAlertDialog(
            initialDate = endDate ?: startDate,
            onConfirm = {
                endDate = it
                if (endTime == null) {
                    showEndTimePicker = true
                }
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
                if (endTime != null && endDate == startDate && endTime!! < it) {
                    endDate = startDate.plusDays(1)
                }
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false },
        )
    }
    if (showEndTimePicker) {
        TimePickerAlertDialog(
            initialTime = endTime ?: LocalTime.now(),
            onConfirm = {
                endTime = it
                if (endDate == null) {
                    endDate = startDate
                }
                if (endDate == startDate && it < startTime) {
                    endDate = startDate.plusDays(1)
                }
                showEndTimePicker = false
            },
            onDismiss = {
                if (!isExistingEntry && endTime == null) {
                    endDate = null
                }
                showEndTimePicker = false
            },
        )
    }
}

internal fun resolveContinueAction(
    isGapCreate: Boolean,
    isActiveEntry: Boolean,
    onSetAndContinue: ((DimensionOption, String?, LocalDate, LocalTime) -> Unit)?,
    onContinueEntry: (() -> Unit)?,
    selectedDimension: DimensionOption,
    selectedTaskId: String?,
    startDate: LocalDate,
    startTime: LocalTime,
): (() -> Unit)? = when {
    isGapCreate && onSetAndContinue != null -> {
        { onSetAndContinue(selectedDimension, selectedTaskId, startDate, startTime) }
    }

    onContinueEntry != null && !isActiveEntry -> onContinueEntry

    else -> null
}

@Composable
private fun TimeBlockModalSectionCard(
    titleRes: Int,
    summary: String,
    summaryContent: @Composable ColumnScope.() -> Unit = {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    },
    expanded: Boolean,
    onExpand: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (expanded) 2.dp else 0.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onExpand,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(id = titleRes), style = MaterialTheme.typography.titleSmall)
                        summaryContent()
                    }
                    Text(if (expanded) "-" else "+")
                }
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
            }
        }
    }
}
