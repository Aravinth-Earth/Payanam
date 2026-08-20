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
    BY_NAME("by_name"),
    BY_NAME_REVERSE("by_name_reverse"),
    BY_DUE_TIME("by_due_time"),
    BY_DUE_TIME_REVERSE("by_due_time_reverse"),
    SCORE_HIGH_LOW("score_high_low"),
    SCORE_LOW_HIGH("score_low_high"),
    ;

    companion object {
        /** Map old (pre-simplification) keys to their new equivalent. */
        private val legacyMigration = mapOf(
            "running_avg_desc" to SCORE_HIGH_LOW,
            "running_avg_asc" to SCORE_LOW_HIGH,
            "score_desc" to SCORE_HIGH_LOW,
            "score_asc" to SCORE_LOW_HIGH,
            "progress_desc" to SCORE_HIGH_LOW,
            "progress_asc" to SCORE_LOW_HIGH,
            "streak_pos_desc" to SCORE_HIGH_LOW,
            "streak_pos_asc" to SCORE_LOW_HIGH,
            "streak_net_desc" to SCORE_HIGH_LOW,
            "streak_net_asc" to SCORE_LOW_HIGH,
            "pos_continue_desc" to SCORE_HIGH_LOW,
            "pos_continue_asc" to SCORE_LOW_HIGH,
            "by_score" to SCORE_HIGH_LOW,
            "by_status" to BY_NAME,
            "by_life_dimension" to BY_NAME,
            "by_position" to BY_NAME,
        )

        fun fromKey(key: String?): HabitSortOption {
            if (key == null) return SCORE_HIGH_LOW
            entries.find { it.key == key }?.let { return it }
            return legacyMigration[key] ?: SCORE_HIGH_LOW
        }
    }

    /** All simplified options are always visible regardless of scoring flag. */
    fun legacyCategory(): Boolean = true
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
