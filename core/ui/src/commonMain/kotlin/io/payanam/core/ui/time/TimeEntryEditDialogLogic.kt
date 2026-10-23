//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

const val EDIT_TIME_ENTRY_END_DATE_BUTTON_TAG = "edit_time_entry_end_date_button"
const val EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG = "edit_time_entry_confirm_button"

/**
 * Whether the edit dialog's end-date control should open the date picker.
 *
 * **Unconditionally true, for every value including null.** The end time is accepted but ignored -
 * the parameter is named with a leading underscore precisely because the compiler would otherwise
 * flag it as unused - so no value of it, present or absent, changes the answer.
 *
 * The point of keeping the end time in the signature despite ignoring it is that the answer is a
 * decision ABOUT the end time: a caller that reaches for this is asking whether the end date can be
 * changed, and it can be changed whether or not the entry has an end time yet. A caller must not
 * read a true here as "this entry already has an end" - the parameter carries no such information,
 * and asking that is what [canSaveEditedTimeEntry] is for.
 *
 * @param _endTime the entry's current end time, or null when the entry is still running. Accepted
 *   for the call site, not consulted.
 * @return always true. Cannot throw.
 */
fun shouldOpenEditDialogEndDatePicker(_endTime: LocalTime?): Boolean = true

/**
 * Whether the edited time entry holds together well enough to save.
 *
 * Three cases, in the order they are decided:
 *
 * 1. **End half-set** - an end date without an end time, or an end time without an end date - is
 *    NOT saveable. Half an end is rejected rather than completed from the other half: there is no
 *    defensible way to guess the missing piece, and defaulting it would write an end the user never
 *    chose.
 * 2. **No end at all** - both null - IS saveable. This is the active-entry case: an entry still
 *    running is a legitimate thing to have edited, so an unset end is not an error state.
 * 3. **End complete** - saveable only if the end is strictly AFTER the start.
 *
 * The strictness in case 3 is worth knowing: an end equal to the start is rejected, so a
 * zero-length entry cannot be saved through this path. The comparison is on the full
 * [LocalDateTime], not on the time of day alone, which is what lets an entry that ends before
 * midnight and starts after it be valid across two days.
 *
 * Pure and total: it reads four values, returns a boolean, and cannot throw. The dialog uses it to
 * enable its confirm button, so a false here is a disabled button rather than a rejected save.
 *
 * @param endDate the chosen end date, or null when the entry has no end yet.
 * @param endTime the chosen end time, or null when the entry has no end yet. Must accompany
 *   [endDate] or the entry is unsaveable.
 * @return true when there is no end, or a complete end strictly after the start; false for a
 *   half-set end or a complete end at or before the start.
 */
fun canSaveEditedTimeEntry(
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
