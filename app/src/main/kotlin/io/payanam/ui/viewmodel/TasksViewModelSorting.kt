//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.ui.components.CheckmarkStatus
import io.payanam.ui.components.DayCheckmark
import java.time.LocalDate
import java.time.LocalDateTime

internal fun sortHabits(
    habits: List<Task>,
    option: HabitSortOption,
    taskCheckmarks: Map<String, List<DayCheckmark>>,
    todayStatusByTaskId: Map<String, CheckmarkStatus> = emptyMap(),
): List<Task> = when (option) {
    HabitSortOption.BY_SCORE -> habits.sortedByDescending { it.currentScore }

    HabitSortOption.BY_NAME -> habits.sortedBy { it.title.lowercase() }

    HabitSortOption.BY_STATUS -> {
        habits.sortedWith(
            compareBy<Task> { task ->
                val status = todayStatusByTaskId[task.id] ?: run {
                    val today = LocalDate.now()
                    val checkmarks = taskCheckmarks[task.id] ?: emptyList()
                    checkmarks.find { it.date == today }?.status
                }
                when (status) {
                    CheckmarkStatus.COMPLETED -> 1
                    CheckmarkStatus.SKIPPED -> 2
                    else -> 0
                }
            }.thenByDescending { it.currentScore },
        )
    }

    HabitSortOption.BY_DUE_TIME -> {
        habits.sortedWith(
            compareBy<Task> { it.dueDate?.toLocalTime() ?: java.time.LocalTime.MAX }
                .thenByDescending { it.currentScore },
        )
    }

    HabitSortOption.BY_LIFE_DIMENSION -> habits.sortedBy { it.lifeIntentionCategory ?: "zzz" }

    HabitSortOption.BY_POSITION -> habits
}

internal fun filterAndSortTasks(
    tasks: List<Task>,
    filter: TaskFilter,
    sort: TaskSortOption,
): List<Task> {
    val filtered = filterTasks(tasks, filter)
    return sortTasks(filtered, sort)
}

internal fun visibleHabitsForDisplay(
    habits: List<Task>,
    todayStatusByTaskId: Map<String, CheckmarkStatus>,
    showCompletedHabits: Boolean,
): List<Task> {
    if (showCompletedHabits) return habits
    return habits.filter { task ->
        (todayStatusByTaskId[task.id] ?: CheckmarkStatus.UNKNOWN) != CheckmarkStatus.COMPLETED
    }
}

internal fun buildTaskFilterCounts(
    oneTimeTasks: List<Task>,
    todayCount: Int,
    overdueCount: Int,
    futureCount: Int,
): TaskFilterCounts {
    val activeCount = oneTimeTasks.count { it.status == "active" || it.status == "pending" || it.status == null }
    val completedCount = oneTimeTasks.count { it.status == "completed" }
    val archivedCount = oneTimeTasks.count { it.status == "archived" }
    return TaskFilterCounts(
        all = oneTimeTasks.size,
        active = activeCount,
        today = todayCount,
        overdue = overdueCount,
        future = futureCount,
        completed = completedCount,
        archived = archivedCount,
        notActive = completedCount + archivedCount,
    )
}

private fun filterTasks(tasks: List<Task>, filter: TaskFilter): List<Task> {
    val now = LocalDateTime.now()
    val today = LocalDate.now()
    return when (filter) {
        TaskFilter.ALL -> tasks

        TaskFilter.ACTIVE -> tasks.filter {
            it.status == "active" || it.status == "pending" || it.status == null
        }

        TaskFilter.TODAY -> tasks.filter { task ->
            task.dueDate?.toLocalDate() == today &&
                task.status != "completed" && task.status != "archived"
        }

        TaskFilter.OVERDUE -> tasks.filter { task ->
            task.dueDate?.isBefore(now) == true &&
                task.status != "completed" && task.status != "archived"
        }

        TaskFilter.FUTURE -> tasks.filter { task ->
            task.status != "completed" && task.status != "archived" &&
                (task.dueDate == null || task.dueDate?.toLocalDate()?.isAfter(today) == true)
        }

        TaskFilter.COMPLETED -> tasks.filter { it.status == "completed" }

        TaskFilter.ARCHIVED -> tasks.filter { it.status == "archived" }

        TaskFilter.NOT_ACTIVE -> tasks.filter { it.status == "completed" || it.status == "archived" }
    }
}

private fun sortTasks(tasks: List<Task>, sort: TaskSortOption): List<Task> = when (sort) {
    TaskSortOption.SCORE_DESC -> tasks.sortedByDescending { it.taskScore ?: 0.0 }

    TaskSortOption.SCORE_ASC -> tasks.sortedBy { it.taskScore ?: 0.0 }

    TaskSortOption.DUE_DATE_ASC -> tasks.sortedWith(
        compareBy(nullsLast()) { it.dueDate },
    )

    TaskSortOption.DUE_DATE_DESC -> tasks.sortedWith(
        compareByDescending(nullsLast()) { it.dueDate },
    )

    TaskSortOption.TITLE_ASC -> tasks.sortedBy { it.title.lowercase() }

    TaskSortOption.TITLE_DESC -> tasks.sortedByDescending { it.title.lowercase() }

    TaskSortOption.CREATED_DESC -> tasks.sortedByDescending { it.createdAt }

    TaskSortOption.CREATED_ASC -> tasks.sortedBy { it.createdAt }

    TaskSortOption.IMPACT_DESC -> tasks.sortedByDescending { impactLevelToValue(it.impactLevel) }

    TaskSortOption.ENERGY_ASC -> tasks.sortedBy { energyLevelToValue(it.energyLevel) }

    TaskSortOption.DIMENSION -> tasks.sortedBy { it.lifeIntentionCategory }
}

private fun impactLevelToValue(level: String): Int = when (level) {
    "Minor Impact" -> 1

    "Low Impact" -> 2

    "Minimal Impact" -> 2

    "Moderate Impact" -> 3

    "High Impact" -> 4

    "Major Impact" -> 4

    "Critical Impact" -> 5

    else -> {
        if (UnifiedLogger.isInitialized()) {
            UnifiedLogger.getInstance().w(
                "TasksViewModelSorting.impactLevelToValue",
                "Unknown impact level, using default sort weight",
                mapOf("impactLevel" to level),
            )
        }
        3
    }
}

private fun energyLevelToValue(level: String): Int = when (level) {
    "Low" -> 1
    "Moderate" -> 2
    "High" -> 3
    else -> 2
}
