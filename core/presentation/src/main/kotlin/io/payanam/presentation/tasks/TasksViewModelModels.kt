//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("UndocumentedPublicProperty")

package io.payanam.presentation.tasks

import io.payanam.domain.model.Task
import java.time.LocalDate

/**
 * A habit's checkmark history ([checkmarks], newest first) keyed by [taskId],
 * used for the streak strip on habit cards.
 */
data class TaskCheckmarks(
    val taskId: String,
    val checkmarks: List<DayCheckmark>,
)

/**
 * Slim chrome-level slice of [TasksUiState] for the app bar / tab headers:
 * loading flag, list counts, active sort/filter/toggles, and the completion
 * dialog visibility. Recomputed on every full-state change without forcing
 * whole-list recompositions.
 */
data class TasksChromeUiState(
    val isLoading: Boolean = true,
    val recurringTaskCount: Int = 0,
    val oneTimeTaskCount: Int = 0,
    val habitSortOption: HabitSortOption = HabitSortOption.SCORE_HIGH_LOW,
    val currentSort: TaskSortOption = TaskSortOption.DUE_DATE_ASC,
    val showArchivedHabits: Boolean = false,
    val showCompletedHabits: Boolean = true,
    val hideAllMarkedToday: Boolean = false,
    val dueTodayOnly: Boolean = false,
    val showCompletionDialog: Boolean = false,
    val completionDialogTask: Task? = null,
    val completionDialogDate: LocalDate? = null,
)

/**
 * Rendered rows + total-count slice of [TasksUiState] for the Habits tab.
 */
data class HabitsTabUiState(
    val rows: List<HabitRowUiModel> = emptyList(),
    val totalHabitCount: Int = 0,
)

/**
 * Rendered rows + active filter/counts/overdue slice of [TasksUiState] for the
 * one-time Tasks tab.
 */
data class TasksTabUiState(
    val rows: List<TaskRowUiModel> = emptyList(),
    val currentFilter: TaskFilter = TaskFilter.TODAY,
    val filterCounts: TaskFilterCounts = TaskFilterCounts(),
    val overdueCount: Int = 0,
)

/**
 * Complete internal state for the Tasks/Habits screen: raw + filtered task
 * lists, shaped habit/task rows, checkmark history and today-status maps,
 * due-today flags, latest L1 summaries per habit, persisted sort/filter/
 * toggle settings, counts, and the completion-dialog payload.
 */
data class TasksUiState(
    val tasks: List<Task> = emptyList(),
    val filteredTasks: List<Task> = emptyList(),
    val filteredOneTimeTasks: List<Task> = emptyList(),
    val recurringTasks: List<Task> = emptyList(),
    val visibleRecurringTasks: List<Task> = emptyList(),
    val oneTimeTasks: List<Task> = emptyList(),
    val visibleHabitRows: List<HabitRowUiModel> = emptyList(),
    val filteredTaskRows: List<TaskRowUiModel> = emptyList(),
    val taskFilterCounts: TaskFilterCounts = TaskFilterCounts(),
    val taskCheckmarks: Map<String, List<DayCheckmark>> = emptyMap(),
    val todayHabitStatusByTaskId: Map<String, CheckmarkStatus> = emptyMap(),
    val dueTodayByTaskId: Map<String, Boolean> = emptyMap(),
    val latestL1ByHabit: Map<String, io.payanam.domain.model.HabitL1Summary> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentFilter: TaskFilter = TaskFilter.TODAY,
    val currentSort: TaskSortOption = TaskSortOption.DUE_DATE_ASC,
    val todayCount: Int = 0,
    val overdueCount: Int = 0,
    val habitSortOption: HabitSortOption = HabitSortOption.SCORE_HIGH_LOW,
    val showArchivedHabits: Boolean = false,
    val showCompletedHabits: Boolean = true,
    val hideAllMarkedToday: Boolean = false,
    val dueTodayOnly: Boolean = false,
    val showCompletionDialog: Boolean = false,
    val completionDialogTask: Task? = null,
    val completionDialogDate: LocalDate? = null,
)
