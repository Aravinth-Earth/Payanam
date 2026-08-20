//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.ui.viewmodel.DimensionPreference

internal data class DayPlanDialogSavePayload(
    /** Mode. */
    val mode: String,
    /** Allocations. */
    val allocations: Map<String, Int>,
    /** Template id. */
    val templateId: String?,
    /** Is starred day. */
    val isStarredDay: Boolean,
    /** Day type template by type. */
    val dayTypeTemplateByType: Map<String, String?>,
)

internal fun buildDayPlanDialogSavePayload(
    /** Day mode. */
    dayMode: String,
    selectedTemplateId: String?,
    /** Starred day. */
    starredDay: Boolean,
    weekdayTemplateId: String?,
    weekendTemplateId: String?,
    starredTemplateId: String?,
    dimensionOptions: List<DimensionPreference>,
    allocationInputs: Map<String, String>,
): DayPlanDialogSavePayload {
    /** Allocations. */
    val allocations = mutableMapOf<String, Int>()
    dimensionOptions.forEach { option ->
        /** Minutes. */
        val minutes = allocationInputs[option.id]?.toIntOrNull()
        /** If. */
        if (minutes != null && minutes > 0) {
            allocations[option.id] = minutes
        }
    }
    return DayPlanDialogSavePayload(
        mode = dayMode,
        allocations = allocations,
        templateId = selectedTemplateId,
        isStarredDay = starredDay,
        dayTypeTemplateByType = mapOf(
            DayPlanRepository.DAY_TYPE_WEEKDAY to weekdayTemplateId,
            DayPlanRepository.DAY_TYPE_WEEKEND to weekendTemplateId,
            DayPlanRepository.DAY_TYPE_STARRED to starredTemplateId,
        ),
    )
}

