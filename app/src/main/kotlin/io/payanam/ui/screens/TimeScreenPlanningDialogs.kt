//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.runtime.Composable
import io.payanam.domain.model.Task
import io.payanam.domain.repository.DayPlanTemplateRecord
import io.payanam.ui.viewmodel.DimensionOption
import io.payanam.ui.viewmodel.DimensionPreference
import java.time.LocalDate

@Composable
internal fun TimeScreenPlanningDialogs(
    showDatePicker: Boolean,
    showStartTrackingDialog: Boolean,
    showDayPlanDialog: Boolean,
    selectedDate: LocalDate,
    taskPickerTasks: List<Task>,
    visibleDimensions: List<DimensionPreference>,
    startTrackingDimensions: List<DimensionOption>,
    templates: List<DayPlanTemplateRecord>,
    dayAllocations: Map<String, Int>,
    dayMode: String,
    selectedDayTemplateId: String?,
    isStarredDay: Boolean,
    dayTypeTemplateByType: Map<String, String?>,
    resolvedTemplateName: String?,
    onDateSelected: (LocalDate) -> Unit,
    onStartTracking: (DimensionOption, String?) -> Unit,
    onSaveDayPlan: (
        mode: String,
        allocations: Map<String, Int>,
        templateId: String?,
        isStarredDay: Boolean,
        dayTypeTemplateByType: Map<String, String?>,
    ) -> Unit,
    onClearDayPlan: () -> Unit,
    onManageTemplates: () -> Unit,
    onDismissDatePicker: () -> Unit,
    onDismissStartTracking: () -> Unit,
    onDismissDayPlan: () -> Unit,
) {
    if (showDatePicker) {
        TimeScreenDatePickerDialog(
            selectedDate = selectedDate,
            onDateSelected = onDateSelected,
            onDismiss = onDismissDatePicker,
        )
    }
    if (showStartTrackingDialog) {
        StartTrackingDialog(
            tasks = taskPickerTasks,
            dimensionOptions = startTrackingDimensions,
            onStart = { dimension, taskId ->
                onStartTracking(dimension, taskId)
                onDismissStartTracking()
            },
            onDismiss = onDismissStartTracking,
        )
    }
    if (showDayPlanDialog) {
        DayPlanDialog(
            dayKey = selectedDate.toString(),
            dimensionOptions = visibleDimensions,
            currentAllocations = dayAllocations,
            templates = templates,
            isPastDay = selectedDate < LocalDate.now(),
            currentMode = dayMode,
            currentTemplateId = selectedDayTemplateId,
            isStarredDay = isStarredDay,
            dayTypeTemplateByType = dayTypeTemplateByType,
            resolvedTemplateName = resolvedTemplateName,
            onSave = onSaveDayPlan,
            onClearPlan = onClearDayPlan,
            onManageTemplates = onManageTemplates,
            onDismiss = onDismissDayPlan,
        )
    }
}
