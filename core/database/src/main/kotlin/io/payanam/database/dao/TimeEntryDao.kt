//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.payanam.database.entity.TimeEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
/**
 * TimeEntryDao.
 */
interface TimeEntryDao {
    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL LIMIT 1")
    /**
     * Get active time entry.
     */
    suspend fun getActiveTimeEntry(): TimeEntryEntity?

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL LIMIT 1")
    /**
     * Observe active time entry.
     */
    fun observeActiveTimeEntry(): Flow<TimeEntryEntity?>

    @Query(
        """
        SELECT * FROM time_entries 
        WHERE datetime(startedAt) >= datetime(:start) 
        AND datetime(startedAt) <= datetime(:end)
        ORDER BY startedAt ASC
    """,
    )
    /**
     * Get time entries for range.
     */
    fun getTimeEntriesForRange(
        /** Start. */
        start: String,
        /** End. */
        end: String,
    ): Flow<List<TimeEntryEntity>>

    @Query(
        """
        SELECT * FROM time_entries 
        WHERE datetime(startedAt) < datetime(:dayEnd)
        AND datetime(COALESCE(endedAt, :currentTime)) > datetime(:dayStart)
        ORDER BY startedAt ASC
    """,
    )
    /**
     * Get time entries for date.
     */
    fun getTimeEntriesForDate(
        /** Day start. */
        dayStart: String,
        /** Day end. */
        dayEnd: String,
        /** Current time. */
        currentTime: String,
    ): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE id = :id")
    /**
     * Get by id.
     */
    suspend fun getById(id: String): TimeEntryEntity?

    @Query("SELECT * FROM time_entries WHERE import_source = :source AND import_id = :importId LIMIT 1")
    /**
     * Get by import ref.
     */
    suspend fun getByImportRef(
        /** Source. */
        source: String,
        /** Import id. */
        importId: String,
    ): TimeEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert.
     */
    suspend fun insert(entry: TimeEntryEntity)

    @Update
    /**
     * Update.
     */
    suspend fun update(entry: TimeEntryEntity)

    @Delete
    /**
     * Delete.
     */
    suspend fun delete(entry: TimeEntryEntity)

    @Query("DELETE FROM time_entries WHERE id = :id")
    /**
     * Delete by id.
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM time_entries")
    /**
     * Delete all.
     */
    suspend fun deleteAll()

    @Query("UPDATE time_entries SET taskId = NULL WHERE taskId IS NOT NULL")
    /**
     * Clear all task links.
     */
    suspend fun clearAllTaskLinks()

    @Query(
        """
        UPDATE time_entries
        SET endedAt = :endedAt,
            focusRating = :focusRating,
            focusNote = :focusNote,
            focusRatedAt = :focusRatedAt,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    /**
     * Stop entry.
     */
    suspend fun stopEntry(
        /** Id. */
        id: String,
        /** Ended at. */
        endedAt: String,
        focusRating: Double?,
        focusNote: String?,
        focusRatedAt: String?,
        /** Updated at. */
        updatedAt: String,
    )

    @Query("SELECT * FROM time_entries ORDER BY startedAt DESC")
    /**
     * Get all.
     */
    fun getAll(): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL ORDER BY startedAt DESC")
    /**
     * Get all active time entries.
     */
    fun getAllActiveTimeEntries(): Flow<List<TimeEntryEntity>>
}
