//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.payanam.domain.model.Frequency
import io.payanam.ui.components.DimensionDropdownBadge
import io.payanam.ui.components.DimensionDropdownBadgeLabelRow
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.viewmodel.DimensionOption
import java.time.LocalDate
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LifeDimensionDropdown(
    /** Selected dimension id. */
    selectedDimensionId: String,
    options: List<DimensionOption>,
    onSelect: (DimensionOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    /** Exposed dropdown menu box. */
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        /** Selected dimension. */
        val selectedDimension = options.firstOrNull { it.id == selectedDimensionId }
        /** Outlined text field. */
        OutlinedTextField(
            value = selectedDimension?.label ?: selectedDimensionId,
            onValueChange = {},
            readOnly = true,
            leadingIcon = {
                selectedDimension?.let { dimension ->
                    /** Dimension dropdown badge. */
                    DimensionDropdownBadge(
                        label = dimension.label,
                        color = dimension.color,
                        iconOption = DimensionIconCatalog.resolve(dimension.iconKey, dimension.id),
                        size = 22.dp,
                    )
                }
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )

        /** Exposed dropdown menu. */
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { dimension ->
                /** Dropdown menu item. */
                DropdownMenuItem(
                    text = {
                        /** Dimension dropdown badge label row. */
                        DimensionDropdownBadgeLabelRow(
                            label = dimension.label,
                            color = dimension.color,
                            iconOption = DimensionIconCatalog.resolve(dimension.iconKey, dimension.id),
                            badgeSize = 22.dp,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        /** On select. */
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
internal fun ScoringSegmentedRow(
    /** Label. */
    label: String,
    options: List<String>,
    /** Selected index. */
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column {
        /** Text. */
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Spacer. */
        Spacer(modifier = Modifier.height(4.dp))
        /** Single choice segmented button row. */
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                /** Segmented button. */
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                    ),
                    onClick = { onSelect(index) },
                    selected = index == selectedIndex,
                ) {
                    /** Text. */
                    Text(
                        text = option.split(" ").first(), // Just first word for brevity
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    /** Time picker state. */
    val timePickerState = rememberTimePickerState(
        initialHour = 9,
        initialMinute = 0,
    )

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_time)) },
        text = {
            /** Time picker. */
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            /** Text button. */
            TextButton(
                onClick = { onConfirm(timePickerState.hour, timePickerState.minute) },
            ) {
                /** Text. */
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
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

/**
 * Recurrence options with RRule generation.
 */
enum class RecurrenceOption {
    /** Daily. */
    DAILY,
    /** Weekdays. */
    WEEKDAYS,
    /** Weekly. */
    WEEKLY,
    /** Biweekly. */
    BIWEEKLY,
    /** Monthly. */
    MONTHLY,
    /** Yearly. */
    YEARLY,
    ;

    /**
     * To rrule.
     */
    fun toRRule(): String = when (this) {
        DAILY -> "FREQ=DAILY;INTERVAL=1"
        WEEKDAYS -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
        WEEKLY -> "FREQ=WEEKLY;INTERVAL=1"
        BIWEEKLY -> "FREQ=WEEKLY;INTERVAL=2"
        MONTHLY -> "FREQ=MONTHLY;INTERVAL=1"
        YEARLY -> "FREQ=YEARLY;INTERVAL=1"
    }

    companion object {
        /**
         * From rrule.
         */
        fun fromRRule(rule: String?): RecurrenceOption {
            /** If. */
            if (rule == null) return DAILY
            return when {
                rule.contains("FREQ=DAILY") -> DAILY
                rule.contains("BYDAY=MO,TU,WE,TH,FR") -> WEEKDAYS
                rule.contains("INTERVAL=2") && rule.contains("FREQ=WEEKLY") -> BIWEEKLY
                rule.contains("FREQ=WEEKLY") -> WEEKLY
                rule.contains("FREQ=MONTHLY") -> MONTHLY
                rule.contains("FREQ=YEARLY") -> YEARLY
                else -> DAILY
            }
        }
    }
}

private enum class RecurrenceSelectionMode { PRESET, CUSTOM }

/**
 * Enhanced recurrence picker supporting:
 * - Specific weekdays (e.g., Mon, Wed, Fri)
 * - Monthly dates (e.g., 1st, 15th)
 * - Custom intervals (e.g., every 3 days)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnhancedRecurrencePickerDialog(
    currentRRule: String?,
    onRRuleSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    /** Current config. */
    val currentConfig = remember(currentRRule) {
        io.payanam.domain.model.RecurrenceConfig.parse(currentRRule)
    }

    /** Is preset type. */
    val isPresetType = remember(currentConfig) {
        currentConfig.type == io.payanam.domain.model.RecurrenceType.DAILY ||
            currentConfig.type == io.payanam.domain.model.RecurrenceType.WEEKDAYS_ONLY ||
            (
                currentConfig.type == io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS &&
                    currentConfig.weekdays.size == 1
                )
    }
    var selectionMode by remember {
        /** Mutable state of. */
        mutableStateOf(if (isPresetType) RecurrenceSelectionMode.PRESET else RecurrenceSelectionMode.CUSTOM)
    }
    var selectedType by remember {
        /** Mutable state of. */
        mutableStateOf(currentConfig.type)
    }
    var selectedWeekdays by remember {
        /** Mutable state of. */
        mutableStateOf(currentConfig.weekdays)
    }
    var selectedMonthlyDates by remember {
        /** Mutable state of. */
        mutableStateOf(currentConfig.monthlyDates)
    }
    var intervalDays by remember {
        /** Mutable state of. */
        mutableStateOf(currentConfig.intervalDays.toString())
    }

    /** Weekday labels. */
    val weekdayLabels = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekday_m),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekday_tu),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekday_w),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekday_th),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekday_f),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekday_sa),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekday_su),
    )
    /** Weekday full names. */
    val weekdayFullNames = listOf(
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_monday),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_tuesday),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_wednesday),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_thursday),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_friday),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_saturday),
        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_sunday),
    )
    /** Preset label. */
    val presetLabel = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_preset)
    /** Custom label. */
    val customLabel = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_custom)

    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_repeat_schedule)) },
        text = {
            /** Column. */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                /** Text. */
                Text(
                    text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_schedule_type),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                /** Single choice segmented button row. */
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    /** List of. */
                    listOf(presetLabel, customLabel).forEachIndexed { index, label ->
                        /** Segmented button. */
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = 2,
                            ),
                            onClick = {
                                selectionMode = if (label == presetLabel) {
                                    RecurrenceSelectionMode.PRESET
                                } else {
                                    RecurrenceSelectionMode.CUSTOM
                                }
                            },
                            selected = (selectionMode == RecurrenceSelectionMode.PRESET && label == presetLabel) ||
                                (selectionMode == RecurrenceSelectionMode.CUSTOM && label == customLabel),
                        ) {
                            /** Text. */
                            Text(text = label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                /** If. */
                if (selectionMode == RecurrenceSelectionMode.PRESET) {
                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_quick_presets),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    /** Row. */
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        /** If. */
                        if (selectedType == io.payanam.domain.model.RecurrenceType.DAILY) {
                            /** Filled tonal button. */
                            FilledTonalButton(
                                onClick = {
                                    selectedType = io.payanam.domain.model.RecurrenceType.DAILY
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                /** Text. */
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_daily), style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            /** Outlined button. */
                            OutlinedButton(
                                onClick = {
                                    selectedType = io.payanam.domain.model.RecurrenceType.DAILY
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                /** Text. */
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_daily), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        /** If. */
                        if (selectedType == io.payanam.domain.model.RecurrenceType.WEEKDAYS_ONLY) {
                            /** Filled tonal button. */
                            FilledTonalButton(
                                onClick = {
                                    selectedType = io.payanam.domain.model.RecurrenceType.WEEKDAYS_ONLY
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                /** Text. */
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekdays), style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            /** Outlined button. */
                            OutlinedButton(
                                onClick = {
                                    selectedType = io.payanam.domain.model.RecurrenceType.WEEKDAYS_ONLY
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                /** Text. */
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekdays), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        /** If. */
                        if (selectedType == io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS) {
                            /** Filled tonal button. */
                            FilledTonalButton(
                                onClick = {
                                    selectedType = io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS
                                    /** If. */
                                    if (selectedWeekdays.isEmpty()) {
                                        selectedWeekdays = setOf(LocalDate.now().dayOfWeek.value)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                /** Text. */
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekly), style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            /** Outlined button. */
                            OutlinedButton(
                                onClick = {
                                    selectedType = io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS
                                    /** If. */
                                    if (selectedWeekdays.isEmpty()) {
                                        selectedWeekdays = setOf(LocalDate.now().dayOfWeek.value)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                /** Text. */
                                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_weekly), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    /** When. */
                    when (selectedType) {
                        io.payanam.domain.model.RecurrenceType.DAILY -> {
                            /** Text. */
                            Text(
                                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_every_day),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        io.payanam.domain.model.RecurrenceType.WEEKDAYS_ONLY -> {
                            /** Text. */
                            Text(
                                text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_every_weekday_mon_fri),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS -> {
                            /** If. */
                            if (selectedWeekdays.isNotEmpty()) {
                                /** Selected names. */
                                val selectedNames = selectedWeekdays.sorted().map { weekdayFullNames[it - 1] }
                                /** Text. */
                                Text(
                                    text = androidx.compose.ui.res.stringResource(
                                        id = io.payanam.R.string.loc_every_selected_names,
                                        selectedNames.joinToString(", "),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        else -> {}
                    }
                } else {
                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_custom_options),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    /** Column. */
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        /** Row. */
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            /** Radio button. */
                            RadioButton(
                                selected = selectedType == io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS,
                                onClick = { selectedType = io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS },
                            )
                            /** Text. */
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_specific_days_of_week), modifier = Modifier.padding(start = 8.dp))
                        }
                        /** Row. */
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            /** Radio button. */
                            RadioButton(
                                selected = selectedType == io.payanam.domain.model.RecurrenceType.MONTHLY_DATES,
                                onClick = { selectedType = io.payanam.domain.model.RecurrenceType.MONTHLY_DATES },
                            )
                            /** Text. */
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_specific_days_of_month), modifier = Modifier.padding(start = 8.dp))
                        }
                        /** Row. */
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            /** Radio button. */
                            RadioButton(
                                selected = selectedType == io.payanam.domain.model.RecurrenceType.INTERVAL,
                                onClick = { selectedType = io.payanam.domain.model.RecurrenceType.INTERVAL },
                            )
                            /** Text. */
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_every_n_days), modifier = Modifier.padding(start = 8.dp))
                        }
                        /** Row. */
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            /** Radio button. */
                            RadioButton(
                                selected = selectedType == io.payanam.domain.model.RecurrenceType.YEARLY,
                                onClick = { selectedType = io.payanam.domain.model.RecurrenceType.YEARLY },
                            )
                            /** Text. */
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_yearly), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }

                /** If. */
                if (selectionMode == RecurrenceSelectionMode.CUSTOM &&
                    selectedType == io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS
                ) {
                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_specific_days_of_week_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    /** Row. */
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        weekdayLabels.forEachIndexed { index, label ->
                            /** Day num. */
                            val dayNum = index + 1
                            /** Is selected. */
                            val isSelected = dayNum in selectedWeekdays

                            androidx.compose.material3.FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedType = io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS
                                    selectedWeekdays = if (dayNum in selectedWeekdays) {
                                        selectedWeekdays - dayNum
                                    } else {
                                        selectedWeekdays + dayNum
                                    }
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.padding(horizontal = 1.dp),
                            )
                        }
                    }

                    /** If. */
                    if (selectedWeekdays.isNotEmpty()) {
                        /** Selected names. */
                        val selectedNames = selectedWeekdays.sorted().map { weekdayFullNames[it - 1] }
                        /** Text. */
                        Text(
                            text = androidx.compose.ui.res.stringResource(
                                id = io.payanam.R.string.loc_every_selected_names,
                                selectedNames.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                /** If. */
                if (selectionMode == RecurrenceSelectionMode.CUSTOM &&
                    selectedType == io.payanam.domain.model.RecurrenceType.MONTHLY_DATES
                ) {
                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_specific_days_of_month_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    /** Row. */
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        /** List of. */
                        listOf(1, 15, 32).forEach { date ->
                            /** Date label. */
                            val dateLabel = when (date) {
                                32 -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_last)
                                else -> "${date}${getDaySuffix(date)}"
                            }
                            /** Is selected. */
                            val isSelected = date in selectedMonthlyDates

                            androidx.compose.material3.FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedType = io.payanam.domain.model.RecurrenceType.MONTHLY_DATES
                                    selectedMonthlyDates = if (date in selectedMonthlyDates) {
                                        selectedMonthlyDates - date
                                    } else {
                                        selectedMonthlyDates + date
                                    }
                                },
                                label = { Text(dateLabel) },
                            )
                        }
                    }

                    var customDateText by remember { mutableStateOf("") }
                    /** Row. */
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        /** Outlined text field. */
                        OutlinedTextField(
                            value = customDateText,
                            onValueChange = {
                                customDateText = it.filter { c -> c.isDigit() }.take(2)
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_add_date)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        /** Button. */
                        Button(
                            onClick = {
                                customDateText.toIntOrNull()?.let { date ->
                                    /** If. */
                                    if (date in 1..31) {
                                        selectedMonthlyDates = selectedMonthlyDates + date
                                        customDateText = ""
                                    }
                                }
                            },
                            enabled = customDateText.toIntOrNull()?.let { it in 1..31 } == true,
                        ) {
                            /** Text. */
                            Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_add))
                        }
                    }

                    /** If. */
                    if (selectedMonthlyDates.isNotEmpty()) {
                        /** Text. */
                        Text(
                            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_selected_dates_click_remove),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        /** Row. */
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            selectedMonthlyDates.sorted().forEach { date ->
                                /** Date label. */
                                val dateLabel = when (date) {
                                    32 -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_last)
                                    else -> "${date}${getDaySuffix(date)}"
                                }
                                androidx.compose.material3.AssistChip(
                                    onClick = {
                                        selectedMonthlyDates = selectedMonthlyDates - date
                                    },
                                    label = { Text(dateLabel, style = MaterialTheme.typography.labelSmall) },
                                    trailingIcon = {
                                        /** Icon. */
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_remove),
                                            modifier = Modifier
                                                .width(16.dp)
                                                .height(16.dp),
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        /** Text. */
                        Text(
                            text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_select_dates_or_add_custom),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                }

                /** If. */
                if (selectionMode == RecurrenceSelectionMode.CUSTOM &&
                    selectedType == io.payanam.domain.model.RecurrenceType.INTERVAL
                ) {
                    /** Text. */
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_every_n_days_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    /** Row. */
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        /** Text. */
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_every))
                        /** Outlined text field. */
                        OutlinedTextField(
                            value = intervalDays,
                            onValueChange = { value ->
                                intervalDays = value.filter { it.isDigit() }.take(3)
                                selectedType = io.payanam.domain.model.RecurrenceType.INTERVAL
                            },
                            modifier = Modifier.width(80.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        /** Text. */
                        Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_days))
                    }
                }
            }
        },
        confirmButton = {
            /** Can confirm. */
            val canConfirm = when (selectedType) {
                io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS -> selectedWeekdays.isNotEmpty()
                io.payanam.domain.model.RecurrenceType.MONTHLY_DATES -> selectedMonthlyDates.isNotEmpty()
                io.payanam.domain.model.RecurrenceType.INTERVAL -> intervalDays.toIntOrNull()?.let { it > 0 } == true
                else -> true
            }
            /** Text button. */
            TextButton(
                onClick = {
                    /** Config. */
                    val config = when (selectedType) {
                        io.payanam.domain.model.RecurrenceType.DAILY ->
                            io.payanam.domain.model.RecurrenceConfig.daily()

                        io.payanam.domain.model.RecurrenceType.WEEKDAYS_ONLY ->
                            io.payanam.domain.model.RecurrenceConfig.weekdays()

                        io.payanam.domain.model.RecurrenceType.SPECIFIC_WEEKDAYS ->
                            io.payanam.domain.model.RecurrenceConfig.specificWeekdays(selectedWeekdays)

                        io.payanam.domain.model.RecurrenceType.MONTHLY_DATES ->
                            io.payanam.domain.model.RecurrenceConfig.monthlyOnDates(*selectedMonthlyDates.toIntArray())

                        io.payanam.domain.model.RecurrenceType.INTERVAL ->
                            io.payanam.domain.model.RecurrenceConfig.everyNDays(intervalDays.toIntOrNull() ?: 1)

                        io.payanam.domain.model.RecurrenceType.FREQUENCY ->
                            io.payanam.domain.model.RecurrenceConfig.daily()

                        // Default
                        io.payanam.domain.model.RecurrenceType.YEARLY ->
                            io.payanam.domain.model.RecurrenceConfig.yearly()
                    }
                    /** Val. */
                    val (numerator, denominator) = config.toFrequency()
                    /** On rrule selected. */
                    onRRuleSelected(
                        /** Frequency. */
                        Frequency(
                            numerator = numerator,
                            denominator = denominator,
                            anchorDate = config.startDate,
                        ).serialize(),
                    )
                    /** On dismiss. */
                    onDismiss()
                },
                enabled = canConfirm,
            ) {
                /** Text. */
                Text(androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_ok))
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
