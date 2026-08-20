//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.compose.runtime.Immutable
import io.payanam.domain.model.Task

@Immutable
internal data class PreparedTaskState(
    /** Filtered tasks. */
    val filteredTasks: List<Task>,
    /** Filtered one time tasks. */
    val filteredOneTimeTasks: List<Task>,
    /** Recurring tasks. */
    val recurringTasks: List<Task>,
    /** Visible recurring tasks. */
    val visibleRecurringTasks: List<Task>,
    /** Visible habit rows. */
    val visibleHabitRows: List<HabitRowUiModel>,
    /** Filtered task rows. */
    val filteredTaskRows: List<TaskRowUiModel>,
    /** Filter counts. */
    val filterCounts: TaskFilterCounts,
)
