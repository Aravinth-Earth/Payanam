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
 * Defines the contract for time entry dao.
 */
interface TimeEntryDao {
    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL LIMIT 1")
    /**
     * Returns the active time entry.
     */
    suspend fun getActiveTimeEntry(): TimeEntryEntity?

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL LIMIT 1")
    /**
     * Registers the observe active time entry.
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
     * Returns the time entries for range.
     */
    fun getTimeEntriesForRange(
        start: String,
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
     * Returns the time entries for date.
     */
    fun getTimeEntriesForDate(
        dayStart: String,
        dayEnd: String,
        currentTime: String,
    ): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE id = :id")
    /**
     * Returns the by id.
     */
    suspend fun getById(id: String): TimeEntryEntity?

    @Query("SELECT * FROM time_entries WHERE import_source = :source AND import_id = :importId LIMIT 1")
    /**
     * Returns the by import ref.
     */
    suspend fun getByImportRef(
        source: String,
        importId: String,
    ): TimeEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert.
     */
    suspend fun insert(entry: TimeEntryEntity)

    @Update
    /**
     * Updates the update.
     */
    suspend fun update(entry: TimeEntryEntity)

    @Delete
    /**
     * Removes the delete.
     */
    suspend fun delete(entry: TimeEntryEntity)

    @Query("DELETE FROM time_entries WHERE id = :id")
    /**
     * Removes the delete by id.
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM time_entries")
    /**
     * Removes the delete all.
     */
    suspend fun deleteAll()

    @Query("UPDATE time_entries SET taskId = NULL WHERE taskId IS NOT NULL")
    /**
     * Removes the clear all task links.
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
     * Performs the stop entry.
     */
    suspend fun stopEntry(
        id: String,
        endedAt: String,
        focusRating: Double?,
        focusNote: String?,
        focusRatedAt: String?,
        updatedAt: String,
    )

    @Query("SELECT * FROM time_entries ORDER BY startedAt DESC")
    /**
     * Returns the all.
     */
    fun getAll(): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL ORDER BY startedAt DESC")
    /**
     * Returns the all active time entries.
     */
    fun getAllActiveTimeEntries(): Flow<List<TimeEntryEntity>>
}
