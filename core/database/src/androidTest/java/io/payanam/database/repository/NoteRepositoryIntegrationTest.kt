//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.NoteInput
import io.payanam.domain.repository.NoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
/**
 * NoteRepositoryIntegrationTest.
 */
class NoteRepositoryIntegrationTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: NoteRepository
    private lateinit var sessionManager: DatabaseSessionManager

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Initialize logger
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }

        // Create in-memory database for testing
        database =
            /** Room. */
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        /** Encryption manager. */
        val encryptionManager = DatabaseEncryptionManager(context)
        sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(database)
        repository = NoteRepositoryImpl(sessionManager)
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        sessionManager.closeDatabase()
        database.close()
    }

    @Test
    /**
     * Create note creates and returns note with generated id.
     */
    fun createNote_createsAndReturnsNoteWithGeneratedId() =
        runBlocking {
            // Given
            /** Input. */
            val input = createTestNoteInput("Test Note", "Test details")

            // When
            /** Created note. */
            val createdNote = repository.createNote(input)

            // Then
            /** Assert that. */
            assertThat(createdNote.id).isNotEmpty()
            /** Assert that. */
            assertThat(createdNote.title).isEqualTo("Test Note")
            /** Assert that. */
            assertThat(createdNote.details).isEqualTo("Test details")
            /** Assert that. */
            assertThat(createdNote.createdAt).isNotNull()
            /** Assert that. */
            assertThat(createdNote.updatedAt).isNotNull()
        }

    @Test
    /**
     * Get all notes returns all created notes.
     */
    fun getAllNotes_returnsAllCreatedNotes() =
        runBlocking {
            // Given
            /** Note1. */
            val note1 = repository.createNote(createTestNoteInput("Note 1", "Details 1"))
            /** Note2. */
            val note2 = repository.createNote(createTestNoteInput("Note 2", "Details 2"))

            // When
            /** All notes. */
            val allNotes = repository.getAllNotes().first()

            // Then
            /** Assert that. */
            assertThat(allNotes).hasSize(2)
            /** Assert that. */
            assertThat(allNotes.map { it.title }).containsExactly("Note 1", "Note 2")
        }

    @Test
    /**
     * Get note by id returns correct note when exists.
     */
    fun getNoteById_returnsCorrectNoteWhenExists() =
        runBlocking {
            // Given
            /** Created note. */
            val createdNote = repository.createNote(createTestNoteInput("Find Me", "My details"))

            // When
            /** Found note. */
            val foundNote = repository.getNoteById(createdNote.id)

            // Then
            /** Assert that. */
            assertThat(foundNote).isNotNull()
            /** Assert that. */
            assertThat(foundNote?.title).isEqualTo("Find Me")
            /** Assert that. */
            assertThat(foundNote?.details).isEqualTo("My details")
            /** Assert that. */
            assertThat(foundNote?.id).isEqualTo(createdNote.id)
        }

    @Test
    /**
     * Get note by id returns null when note does not exist.
     */
    fun getNoteById_returnsNullWhenNoteDoesNotExist() =
        runBlocking {
            // When
            /** Found note. */
            val foundNote = repository.getNoteById("nonexistent-id")

            // Then
            /** Assert that. */
            assertThat(foundNote).isNull()
        }

    @Test
    /**
     * Get notes by dimension filters notes by life intention category.
     */
    fun getNotesByDimension_filtersNotesByLifeIntentionCategory() =
        runBlocking {
            // Given
            /** Career note. */
            val careerNote =
                repository.createNote(
                    /** Create test note input. */
                    createTestNoteInput("Career Note", "Career details").copy(lifeIntentionCategory = "Career & Work"),
                )
            /** Health note. */
            val healthNote =
                repository.createNote(
                    /** Create test note input. */
                    createTestNoteInput("Health Note", "Health details").copy(lifeIntentionCategory = "Health & Wellness"),
                )

            // When
            /** Career notes. */
            val careerNotes = repository.getNotesByDimension("Career & Work").first()
            /** Health notes. */
            val healthNotes = repository.getNotesByDimension("Health & Wellness").first()

            // Then
            /** Assert that. */
            assertThat(careerNotes).hasSize(1)
            /** Assert that. */
            assertThat(careerNotes.first().title).isEqualTo("Career Note")
            /** Assert that. */
            assertThat(healthNotes).hasSize(1)
            /** Assert that. */
            assertThat(healthNotes.first().title).isEqualTo("Health Note")
        }

    @Test
    /**
     * Update note modifies existing note.
     */
    fun updateNote_modifiesExistingNote() =
        runBlocking {
            // Given
            /** Created note. */
            val createdNote = repository.createNote(createTestNoteInput("Original Title", "Original details"))
            /** Update input. */
            val updateInput = createTestNoteInput("Updated Title", "Updated details")

            // When
            /** Updated note. */
            val updatedNote = repository.updateNote(createdNote.id, updateInput)

            // Then
            /** Assert that. */
            assertThat(updatedNote.id).isEqualTo(createdNote.id)
            /** Assert that. */
            assertThat(updatedNote.title).isEqualTo("Updated Title")
            /** Assert that. */
            assertThat(updatedNote.details).isEqualTo("Updated details")
            /** Assert that. */
            assertThat(updatedNote.updatedAt).isAtLeast(updatedNote.createdAt)
        }

    @Test
    /**
     * Delete note removes note from database.
     */
    fun deleteNote_removesNoteFromDatabase() =
        runBlocking {
            // Given
            /** Note. */
            val note = repository.createNote(createTestNoteInput("Delete Me", "Delete details"))
            /** Note id. */
            val noteId = note.id

            // Verify note exists
            /** Assert that. */
            assertThat(repository.getNoteById(noteId)).isNotNull()

            // When
            repository.deleteNote(noteId)

            // Then
            /** Assert that. */
            assertThat(repository.getNoteById(noteId)).isNull()
        }

    private fun createTestNoteInput(
        /** Title. */
        title: String,
        /** Details. */
        details: String,
    ): NoteInput =
        /** Note input. */
        NoteInput(
            title = title,
            details = details,
            lifeIntentionCategory = "Career & Work",
        )
}
