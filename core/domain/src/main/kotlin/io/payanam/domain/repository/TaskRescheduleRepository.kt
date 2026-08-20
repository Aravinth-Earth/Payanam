//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.TaskReschedule
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * Repository interface for Task Reschedule history.
 */
interface TaskRescheduleRepository {

    /**
     * Get all reschedule events for a task.
     */
    suspend fun getReschedulesByTaskId(taskId: String): List<TaskReschedule>

    /**
     * Observe reschedule history for a task.
     */
    fun getReschedulesForTask(taskId: String): Flow<List<TaskReschedule>>

    /**
     * Record a reschedule event.
     */
    suspend fun recordReschedule(reschedule: TaskReschedule)

    /**
     * Record a reschedule event with parameters.
     */
    suspend fun recordReschedule(
        /** Task id. */
        taskId: String,
        /** Previous due date. */
        previousDueDate: LocalDateTime,
        /** New due date. */
        newDueDate: LocalDateTime,
        /** Was overdue. */
        wasOverdue: Boolean
    ): TaskReschedule
}
