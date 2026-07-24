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
interface TimeEntryDao {
    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL LIMIT 1")
    suspend fun getActiveTimeEntry(): TimeEntryEntity?

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL LIMIT 1")
    fun observeActiveTimeEntry(): Flow<TimeEntryEntity?>

    @Query(
        """
        SELECT * FROM time_entries 
        WHERE datetime(startedAt) >= datetime(:start) 
        AND datetime(startedAt) <= datetime(:end)
        ORDER BY startedAt ASC
    """,
    )
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
    fun getTimeEntriesForDate(
        dayStart: String,
        dayEnd: String,
        currentTime: String,
    ): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE id = :id")
    suspend fun getById(id: String): TimeEntryEntity?

    @Query("SELECT * FROM time_entries WHERE import_source = :source AND import_id = :importId LIMIT 1")
    suspend fun getByImportRef(
        source: String,
        importId: String,
    ): TimeEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TimeEntryEntity)

    @Update
    suspend fun update(entry: TimeEntryEntity)

    @Delete
    suspend fun delete(entry: TimeEntryEntity)

    @Query("DELETE FROM time_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM time_entries")
    suspend fun deleteAll()

    @Query("UPDATE time_entries SET taskId = NULL WHERE taskId IS NOT NULL")
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
    suspend fun stopEntry(
        id: String,
        endedAt: String,
        focusRating: Double?,
        focusNote: String?,
        focusRatedAt: String?,
        updatedAt: String,
    )

    @Query("SELECT * FROM time_entries ORDER BY startedAt DESC")
    fun getAll(): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE endedAt IS NULL ORDER BY startedAt DESC")
    fun getAllActiveTimeEntries(): Flow<List<TimeEntryEntity>>
}
