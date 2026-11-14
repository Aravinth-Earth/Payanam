//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.tasks

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The due date an EDIT saves for a recurring task: the stored day carrying the form's time of day.
 *
 * The edit screen shows no date field for a recurring task, so the rule's next occurrence must not stand in
 * for the stored date - it moves a past date forward and shifts a weekday-only task edited at the weekend.
 * It is used only when the task has no stored date to keep.
 *
 * @param storedDueDate the due date the store already holds for this task, or null when it has none.
 * @param firstOccurrence the rule's next occurrence at the selected time, or null when no time is selected.
 */
fun resolveRecurringEditDueDateTime(
    storedDueDate: LocalDateTime?,
    selectedTime: LocalTime?,
    firstOccurrence: LocalDateTime?,
): LocalDateTime? = when {
    storedDueDate == null -> firstOccurrence
    selectedTime == null -> storedDueDate
    else -> storedDueDate.toLocalDate().atTime(selectedTime)
}
