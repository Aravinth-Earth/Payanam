//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.compose.runtime.Immutable
import io.payanam.domain.model.Task

@Immutable
internal data class PreparedTaskState(
    val filteredTasks: List<Task>,
    val filteredOneTimeTasks: List<Task>,
    val recurringTasks: List<Task>,
    val visibleRecurringTasks: List<Task>,
    val visibleHabitRows: List<HabitRowUiModel>,
    val filteredTaskRows: List<TaskRowUiModel>,
    val filterCounts: TaskFilterCounts,
)
