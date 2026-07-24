//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Repository interface for Note operations.
 */
interface NoteRepository {
    
    /**
     * Get all notes.
     */
    fun getAllNotes(): Flow<List<Note>>
    
    /**
     * Get notes by life dimension.
     */
    fun getNotesByDimension(dimension: String): Flow<List<Note>>

    /**
     * Get notes for a specific date (based on note day key).
     */
    fun getNotesForDate(date: LocalDate): Flow<List<Note>>
    
    /**
     * Get a single note by ID.
     */
    suspend fun getNoteById(id: String): Note?
    
    /**
     * Create a new note.
     */
    suspend fun createNote(input: NoteInput): Note
    
    /**
     * Update an existing note.
     */
    suspend fun updateNote(id: String, input: NoteInput): Note
    
    /**
     * Delete a note.
     */
    suspend fun deleteNote(id: String)
}
