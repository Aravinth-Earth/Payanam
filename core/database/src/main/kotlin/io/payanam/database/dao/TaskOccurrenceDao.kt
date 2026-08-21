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
 * Room DAO for the `task_occurrences` table: the per-day instances of a task
 * (one row per scheduled day), tracking completion status and actuals. Read
 * methods are [Flow]; writes are single-shot.
 */
interface TaskOccurrenceDao {
    @Query("SELECT * FROM task_occurrences")
    /**
     * Returns every occurrence row across all tasks.
     */
    suspend fun getAllOccurrences(): List<TaskOccurrenceEntity>

    @Query("SELECT COUNT(*) FROM task_occurrences")
    /**
     * Returns the total number of occurrence rows.
     */
    suspend fun countAllOccurrences(): Int

    @Query("SELECT * FROM task_occurrences WHERE taskId = :taskId ORDER BY dueDate ASC")
    /**
     * Returns all occurrences for [taskId] ordered oldest-due-first. Used by
     * the backfill process that recomputes past completion state.
     */
    suspend fun getOccurrencesForTaskForBackfill(taskId: String): List<TaskOccurrenceEntity>

    @Query("SELECT * FROM task_occurrences WHERE taskId = :taskId ORDER BY dueDate DESC")
    /**
     * Emits all occurrences for [taskId], newest-due-first, as a [Flow].
     */
    fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrenceEntity>>

    @Query("SELECT * FROM task_occurrences WHERE date(dueDate) = date(:date)")
    /**
     * Emits occurrences whose `dueDate` falls on [date], as a [Flow].
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
     * Returns occurrences for [taskId] within the inclusive [startDate]..[endDate]
     * window, newest-first. Backs the habit-view checkmark grid.
     */
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
    /**
     * Returns the single occurrence for [taskId] on [date], or null.
     */
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
    /**
     * Returns occurrences for all [taskIds] within the inclusive window,
     * ordered by task then newest-due-first. Bulk variant for rendering many
     * habit grids at once.
     */
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
    /**
     * Records the outcome of an occurrence: [status] (done/skipped/partial),
     * its [statusReason] and [note], plus the actual completion time and
     * duration. Updates the row with [id].
     */
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
    /**
     * Inserts or replaces an occurrence row.
     */
    suspend fun insert(occurrence: TaskOccurrenceEntity)

    @Query("DELETE FROM task_occurrences WHERE id = :id")
    /**
     * Deletes the occurrence with [id].
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM task_occurrences")
    /**
     * Deletes every occurrence row.
     */
    suspend fun deleteAll()
}

@Dao
/**
 * Room DAO for the `task_reschedules` audit table: one row per time a task's
 * schedule was pushed, keeping a history of reschedule events.
 */
interface TaskRescheduleDao {
    @Query("SELECT * FROM task_reschedules")
    /**
     * Returns every reschedule record.
     */
    suspend fun getAllReschedules(): List<TaskRescheduleEntity>

    @Query("SELECT * FROM task_reschedules WHERE taskId = :taskId ORDER BY rescheduledAt DESC")
    /**
     * Emits the reschedule history for [taskId], newest-first, as a [Flow].
     */
    fun getReschedulesForTask(taskId: String): Flow<List<TaskRescheduleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a reschedule record.
     */
    suspend fun insert(reschedule: TaskRescheduleEntity)

    @Query("DELETE FROM task_reschedules")
    /**
     * Deletes every reschedule record.
     */
    suspend fun deleteAll()
}
