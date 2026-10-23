//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.time.DurationMinutesPickerField
import io.payanam.domain.repository.DayPlanRepository
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.shared.logging.AppLogger

/**
 * Everything the day-plan dialog collects, validated and in save-ready form.
 *
 * The dialog's confirm button does not write anything itself; it builds one of these and hands the
 * five fields to the save callback, so this is the boundary where unparseable UI state stops. The
 * minutes in [allocations] are already integers and the day-type map already has one key per day
 * type, which is why neither the view model nor the repository has to re-validate either.
 *
 * A plain value holder, not a sealed hierarchy: the dialog resolves every choice into this single
 * shape, so the save path has one case rather than a discriminated one.
 */
data class DayPlanDialogSavePayload(
    val mode: String,
    val allocations: Map<String, Int>,
    val templateId: String?,
    val isStarredDay: Boolean,
    val dayTypeTemplateByType: Map<String, String?>,
)

/**
 * Turns the dialog's raw widget state into a [DayPlanDialogSavePayload] the save path can consume
 * without further checks.
 *
 * **The allocation filter is the reason this exists as a function.** Each dimension's field holds
 * a string, and a user can leave one empty or make it nonsense; the dialog stores that text as typed
 * and never parses it. So the text is parsed HERE, on save, and a field is only carried into the
 * payload if it is a positive number of minutes. Empty, zero, negative, non-numeric and - the
 * common one - a dimension the user never touched are all simply ABSENT from [allocations] rather
 * than stored as a zero. That absence is what "no allocation for this dimension" means downstream,
 * so a blank field must not be written as an explicit 0. A key in the input map naming a dimension
 * that is not offered in [dimensionOptions] is dropped for the same reason: the payload is keyed by
 * the options actually on screen, not by whatever the widget map happens to contain.
 *
 * [dayTypeTemplateByType] is built with all three day types present unconditionally, including the
 * ones the user did not set. A `null` VALUE means "this day type has no template", which is
 * different from the key being absent, and the caller relies on all three being there to apply a
 * template change to the whole month in one pass.
 *
 * @param dayMode `MODE_CUSTOM` or `MODE_TEMPLATE` from [DayPlanRepository]; carried through
 *   unvalidated, so a caller is responsible for passing a real mode.
 * @param selectedTemplateId the template the day itself uses, or null for "no template". Kept
 *   independent of the per-day-type templates: a day can follow its type's template while naming
 *   none of its own.
 * @param dimensionOptions the dimensions on screen, whose ids key [allocations].
 * @param allocationInputs raw text per dimension id, exactly as typed.
 */
fun buildDayPlanDialogSavePayload(
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
        dayTypeTemplateByType =
            mapOf(
                DayPlanRepository.DAY_TYPE_WEEKDAY to weekdayTemplateId,
                DayPlanRepository.DAY_TYPE_WEEKEND to weekendTemplateId,
                DayPlanRepository.DAY_TYPE_STARRED to starredTemplateId,
            ),
    )
}

@Composable
fun DayPlanDialog(
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
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
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
    val allocationInputs =
        remember(dimensionOptions, currentAllocations) {
            mutableStateMapOf<String, String>().apply {
                dimensionOptions.forEach { option ->
                    this[option.id] = currentAllocations[option.id]?.toString().orEmpty()
                }
            }
        }
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.locDayPlan) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = strings.locDayPlanMode,
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = dayMode == DayPlanRepository.MODE_AUTO,
                        onClick = { dayMode = DayPlanRepository.MODE_AUTO },
                        label = { Text(strings.locDayPlanModeAuto) },
                        enabled = !isPastDay,
                    )
                    FilterChip(
                        selected = dayMode == DayPlanRepository.MODE_TEMPLATE,
                        onClick = { dayMode = DayPlanRepository.MODE_TEMPLATE },
                        label = { Text(strings.locDayPlanModeTemplate) },
                        enabled = !isPastDay,
                    )
                    FilterChip(
                        selected = dayMode == DayPlanRepository.MODE_CUSTOM,
                        onClick = { dayMode = DayPlanRepository.MODE_CUSTOM },
                        label = { Text(strings.locDayPlanModeCustom) },
                        enabled = !isPastDay,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = strings.locDayPlanMarkStarred,
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
                    text = strings.locDayPlanAutoDefaults,
                    style = MaterialTheme.typography.titleSmall,
                )
                TemplatePickerRow(
                    label = strings.locDayTypeWeekday,
                    selectedTemplateId = weekdayTemplateId,
                    templates = templates,
                    enabled = !isPastDay,
                    onTemplateSelected = { weekdayTemplateId = it },
                )
                TemplatePickerRow(
                    label = strings.locDayTypeWeekend,
                    selectedTemplateId = weekendTemplateId,
                    templates = templates,
                    enabled = !isPastDay,
                    onTemplateSelected = { weekendTemplateId = it },
                )
                TemplatePickerRow(
                    label = strings.locDayTypeStarred,
                    selectedTemplateId = starredTemplateId,
                    templates = templates,
                    enabled = !isPastDay,
                    onTemplateSelected = { starredTemplateId = it },
                )
                if (dayMode == DayPlanRepository.MODE_AUTO) {
                    val resolvedText =
                        resolvedTemplateName
                            ?: strings.locDayPlanAutoUnassigned
                    Text(
                        text = strings.locDayPlanAutoResolved.format(resolvedText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (dayMode == DayPlanRepository.MODE_TEMPLATE) {
                    Text(
                        text = strings.locSelectTemplate,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TemplatePickerRow(
                        label = strings.locTemplate,
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
                        text = strings.locPlannedMinutes,
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
                    Text(strings.locTemplateManagement)
                }
            }
        },
        confirmButton = {
            if (!isPastDay) {
                TextButton(
                    onClick = {
                        val savePayload =
                            buildDayPlanDialogSavePayload(
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
                    Text(strings.locSave)
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
                        Text(strings.locClearPlan)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(strings.settingsActionCancel)
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
    val strings = LocalUiStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedTemplateId == null,
                onClick = { onTemplateSelected(null) },
                label = { Text(strings.locNoTemplate) },
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
