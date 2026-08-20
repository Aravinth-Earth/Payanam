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
    /** Show date picker. */
    showDatePicker: Boolean,
    /** Show start tracking dialog. */
    showStartTrackingDialog: Boolean,
    /** Show day plan dialog. */
    showDayPlanDialog: Boolean,
    /** Selected date. */
    selectedDate: LocalDate,
    taskPickerTasks: List<Task>,
    visibleDimensions: List<DimensionPreference>,
    startTrackingDimensions: List<DimensionOption>,
    templates: List<DayPlanTemplateRecord>,
    dayAllocations: Map<String, Int>,
    /** Day mode. */
    dayMode: String,
    selectedDayTemplateId: String?,
    /** Is starred day. */
    isStarredDay: Boolean,
    dayTypeTemplateByType: Map<String, String?>,
    resolvedTemplateName: String?,
    onDateSelected: (LocalDate) -> Unit,
    onStartTracking: (DimensionOption, String?) -> Unit,
    onSaveDayPlan: (
        /** Mode. */
        mode: String,
        allocations: Map<String, Int>,
        templateId: String?,
        /** Is starred day. */
        isStarredDay: Boolean,
        dayTypeTemplateByType: Map<String, String?>,
    ) -> Unit,
    onClearDayPlan: () -> Unit,
    onManageTemplates: () -> Unit,
    onDismissDatePicker: () -> Unit,
    onDismissStartTracking: () -> Unit,
    onDismissDayPlan: () -> Unit,
) {
    /** If. */
    if (showDatePicker) {
        /** Time screen date picker dialog. */
        TimeScreenDatePickerDialog(
            selectedDate = selectedDate,
            onDateSelected = onDateSelected,
            onDismiss = onDismissDatePicker,
        )
    }

    /** If. */
    if (showStartTrackingDialog) {
        /** Start tracking dialog. */
        StartTrackingDialog(
            tasks = taskPickerTasks,
            dimensionOptions = startTrackingDimensions,
            onStart = { dimension, taskId ->
                /** On start tracking. */
                onStartTracking(dimension, taskId)
                /** On dismiss start tracking. */
                onDismissStartTracking()
            },
            onDismiss = onDismissStartTracking,
        )
    }

    /** If. */
    if (showDayPlanDialog) {
        /** Day plan dialog. */
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
