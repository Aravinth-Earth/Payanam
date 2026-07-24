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
interface TaskOccurrenceDao {
    @Query("SELECT * FROM task_occurrences")
    suspend fun getAllOccurrences(): List<TaskOccurrenceEntity>

    @Query("SELECT * FROM task_occurrences WHERE taskId = :taskId ORDER BY dueDate DESC")
    fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrenceEntity>>

    @Query("SELECT * FROM task_occurrences WHERE date(dueDate) = date(:date)")
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
    suspend fun getOccurrencesForTaskInRange(
        taskId: String,
        startDate: String,
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
    suspend fun getOccurrenceForTaskOnDate(
        taskId: String,
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
    suspend fun getOccurrencesForTasksInRange(
        taskIds: List<String>,
        startDate: String,
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
    suspend fun updateOccurrence(
        id: String,
        status: String,
        statusReason: String?,
        note: String?,
        completedAt: String?,
        actualCompletedAt: String?,
        actualDurationMinutes: Int?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(occurrence: TaskOccurrenceEntity)

    @Query("DELETE FROM task_occurrences WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM task_occurrences")
    suspend fun deleteAll()
}

@Dao
interface TaskRescheduleDao {
    @Query("SELECT * FROM task_reschedules")
    suspend fun getAllReschedules(): List<TaskRescheduleEntity>

    @Query("SELECT * FROM task_reschedules WHERE taskId = :taskId ORDER BY rescheduledAt DESC")
    fun getReschedulesForTask(taskId: String): Flow<List<TaskRescheduleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reschedule: TaskRescheduleEntity)

    @Query("DELETE FROM task_reschedules")
    suspend fun deleteAll()
}
