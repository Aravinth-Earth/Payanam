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
 * NoteDao.
 */
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    /**
     * Get all notes.
     */
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE lifeIntentionCategory = :dimension ORDER BY updatedAt DESC")
    /**
     * Get notes by dimension.
     */
    fun getNotesByDimension(dimension: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    /**
     * Get note by id.
     */
    suspend fun getNoteById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert.
     */
    suspend fun insert(note: NoteEntity)

    @Update
    /**
     * Update.
     */
    suspend fun update(note: NoteEntity)

    @Delete
    /**
     * Delete.
     */
    suspend fun delete(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    /**
     * Delete by id.
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM notes")
    /**
     * Delete all.
     */
    suspend fun deleteAll()
}
