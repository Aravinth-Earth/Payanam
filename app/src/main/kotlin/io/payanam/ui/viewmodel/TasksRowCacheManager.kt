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
 * HabitRowUiModel.
 */
data class HabitRowUiModel(
    /** Id. */
    val id: String,
    /** Task. */
    val task: Task,
    /** Checkmarks. */
    val checkmarks: List<DayCheckmark>,
    /** Today status. */
    val todayStatus: CheckmarkStatus,
    /** Fingerprint. */
    val fingerprint: Int,
    /** Latest l1. */
    val latestL1: io.payanam.domain.model.HabitL1Summary? = null,
)

@Immutable
/**
 * TaskRowUiModel.
 */
data class TaskRowUiModel(
    /** Id. */
    val id: String,
    /** Task. */
    val task: Task,
    /** Fingerprint. */
    val fingerprint: Int,
)

internal object TasksRowCacheManager {
    private val logger = UnifiedLogger.getInstance()
    private val habitRowsById = linkedMapOf<String, HabitRowUiModel>()
    private val taskRowsById = linkedMapOf<String, TaskRowUiModel>()

    @Synchronized
    /**
     * Build habit rows.
     */
    fun buildHabitRows(
        tasks: List<Task>,
        checkmarksByTaskId: Map<String, List<DayCheckmark>>,
        todayStatusByTaskId: Map<String, CheckmarkStatus>,
        /** Show completed habits. */
        showCompletedHabits: Boolean,
        hideAllMarkedToday: Boolean = false,
        dueTodayOnly: Boolean = false,
        dueTodayByTaskId: Map<String, Boolean> = emptyMap(),
        latestL1ByHabit: Map<String, io.payanam.domain.model.HabitL1Summary> = emptyMap(),
    ): List<HabitRowUiModel> {
        /** Active task ids. */
        val activeTaskIds = tasks.map { it.id }.toSet()
        habitRowsById.keys.retainAll(activeTaskIds)
        /** Rows. */
        val rows = ArrayList<HabitRowUiModel>(tasks.size)
        /** Rebuilt rows. */
        var rebuiltRows = 0
        tasks.forEach { task ->
            /** Checkmarks. */
            val checkmarks = checkmarksByTaskId[task.id] ?: emptyList()
            /** Today status. */
            val todayStatus = todayStatusByTaskId[task.id] ?: CheckmarkStatus.UNKNOWN
            /** Latest l1. */
            val latestL1 = latestL1ByHabit[task.id]
            /** Should hide. */
            val shouldHide = when {
                dueTodayOnly && dueTodayByTaskId[task.id] == false -> true
                hideAllMarkedToday -> todayStatus in setOf(CheckmarkStatus.COMPLETED, CheckmarkStatus.SKIPPED, CheckmarkStatus.MISSED)
                !showCompletedHabits -> todayStatus == CheckmarkStatus.COMPLETED
                else -> false
            }
            /** If. */
            if (shouldHide) {
                return@forEach
            }
            /** Fingerprint. */
            val fingerprint = habitFingerprint(task, checkmarks, todayStatus, latestL1?.runningAvg)
            /** Existing. */
            val existing = habitRowsById[task.id]
            /** Row. */
            val row = if (existing != null && existing.fingerprint == fingerprint) {
                /** Existing. */
                existing
            } else {
                rebuiltRows += 1
                /** Habit row ui model. */
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
            /** Map of. */
            mapOf("total" to rows.size, "rebuiltRows" to rebuiltRows, "showCompletedHabits" to showCompletedHabits),
        )
        return rows
    }

    @Synchronized
    /**
     * Build task rows.
     */
    fun buildTaskRows(tasks: List<Task>): List<TaskRowUiModel> {
        /** Rows. */
        val rows = ArrayList<TaskRowUiModel>(tasks.size)
        /** Rebuilt rows. */
        var rebuiltRows = 0
        tasks.forEach { task ->
            /** Fingerprint. */
            val fingerprint = taskFingerprint(task)
            /** Existing. */
            val existing = taskRowsById[task.id]
            /** Row. */
            val row = if (existing != null && existing.fingerprint == fingerprint) {
                /** Existing. */
                existing
            } else {
                rebuiltRows += 1
                /** Task row ui model. */
                TaskRowUiModel(id = task.id, task = task, fingerprint = fingerprint)
            }
            taskRowsById[task.id] = row
            rows.add(row)
        }
        logger.d(
            "TasksRowCacheManager.buildTaskRows",
            "Built task rows from cache",
            /** Map of. */
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
