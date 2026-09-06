//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.payanam.database.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
/**
 * Room DAO for the `notes` table: standalone user notes (distinct from the
 * journal's dimensions-scoped note rows).
 */
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    /**
     * Emits all notes ordered by last-updated (newest first) as a [Flow].
     */
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE lifeIntentionCategory = :dimension ORDER BY updatedAt DESC")
    /**
     * Emits notes tagged with the given life-intention [dimension], newest
     * first, as a [Flow].
     */
    fun getNotesByDimension(dimension: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    /**
     * Returns the note with [id], or null.
     */
    suspend fun getNoteById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a note.
     */
    suspend fun insert(note: NoteEntity)

    @Update
    /**
     * Updates all columns of an existing note.
     */
    suspend fun update(note: NoteEntity)

    @Delete
    /**
     * Deletes the given [note] row.
     */
    suspend fun delete(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    /**
     * Deletes the note with [id].
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM notes")
    /**
     * Deletes every note row.
     */
    suspend fun deleteAll()
}
