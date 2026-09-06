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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Frequency
import io.payanam.domain.model.RecurrenceConfig
import io.payanam.ui.components.TagEditorField
import io.payanam.ui.components.parseTagsInput
import io.payanam.ui.viewmodel.AddTaskViewModel
import io.payanam.ui.viewmodel.LocalAppPreferences
import io.payanam.ui.viewmodel.visibleDimensionOptions
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
private val logger = UnifiedLogger.getInstance()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskScreen(
    viewModel: AddTaskViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onTaskSaved: () -> Unit = {},
) {
    val tagSuggestions by viewModel.tagSuggestions.collectAsState()
    val prefs = LocalAppPreferences.current
    val dimensionOptions = prefs.visibleDimensionOptions()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unknownErrorMessage = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_unknown_error_occurred)
    val actionFailedPlaceholder = "__ACTION_FAILED_REASON__"
    val actionFailedTemplate = androidx.compose.ui.res.stringResource(
        id = io.payanam.R.string.loc_action_failed_with_reason,
        actionFailedPlaceholder,
    )
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedDimensionId by remember { mutableStateOf(dimensionOptions.firstOrNull()?.id.orEmpty()) }
    var selectedDimensionLabel by remember { mutableStateOf(dimensionOptions.firstOrNull()?.label.orEmpty()) }
    androidx.compose.runtime.LaunchedEffect(dimensionOptions, selectedDimensionId) {
        if (selectedDimensionId.isBlank()) {
            val first = dimensionOptions.firstOrNull() ?: return@LaunchedEffect
            selectedDimensionId = first.id
            selectedDimensionLabel = first.label
            return@LaunchedEffect
        }
        if (dimensionOptions.none { it.id == selectedDimensionId }) {
            val first = dimensionOptions.firstOrNull() ?: return@LaunchedEffect
            selectedDimensionId = first.id
            selectedDimensionLabel = first.label
            return@LaunchedEffect
        }
        selectedDimensionLabel = dimensionOptions.firstOrNull { it.id == selectedDimensionId }?.label
            ?: selectedDimensionLabel
    }
    var selectedDate by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    var selectedTime by remember { mutableStateOf<LocalTime?>(LocalTime.now().withSecond(0).withNano(0)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var notificationMode by remember { mutableStateOf("auto") } // auto | custom | off
    var customNotificationMinutes by remember { mutableStateOf("15") }
    var recurrenceEnabled by remember { mutableStateOf(false) }
    var showRecurrenceDialog by remember { mutableStateOf(false) }
    var recurrenceRule by remember { mutableStateOf(Frequency.DAILY.serialize()) }
    val recurrenceDisplayName by remember {
        androidx.compose.runtime.derivedStateOf {
            io.payanam.domain.model.RecurrenceConfig.parse(recurrenceRule).displayName
        }
    }
    var durationMinutes by remember { mutableStateOf(10) }
    var impactIndex by remember { mutableIntStateOf(1) } // Moderate Impact
    var alignmentIndex by remember { mutableIntStateOf(1) } // Moderate Alignment
    var energyIndex by remember { mutableIntStateOf(1) } // Moderate Energy
    var controlIndex by remember { mutableIntStateOf(2) } // Office/Colleagues Dependent
    var explicitUrgency by remember { mutableStateOf("") }
    var focusRequired by remember { mutableStateOf("") }
    var blockedReason by remember { mutableStateOf("") }
    var externalDependency by remember { mutableStateOf("") }
    var tagsInput by remember { mutableStateOf("") }
    val impactValues = listOf("Minimal Impact", "Moderate Impact", "Major Impact")
    val alignmentValues = listOf("Low Alignment", "Moderate Alignment", "High Alignment")
    val energyValues = listOf("Low", "Moderate", "High")
    val controlValues = listOf("Full Control", "Mostly Controllable", "Office/Colleagues Dependent", "External Dependent")
    val impactOptions = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_minimal_impact),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_moderate_impact),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_major_impact),
    )
    val alignmentOptions = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_low_alignment),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_moderate_alignment),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_high_alignment),
    )
    val energyOptions = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_low),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_moderate),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_high),
    )
    val controlOptions = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_full_control),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_mostly_controllable),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_office_colleagues_dependent),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_external_dependent),
    )
    val taskTypeOptions = listOf(
        false to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_one_time),
        true to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_recurring),
    )
    val notificationOptions = listOf(
        "auto" to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_auto),
        "custom" to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_custom),
        "off" to androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_off),
    )
    val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    val timePattern = if (prefs.timeFormat.use24Hour) "HH:mm" else "h:mm a"
    val timeFormatter = DateTimeFormatter.ofPattern(timePattern)
    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_add_task)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_title)) },
                placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_what_needs_to_be_done)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (FeatureFlags.recurringTasksEnabled) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_type),
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
                                    if (selectedTime == null) {
                                        selectedTime = LocalTime.of(9, 0) // Default 9 AM for new recurring
                                    }
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
                                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_recurrence_pattern),
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
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_change))
                        }
                    }
                }
            }
            Text(
                text = if (recurrenceEnabled) {
                    androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_time_of_day)
                } else {
                    androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_due_date_time)
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
                            ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_time),
                    )
                }
                if (selectedTime != null) {
                    val calculatedFirstOccurrence = run {
                        val config = RecurrenceConfig.parse(recurrenceRule)
                        val nextDates = config.getScheduledDatesInRange(
                            LocalDate.now(),
                            LocalDate.now().plusMonths(1),
                        )
                        nextDates.firstOrNull()?.atTime(selectedTime!!)
                    }
                    if (calculatedFirstOccurrence != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_first_occurrence),
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
                                ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_date),
                        )
                    }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                        enabled = selectedDate != null,
                    ) {
                        Text(
                            text = selectedTime?.format(timeFormatter)
                                ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_time),
                        )
                    }
                }
            }
            if (FeatureFlags.remindersEnabled) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_reminder_notifications),
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
                        label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_custom_lead_minutes)) },
                        placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_15)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_description_optional)) },
                placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_add_details)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_life_dimension),
                style = MaterialTheme.typography.labelLarge,
            )
            LifeDimensionDropdown(
                selectedDimensionId = selectedDimensionId,
                options = dimensionOptions,
                onSelect = {
                    selectedDimensionId = it.id
                    selectedDimensionLabel = it.label
                },
            )
            Text(
                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_estimated_duration),
                style = MaterialTheme.typography.labelLarge,
            )
            DurationMinutesPickerField(
                label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_duration),
                minutes = durationMinutes,
                enabled = true,
                onMinutesChange = { durationMinutes = it ?: 1 },
            )
            if (FeatureFlags.scoringEnabled) {
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_task_properties),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ScoringSegmentedRow(
                    label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_impact),
                    options = impactOptions,
                    selectedIndex = impactIndex,
                    onSelect = { impactIndex = it },
                )
                ScoringSegmentedRow(
                    label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_goal_alignment),
                    options = alignmentOptions,
                    selectedIndex = alignmentIndex,
                    onSelect = { alignmentIndex = it },
                )
                ScoringSegmentedRow(
                    label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_energy_required),
                    options = energyOptions,
                    selectedIndex = energyIndex,
                    onSelect = { energyIndex = it },
                )
                ScoringSegmentedRow(
                    label = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_control_level),
                    options = controlOptions.map { it.split(" ").first() }, // Shortened labels
                    selectedIndex = controlIndex,
                    onSelect = { controlIndex = it },
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_additional_properties),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_optional_fields_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                OutlinedTextField(
                    value = blockedReason,
                    onValueChange = { blockedReason = it },
                    label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_blocked_reason)) },
                    placeholder = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_why_is_this_task_blocked)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_leave_empty_if_not_blocked)) },
                )
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
            if (FeatureFlags.tagsEnabled) {
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
                        "AddTaskScreen.onSave",
                        "Saving task",
                        mapOf(
                            "title" to title,
                            "recurrenceEnabled" to recurrenceEnabled,
                            "selectedDate" to (selectedDate?.toString() ?: "none"),
                            "selectedTime" to (selectedTime?.toString() ?: "none"),
                            "dueDateTime" to (dueDateTime?.toString() ?: "none"),
                            "recurrenceRule" to (if (recurrenceEnabled) recurrenceRule else "none"),
                        ),
                    )
                    viewModel.createTask(
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
                        onResult = { result ->
                            result.onSuccess {
                                onTaskSaved()
                            }.onFailure { error ->
                                val message = error.message ?: unknownErrorMessage
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        actionFailedTemplate.replace(actionFailedPlaceholder, message),
                                    )
                                }
                            }
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank() && selectedDimensionId.isNotBlank(),
            ) {
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_save_task))
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
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.settings_action_cancel))
                    }
                },
            ) {
                DatePicker(state = datePickerState)
            }
        }
        if (showTimePicker) {
            TimePickerDialog(
                onDismiss = { showTimePicker = false },
                onConfirm = { hour, minute ->
                    selectedTime = LocalTime.of(hour, minute)
                    showTimePicker = false
                },
            )
        }
    }
}
