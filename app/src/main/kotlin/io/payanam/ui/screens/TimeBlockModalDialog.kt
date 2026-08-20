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
    /** Task. */
    val task: Task,
    /** Is completed block. */
    val isCompletedBlock: Boolean,
)

private enum class TimeBlockModalSection { CONTEXT, TIME, FOCUS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeBlockModalDialog(
    /** Title. */
    title: String,
    tasks: List<Task>,
    dimensionOptions: List<DimensionOption>,
    /** Initial dimension. */
    initialDimension: DimensionOption,
    initialTaskId: String?,
    /** Initial start date. */
    initialStartDate: LocalDate,
    /** Initial start time. */
    initialStartTime: LocalTime,
    initialEndDate: LocalDate?,
    initialEndTime: LocalTime?,
    initialFocusRating: Double?,
    initialFocusNote: String?,
    initialTags: List<String>,
    tagSuggestions: List<String>,
    /** Use24hour. */
    use24Hour: Boolean,
    /** Is existing entry. */
    isExistingEntry: Boolean,
    /** Is active entry. */
    isActiveEntry: Boolean,
    /** Is gap create. */
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
    /** Logger. */
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
    /** Scroll state. */
    val scrollState = rememberScrollState()
    /** Default section. */
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

    /** Selected label. */
    val selectedLabel = selectedDimension.label
    /** Selected task title. */
    val selectedTaskTitle = tasks.firstOrNull { it.id == selectedTaskId }?.title
    /** Time formatter. */
    val timeFormatter = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a")
    /** Date formatter. */
    val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    /** Live end date. */
    val liveEndDate = endDate ?: LocalDate.now()
    /** Live end time. */
    val liveEndTime = endTime ?: LocalTime.now()
    /** Live duration minutes. */
    val liveDurationMinutes = Duration.between(
        LocalDateTime.of(startDate, startTime),
        LocalDateTime.of(liveEndDate, liveEndTime),
    ).toMinutes().coerceAtLeast(0).toInt()
    /** Can save. */
    val canSave = canSaveEditedTimeEntry(startDate, startTime, endDate, endTime)
    /** Selected canonical dimension id. */
    val selectedCanonicalDimensionId = selectedDimension.canonicalId
    /** Filtered tasks. */
    val filteredTasks = tasks.filter {
        /** Task matches dimension. */
        taskMatchesDimension(it, selectedDimension) ||
            (
                !selectedCanonicalDimensionId.isNullOrBlank() &&
                    DimensionTaxonomyCatalog.fromCanonicalId(it.dimensionId)?.id == selectedCanonicalDimensionId
                )
    }
    /** Task tags. */
    val taskTags = parseTagsInput(tagsRaw)
    /** Is recurring task block. */
    val isRecurringTaskBlock = taskActionState?.task?.recurrenceEnabled == true
    /** Is completed task block. */
    val isCompletedTaskBlock = taskActionState?.isCompletedBlock == true
    /** Delete action. */
    val deleteAction = when {
        onDeleteTask != null -> ({ onDeleteTask.invoke(taskTags) })
        onDeleteEntry != null -> onDeleteEntry
        else -> null
    }
    /** Primary action label. */
    val primaryActionLabel = when {
        isGapCreate || isActiveEntry || taskActionState != null -> io.payanam.R.string.loc_end
        isExistingEntry -> io.payanam.R.string.loc_save
        else -> io.payanam.R.string.loc_add
    }

    /**
     * Submit time entry.
     */
    fun submitTimeEntry() {
        /** Normalized focus note. */
        val normalizedFocusNote = focusNote.takeIf { it.isNotBlank() }
        /** Resolved end date. */
        val resolvedEndDate = if (isActiveEntry && endDate == null && endTime == null) {
            LocalDate.now()
        } else {
            /** End date. */
            endDate
        }
        /** Resolved end time. */
        val resolvedEndTime = if (isActiveEntry && endDate == null && endTime == null) {
            LocalTime.now()
        } else {
            /** End time. */
            endTime
        }
        /** On confirm time entry. */
        onConfirmTimeEntry(
            /** Selected dimension. */
            selectedDimension,
            /** Selected task id. */
            selectedTaskId,
            /** Start date. */
            startDate,
            /** Start time. */
            startTime,
            /** Resolved end date. */
            resolvedEndDate,
            /** Resolved end time. */
            resolvedEndTime,
            focusRating.toDouble(),
            /** Normalized focus note. */
            normalizedFocusNote,
            /** Task tags. */
            taskTags,
        )
    }

    /** Continue action. */
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
    /** Launched effect. */
    LaunchedEffect(isGapCreate, isActiveEntry, continueAction) {
        logger.d(
            "TimeBlockModalDialog.continueAction",
            "Continue button visibility resolved",
            /** Map of. */
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

    /** Context summary. */
    val contextSummary = selectedTaskTitle?.let { "$selectedLabel - $it" } ?: selectedLabel
    /** Time summary. */
    val timeSummary = "${startTime.format(timeFormatter)} - ${liveEndTime.format(timeFormatter)}"
    /** Focus summary. */
    val focusSummary = String.format(Locale.US, "%.2f", focusRating.toDouble())

    /** Dialog. */
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        /** Surface. */
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            /** Column. */
            Column(modifier = Modifier.fillMaxSize()) {
                /** Column. */
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    /** Text. */
                    Text(text = title, style = MaterialTheme.typography.titleLarge)
                    /** Text. */
                    Text(
                        text = stringResource(id = io.payanam.R.string.loc_duration_minutes_plain, liveDurationMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                /** Horizontal divider. */
                HorizontalDivider()

                /** Column. */
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    /** Time block modal section card. */
                    TimeBlockModalSectionCard(
                        titleRes = io.payanam.R.string.loc_life_dimension,
                        summary = contextSummary,
                        summaryContent = {
                            /** Dimension badge label row. */
                            DimensionBadgeLabelRow(
                                label = selectedLabel,
                                color = selectedDimension.color,
                                iconOption = DimensionIconCatalog.resolve(selectedDimension.iconKey, selectedDimension.id),
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                badgeSize = 24.dp,
                            )
                            selectedTaskTitle?.let { taskTitle ->
                                /** Text. */
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
                        /** Text. */
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_life_dimension),
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
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            /** Dropdown menu. */
                            DropdownMenu(
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
                                            /** If. */
                                            if (taskActionState == null) {
                                                selectedTaskId = null
                                            }
                                            dimensionExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        /** Text. */
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_task_optional),
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
                                    ?: stringResource(id = io.payanam.R.string.loc_none),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(taskExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            /** Dropdown menu. */
                            DropdownMenu(
                                expanded = taskExpanded,
                                onDismissRequest = { taskExpanded = false },
                            ) {
                                /** Dropdown menu item. */
                                DropdownMenuItem(
                                    text = { Text(stringResource(id = io.payanam.R.string.loc_none)) },
                                    onClick = {
                                        selectedTaskId = null
                                        taskExpanded = false
                                    },
                                )
                                filteredTasks.forEach { task ->
                                    /** Dropdown menu item. */
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

                    /** Time block modal section card. */
                    TimeBlockModalSectionCard(
                        titleRes = io.payanam.R.string.loc_time,
                        summary = timeSummary,
                        expanded = expandedSection == TimeBlockModalSection.TIME,
                        onExpand = { expandedSection = TimeBlockModalSection.TIME },
                    ) {
                        /** Text. */
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_start),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        /** Row. */
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            /** Column. */
                            Column(modifier = Modifier.weight(1f)) {
                                /** Text. */
                                Text(
                                    text = stringResource(id = io.payanam.R.string.loc_start_date),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                /** Text button. */
                                TextButton(onClick = { showStartDatePicker = true }) {
                                    /** Text. */
                                    Text(startDate.format(dateFormatter))
                                }
                            }
                            /** Column. */
                            Column(modifier = Modifier.weight(1f)) {
                                /** Text. */
                                Text(
                                    text = stringResource(id = io.payanam.R.string.loc_start_time),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                /** Text button. */
                                TextButton(onClick = { showStartTimePicker = true }) {
                                    /** Text. */
                                    Text(startTime.format(timeFormatter))
                                }
                            }
                        }

                        /** Text. */
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_end),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        /** Row. */
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            /** Column. */
                            Column(modifier = Modifier.weight(1f)) {
                                /** Text. */
                                Text(
                                    text = stringResource(id = io.payanam.R.string.loc_end_date),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                /** Text button. */
                                TextButton(onClick = { showEndDatePicker = true }) {
                                    /** Text. */
                                    Text(endDate?.format(dateFormatter) ?: stringResource(id = io.payanam.R.string.loc_now))
                                }
                            }
                            /** Column. */
                            Column(modifier = Modifier.weight(1f)) {
                                /** Text. */
                                Text(
                                    text = stringResource(id = io.payanam.R.string.loc_end_time),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                /** Text button. */
                                TextButton(onClick = { showEndTimePicker = true }) {
                                    /** Text. */
                                    Text(endTime?.format(timeFormatter) ?: stringResource(id = io.payanam.R.string.loc_now))
                                }
                            }
                        }
                    }

                    /** Time block modal section card. */
                    TimeBlockModalSectionCard(
                        titleRes = io.payanam.R.string.loc_focus,
                        summary = focusSummary,
                        expanded = expandedSection == TimeBlockModalSection.FOCUS,
                        onExpand = { expandedSection = TimeBlockModalSection.FOCUS },
                    ) {
                        /** Text. */
                        Text(
                            text = stringResource(id = io.payanam.R.string.loc_focus_rating_0_1),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        /** Text. */
                        Text(
                            text = stringResource(
                                id = io.payanam.R.string.loc_focus_rating_value,
                                String.format(Locale.US, "%.2f", focusRating.toDouble()),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        /** Slider. */
                        Slider(
                            value = focusRating,
                            onValueChange = { focusRating = it },
                            valueRange = 0f..1f,
                        )
                        /** Outlined text field. */
                        OutlinedTextField(
                            value = focusNote,
                            onValueChange = { focusNote = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(id = io.payanam.R.string.loc_focus_note_optional)) },
                            placeholder = { Text(stringResource(id = io.payanam.R.string.loc_add_a_note)) },
                            minLines = 2,
                            maxLines = 3,
                        )
                        /** Tag editor field. */
                        TagEditorField(
                            rawValue = tagsRaw,
                            onValueChange = { tagsRaw = it },
                            suggestions = tagSuggestions,
                        )

                        taskActionState?.let {
                            /** Row. */
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                /** If. */
                                if (!isCompletedTaskBlock) {
                                    /** Outlined button. */
                                    OutlinedButton(
                                        onClick = {
                                            logger.i("TimeBlockModalDialog", "Task start from modal", mapOf("taskId" to it.task.id))
                                            onStartTaskTracking?.invoke()
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        /** Icon. */
                                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                                        /** Spacer. */
                                        Spacer(Modifier.width(4.dp))
                                        /** Text. */
                                        Text(stringResource(id = io.payanam.R.string.loc_start))
                                    }
                                }
                                /** If. */
                                if (isRecurringTaskBlock && !isCompletedTaskBlock) {
                                    /** Outlined button. */
                                    OutlinedButton(
                                        onClick = {
                                            /** Completion date time. */
                                            val completionDateTime = endDate?.let { selectedEndDate ->
                                                endTime?.let { selectedEndTime ->
                                                    LocalDateTime.of(selectedEndDate, selectedEndTime)
                                                }
                                            }
                                            onCompleteTask?.invoke(
                                                focusNote.takeIf { note -> note.isNotBlank() },
                                                /** Completion date time. */
                                                completionDateTime,
                                                /** Live duration minutes. */
                                                liveDurationMinutes,
                                                /** Task tags. */
                                                taskTags,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        /** Text. */
                                        Text(stringResource(id = io.payanam.R.string.loc_done_2))
                                    }
                                }
                            }
                            /** Row. */
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                /** If. */
                                if (isRecurringTaskBlock && !isCompletedTaskBlock) {
                                    /** Outlined button. */
                                    OutlinedButton(
                                        onClick = {
                                            onSkipTask?.invoke(focusNote.takeIf { note -> note.isNotBlank() }, taskTags)
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        /** Text. */
                                        Text(stringResource(id = io.payanam.R.string.task_notification_action_skip))
                                    }
                                    /** Outlined button. */
                                    OutlinedButton(
                                        onClick = {
                                            onMissTask?.invoke(focusNote.takeIf { note -> note.isNotBlank() }, taskTags)
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        /** Text. */
                                        Text(stringResource(id = io.payanam.R.string.loc_miss))
                                    }
                                }
                            }
                            /** Row. */
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                /** Outlined button. */
                                OutlinedButton(
                                    onClick = { onEditTask?.invoke() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    /** Icon. */
                                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                    /** Spacer. */
                                    Spacer(Modifier.width(4.dp))
                                    /** Text. */
                                    Text(stringResource(id = io.payanam.R.string.loc_edit))
                                }
                                /** If. */
                                if (isRecurringTaskBlock) {
                                    /** Outlined button. */
                                    OutlinedButton(
                                        onClick = { onArchiveTask?.invoke(taskTags) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        /** Text. */
                                        Text(stringResource(id = io.payanam.R.string.loc_archive))
                                    }
                                }
                            }
                        }
                    }

                    /** Spacer. */
                    Spacer(modifier = Modifier.height(120.dp))
                }

                /** Horizontal divider. */
                HorizontalDivider()
                /** Column. */
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    deleteAction?.let { onDelete ->
                        /** Outlined button. */
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            /** Icon. */
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                            /** Spacer. */
                            Spacer(Modifier.width(4.dp))
                            /** Text. */
                            Text(stringResource(id = io.payanam.R.string.loc_delete))
                        }
                    }
                    /** Row. */
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        /** Outlined button. */
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            /** Text. */
                            Text(stringResource(id = io.payanam.R.string.settings_action_cancel))
                        }
                        continueAction?.let { onContinue ->
                            /** Outlined button. */
                            OutlinedButton(
                                onClick = onContinue,
                                modifier = Modifier.weight(1f),
                                enabled = canSave,
                            ) {
                                /** Text. */
                                Text(stringResource(id = io.payanam.R.string.loc_continue))
                            }
                        }
                        /** Button. */
                        Button(
                            modifier = Modifier
                                .testTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG)
                                .weight(1f),
                            enabled = canSave,
                            onClick = { submitTimeEntry() },
                        ) {
                            /** Text. */
                            Text(stringResource(id = primaryActionLabel))
                        }
                    }
                }
            }
        }
    }

    /** If. */
    if (showStartDatePicker) {
        /** Date picker alert dialog. */
        DatePickerAlertDialog(
            initialDate = startDate,
            onConfirm = {
                startDate = it
                /** If. */
                if (endDate == null && endTime != null) {
                    endDate = it
                }
                showStartDatePicker = false
            },
            onDismiss = { showStartDatePicker = false },
        )
    }
    /** If. */
    if (showEndDatePicker) {
        /** Date picker alert dialog. */
        DatePickerAlertDialog(
            initialDate = endDate ?: startDate,
            onConfirm = {
                endDate = it
                /** If. */
                if (endTime == null) {
                    showEndTimePicker = true
                }
                showEndDatePicker = false
            },
            onDismiss = { showEndDatePicker = false },
        )
    }
    /** If. */
    if (showStartTimePicker) {
        /** Time picker alert dialog. */
        TimePickerAlertDialog(
            initialTime = startTime,
            onConfirm = {
                startTime = it
                /** If. */
                if (endTime != null && endDate == startDate && endTime!! < it) {
                    endDate = startDate.plusDays(1)
                }
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false },
        )
    }
    /** If. */
    if (showEndTimePicker) {
        /** Time picker alert dialog. */
        TimePickerAlertDialog(
            initialTime = endTime ?: LocalTime.now(),
            onConfirm = {
                endTime = it
                /** If. */
                if (endDate == null) {
                    endDate = startDate
                }
                /** If. */
                if (endDate == startDate && it < startTime) {
                    endDate = startDate.plusDays(1)
                }
                showEndTimePicker = false
            },
            onDismiss = {
                /** If. */
                if (!isExistingEntry && endTime == null) {
                    endDate = null
                }
                showEndTimePicker = false
            },
        )
    }
}

internal fun resolveContinueAction(
    /** Is gap create. */
    isGapCreate: Boolean,
    /** Is active entry. */
    isActiveEntry: Boolean,
    onSetAndContinue: ((DimensionOption, String?, LocalDate, LocalTime) -> Unit)?,
    onContinueEntry: (() -> Unit)?,
    /** Selected dimension. */
    selectedDimension: DimensionOption,
    selectedTaskId: String?,
    /** Start date. */
    startDate: LocalDate,
    /** Start time. */
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
    /** Title res. */
    titleRes: Int,
    /** Summary. */
    summary: String,
    summaryContent: @Composable ColumnScope.() -> Unit = {
        /** Text. */
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    },
    /** Expanded. */
    expanded: Boolean,
    onExpand: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    /** Surface. */
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (expanded) 2.dp else 0.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        /** Column. */
        Column(modifier = Modifier.fillMaxWidth()) {
            /** Outlined button. */
            OutlinedButton(
                onClick = onExpand,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                /** Row. */
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    /** Column. */
                    Column(modifier = Modifier.weight(1f)) {
                        /** Text. */
                        Text(stringResource(id = titleRes), style = MaterialTheme.typography.titleSmall)
                        /** Summary content. */
                        summaryContent()
                    }
                    /** Text. */
                    Text(if (expanded) "-" else "+")
                }
            }
            /** If. */
            if (expanded) {
                /** Horizontal divider. */
                HorizontalDivider()
                /** Column. */
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    /** Content. */
                    content()
                }
            }
        }
    }
}
