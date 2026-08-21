//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for Task operations.
 * Implementation provided by database module.
 */
interface TaskRepository {
    
    /**
     * Get all tasks as a flow for reactive updates.
     */
    fun getAllTasks(): Flow<List<Task>>
    
    /**
     * Get tasks filtered by status.
     */
    fun getTasksByStatus(status: String): Flow<List<Task>>
    
    /**
     * Get tasks due on a specific date.
     */
    fun getTasksDueOn(date: java.time.LocalDate): Flow<List<Task>>
    
    /**
     * Get a single task by ID.
     */
    suspend fun getTaskById(id: String): Task?
    
    /**
     * Create a new task. returns the created task with generated ID.
     */
    suspend fun createTask(input: TaskInput): Task
    
    /**
     * Update an existing task.
     */
    suspend fun updateTask(id: String, input: TaskInput): Task
    /**
     * Updates the update task score.
     */
    suspend fun updateTaskScore(id: String, score: Double)
    
    /**
     * Delete a task.
     */
    suspend fun deleteTask(id: String)
    
    /**
     * Mark a task as completed.
     */
    suspend fun completeTask(id: String, note: String? = null): Task
    
    /**
     * Mark a task as skipped.
     */
    suspend fun skipTask(id: String, note: String? = null): Task
    
    /**
     * Mark a task as missed.
     */
    suspend fun missTask(id: String, note: String? = null): Task
    
    /**
     * Archive a task.
     */
    suspend fun archiveTask(id: String): Task
    
    /**
     * Get overdue tasks (due date in the past, not completed).
     */
    fun getOverdueTasks(): Flow<List<Task>>
    
    /**
     * Get tasks for today.
     */
    fun getTodaysTasks(): Flow<List<Task>>
    
    /**
     * Get all recurring tasks (recurrenceEnabled = true).
     */
    suspend fun getRecurringTasks(): List<Task>
    
    /**
     * Update recurrence state after completion/skip/miss or auto-advance.
     * Sets new due date and records last occurrence (Inc 4b: decay score removed).
     */
    suspend fun updateRecurrenceState(
        taskId: String,
        newDueDate: java.time.LocalDateTime,
        lastOccurrenceDate: java.time.LocalDateTime
    )
}
