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
    val mode: String,
    val allocations: Map<String, Int>,
    val templateId: String?,
    val isStarredDay: Boolean,
    val dayTypeTemplateByType: Map<String, String?>,
)

internal fun buildDayPlanDialogSavePayload(
    dayMode: String,
    selectedTemplateId: String?,
    starredDay: Boolean,
    weekdayTemplateId: String?,
    weekendTemplateId: String?,
    starredTemplateId: String?,
    dimensionOptions: List<DimensionPreference>,
    allocationInputs: Map<String, String>,
): DayPlanDialogSavePayload {
    val allocations = mutableMapOf<String, Int>()
    dimensionOptions.forEach { option ->
        val minutes = allocationInputs[option.id]?.toIntOrNull()
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
    dayKey: String,
    dimensionOptions: List<DimensionPreference>,
    currentAllocations: Map<String, Int>,
    templates: List<DayPlanTemplateRecord>,
    isPastDay: Boolean,
    currentMode: String,
    currentTemplateId: String?,
    isStarredDay: Boolean,
    dayTypeTemplateByType: Map<String, String?>,
    resolvedTemplateName: String?,
    onSave: (
        mode: String,
        allocations: Map<String, Int>,
        templateId: String?,
        isStarredDay: Boolean,
        dayTypeTemplateByType: Map<String, String?>,
    ) -> Unit,
    onClearPlan: () -> Unit,
    onManageTemplates: () -> Unit,
    onDismiss: () -> Unit,
) {
    val logger = remember { UnifiedLogger.getInstance() }
    var dayMode by remember(dayKey, currentMode) { mutableStateOf(currentMode) }
    var selectedTemplateId by remember(dayKey, currentTemplateId) { mutableStateOf(currentTemplateId) }
    var starredDay by remember(dayKey, isStarredDay) { mutableStateOf(isStarredDay) }
    var weekdayTemplateId by remember(dayTypeTemplateByType) {
        mutableStateOf(dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKDAY])
    }
    var weekendTemplateId by remember(dayTypeTemplateByType) {
        mutableStateOf(dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_WEEKEND])
    }
    var starredTemplateId by remember(dayTypeTemplateByType) {
        mutableStateOf(dayTypeTemplateByType[DayPlanRepository.DAY_TYPE_STARRED])
    }
    val allocationInputs = remember(dimensionOptions, currentAllocations) {
        mutableStateMapOf<String, String>().apply {
            dimensionOptions.forEach { option ->
                this[option.id] = currentAllocations[option.id]?.toString().orEmpty()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.loc_day_plan)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.loc_day_plan_mode),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = dayMode == DayPlanRepository.MODE_AUTO,
                        onClick = { dayMode = DayPlanRepository.MODE_AUTO },
                        label = { Text(stringResource(id = R.string.loc_day_plan_mode_auto)) },
                        enabled = !isPastDay,
                    )
                    FilterChip(
                        selected = dayMode == DayPlanRepository.MODE_TEMPLATE,
                        onClick = { dayMode = DayPlanRepository.MODE_TEMPLATE },
                        label = { Text(stringResource(id = R.string.loc_day_plan_mode_template)) },
                        enabled = !isPastDay,
                    )
                    FilterChip(
                        selected = dayMode == DayPlanRepository.MODE_CUSTOM,
                        onClick = { dayMode = DayPlanRepository.MODE_CUSTOM },
                        label = { Text(stringResource(id = R.string.loc_day_plan_mode_custom)) },
                        enabled = !isPastDay,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(id = R.string.loc_day_plan_mark_starred),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = starredDay,
                        onCheckedChange = { starredDay = it },
                        enabled = !isPastDay,
                    )
                }
                HorizontalDivider()
                Text(
                    text = stringResource(id = R.string.loc_day_plan_auto_defaults),
                    style = MaterialTheme.typography.titleSmall,
                )
                TemplatePickerRow(
                    label = stringResource(id = R.string.loc_day_type_weekday),
                    selectedTemplateId = weekdayTemplateId,
                    templates = templates,
                    enabled = !isPastDay,
                    onTemplateSelected = { weekdayTemplateId = it },
                )
                TemplatePickerRow(
                    label = stringResource(id = R.string.loc_day_type_weekend),
                    selectedTemplateId = weekendTemplateId,
                    templates = templates,
                    enabled = !isPastDay,
                    onTemplateSelected = { weekendTemplateId = it },
                )
                TemplatePickerRow(
                    label = stringResource(id = R.string.loc_day_type_starred),
                    selectedTemplateId = starredTemplateId,
                    templates = templates,
                    enabled = !isPastDay,
                    onTemplateSelected = { starredTemplateId = it },
                )
                if (dayMode == DayPlanRepository.MODE_AUTO) {
                    val resolvedText = resolvedTemplateName
                        ?: stringResource(id = R.string.loc_day_plan_auto_unassigned)
                    Text(
                        text = stringResource(id = R.string.loc_day_plan_auto_resolved, resolvedText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (dayMode == DayPlanRepository.MODE_TEMPLATE) {
                    Text(
                        text = stringResource(id = R.string.loc_select_template),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TemplatePickerRow(
                        label = stringResource(id = R.string.loc_template),
                        selectedTemplateId = selectedTemplateId,
                        templates = templates,
                        enabled = !isPastDay,
                    ) { newTemplateId ->
                        selectedTemplateId = newTemplateId
                        val selected = templates.firstOrNull { it.id == newTemplateId }
                        selected?.allocations?.forEach { alloc ->
                            allocationInputs[alloc.dimensionId] = alloc.plannedMinutes.toString()
                        }
                        if (selected != null) {
                            dimensionOptions.forEach { option ->
                                if (selected.allocations.none { it.dimensionId == option.id }) {
                                    allocationInputs[option.id] = ""
                                }
                            }
                        }
                    }
                }
                if (dayMode == DayPlanRepository.MODE_CUSTOM || dayMode == DayPlanRepository.MODE_TEMPLATE) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = stringResource(id = R.string.loc_planned_minutes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    dimensionOptions.forEach { option ->
                        DurationMinutesPickerField(
                            label = option.label,
                            minutes = allocationInputs[option.id]?.toIntOrNull(),
                            enabled = !isPastDay && dayMode == DayPlanRepository.MODE_CUSTOM,
                        ) { selectedMinutes ->
                            allocationInputs[option.id] = selectedMinutes?.toString().orEmpty()
                        }
                    }
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        onManageTemplates()
                    },
                ) {
                    Text(stringResource(id = R.string.loc_template_management))
                }
            }
        },
        confirmButton = {
            if (!isPastDay) {
                TextButton(
                    onClick = {
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
                            mapOf(
                                "dayKey" to dayKey,
                                "mode" to savePayload.mode,
                                "allocations" to savePayload.allocations.size.toString(),
                            ),
                        )
                        onDismiss()
                    },
                ) {
                    Text(stringResource(id = R.string.loc_save))
                }
            }
        },
        dismissButton = {
            Row {
                if (!isPastDay && currentAllocations.isNotEmpty()) {
                    TextButton(onClick = {
                        onClearPlan()
                        onDismiss()
                    }) {
                        Text(stringResource(id = R.string.loc_clear_plan))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(id = R.string.settings_action_cancel))
                }
            }
        },
    )
}

@Composable
private fun TemplatePickerRow(
    label: String,
    selectedTemplateId: String?,
    templates: List<DayPlanTemplateRecord>,
    enabled: Boolean,
    onTemplateSelected: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedTemplateId == null,
                onClick = { onTemplateSelected(null) },
                label = { Text(stringResource(id = R.string.loc_no_template)) },
                enabled = enabled,
            )
            templates.forEach { template ->
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