@Composable
internal fun DayPlanDialog(
    /** Day key. */
    dayKey: String,
    dimensionOptions: List<DimensionPreference>,
    currentAllocations: Map<String, Int>,
    templates: List<DayPlanTemplateRecord>,
    /** Is past day. */
    isPastDay: Boolean,
    /** Current mode. */
    currentMode: String,
    currentTemplateId: String?,
    /** Is starred day. */
    isStarredDay: Boolean,
    dayTypeTemplateByType: Map<String, String?>,
    resolvedTemplateName: String?,
    onSave: (
        /** Mode. */
        mode: String,
        allocations: Map<String, Int>,
        templateId: String?,
        /** Is starred day. */
        isStarredDay: Boolean,
        dayTypeTemplateByType: Map<String, String?>,
    ) -> Unit,
    onClearPlan: () -> Unit,
    onManageTemplates: () -> Unit,
    onDismiss: () -> Unit,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    var dayMode by remember(dayKey, currentMode) { mutableStateOf(currentMode) }
    var selectedTemplateId by remember(dayKey, currentTemplateId) { mutableStateOf(currentTemplateId) }
    var starredDay by remember(dayKey, isStarredDay) { mutableStateOf(isStarredDay) }
    var weekdayTemplateId by remember(dayTypeTemplateByType) {
        /** Mutable state of. */
        mutableStateOf(dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKDAY])
    }
    var weekendTemplateId by remember(dayTypeTemplateByType) {
        /** Mutable state of. */
        mutableStateOf(dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKEND])
    }
    var starredTemplateId by remember(dayTypeTemplateByType) {
        /** Mutable state of. */
        mutableStateOf(dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_STARRED])
    }
    /** Allocation inputs. */
    val allocationInputs = remember(dimensionOptions, currentAllocations) {
        mutableStateMapOf<String, String>().apply {
            dimensionOptions.forEach { option ->
                this[option.id] = currentAllocations[option.id]?.toString().orEmpty()
            }
        }
    }

    /** Alert dialog. */
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.loc_day_plan)) },
        text = {
            /** Column. */
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.loc_day_plan_mode),
                    style = MaterialTheme.typography.titleSmall,
                )
                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    /** Filter chip. */
                    FilterChip(
                        selected = dayMode == DayPlanRepository.MODE_AUTO,
                        onClick = { dayMode = DayPlanRepository.MODE_AUTO },
                        label = { Text(stringResource(id = R.string.loc_day_plan_mode_auto)) },
                        enabled = !isPastDay,
                    )
                    /** Filter chip. */
                    FilterChip(
                        selected = dayMode == DayPlanRepository.MODE_TEMPLATE,
                        onClick = { dayMode = DayPlanRepository.MODE_TEMPLATE },
                        label = { Text(stringResource(id = R.string.loc_day_plan_mode_template)) },
                        enabled = !isPastDay,
                    )
                    /** Filter chip. */
                    FilterChip(
                        selected = dayMode == DayPlanRepository.MODE_CUSTOM,
                        onClick = { dayMode = DayPlanRepository.MODE_CUSTOM },
                        label = { Text(stringResource(id = R.string.loc_day_plan_mode_custom)) },
                        enabled = !isPastDay,
                    )
                }

                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_day_plan_mark_starred),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    /** Switch. */
                    Switch(
                        checked = starredDay,
                        onCheckedChange = { starredDay = it },
                        enabled = !isPastDay,
                    )
                }

                /** Horizontal divider. */
                HorizontalDivider()

                /** Text. */
                Text(
                    text = stringResource(id = R.string.loc_day_plan_auto_defaults),
                    style = MaterialTheme.typography.titleSmall,
                )

                /** Template picker row. */
                TemplatePickerRow(
                    label = stringResource(id = R.string.loc_day_type_weekday),
                    selectedTemplateId = weekdayTemplateId,
                    templates = templates,
                    enabled = !isPastDay,
                    onTemplateSelected = { weekdayTemplateId = it },
                )
                /** Template picker row. */
                TemplatePickerRow(
                    label = stringResource(id = R.string.loc_day_type_weekend),
                    selectedTemplateId = weekendTemplateId,
                    templates = templates,
                    enabled = !isPastDay,
                    onTemplateSelected = { weekendTemplateId = it },
                )
                /** Template picker row. */
                TemplatePickerRow(
                    label = stringResource(id = R.string.loc_day_type_starred),
                    selectedTemplateId = starredTemplateId,
                    templates = templates,
                    enabled = !isPastDay,
                    onTemplateSelected = { starredTemplateId = it },
                )

                /** If. */
                if (dayMode == DayPlanRepository.MODE_AUTO) {
                    /** Resolved text. */
                    val resolvedText = resolvedTemplateName
                        ?: stringResource(id = R.string.loc_day_plan_auto_unassigned)
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_day_plan_auto_resolved, resolvedText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                /** If. */
                if (dayMode == DayPlanRepository.MODE_TEMPLATE) {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_select_template),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    /** Template picker row. */
                    TemplatePickerRow(
                        label = stringResource(id = R.string.loc_template),
                        selectedTemplateId = selectedTemplateId,
                        templates = templates,
                        enabled = !isPastDay,
                    ) { newTemplateId ->
                        selectedTemplateId = newTemplateId
                        /** Selected. */
                        val selected = templates.firstOrNull { it.id == newTemplateId }
                        selected?.allocations?.forEach { alloc ->
                            allocationInputs[alloc.dimensionId] = alloc.plannedMinutes.toString()
                        }
                        /** If. */
                        if (selected != null) {
                            dimensionOptions.forEach { option ->
                                /** If. */
                                if (selected.allocations.none { it.dimensionId == option.id }) {
                                    allocationInputs[option.id] = ""
                                }
                            }
                        }
                    }
                }

                /** If. */
                if (dayMode == DayPlanRepository.MODE_CUSTOM || dayMode == DayPlanRepository.MODE_TEMPLATE) {
                    /** Horizontal divider. */
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.loc_planned_minutes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    dimensionOptions.forEach { option ->
                        /** Duration minutes picker field. */
                        DurationMinutesPickerField(
                            label = option.label,
                            minutes = allocationInputs[option.id]?.toIntOrNull(),
                            enabled = !isPastDay && dayMode == DayPlanRepository.MODE_CUSTOM,
                        ) { selectedMinutes ->
                            allocationInputs[option.id] = selectedMinutes?.toString().orEmpty()
                        }
                    }
                }

                /** Text button. */
                TextButton(
                    onClick = {
                        /** On dismiss. */
                        onDismiss()
                        /** On manage templates. */
                        onManageTemplates()
                    },
                ) {
                    /** Text. */
                    Text(stringResource(id = R.string.loc_template_management))
                }
            }
        },
        confirmButton = {
            /** If. */
            if (!isPastDay) {
                /** Text button. */
                TextButton(
                    onClick = {
                        /** Save payload. */
                        val savePayload = buildDayPlanDialogSavePayload(
                            dayMode = dayMode,
                            selectedTemplateId = selectedTemplateId,
                            starredDay = starredDay,
                            weekdayTemplateId = weekdayTemplateId,
                            weekendTemplateId = weekendTemplateId,
                            starredTemplateId = starredTemplateId,
                            dimensionOptions = dimensionOptions,
                            allocationInputs = allocationInputs,
                        )
                        /** On save. */
                        onSave(
                            savePayload.mode,
                            savePayload.allocations,
                            savePayload.templateId,
                            savePayload.isStarredDay,
                            savePayload.dayTypeTemplateByType,
                        )
                        logger.i(
                            "DayPlanDialog",
                            "Saved day plan with mode",
                            /** Map of. */
                            mapOf(
                                "dayKey" to dayKey,
                                "mode" to savePayload.mode,
                                "allocations" to savePayload.allocations.size.toString(),
                            ),
                        )
                        /** On dismiss. */
                        onDismiss()
                    },
                ) {
                    /** Text. */
                    Text(stringResource(id = R.string.loc_save))
                }
            }
        },
        dismissButton = {
            Row {
                /** If. */
                if (!isPastDay && currentAllocations.isNotEmpty()) {
                    /** Text button. */
                    TextButton(onClick = {
                        /** On clear plan. */
                        onClearPlan()
                        /** On dismiss. */
                        onDismiss()
                    }) {
                        /** Text. */
                        Text(stringResource(id = R.string.loc_clear_plan))
                    }
                }
                /** Text button. */
                TextButton(onClick = onDismiss) {
                    /** Text. */
                    Text(stringResource(id = R.string.settings_action_cancel))
                }
            }
        },
    )
}

@Composable
private fun TemplatePickerRow(
    /** Label. */
    label: String,
    selectedTemplateId: String?,
    templates: List<DayPlanTemplateRecord>,
    /** Enabled. */
    enabled: Boolean,
    onTemplateSelected: (String?) -> Unit,
) {
    /** Column. */
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        /** Text. */
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        /** Row. */
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /** Filter chip. */
            FilterChip(
                selected = selectedTemplateId == null,
                onClick = { onTemplateSelected(null) },
                label = { Text(stringResource(id = R.string.loc_no_template)) },
                enabled = enabled,
            )
            templates.forEach { template ->
                /** Filter chip. */
                FilterChip(
                    selected = selectedTemplateId == template.id,
                    onClick = { onTemplateSelected(template.id) },
                    label = { Text(template.name) },
                    enabled = enabled,
                )
            }
        }
    }
}
