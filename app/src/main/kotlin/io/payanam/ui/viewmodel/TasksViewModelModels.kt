//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.compose.runtime.Immutable
import io.payanam.domain.model.Task
import io.payanam.ui.components.CheckmarkStatus
import io.payanam.ui.components.DayCheckmark
import java.time.LocalDate

enum class TaskFilter(val key: String) {
    ALL("all"),
    ACTIVE("active"),
    TODAY("today"),
    OVERDUE("overdue"),
    FUTURE("future"),
    COMPLETED("completed"),
    ARCHIVED("archived"),
    NOT_ACTIVE("not_active"),
    ;

    companion object {
        fun fromKey(key: String?): TaskFilter = entries.find { it.key == key } ?: TODAY
    }
}

enum class TaskSortOption(val key: String) {
    SCORE_DESC("score_desc"),
    SCORE_ASC("score_asc"),
    DUE_DATE_ASC("due_asc"),
    DUE_DATE_DESC("due_desc"),
    TITLE_ASC("title_asc"),
    TITLE_DESC("title_desc"),
    CREATED_DESC("created_desc"),
    CREATED_ASC("created_asc"),
    IMPACT_DESC("impact_desc"),
    ENERGY_ASC("energy_asc"),
    DIMENSION("dimension"),
    ;

    companion object {
        fun fromKey(key: String?): TaskSortOption = entries.find { it.key == key } ?: DUE_DATE_ASC
    }
}

enum class HabitSortOption(val key: String) {
    BY_SCORE("by_score"),
    BY_NAME("by_name"),
    BY_STATUS("by_status"),
    BY_DUE_TIME("by_due_time"),
    BY_LIFE_DIMENSION("by_life_dimension"),
    BY_POSITION("by_position"),
    ;

    companion object {
        fun fromKey(key: String?): HabitSortOption = entries.find { it.key == key } ?: BY_SCORE
    }
}

val TaskFilter.displayName: String
    get() = key

val TaskSortOption.displayName: String
    get() = key

val HabitSortOption.displayName: String
    get() = key

@Immutable
data class TaskCheckmarks(
    val taskId: String,
    val checkmarks: List<DayCheckmark>,
)

@Immutable
data class TaskFilterCounts(
    val all: Int = 0,
    val active: Int = 0,
    val today: Int = 0,
    val overdue: Int = 0,
    val future: Int = 0,
    val completed: Int = 0,
    val archived: Int = 0,
    val notActive: Int = 0,
) {
    fun countFor(filter: TaskFilter): Int = when (filter) {
        TaskFilter.ALL -> all
        TaskFilter.ACTIVE -> active
        TaskFilter.TODAY -> today
        TaskFilter.OVERDUE -> overdue
        TaskFilter.FUTURE -> future
        TaskFilter.COMPLETED -> completed
        TaskFilter.ARCHIVED -> archived
        TaskFilter.NOT_ACTIVE -> notActive
    }
}

@Immutable
data class TasksChromeUiState(
    val isLoading: Boolean = true,
    val recurringTaskCount: Int = 0,
    val oneTimeTaskCount: Int = 0,
    val habitSortOption: HabitSortOption = HabitSortOption.BY_SCORE,
    val currentSort: TaskSortOption = TaskSortOption.DUE_DATE_ASC,
    val showArchivedHabits: Boolean = false,
    val showCompletedHabits: Boolean = true,
    val hideAllMarkedToday: Boolean = false,
    val showCompletionDialog: Boolean = false,
    val completionDialogTask: Task? = null,
    val completionDialogDate: LocalDate? = null,
)

@Immutable
data class HabitsTabUiState(
    val rows: List<HabitRowUiModel> = emptyList(),
    val totalHabitCount: Int = 0,
)

@Immutable
data class TasksTabUiState(
    val rows: List<TaskRowUiModel> = emptyList(),
    val currentFilter: TaskFilter = TaskFilter.TODAY,
    val filterCounts: TaskFilterCounts = TaskFilterCounts(),
    val overdueCount: Int = 0,
)

@Immutable
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
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentFilter: TaskFilter = TaskFilter.TODAY,
    val currentSort: TaskSortOption = TaskSortOption.DUE_DATE_ASC,
    val todayCount: Int = 0,
    val overdueCount: Int = 0,
    val habitSortOption: HabitSortOption = HabitSortOption.BY_SCORE,
    val showArchivedHabits: Boolean = false,
    val showCompletedHabits: Boolean = true,
    val hideAllMarkedToday: Boolean = false,
    val showCompletionDialog: Boolean = false,
    val completionDialogTask: Task? = null,
    val completionDialogDate: LocalDate? = null,
)
