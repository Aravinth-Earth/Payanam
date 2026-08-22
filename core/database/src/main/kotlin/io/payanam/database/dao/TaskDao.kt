//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooManyFunctions")

package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.payanam.database.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
/**
 * Room DAO for the `tasks` table.
 *
 * Reads are exposed as [Flow] so UI collectors re-emit on every table change;
 * writes are single-shot suspend functions. Most queries order by `taskScore`
 * (descending) so the highest-priority task surfaces first.
 */
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY taskScore DESC, createdAt DESC")
    /**
     * Returns every task ordered by score (highest first), then creation time
     * (newest first). Emitted as a [Flow] that re-emits whenever the table changes.
     */
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY taskScore DESC")
    /**
     * Returns tasks with the given status, ordered by score descending.
     * Emitted as a [Flow] for reactive UI observation.
     */
    fun getTasksByStatus(status: String): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE status = 'pending'
        AND (
            day_key = :date
            OR (day_key IS NULL AND date(dueDate) = date(:date))
        )
        ORDER BY taskScore DESC
        """,
    )
    /**
     * Returns pending tasks due on [date].
     *
     * Matches a task when its `day_key` equals [date], or — when `day_key` is
     * null — when the calendar date parsed from `dueDate` equals [date]. This
     * lets tasks imported without an explicit day still surface on their due day.
     */
    fun getTasksDueOn(date: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    /**
     * Returns the single task with [id], or null when no row matches.
     */
    suspend fun getTaskById(id: String): TaskEntity?

    @Query("UPDATE tasks SET recurrenceRule = :rule, updatedAt = :updatedAt WHERE id = :id")
    /**
     * Replaces the `recurrenceRule` of one task and stamps [updatedAt].
     * [updatedAt] defaults to the current local timestamp when omitted.
     */
    suspend fun updateRecurrenceRule(id: String, rule: String, updatedAt: String = java.time.LocalDateTime.now().toString())

    @Query("SELECT * FROM tasks WHERE import_source = :source AND import_id = :importId LIMIT 1")
    /**
     * Returns the task linked to an external import ([source] + [importId]), or
     * null. Used to detect duplicates so re-importing the same external item
     * updates the existing row instead of creating a second one.
     */
    suspend fun getTaskByImportRef(
        source: String,
        importId: String,
    ): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE import_source = :source")
    /**
     * Counts tasks that originated from the given import [source].
     * Used during import reconciliation and cleanup.
     */
    suspend fun countByImportSource(source: String): Int

    @Query(
        """
        SELECT * FROM tasks
        WHERE status = 'pending'
        AND dueDate IS NOT NULL
        AND datetime(dueDate) < datetime(:now)
        ORDER BY dueDate ASC
    """,
    )
    /**
     * Returns pending tasks whose `dueDate` is set and already in the past
     * relative to [now], ordered oldest-due first. Emitted as a [Flow].
     */
    fun getOverdueTasks(now: String): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE (
            status = 'pending'
            AND (
                day_key = :today
                OR (day_key IS NULL AND date(dueDate) = date(:today))
            )
        )
        OR (recurrenceEnabled = 1 AND status = 'pending')
        ORDER BY taskScore DESC
    """,
    )
    /**
     * Returns today's actionable tasks for [today]: pending tasks scheduled for
     * that day (by `day_key` or, when null, by `dueDate`), plus every pending
     * task that has recurrence enabled. Ordered by score descending.
     */
    fun getTodaysTasks(today: String): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts [task], replacing any existing row with the same primary key.
     */
    suspend fun insert(task: TaskEntity)

    @Update
    /**
     * Updates all columns of an existing [task].
     */
    suspend fun update(task: TaskEntity)

    @Delete
    /**
     * Deletes the given [task] row.
     */
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    /**
     * Deletes the task with [id].
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks")
    /**
     * Deletes every task row. Wipes local task data — call only on full reset
     * or account teardown.
     */
    suspend fun deleteAll()

    @Query("UPDATE tasks SET status = :status, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :id")
    /**
     * Sets the [status] of one task and records when it was completed
     * ([completedAt], or null when moving away from done) plus [updatedAt].
     */
    suspend fun updateStatus(
        id: String,
        status: String,
        completedAt: String?,
        updatedAt: String,
    )

    @Query("UPDATE tasks SET status = :status, archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :id")
    /**
     * Sets [status] and archives the task, recording [archivedAt] and
     * [updatedAt]. Archiving hides the task from active views without deleting
     * its history.
     */
    suspend fun updateStatusWithArchive(
        id: String,
        status: String,
        archivedAt: String,
        updatedAt: String,
    )

    @Query("UPDATE tasks SET taskScore = :score, updatedAt = :updatedAt WHERE id = :id")
    /**
     * Writes the computed [score] for a task and stamps [updatedAt].
     * `taskScore` drives ordering in most read queries.
     */
    suspend fun updateTaskScore(
        id: String,
        score: Double,
        updatedAt: String,
    )

    @Query(
        """
        UPDATE tasks
        SET dimension_id = :dimensionId,
            lifeIntentionCategory = :lifeIntentionCategory,
            updatedAt = :updatedAt
        WHERE import_source = :source
    """,
    )
    /**
     * Bulk-assigns a life-dimension ([dimensionId]) and intention category
     * ([lifeIntentionCategory]) to every task from the given import [source].
     * Returns the number of rows updated. Used during import to map external
     * categories onto Payanam's dimension model.
     */
    suspend fun bulkMapImportSourceDimension(
        source: String,
        dimensionId: String,
        lifeIntentionCategory: String,
        updatedAt: String,
    ): Int

    @Query("SELECT * FROM tasks WHERE recurrenceEnabled = 1")
    /**
     * Returns all tasks that have recurrence enabled. Called by the scheduler
     * to regenerate future occurrences.
     */
    suspend fun getRecurringTasks(): List<TaskEntity>

    @Query(
        """
        UPDATE tasks SET
            dueDate = :newDueDate,
            day_key = :dayKey,
            status = 'pending',
            lastOccurrenceDate = :lastOccurrenceDate,
            updatedAt = :updatedAt
        WHERE id = :id
    """,
    )
    /**
     * Advances a recurring task to its next occurrence: sets the new [newDueDate]
     * and [dayKey], records [lastOccurrenceDate], resets [status] to pending, and
     * stamps [updatedAt].
     */
    suspend fun updateRecurrenceState(
        id: String,
        newDueDate: String,
        dayKey: String,
        lastOccurrenceDate: String,
        updatedAt: String,
    )
}
