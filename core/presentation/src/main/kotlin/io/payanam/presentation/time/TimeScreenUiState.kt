//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import java.time.LocalDate

/**
 * UI state for the Time-tracking screen: the selected date, the day's time
 * entries, the active entry, the task list/picker/planned tasks, past
 * occurrences, load/readiness flags, and any error.
 *
 * This is the whole state contract between the screen's ViewModel and whatever renders it, and it
 * lives here rather than next to the ViewModel so that the desktop renders from exactly the state the
 * Android ViewModel produces instead of a second, hand-written shape that can drift.
 *
 * It is a `data class` deliberately - the generated `copy` and structural equality are load-bearing
 * here, not a convenience:
 *
 *  - The ViewModel updates it exclusively through `copy`, and those call sites are the update
 *    mechanism for the screen; a private `copy` here would mean rewriting every one of them.
 *  - It is carried in a `StateFlow`, which only re-emits when the new value is not `equals` to the
 *    current one. The screen keys its effects on these fields (`uiState.selectedDate`,
 *    `uiState.timeEntries`, `uiState.pastOccurrences`), so the value semantics are what stop a
 *    no-op update from either skipping a needed recomposition or firing a pointless one.
 */
data class TimeScreenUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val timeEntries: List<TimeEntry> = emptyList(),
    val activeEntry: TimeEntry? = null,
    val tasks: List<Task> = emptyList(),
    val taskPickerTasks: List<Task> = emptyList(),
    val plannedTasks: List<Task> = emptyList(),
    val pastOccurrences: List<TaskOccurrence> = emptyList(),
    val lastEntry: TimeEntry? = null,
    val isLoading: Boolean = true,
    val isDateContentReady: Boolean = false,
    val error: String? = null,
)
