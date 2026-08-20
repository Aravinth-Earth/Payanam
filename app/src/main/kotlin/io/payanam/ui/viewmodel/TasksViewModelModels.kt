//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("UndocumentedPublicProperty")

package io.payanam.ui.viewmodel

import androidx.compose.runtime.Immutable
import io.payanam.domain.model.Task
import io.payanam.ui.components.CheckmarkStatus
import io.payanam.ui.components.DayCheckmark
import java.time.LocalDate

/**
 * TaskFilter.
 */
enum class TaskFilter(val key: String) {
    /** All. */
    ALL("all"),
    /** Active. */
    ACTIVE("active"),
    /** Today. */
    TODAY("today"),
    /** Overdue. */
    OVERDUE("overdue"),
    /** Future. */
    FUTURE("future"),
    /** Completed. */
    COMPLETED("completed"),
    /** Archived. */
    ARCHIVED("archived"),
    /** Not active. */
    NOT_ACTIVE("not_active"),
    ;

    companion object {
        /**
         * From key.
         */
        fun fromKey(key: String?): TaskFilter = entries.find { it.key == key } ?: TODAY
    }
}

/**
 * TaskSortOption.
 */
enum class TaskSortOption(val key: String) {
    /** Score desc. */
    SCORE_DESC("score_desc"),
    /** Score asc. */
    SCORE_ASC("score_asc"),
    /** Due date asc. */
    DUE_DATE_ASC("due_asc"),
    /** Due date desc. */
    DUE_DATE_DESC("due_desc"),
    /** Title asc. */
    TITLE_ASC("title_asc"),
    /** Title desc. */
    TITLE_DESC("title_desc"),
    /** Created desc. */
    CREATED_DESC("created_desc"),
    /** Created asc. */
    CREATED_ASC("created_asc"),
    /** Impact desc. */
    IMPACT_DESC("impact_desc"),
    /** Energy asc. */
    ENERGY_ASC("energy_asc"),
    /** Dimension. */
    DIMENSION("dimension"),
    ;

    companion object {
        /**
         * From key.
         */
        fun fromKey(key: String?): TaskSortOption = entries.find { it.key == key } ?: DUE_DATE_ASC
    }
}

/**
 * HabitSortOption.
 */
enum class HabitSortOption(val key: String) {
    /** By name. */
    BY_NAME("by_name"),
    /** By name reverse. */
    BY_NAME_REVERSE("by_name_reverse"),
    /** By due time. */
    BY_DUE_TIME("by_due_time"),
    /** By due time reverse. */
    BY_DUE_TIME_REVERSE("by_due_time_reverse"),
    /** Score high low. */
    SCORE_HIGH_LOW("score_high_low"),
    /** Score low high. */
    SCORE_LOW_HIGH("score_low_high"),
    ;

    companion object {
        /**
         * Resolves a stored sort key (from preferences) back to a [HabitSortOption].
         *
         * New keys map directly. Legacy keys from the pre-simplification 18-option
         * model (metric sorts, by_status, by_life_dimension, by_position) are mapped
         * to the nearest simplified equivalent so previously saved preferences keep
         * working. Unknown or null keys fall back to [SCORE_HIGH_LOW].
         */
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

        /**
         * From key.
         */
        fun fromKey(key: String?): HabitSortOption {
            /** If. */
            if (key == null) return SCORE_HIGH_LOW
            entries.find { it.key == key }?.let { return it }
            return legacyMigration[key] ?: SCORE_HIGH_LOW
        }
    }

    /** All simplified options are always visible regardless of scoring flag. */
    fun legacyCategory(): Boolean = true
}

val TaskFilter.displayName: String
    /** Get. */
    get() = key

val TaskSortOption.displayName: String
    /** Get. */
    get() = key

val HabitSortOption.displayName: String
    /** Get. */
    get() = key

@Immutable
/**
 * TaskCheckmarks.
 */
data class TaskCheckmarks(
    /** Task id. */
    val taskId: String,
    /** Checkmarks. */
    val checkmarks: List<DayCheckmark>,
)

@Immutable
/**
 * TaskFilterCounts.
 */
