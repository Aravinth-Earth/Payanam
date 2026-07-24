//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
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
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY taskScore DESC, createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY taskScore DESC")
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
    fun getTasksDueOn(date: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE import_source = :source AND import_id = :importId LIMIT 1")
    suspend fun getTaskByImportRef(
        source: String,
        importId: String,
    ): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE import_source = :source")
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
    fun getTodaysTasks(today: String): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()

    @Query("UPDATE tasks SET status = :status, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        completedAt: String?,
        updatedAt: String,
    )

    @Query("UPDATE tasks SET status = :status, archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatusWithArchive(
        id: String,
        status: String,
        archivedAt: String,
        updatedAt: String,
    )

    @Query("UPDATE tasks SET taskScore = :score, updatedAt = :updatedAt WHERE id = :id")
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
    suspend fun bulkMapImportSourceDimension(
        source: String,
        dimensionId: String,
        lifeIntentionCategory: String,
        updatedAt: String,
    ): Int

    @Query("SELECT * FROM tasks WHERE recurrenceEnabled = 1")
    suspend fun getRecurringTasks(): List<TaskEntity>

    @Query(
        """
        UPDATE tasks SET
            dueDate = :newDueDate,
            day_key = :dayKey,
            status = 'pending',
            currentScore = :newScore,
            lastOccurrenceDate = :lastOccurrenceDate,
            updatedAt = :updatedAt
        WHERE id = :id
    """,
    )
    suspend fun updateRecurrenceState(
        id: String,
        newDueDate: String,
        dayKey: String,
        newScore: Double,
        lastOccurrenceDate: String,
        updatedAt: String,
    )
}
