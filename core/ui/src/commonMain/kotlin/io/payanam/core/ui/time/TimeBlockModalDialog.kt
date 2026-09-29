//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.payanam.core.ui.model.DimensionIconCatalog
import io.payanam.core.ui.preferences.DimensionOption
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.core.ui.components.DimensionBadgeLabelRow
import io.payanam.core.ui.components.DimensionDropdownBadge
import io.payanam.core.ui.components.DimensionDropdownBadgeLabelRow
import io.payanam.core.ui.components.TagEditorField
import io.payanam.core.ui.components.parseTagsInput
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The modal is opened on a TASK rather than on a time entry, so the time fields describe the work
 * done rather than an entry being edited.
 *
 * Non-null is the whole point: for every other target - a new entry, a gap, an existing entry -
 * the modal carries no task and takes no task action, whereas its presence here both marks the modal
 * as a task block and unlocks the task action row (start, complete, skip, miss, edit, archive).
 * [task] is the task being logged against, read for its title and its recurrence, which is what
 * decides whether the recurring-only actions appear at all.
 *
 * [isCompletedBlock] encodes the state transition this block represents: true once the work behind
 * this block is already done, and the modal then drops Start, Complete, Skip and Miss, because a
 * block that is already complete offers only Edit and Archive. It is passed in rather than derived
 * from [task] because for a recurring task the answer is the OCCURRENCE, not the task: the same task
 * is open every day and completed only on the days it was done, so a completed block is a completed
 * occurrence of a task whose own status may still be open.
 */
data class TaskBlockActionState(
    val task: Task,
    val isCompletedBlock: Boolean,
)

private enum class TimeBlockModalSection { CONTEXT, TIME, FOCUS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeBlockModalDialog(
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
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
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
        isGapCreate || isActiveEntry || taskActionState != null -> strings.locEnd
        isExistingEntry -> strings.locSave
        else -> strings.locAdd
    }
    /**
     * Validates and saves the entry form, defaulting an active block's end to
     * now before invoking the submit callback.
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
                        text = strings.locDurationMinutesPlain.format(liveDurationMinutes),
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
                        title = strings.locLifeDimension,
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
                            text = strings.locLifeDimension,
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
                            text = strings.locTaskOptional,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        ExposedDropdownMenuBox(
                            expanded = taskExpanded,
                            onExpandedChange = { taskExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = filteredTasks.find { it.id == selectedTaskId }?.title
                                    ?: strings.locNone,
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
                                    text = { Text(strings.locNone) },
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
                        title = strings.locTime,
                        summary = timeSummary,
                        expanded = expandedSection == TimeBlockModalSection.TIME,
                        onExpand = { expandedSection = TimeBlockModalSection.TIME },
                    ) {
                        Text(
                            text = strings.locStart,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.locStartDate,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = { showStartDatePicker = true }) {
                                    Text(startDate.format(dateFormatter))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.locStartTime,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = { showStartTimePicker = true }) {
                                    Text(startTime.format(timeFormatter))
                                }
                            }
                        }
                        Text(
                            text = strings.locEnd,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.locEndDate,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = { showEndDatePicker = true }) {
                                    Text(endDate?.format(dateFormatter) ?: strings.locNow)
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.locEndTime,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                TextButton(onClick = { showEndTimePicker = true }) {
                                    Text(endTime?.format(timeFormatter) ?: strings.locNow)
                                }
                            }
                        }
                    }
                    TimeBlockModalSectionCard(
                        title = strings.locFocus,
                        summary = focusSummary,
                        expanded = expandedSection == TimeBlockModalSection.FOCUS,
                        onExpand = { expandedSection = TimeBlockModalSection.FOCUS },
                    ) {
                        Text(
                            text = strings.locFocusRating01,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = strings.locFocusRatingValue.format(String.format(Locale.US, "%.2f", focusRating.toDouble()),),
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
                                        Text(strings.locStart)
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
                                        Text(strings.locDone2)
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
                                        Text(strings.taskNotificationActionSkip)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onMissTask?.invoke(focusNote.takeIf { note -> note.isNotBlank() }, taskTags)
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(strings.locMiss)
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
                                    Text(strings.locEdit)
                                }
                                if (isRecurringTaskBlock) {
                                    OutlinedButton(
                                        onClick = { onArchiveTask?.invoke(taskTags) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(strings.locArchive)
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
                            Text(strings.locDelete)
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
                            Text(strings.settingsActionCancel)
                        }
                        continueAction?.let { onContinue ->
                            OutlinedButton(
                                onClick = onContinue,
                                modifier = Modifier.weight(1f),
                                enabled = canSave,
                            ) {
                                Text(strings.locContinue)
                            }
                        }
                        Button(
                            modifier = Modifier
                                .testTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG)
                                .weight(1f),
                            enabled = canSave,
                            onClick = { submitTimeEntry() },
                        ) {
                            Text(primaryActionLabel)
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

/**
 * Decides whether the modal offers a "carry on from here" button, and what that button does.
 *
 * Returned as a nullable lambda rather than a boolean so the answer and the effect cannot drift
 * apart: null means the button is hidden, and a non-null lambda is the exact action the button runs.
 * The dialog binds the button's visibility to it, so a caller that only wants to offer the action
 * passes the callback and leaves the decision to this function.
 *
 * Two different actions, because the two situations are genuinely different. Filling a detected
 * GAP starts tracking at the chosen start through [onSetAndContinue], which needs the modal's
 * currently selected dimension, task, date and time forwarded in, because "the user chose this
 * start point" is the whole content of that tap and the modal is where the choice was made. Editing
 * an existing, already-ended entry simply resumes the NEXT one after it through [onContinueEntry],
 * which takes no context: the entry to continue is already known.
 *
 * The gap case wins when both would apply, and gap mode is offered only when [onSetAndContinue] is
 * non-null - a null callback means that action is unavailable, and the gap's own continue is then
 * not silently substituted for it.
 *
 * **An ACTIVE entry never gets a continue button.** An entry still running has no successor to
 * continue into, and offering the action there is what produced a button that did nothing when
 * pressed. That is also why [onContinueEntry] is checked for null before [isActiveEntry]: an
 * unavailable action and an inapplicable one are both hidden, and neither falls through to a
 * visible no-op.
 *
 * @return the action to run, or null when the modal should hide the continue button.
 */
fun resolveContinueAction(
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
    title: String,
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
                        Text(title, style = MaterialTheme.typography.titleSmall)
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
