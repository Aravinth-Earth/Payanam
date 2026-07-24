//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal const val EDIT_TIME_ENTRY_END_DATE_BUTTON_TAG = "edit_time_entry_end_date_button"
internal const val EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG = "edit_time_entry_confirm_button"

internal fun shouldOpenEditDialogEndDatePicker(_endTime: LocalTime?): Boolean = true

internal fun canSaveEditedTimeEntry(
    startDate: LocalDate,
    startTime: LocalTime,
    endDate: LocalDate?,
    endTime: LocalTime?,
): Boolean {
    val hasAnyEnd = endDate != null || endTime != null
    val hasCompleteEnd = endDate != null && endTime != null
    if (hasAnyEnd && !hasCompleteEnd) {
        return false
    }
    if (!hasCompleteEnd) {
        return true
    }
    return LocalDateTime.of(startDate, startTime) < LocalDateTime.of(endDate, endTime)
}