data class TaskFilterCounts(
    /** All. */
    val all: Int = 0,
    /** Active. */
    val active: Int = 0,
    /** Today. */
    val today: Int = 0,
    /** Overdue. */
    val overdue: Int = 0,
    /** Future. */
    val future: Int = 0,
    /** Completed. */
    val completed: Int = 0,
    /** Archived. */
    val archived: Int = 0,
    /** Not active. */
    val notActive: Int = 0,
) {
    /**
     * Count for.
     */
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
/**
 * TasksChromeUiState.
 */
data class TasksChromeUiState(
    /** Is loading. */
    val isLoading: Boolean = true,
    /** Recurring task count. */
    val recurringTaskCount: Int = 0,
    /** One time task count. */
    val oneTimeTaskCount: Int = 0,
    /** Habit sort option. */
    val habitSortOption: HabitSortOption = HabitSortOption.SCORE_HIGH_LOW,
    /** Current sort. */
    val currentSort: TaskSortOption = TaskSortOption.DUE_DATE_ASC,
    /** Show archived habits. */
    val showArchivedHabits: Boolean = false,
    /** Show completed habits. */
    val showCompletedHabits: Boolean = true,
    /** Hide all marked today. */
    val hideAllMarkedToday: Boolean = false,
    /** Due today only. */
    val dueTodayOnly: Boolean = false,
    /** Show completion dialog. */
    val showCompletionDialog: Boolean = false,
    /** Completion dialog task. */
    val completionDialogTask: Task? = null,
    /** Completion dialog date. */
    val completionDialogDate: LocalDate? = null,
)

@Immutable
/**
 * HabitsTabUiState.
 */
data class HabitsTabUiState(
    /** Rows. */
    val rows: List<HabitRowUiModel> = emptyList(),
    /** Total habit count. */
    val totalHabitCount: Int = 0,
)

@Immutable
/**
 * TasksTabUiState.
 */
data class TasksTabUiState(
    /** Rows. */
    val rows: List<TaskRowUiModel> = emptyList(),
    /** Current filter. */
    val currentFilter: TaskFilter = TaskFilter.TODAY,
    /** Filter counts. */
    val filterCounts: TaskFilterCounts = TaskFilterCounts(),
    /** Overdue count. */
    val overdueCount: Int = 0,
)

@Immutable
/**
 * TasksUiState.
 */
data class TasksUiState(
    /** Tasks. */
    val tasks: List<Task> = emptyList(),
    /** Filtered tasks. */
    val filteredTasks: List<Task> = emptyList(),
    /** Filtered one time tasks. */
    val filteredOneTimeTasks: List<Task> = emptyList(),
    /** Recurring tasks. */
    val recurringTasks: List<Task> = emptyList(),
    /** Visible recurring tasks. */
    val visibleRecurringTasks: List<Task> = emptyList(),
    /** One time tasks. */
    val oneTimeTasks: List<Task> = emptyList(),
    /** Visible habit rows. */
    val visibleHabitRows: List<HabitRowUiModel> = emptyList(),
    /** Filtered task rows. */
    val filteredTaskRows: List<TaskRowUiModel> = emptyList(),
    /** Task filter counts. */
    val taskFilterCounts: TaskFilterCounts = TaskFilterCounts(),
    /** Task checkmarks. */
    val taskCheckmarks: Map<String, List<DayCheckmark>> = emptyMap(),
    /** Today habit status by task id. */
    val todayHabitStatusByTaskId: Map<String, CheckmarkStatus> = emptyMap(),
    /** Due today by task id. */
    val dueTodayByTaskId: Map<String, Boolean> = emptyMap(),
    /** Latest l1by habit. */
    val latestL1ByHabit: Map<String, io.payanam.domain.model.HabitL1Summary> = emptyMap(),
    /** Is loading. */
    val isLoading: Boolean = true,
    /** Error. */
    val error: String? = null,
    /** Current filter. */
    val currentFilter: TaskFilter = TaskFilter.TODAY,
    /** Current sort. */
    val currentSort: TaskSortOption = TaskSortOption.DUE_DATE_ASC,
    /** Today count. */
    val todayCount: Int = 0,
    /** Overdue count. */
    val overdueCount: Int = 0,
    /** Habit sort option. */
    val habitSortOption: HabitSortOption = HabitSortOption.SCORE_HIGH_LOW,
    /** Show archived habits. */
    val showArchivedHabits: Boolean = false,
    /** Show completed habits. */
    val showCompletedHabits: Boolean = true,
    /** Hide all marked today. */
    val hideAllMarkedToday: Boolean = false,
    /** Due today only. */
    val dueTodayOnly: Boolean = false,
    /** Show completion dialog. */
    val showCompletionDialog: Boolean = false,
    /** Completion dialog task. */
    val completionDialogTask: Task? = null,
    /** Completion dialog date. */
    val completionDialogDate: LocalDate? = null,
)
