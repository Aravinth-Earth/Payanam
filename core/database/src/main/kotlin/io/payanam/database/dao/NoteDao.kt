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
 * Defines the contract for note dao.
 */
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    /**
     * Returns the all notes.
     */
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE lifeIntentionCategory = :dimension ORDER BY updatedAt DESC")
    /**
     * Returns the notes by dimension.
     */
    fun getNotesByDimension(dimension: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    /**
     * Returns the note by id.
     */
    suspend fun getNoteById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert.
     */
    suspend fun insert(note: NoteEntity)

    @Update
    /**
     * Updates the update.
     */
    suspend fun update(note: NoteEntity)

    @Delete
    /**
     * Removes the delete.
     */
    suspend fun delete(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    /**
     * Removes the delete by id.
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM notes")
    /**
     * Removes the delete all.
     */
    suspend fun deleteAll()
}
