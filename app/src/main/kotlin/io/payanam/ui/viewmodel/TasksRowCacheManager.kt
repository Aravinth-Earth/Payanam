//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import androidx.compose.runtime.Immutable
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.ui.components.CheckmarkStatus
import io.payanam.ui.components.DayCheckmark

@Immutable
/**
 * Rendered habit row kept stable across recompositions via [fingerprint].
 */
data class HabitRowUiModel(
    val id: String,
    val task: Task,
    val checkmarks: List<DayCheckmark>,
    val todayStatus: CheckmarkStatus,
    val fingerprint: Int,
    val latestL1: io.payanam.domain.model.HabitL1Summary? = null,
)

@Immutable
/**
 * Rendered one-time task row kept stable across recompositions via
 * [fingerprint].
 */
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
    /**
     * Builds (or reuses cached) habit rows, applying the visibility filters
     * and evicting entries for tasks no longer present.
     */
    fun buildHabitRows(
        tasks: List<Task>,
        checkmarksByTaskId: Map<String, List<DayCheckmark>>,
        todayStatusByTaskId: Map<String, CheckmarkStatus>,
        showCompletedHabits: Boolean,
        hideAllMarkedToday: Boolean = false,
        dueTodayOnly: Boolean = false,
        dueTodayByTaskId: Map<String, Boolean> = emptyMap(),
        latestL1ByHabit: Map<String, io.payanam.domain.model.HabitL1Summary> = emptyMap(),
    ): List<HabitRowUiModel> {
        val activeTaskIds = tasks.map { it.id }.toSet()
        habitRowsById.keys.retainAll(activeTaskIds)
        val rows = ArrayList<HabitRowUiModel>(tasks.size)
        var rebuiltRows = 0
        tasks.forEach { task ->
            val checkmarks = checkmarksByTaskId[task.id] ?: emptyList()
            val todayStatus = todayStatusByTaskId[task.id] ?: CheckmarkStatus.UNKNOWN
            val latestL1 = latestL1ByHabit[task.id]
            val shouldHide = when {
                dueTodayOnly && dueTodayByTaskId[task.id] == false -> true
                hideAllMarkedToday -> todayStatus in setOf(CheckmarkStatus.COMPLETED, CheckmarkStatus.SKIPPED, CheckmarkStatus.MISSED)
                !showCompletedHabits -> todayStatus == CheckmarkStatus.COMPLETED
                else -> false
            }
            if (shouldHide) {
                return@forEach
            }
            val fingerprint = habitFingerprint(task, checkmarks, todayStatus, latestL1?.runningAvg)
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
                    latestL1 = latestL1,
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
    /**
     * Builds (or reuses cached) one-time task rows keyed by fingerprint.
     */
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
        task.lifeIntentionCategory,
        task.recurrenceEnabled,
    ).contentHashCode()

    private fun habitFingerprint(task: Task, checkmarks: List<DayCheckmark>, todayStatus: CheckmarkStatus, latestL1RunningAvg: Double? = null): Int = 31 * taskFingerprint(task) + 17 * checkmarks.hashCode() + todayStatus.hashCode() + (latestL1RunningAvg?.hashCode() ?: 0)
}
