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
 * Room DAO for the `time_entries` table: tracked work sessions. An entry with a
 * null `endedAt` is currently running. Reads are exposed as [Flow].
 */
interface TimeEntryDao {
    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL LIMIT 1")
    /**
     * Returns the single running entry (null `endedAt`), or null when nothing
     * is being tracked.
     */
    suspend fun getActiveTimeEntry(): TimeEntryEntity?

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL LIMIT 1")
    /**
     * Emits the running entry (null `endedAt`) as a [Flow], or null.
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
     * Emits entries whose `startedAt` falls within the inclusive [start]..[end]
     * window, ordered oldest-first, as a [Flow].
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
     * Emits entries overlapping the day bounded by [dayStart]..[dayEnd]. An
     * ongoing entry (null `endedAt`) is treated as ending at [currentTime] so it
     * still counts for the current day, as a [Flow].
     */
    fun getTimeEntriesForDate(
        dayStart: String,
        dayEnd: String,
        currentTime: String,
    ): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE id = :id")
    /**
     * Returns the entry with [id], or null.
     */
    suspend fun getById(id: String): TimeEntryEntity?

    @Query("SELECT * FROM time_entries WHERE import_source = :source AND import_id = :importId LIMIT 1")
    /**
     * Returns the entry linked to an external import ([source] + [importId]),
     * or null. Used for dedupe during import.
     */
    suspend fun getByImportRef(
        source: String,
        importId: String,
    ): TimeEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a time entry.
     */
    suspend fun insert(entry: TimeEntryEntity)

    @Update
    /**
     * Updates all columns of an existing time entry.
     */
    suspend fun update(entry: TimeEntryEntity)

    @Delete
    /**
     * Deletes the given [entry] row.
     */
    suspend fun delete(entry: TimeEntryEntity)

    @Query("DELETE FROM time_entries WHERE id = :id")
    /**
     * Deletes the entry with [id].
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM time_entries")
    /**
     * Deletes every time-entry row.
     */
    suspend fun deleteAll()

    @Query("UPDATE time_entries SET taskId = NULL WHERE taskId IS NOT NULL")
    /**
     * Nulls the `taskId` link on every entry. Used when a task is deleted so
     * entries are retained as untracked-time history.
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
     * Stops a running entry: records [endedAt], the post-session focus
     * [focusRating] / [focusNote] / [focusRatedAt], and [updatedAt]. Leaving
     * `endedAt` null keeps the entry running.
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
     * Emits all entries ordered by start time (newest first) as a [Flow].
     */
    fun getAll(): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL ORDER BY startedAt DESC")
    /**
     * Emits all currently-running (null `endedAt`) entries, newest first, as a
     * [Flow].
     */
    fun getAllActiveTimeEntries(): Flow<List<TimeEntryEntity>>
}
