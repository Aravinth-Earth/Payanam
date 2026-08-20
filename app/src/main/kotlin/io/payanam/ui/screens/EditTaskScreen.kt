//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Frequency
import io.payanam.domain.model.Task
import io.payanam.ui.components.TagEditorField
import io.payanam.ui.components.parseTagsInput
import io.payanam.ui.viewmodel.DimensionOption
import io.payanam.ui.viewmodel.EditTaskViewModel
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.labelForDimensionId
import io.payanam.ui.viewmodel.optionsForSelection
import io.payanam.ui.viewmodel.visibleDimensionOptions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
private val logger = UnifiedLogger.getInstance()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * Edit task screen.
 */
fun EditTaskScreen(
    /** Task id. */
    taskId: String,
    viewModel: EditTaskViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onTaskSaved: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    /** Launched effect. */
    LaunchedEffect(taskId) {
        viewModel.loadTask(taskId)
    }
    /** Scaffold. */
    Scaffold(
        topBar = {
            /** Top app bar. */
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_edit_task)) },
                navigationIcon = {
                    /** Icon button. */
                    IconButton(onClick = onNavigateBack) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> {
                /** Column. */
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    /** Circular progress indicator. */
                    CircularProgressIndicator()
                }
            }

            uiState.task == null -> {
                /** Column. */
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_not_found))
                }
            }

            else -> {
                /** Edit task content. */
                EditTaskContent(
                    task = uiState.task!!,
                    initialTags = uiState.taskTags,
                    tagSuggestions = uiState.tagSuggestions,
                    onSave = { input ->
                        viewModel.updateTask(input)
                        /** On task saved. */
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
    /** Task. */
    task: Task,
    initialTags: List<String>,
    tagSuggestions: List<String>,
    onSave: (EditTaskInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    /** Prefs. */
    val prefs = LocalAppPreferences.current
    /** Fallback dimension. */
    val fallbackDimension = prefs.visibleDimensionOptions().firstOrNull() ?: DimensionOption(
        id = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
        label = task.lifeIntentionCategory,
        color = MaterialTheme.colorScheme.primary,
        isVisible = true,
        iconKey = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.defaultIconKey,
        canonicalId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
    )
    /** Default dimension id. */
    val defaultDimensionId = task.dimensionId
        ?: fallbackDimension.id
    var title by remember { mutableStateOf(task.title) }
    var description by remember { mutableStateOf(task.description ?: "") }
    var selectedDimensionId by remember { mutableStateOf(defaultDimensionId) }
    /** Dimension options. */
    val dimensionOptions = prefs.optionsForSelection(selectedDimensionId)
    var selectedDate by remember { mutableStateOf(if (task.recurrenceEnabled) null else task.dueDate?.toLocalDate()) }
    var selectedTime by remember { mutableStateOf(task.dueDate?.toLocalTime()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var notificationMode by remember { mutableStateOf(task.notificationMode ?: "auto") }
    var customNotificationMinutes by remember {
        /** Mutable state of. */
        mutableStateOf(task.customNotificationMinutes?.toString() ?: "15")
    }
    var recurrenceEnabled by remember { mutableStateOf(task.recurrenceEnabled) }
    var showRecurrenceDialog by remember { mutableStateOf(false) }
    var recurrenceRule by remember {
        /** Mutable state of. */
        mutableStateOf(task.recurrenceRule ?: Frequency.DAILY.serialize())
    }
    val recurrenceDisplayName by remember {
        androidx.compose.runtime.derivedStateOf {
            io.payanam.domain.model.RecurrenceConfig.parse(recurrenceRule).displayName
        }
    }
    var durationMinutes by remember { mutableIntStateOf(task.durationMinutes.coerceAtLeast(1)) }
    /** Impact values. */
    val impactValues = listOf("Minimal Impact", "Moderate Impact", "Major Impact")
    /** Alignment values. */
    val alignmentValues = listOf("Low Alignment", "Moderate Alignment", "High Alignment")
    /** Energy values. */
    val energyValues = listOf("Low", "Moderate", "High")
    /** Control values. */
    val controlValues = listOf("Full Control", "Mostly Controllable", "Office/Colleagues Dependent", "External Dependent")
    /** Impact options. */
    val impactOptions = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_minimal_impact),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_moderate_impact),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_major_impact),
    )
    /** Alignment options. */
    val alignmentOptions = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_low_alignment),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_moderate_alignment),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_high_alignment),
    )
    /** Energy options. */
    val energyOptions = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_low),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_moderate),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_high),
    )
    /** Control options. */
    val controlOptions = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_full_control),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mostly_controllable),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_office_colleagues_dependent),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_external_dependent),
    )
    /** Task type options. */
    val taskTypeOptions = listOf(
        false to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_one_time),
        true to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_recurring),
    )
    /** Notification options. */
    val notificationOptions = listOf(
        "auto" to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_auto),
        "custom" to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_custom),
        "off" to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_off),
    )

    var impactIndex by remember {
        /** Mutable int state of. */
        mutableIntStateOf(resolveImpactIndex(task.impactLevel))
    }
    var alignmentIndex by remember {
        /** Mutable int state of. */
        mutableIntStateOf(resolveAlignmentIndex(task.goalAlignment))
    }
    var energyIndex by remember {
        /** Mutable int state of. */
        mutableIntStateOf(energyValues.indexOf(task.energyLevel).coerceAtLeast(0))
    }
    var controlIndex by remember {
        /** Mutable int state of. */
        mutableIntStateOf(controlValues.indexOf(task.controlLevel).coerceAtLeast(0))
    }
    var explicitUrgency by remember { mutableStateOf(task.explicitUrgency?.toString() ?: "") }
    var focusRequired by remember { mutableStateOf(task.focusRequired?.toString() ?: "") }
    var blockedReason by remember { mutableStateOf(task.blockedReason ?: "") }
    var externalDependency by remember { mutableStateOf(task.externalDependency ?: "") }
    var tagsInput by remember(initialTags) { mutableStateOf(initialTags.joinToString(", ")) }
    /** Date formatter. */
    val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    /** Time pattern. */
    val timePattern = if (prefs.timeFormat.use24Hour) "HH:mm" else "h:mm a"
    /** Time formatter. */
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern)
    /** Column. */
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        /** Outlined text field. */
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_title)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        /** Outlined text field. */
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_description_optional)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )
        /** Text. */
        Text(
            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_life_dimension),
            style = MaterialTheme.typography.labelLarge,
        )
        /** Edit life dimension dropdown. */
        EditLifeDimensionDropdown(
            selectedDimensionId = selectedDimensionId,
            options = dimensionOptions,
            onSelect = { selectedDimensionId = it.id },
        )
        /** If. */
        if (FeatureFlags.recurringTasksEnabled) {
            /** Text. */
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_type),
                style = MaterialTheme.typography.labelLarge,
            )
            /** Single choice segmented button row. */
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                taskTypeOptions.forEachIndexed { index, option ->
                    /** Segmented button. */
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = 2,
                        ),
                        onClick = {
                            recurrenceEnabled = option.first
                            /** If. */
                            if (recurrenceEnabled) {
                                selectedDate = null
                                showRecurrenceDialog = true
                            }
                        },
                        selected = recurrenceEnabled == option.first,
                    ) {
                        /** Text. */
                        Text(
                            text = option.second,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            /** If. */
            if (recurrenceEnabled) {
                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /** Column. */
                    Column(modifier = Modifier.weight(1f)) {
                        /** Text. */
                        Text(
                            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_recurrence_pattern),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        /** Text. */
                        Text(
                            text = recurrenceDisplayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    /** Text button. */
                    TextButton(onClick = { showRecurrenceDialog = true }) {
                        /** Text. */
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_change))
                    }
                }
            }
        }
        /** Text. */
        Text(
            text = if (recurrenceEnabled) {
                androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_time_of_day)
            } else {
                androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_due_date_time)
            },
            style = MaterialTheme.typography.labelLarge,
        )
        /** If. */
        if (recurrenceEnabled) {
            /** Outlined button. */
            OutlinedButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                /** Text. */
                Text(
                    text = selectedTime?.format(timeFormatter)
                        ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_time),
                )
            }
            /** If. */
            if (selectedTime != null) {
                /** Calculated first occurrence. */
                val calculatedFirstOccurrence = run {
                    /** Config. */
                    val config = io.payanam.domain.model.RecurrenceConfig.parse(recurrenceRule)
                    /** Next dates. */
                    val nextDates = config.getScheduledDatesInRange(
                        LocalDate.now(),
                        LocalDate.now().plusMonths(1),
                    )
                    nextDates.firstOrNull()?.atTime(selectedTime!!)
                }
                /** If. */
                if (calculatedFirstOccurrence != null) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    ) {
                        /** Column. */
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            /** Text. */
                            Text(
                                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_first_occurrence),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            /** Text. */
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
            /** Row. */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                /** Outlined button. */
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f),
                ) {
                    /** Icon. */
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    /** Text. */
                    Text(
                        text = selectedDate?.format(dateFormatter)
                            ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_date),
                    )
                }
                /** Outlined button. */
                OutlinedButton(
                    onClick = { showTimePicker = true },
                    modifier = Modifier.weight(1f),
                    enabled = selectedDate != null,
                ) {
                    /** Text. */
                    Text(
                        text = selectedTime?.format(timeFormatter)
                            ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_time),
                    )
                }
            }
        }
        /** If. */
        if (FeatureFlags.remindersEnabled) {
            /** Text. */
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reminder_notifications),
                style = MaterialTheme.typography.labelLarge,
            )
            /** Single choice segmented button row. */
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                notificationOptions.forEachIndexed { index, option ->
                    /** Segmented button. */
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = 3,
                        ),
                        onClick = { notificationMode = option.first },
                        selected = notificationMode == option.first,
                    ) {
                        /** Text. */
                        Text(text = option.second, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            /** If. */
            if (notificationMode == "custom") {
                /** Outlined text field. */
                OutlinedTextField(
                    value = customNotificationMinutes,
                    onValueChange = { value ->
                        customNotificationMinutes = value.filter { it.isDigit() }.take(3)
                    },
                    label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_custom_lead_minutes)) },
                    placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_15)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
        /** Text. */
        Text(
            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_estimated_duration),
            style = MaterialTheme.typography.labelLarge,
        )
        /** Duration minutes picker field. */
        DurationMinutesPickerField(
            label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration),
            minutes = durationMinutes,
            enabled = true,
            onMinutesChange = { durationMinutes = it ?: 1 },
        )
        /** If. */
        if (FeatureFlags.scoringEnabled) {
            /** Text. */
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_properties),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            /** Edit scoring segmented row. */
            EditScoringSegmentedRow(
                label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_impact),
                options = impactOptions,
                selectedIndex = impactIndex,
                onSelect = { impactIndex = it },
            )
            /** Edit scoring segmented row. */
            EditScoringSegmentedRow(
                label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_goal_alignment),
                options = alignmentOptions,
                selectedIndex = alignmentIndex,
                onSelect = { alignmentIndex = it },
            )
            /** Edit scoring segmented row. */
            EditScoringSegmentedRow(
                label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_energy_required),
                options = energyOptions,
                selectedIndex = energyIndex,
                onSelect = { energyIndex = it },
            )
            /** Edit scoring segmented row. */
            EditScoringSegmentedRow(
                label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_control_level),
                options = controlOptions.map { it.split(" ").first() },
                selectedIndex = controlIndex,
                onSelect = { controlIndex = it },
            )
            /** Text. */
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_additional_properties),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            /** Text. */
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_optional_fields_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            /** Outlined text field. */
            OutlinedTextField(
                value = explicitUrgency,
                onValueChange = { value ->
                    explicitUrgency = value.filter { it.isDigit() || it == '.' }.take(4)
                },
                label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_explicit_urgency_0_1)) },
                placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_e_g_0_8)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_independent_of_due_date_leave_empty_to_skip)) },
            )
            /** Outlined text field. */
            OutlinedTextField(
                value = focusRequired,
                onValueChange = { value ->
                    focusRequired = value.filter { it.isDigit() || it == '.' }.take(4)
                },
                label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_focus_required_0_1)) },
                placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_e_g_0_7)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_concentration_level_needed)) },
            )
            /** Outlined text field. */
            OutlinedTextField(
                value = blockedReason,
                onValueChange = { blockedReason = it },
                label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_blocked_reason)) },
                placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_why_is_this_task_blocked)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_leave_empty_if_not_blocked)) },
            )
            /** Outlined text field. */
            OutlinedTextField(
                value = externalDependency,
                onValueChange = { externalDependency = it },
                label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_external_dependency)) },
                placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_waiting_on)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_external_factors_or_people_this_depends_on)) },
            )
        }
        /** If. */
        if (FeatureFlags.tagsEnabled) {
            /** Tag editor field. */
            TagEditorField(
                rawValue = tagsInput,
                onValueChange = { tagsInput = it },
                suggestions = tagSuggestions,
            )
        }
        /** Spacer. */
        Spacer(modifier = Modifier.height(16.dp))
        /** Button. */
        Button(
            onClick = {
                /** Due date time. */
                val dueDateTime = if (selectedDate != null) {
                    LocalDateTime.of(selectedDate, selectedTime ?: LocalTime.MIDNIGHT)
                } else {
                    /** Null. */
                    null
                }
                /** Custom minutes. */
                val customMinutes = customNotificationMinutes.toIntOrNull()
                /** Normalized custom minutes. */
                val normalizedCustomMinutes = if (notificationMode == "custom") {
                    customMinutes?.takeIf { it > 0 }
                } else {
                    /** Null. */
                    null
                }
                logger.i(
                    "EditTaskScreen.onSave",
                    "Saving task changes",
                    /** Map of. */
                    mapOf(
                        "taskId" to task.id,
                        "mode" to notificationMode,
                        "customMinutes" to (normalizedCustomMinutes ?: "none"),
                    ),
                )
                /** Selected dimension label. */
                val selectedDimensionLabel = dimensionOptions.firstOrNull { it.id == selectedDimensionId }?.label
                    ?: prefs.labelForDimensionId(selectedDimensionId)
                    ?: fallbackDimension.label
                /** On save. */
                onSave(
                    /** Edit task input. */
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
            /** Text. */
            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_save_changes))
        }
        /** Spacer. */
        Spacer(modifier = Modifier.height(32.dp))
    }
    /** If. */
    if (showRecurrenceDialog) {
        /** Enhanced recurrence picker dialog. */
        EnhancedRecurrencePickerDialog(
            currentRRule = recurrenceRule,
            onRRuleSelected = { recurrenceRule = it },
            onDismiss = { showRecurrenceDialog = false },
        )
    }
    /** If. */
    if (showDatePicker) {
        /** Date picker state. */
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate?.atStartOfDay(ZoneId.systemDefault())
                ?.toInstant()?.toEpochMilli()
                ?: System.currentTimeMillis(),
        )
        /** Date picker dialog. */
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                /** Text button. */
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
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
                }
            },
            dismissButton = {
                /** Text button. */
                TextButton(onClick = { showDatePicker = false }) {
                    /** Text. */
                    Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                }
            },
        ) {
            /** Date picker. */
            DatePicker(state = datePickerState)
        }
    }
    /** If. */
    if (showTimePicker) {
        /** Edit time picker dialog. */
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
