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
 * TaskDao.
 */
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY taskScore DESC, createdAt DESC")
    /**
     * Get all tasks.
     */
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY taskScore DESC")
    /**
     * Get tasks by status.
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
     * Get tasks due on.
     */
    fun getTasksDueOn(date: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    /**
     * Get task by id.
     */
    suspend fun getTaskById(id: String): TaskEntity?

    @Query("UPDATE tasks SET recurrenceRule = :rule, updatedAt = :updatedAt WHERE id = :id")
    /**
     * Update recurrence rule.
     */
    suspend fun updateRecurrenceRule(id: String, rule: String, updatedAt: String = java.time.LocalDateTime.now().toString())

    @Query("SELECT * FROM tasks WHERE import_source = :source AND import_id = :importId LIMIT 1")
    /**
     * Get task by import ref.
     */
    suspend fun getTaskByImportRef(
        source: String,
        importId: String,
    ): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE import_source = :source")
    /**
     * Count by import source.
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
     * Get overdue tasks.
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
     * Get todays tasks.
     */
    fun getTodaysTasks(today: String): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert.
     */
    suspend fun insert(task: TaskEntity)

    @Update
    /**
     * Update.
     */
    suspend fun update(task: TaskEntity)

    @Delete
    /**
     * Delete.
     */
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    /**
     * Delete by id.
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks")
    /**
     * Delete all.
     */
    suspend fun deleteAll()

    @Query("UPDATE tasks SET status = :status, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :id")
    /**
     * Update status.
     */
    suspend fun updateStatus(
        id: String,
        status: String,
        completedAt: String?,
        updatedAt: String,
    )

    @Query("UPDATE tasks SET status = :status, archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :id")
    /**
     * Update status with archive.
     */
    suspend fun updateStatusWithArchive(
        id: String,
        status: String,
        archivedAt: String,
        updatedAt: String,
    )

    @Query("UPDATE tasks SET taskScore = :score, updatedAt = :updatedAt WHERE id = :id")
    /**
     * Update task score.
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
     * Bulk map import source dimension.
     */
    suspend fun bulkMapImportSourceDimension(
        source: String,
        dimensionId: String,
        lifeIntentionCategory: String,
        updatedAt: String,
    ): Int

    @Query("SELECT * FROM tasks WHERE recurrenceEnabled = 1")
    /**
     * Get recurring tasks.
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
     * Update recurrence state.
     */
    suspend fun updateRecurrenceState(
        id: String,
        newDueDate: String,
        dayKey: String,
        lastOccurrenceDate: String,
        updatedAt: String,
    )
}
