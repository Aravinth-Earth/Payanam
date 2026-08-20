//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.entity.TaskRescheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
/**
 * TaskOccurrenceDao.
 */
interface TaskOccurrenceDao {
    @Query("SELECT * FROM task_occurrences")
    /**
     * Get all occurrences.
     */
    suspend fun getAllOccurrences(): List<TaskOccurrenceEntity>

    @Query("SELECT COUNT(*) FROM task_occurrences")
    /**
     * Count all occurrences.
     */
    suspend fun countAllOccurrences(): Int

    @Query("SELECT * FROM task_occurrences WHERE taskId = :taskId ORDER BY dueDate ASC")
    /**
     * Get occurrences for task for backfill.
     */
    suspend fun getOccurrencesForTaskForBackfill(taskId: String): List<TaskOccurrenceEntity>

    @Query("SELECT * FROM task_occurrences WHERE taskId = :taskId ORDER BY dueDate DESC")
    /**
     * Get occurrences for task.
     */
    fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrenceEntity>>

    @Query("SELECT * FROM task_occurrences WHERE date(dueDate) = date(:date)")
    /**
     * Get occurrences for date.
     */
    fun getOccurrencesForDate(date: String): Flow<List<TaskOccurrenceEntity>>

    /**
     * Get occurrences for a task within a date range.
     * Used for building checkmark grids in habit view.
     */
    @Query(
        """
        SELECT * FROM task_occurrences 
        WHERE taskId = :taskId 
        AND date(dueDate) >= date(:startDate) 
        AND date(dueDate) <= date(:endDate)
        ORDER BY dueDate DESC
    """,
    )
    /**
     * Get occurrences for task in range.
     */
    suspend fun getOccurrencesForTaskInRange(
        /** Task id. */
        taskId: String,
        /** Start date. */
        startDate: String,
        /** End date. */
        endDate: String,
    ): List<TaskOccurrenceEntity>

    /**
     * Get occurrence for a specific task and date.
     */
    @Query(
        """
        SELECT * FROM task_occurrences 
        WHERE taskId = :taskId 
        AND date(dueDate) = date(:date)
        LIMIT 1
    """,
    )
    /**
     * Get occurrence for task on date.
     */
    suspend fun getOccurrenceForTaskOnDate(
        /** Task id. */
        taskId: String,
        /** Date. */
        date: String,
    ): TaskOccurrenceEntity?

    /**
     * Get occurrences for multiple tasks within a date range.
     * Used for building checkmark grids in habit view - optimized for bulk loading.
     */
    @Query(
        """
        SELECT * FROM task_occurrences 
        WHERE taskId IN (:taskIds) 
        AND date(dueDate) >= date(:startDate) 
        AND date(dueDate) <= date(:endDate)
        ORDER BY taskId, dueDate DESC
    """,
    )
    /**
     * Get occurrences for tasks in range.
     */
    suspend fun getOccurrencesForTasksInRange(
        taskIds: List<String>,
        /** Start date. */
        startDate: String,
        /** End date. */
        endDate: String,
    ): List<TaskOccurrenceEntity>

    /**
     * Update an existing occurrence status and note.
     */
    @Query(
        """
        UPDATE task_occurrences 
        SET status = :status, statusReason = :statusReason, note = :note, completedAt = :completedAt,
            actualCompletedAt = :actualCompletedAt, actualDurationMinutes = :actualDurationMinutes
        WHERE id = :id
    """,
    )
    /**
     * Update occurrence.
     */
    suspend fun updateOccurrence(
        /** Id. */
        id: String,
        /** Status. */
        status: String,
        statusReason: String?,
        note: String?,
        completedAt: String?,
        actualCompletedAt: String?,
        actualDurationMinutes: Int?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert.
     */
    suspend fun insert(occurrence: TaskOccurrenceEntity)

    @Query("DELETE FROM task_occurrences WHERE id = :id")
    /**
     * Delete by id.
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM task_occurrences")
    /**
     * Delete all.
     */
    suspend fun deleteAll()
}

@Dao
/**
 * TaskRescheduleDao.
 */
interface TaskRescheduleDao {
    @Query("SELECT * FROM task_reschedules")
    /**
     * Get all reschedules.
     */
    suspend fun getAllReschedules(): List<TaskRescheduleEntity>

    @Query("SELECT * FROM task_reschedules WHERE taskId = :taskId ORDER BY rescheduledAt DESC")
    /**
     * Get reschedules for task.
     */
    fun getReschedulesForTask(taskId: String): Flow<List<TaskRescheduleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert.
     */
    suspend fun insert(reschedule: TaskRescheduleEntity)

    @Query("DELETE FROM task_reschedules")
    /**
     * Delete all.
     */
    suspend fun deleteAll()
}
