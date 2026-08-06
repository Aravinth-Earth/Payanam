//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.compose.runtime.Immutable
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.ui.components.CheckmarkStatus
import io.payanam.ui.components.DayCheckmark

@Immutable
data class HabitRowUiModel(
    val id: String,
    val task: Task,
    val checkmarks: List<DayCheckmark>,
    val todayStatus: CheckmarkStatus,
    val fingerprint: Int,
)

@Immutable
data class TaskRowUiModel(
    val id: String,
    val task: Task,
    val fingerprint: Int,
)

internal object TasksRowCacheManager {
    private val logger = UnifiedLogger.getInstance()
    private val habitRowsById = linkedMapOf<String, HabitRowUiModel>()
    private val taskRowsById = linkedMapOf<String, TaskRowUiModel>()

    @Synchronized
    fun buildHabitRows(
        tasks: List<Task>,
        checkmarksByTaskId: Map<String, List<DayCheckmark>>,
        todayStatusByTaskId: Map<String, CheckmarkStatus>,
        showCompletedHabits: Boolean,
        hideAllMarkedToday: Boolean = false,
    ): List<HabitRowUiModel> {
        val activeTaskIds = tasks.map { it.id }.toSet()
        habitRowsById.keys.retainAll(activeTaskIds)
        val rows = ArrayList<HabitRowUiModel>(tasks.size)
        var rebuiltRows = 0
        tasks.forEach { task ->
            val checkmarks = checkmarksByTaskId[task.id] ?: emptyList()
            val todayStatus = todayStatusByTaskId[task.id] ?: CheckmarkStatus.UNKNOWN
            val shouldHide = when {
                hideAllMarkedToday -> todayStatus in setOf(CheckmarkStatus.COMPLETED, CheckmarkStatus.SKIPPED, CheckmarkStatus.MISSED)
                !showCompletedHabits -> todayStatus == CheckmarkStatus.COMPLETED
                else -> false
            }
            if (shouldHide) {
                return@forEach
            }
            val fingerprint = habitFingerprint(task, checkmarks, todayStatus)
            val existing = habitRowsById[task.id]
            val row = if (existing != null && existing.fingerprint == fingerprint) {
                existing
            } else {
                rebuiltRows += 1
                HabitRowUiModel(
                    id = task.id,
                    task = task,
                    checkmarks = checkmarks,
                    todayStatus = todayStatus,
                    fingerprint = fingerprint,
                )
            }
            habitRowsById[task.id] = row
            rows.add(row)
        }
        logger.d(
            "TasksRowCacheManager.buildHabitRows",
            "Built habit rows from cache",
            mapOf("total" to rows.size, "rebuiltRows" to rebuiltRows, "showCompletedHabits" to showCompletedHabits),
        )
        return rows
    }

    @Synchronized
    fun buildTaskRows(tasks: List<Task>): List<TaskRowUiModel> {
        val rows = ArrayList<TaskRowUiModel>(tasks.size)
        var rebuiltRows = 0
        tasks.forEach { task ->
            val fingerprint = taskFingerprint(task)
            val existing = taskRowsById[task.id]
            val row = if (existing != null && existing.fingerprint == fingerprint) {
                existing
            } else {
                rebuiltRows += 1
                TaskRowUiModel(id = task.id, task = task, fingerprint = fingerprint)
            }
            taskRowsById[task.id] = row
            rows.add(row)
        }
        logger.d(
            "TasksRowCacheManager.buildTaskRows",
            "Built task rows from cache",
            mapOf("total" to rows.size, "rebuiltRows" to rebuiltRows),
        )
        return rows
    }

    private fun taskFingerprint(task: Task): Int = arrayOf<Any?>(
        task.id,
        task.title,
        task.description,
        task.status,
        task.dueDate?.toString(),
        task.taskScore,
        task.currentScore,
        task.lifeIntentionCategory,
        task.recurrenceEnabled,
    ).contentHashCode()

    private fun habitFingerprint(task: Task, checkmarks: List<DayCheckmark>, todayStatus: CheckmarkStatus): Int = 31 * taskFingerprint(task) + 17 * checkmarks.hashCode() + todayStatus.hashCode()
}
