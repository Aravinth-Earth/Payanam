//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.tasks

import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.shared.logging.AppLogger
import io.payanam.core.ui.flags.LocalFeatureFlags
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.TagEditorField
import io.payanam.core.ui.components.parseTagsInput
import io.payanam.core.ui.preferences.DimensionOption
import io.payanam.core.ui.preferences.LocalAppPreferences
import io.payanam.core.ui.preferences.labelForDimensionId
import io.payanam.core.ui.preferences.optionsForSelection
import io.payanam.core.ui.preferences.visibleDimensionOptions
import io.payanam.core.ui.time.DurationMinutesPickerField
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Frequency
import io.payanam.domain.model.Task
import io.payanam.presentation.tasks.EditTaskViewModel
import io.payanam.core.ui.components.EditLifeDimensionDropdown
import io.payanam.core.ui.components.EditScoringSegmentedRow
import io.payanam.presentation.tasks.EditTaskInput
import io.payanam.core.ui.components.EditTimePickerDialog
import io.payanam.core.ui.components.EnhancedRecurrencePickerDialog
import io.payanam.core.ui.components.resolveAlignmentIndex
import io.payanam.core.ui.components.resolveImpactIndex
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
private val logger = AppLogger.current

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTaskScreen(
    taskId: String,
    viewModel: EditTaskViewModel,
    onNavigateBack: () -> Unit = {},
    onTaskSaved: () -> Unit = {},
) {
    val strings = LocalUiStrings.current
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(taskId) {
        viewModel.loadTask(taskId)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.locEditTask) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.locBack,
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.task == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(strings.locTaskNotFound)
                }
            }

            else -> {
                EditTaskContent(
                    task = uiState.task!!,
                    initialTags = uiState.taskTags,
                    tagSuggestions = uiState.tagSuggestions,
                    onSave = { input ->
                        viewModel.updateTask(input)
                        onTaskSaved()
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTaskContent(
    task: Task,
    initialTags: List<String>,
    tagSuggestions: List<String>,
    onSave: (EditTaskInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val prefs = LocalAppPreferences.current
    val fallbackDimension = prefs.visibleDimensionOptions().firstOrNull() ?: DimensionOption(
        id = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
        label = task.lifeIntentionCategory,
        color = MaterialTheme.colorScheme.primary,
        isVisible = true,
        iconKey = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.defaultIconKey,
        canonicalId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
    )
    val defaultDimensionId = task.dimensionId
        ?: fallbackDimension.id
    var title by remember { mutableStateOf(task.title) }
    var description by remember { mutableStateOf(task.description ?: "") }
    var selectedDimensionId by remember { mutableStateOf(defaultDimensionId) }
    val dimensionOptions = prefs.optionsForSelection(selectedDimensionId)
    var selectedDate by remember { mutableStateOf(if (task.recurrenceEnabled) null else task.dueDate?.toLocalDate()) }
    var selectedTime by remember { mutableStateOf(task.dueDate?.toLocalTime()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var notificationMode by remember { mutableStateOf(task.notificationMode ?: "auto") }
    var customNotificationMinutes by remember {
        mutableStateOf(task.customNotificationMinutes?.toString() ?: "15")
    }
    var recurrenceEnabled by remember { mutableStateOf(task.recurrenceEnabled) }
    var showRecurrenceDialog by remember { mutableStateOf(false) }
    var recurrenceRule by remember {
        mutableStateOf(task.recurrenceRule ?: Frequency.DAILY.serialize())
    }
    val recurrenceDisplayName by remember {
        androidx.compose.runtime.derivedStateOf {
            io.payanam.domain.model.RecurrenceConfig.parse(recurrenceRule).displayName
        }
    }
    var durationMinutes by remember { mutableIntStateOf(task.durationMinutes.coerceAtLeast(1)) }
    val impactValues = listOf("Minimal Impact", "Moderate Impact", "Major Impact")
    val alignmentValues = listOf("Low Alignment", "Moderate Alignment", "High Alignment")
    val energyValues = listOf("Low", "Moderate", "High")
    val controlValues = listOf("Full Control", "Mostly Controllable", "Office/Colleagues Dependent", "External Dependent")
    val impactOptions = listOf(
        strings.locMinimalImpact,
        strings.locModerateImpact,
        strings.locMajorImpact,
    )
    val alignmentOptions = listOf(
        strings.locLowAlignment,
        strings.locModerateAlignment,
        strings.locHighAlignment,
    )
    val energyOptions = listOf(
        strings.locLow,
        strings.locModerate,
        strings.locHigh,
    )
    val controlOptions = listOf(
        strings.locFullControl,
        strings.locMostlyControllable,
        strings.locOfficeColleaguesDependent,
        strings.locExternalDependent,
    )
    val taskTypeOptions = listOf(
        false to strings.locOneTime,
        true to strings.locRecurring,
    )
    val notificationOptions = listOf(
        "auto" to strings.locAuto,
        "custom" to strings.locCustom,
        "off" to strings.locOff,
    )

    var impactIndex by remember {
        mutableIntStateOf(resolveImpactIndex(task.impactLevel))
    }
    var alignmentIndex by remember {
        mutableIntStateOf(resolveAlignmentIndex(task.goalAlignment))
    }
    var energyIndex by remember {
        mutableIntStateOf(energyValues.indexOf(task.energyLevel).coerceAtLeast(0))
    }
    var controlIndex by remember {
        mutableIntStateOf(controlValues.indexOf(task.controlLevel).coerceAtLeast(0))
    }
    var explicitUrgency by remember { mutableStateOf(task.explicitUrgency?.toString() ?: "") }
    var focusRequired by remember { mutableStateOf(task.focusRequired?.toString() ?: "") }
    var blockedReason by remember { mutableStateOf(task.blockedReason ?: "") }
    var externalDependency by remember { mutableStateOf(task.externalDependency ?: "") }
    var tagsInput by remember(initialTags) { mutableStateOf(initialTags.joinToString(", ")) }
    val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    val timePattern = if (prefs.timeFormat.use24Hour) "HH:mm" else "h:mm a"
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(strings.locTaskTitle) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(strings.locDescriptionOptional) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )
        Text(
            text = strings.locLifeDimension,
            style = MaterialTheme.typography.labelLarge,
        )
        EditLifeDimensionDropdown(
            selectedDimensionId = selectedDimensionId,
            options = dimensionOptions,
            onSelect = { selectedDimensionId = it.id },
        )
        if (LocalFeatureFlags.current.recurringTasksEnabled) {
            Text(
                text = strings.locTaskType,
                style = MaterialTheme.typography.labelLarge,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                taskTypeOptions.forEachIndexed { index, option ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = 2,
                        ),
                        onClick = {
                            recurrenceEnabled = option.first
                            if (recurrenceEnabled) {
                                selectedDate = null
                                showRecurrenceDialog = true
                            }
                        },
                        selected = recurrenceEnabled == option.first,
                    ) {
                        Text(
                            text = option.second,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (recurrenceEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.locRecurrencePattern,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = recurrenceDisplayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = { showRecurrenceDialog = true }) {
                        Text(strings.locChange)
                    }
                }
            }
        }
        Text(
            text = if (recurrenceEnabled) {
                strings.locTimeOfDay
            } else {
                strings.locDueDateTime
            },
            style = MaterialTheme.typography.labelLarge,
        )
        if (recurrenceEnabled) {
            OutlinedButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = selectedTime?.format(timeFormatter)
                        ?: strings.locSelectTime,
                )
            }
            if (selectedTime != null) {
                val calculatedFirstOccurrence = run {
                    val config = io.payanam.domain.model.RecurrenceConfig.parse(recurrenceRule)
                    val nextDates = config.getScheduledDatesInRange(
                        LocalDate.now(),
                        LocalDate.now().plusMonths(1),
                    )
                    nextDates.firstOrNull()?.atTime(selectedTime!!)
                }
                if (calculatedFirstOccurrence != null) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = strings.locFirstOccurrence,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = calculatedFirstOccurrence.format(
                                    DateTimeFormatter.ofPattern("EEE, MMM d, yyyy • h:mm a"),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = selectedDate?.format(dateFormatter)
                            ?: strings.locSelectDate,
                    )
                }
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.weight(1f),
                    enabled = selectedDate != null,
                ) {
                    Text(
                        text = selectedTime?.format(timeFormatter)
                            ?: strings.locSelectTime,
                    )
                }
            }
        }
        if (LocalFeatureFlags.current.remindersEnabled) {
            Text(
                text = strings.locReminderNotifications,
                style = MaterialTheme.typography.labelLarge,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                notificationOptions.forEachIndexed { index, option ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = 3,
                        ),
                        onClick = { notificationMode = option.first },
                        selected = notificationMode == option.first,
                    ) {
                        Text(text = option.second, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (notificationMode == "custom") {
                OutlinedTextField(
                    value = customNotificationMinutes,
                    onValueChange = { value ->
                        customNotificationMinutes = value.filter { it.isDigit() }.take(3)
                    },
                    label = { Text(strings.locCustomLeadMinutes) },
                    placeholder = { Text(strings.loc15) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
        Text(
            text = strings.locEstimatedDuration,
            style = MaterialTheme.typography.labelLarge,
        )
        DurationMinutesPickerField(
            label = strings.locDuration,
            minutes = durationMinutes,
            enabled = true,
            onMinutesChange = { durationMinutes = it ?: 1 },
        )
        if (LocalFeatureFlags.current.scoringEnabled) {
            Text(
                text = strings.locTaskProperties,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            EditScoringSegmentedRow(
                label = strings.locImpact,
                options = impactOptions,
                selectedIndex = impactIndex,
                onSelect = { impactIndex = it },
            )
            EditScoringSegmentedRow(
                label = strings.locGoalAlignment,
                options = alignmentOptions,
                selectedIndex = alignmentIndex,
                onSelect = { alignmentIndex = it },
            )
            EditScoringSegmentedRow(
                label = strings.locEnergyRequired,
                options = energyOptions,
                selectedIndex = energyIndex,
                onSelect = { energyIndex = it },
            )
            EditScoringSegmentedRow(
                label = strings.locControlLevel,
                options = controlOptions.map { it.split(" ").first() },
                selectedIndex = controlIndex,
                onSelect = { controlIndex = it },
            )
            Text(
                text = strings.locAdditionalProperties,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = strings.locOptionalFieldsNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = explicitUrgency,
                onValueChange = { value ->
                    explicitUrgency = value.filter { it.isDigit() || it == '.' }.take(4)
                },
                label = { Text(strings.locExplicitUrgency01) },
                placeholder = { Text(strings.locEG08) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(strings.locIndependentOfDueDateLeaveEmptyToSkip) },
            )
            OutlinedTextField(
                value = focusRequired,
                onValueChange = { value ->
                    focusRequired = value.filter { it.isDigit() || it == '.' }.take(4)
                },
                label = { Text(strings.locFocusRequired01) },
                placeholder = { Text(strings.locEG07) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(strings.locConcentrationLevelNeeded) },
            )
            OutlinedTextField(
                value = blockedReason,
                onValueChange = { blockedReason = it },
                label = { Text(strings.locBlockedReason) },
                placeholder = { Text(strings.locWhyIsThisTaskBlocked) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(strings.locLeaveEmptyIfNotBlocked) },
            )
            OutlinedTextField(
                value = externalDependency,
                onValueChange = { externalDependency = it },
                label = { Text(strings.locExternalDependency) },
                placeholder = { Text(strings.locWaitingOn) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(strings.locExternalFactorsOrPeopleThisDependsOn) },
            )
        }
        if (LocalFeatureFlags.current.tagsEnabled) {
            TagEditorField(
                rawValue = tagsInput,
                onValueChange = { tagsInput = it },
                suggestions = tagSuggestions,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val dueDateTime = if (selectedDate != null) {
                    LocalDateTime.of(selectedDate, selectedTime ?: LocalTime.MIDNIGHT)
                } else {
                    null
                }
                val customMinutes = customNotificationMinutes.toIntOrNull()
                val normalizedCustomMinutes = if (notificationMode == "custom") {
                    customMinutes?.takeIf { it > 0 }
                } else {
                    null
                }
                logger.i(
                    "EditTaskScreen.onSave",
                    "Saving task changes",
                    mapOf(
                        "taskId" to task.id,
                        "mode" to notificationMode,
                        "customMinutes" to (normalizedCustomMinutes ?: "none"),
                    ),
                )
                val selectedDimensionLabel = dimensionOptions.firstOrNull { it.id == selectedDimensionId }?.label
                    ?: prefs.labelForDimensionId(selectedDimensionId)
                    ?: fallbackDimension.label
                onSave(
                    EditTaskInput(
                        title = title,
                        description = description.ifBlank { null },
                        dimensionId = selectedDimensionId,
                        dimensionLabel = selectedDimensionLabel,
                        dueDate = dueDateTime,
                        impactLevel = impactValues[impactIndex],
                        goalAlignment = alignmentValues[alignmentIndex],
                        energyLevel = energyValues[energyIndex],
                        controlLevel = controlValues[controlIndex],
                        durationMinutes = durationMinutes,
                        recurrenceEnabled = recurrenceEnabled,
                        recurrenceRule = if (recurrenceEnabled) recurrenceRule else null,
                        notificationMode = notificationMode,
                        customNotificationMinutes = normalizedCustomMinutes,
                        explicitUrgency = explicitUrgency.toDoubleOrNull()?.coerceIn(0.0, 1.0),
                        focusRequired = focusRequired.toDoubleOrNull()?.coerceIn(0.0, 1.0),
                        blockedReason = blockedReason.ifBlank { null },
                        externalDependency = externalDependency.ifBlank { null },
                        tags = parseTagsInput(tagsInput),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = title.isNotBlank(),
        ) {
            Text(strings.locSaveChanges)
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
    if (showRecurrenceDialog) {
        EnhancedRecurrencePickerDialog(
            currentRRule = recurrenceRule,
            onRRuleSelected = { recurrenceRule = it },
            onDismiss = { showRecurrenceDialog = false },
        )
    }
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
                ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            selectedDate = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(strings.locOk)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(strings.settingsActionCancel)
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
    if (showTimePicker) {
        EditTimePickerDialog(
            initialHour = selectedTime?.hour ?: 9,
            initialMinute = selectedTime?.minute ?: 0,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                selectedTime = LocalTime.of(hour, minute)
                showTimePicker = false
            },
        )
    }
}
