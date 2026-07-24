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
class NoteRepositoryIntegrationTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: NoteRepository
    private lateinit var sessionManager: DatabaseSessionManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Initialize logger
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }

        // Create in-memory database for testing
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val encryptionManager = DatabaseEncryptionManager(context)
        sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(database)
        repository = NoteRepositoryImpl(sessionManager)
    }

    @After
    fun tearDown() {
        sessionManager.closeDatabase()
        database.close()
    }

    @Test
    fun createNote_createsAndReturnsNoteWithGeneratedId() =
        runBlocking {
            // Given
            val input = createTestNoteInput("Test Note", "Test details")

            // When
            val createdNote = repository.createNote(input)

            // Then
            assertThat(createdNote.id).isNotEmpty()
            assertThat(createdNote.title).isEqualTo("Test Note")
            assertThat(createdNote.details).isEqualTo("Test details")
            assertThat(createdNote.createdAt).isNotNull()
            assertThat(createdNote.updatedAt).isNotNull()
        }

    @Test
    fun getAllNotes_returnsAllCreatedNotes() =
        runBlocking {
            // Given
            val note1 = repository.createNote(createTestNoteInput("Note 1", "Details 1"))
            val note2 = repository.createNote(createTestNoteInput("Note 2", "Details 2"))

            // When
            val allNotes = repository.getAllNotes().first()

            // Then
            assertThat(allNotes).hasSize(2)
            assertThat(allNotes.map { it.title }).containsExactly("Note 1", "Note 2")
        }

    @Test
    fun getNoteById_returnsCorrectNoteWhenExists() =
        runBlocking {
            // Given
            val createdNote = repository.createNote(createTestNoteInput("Find Me", "My details"))

            // When
            val foundNote = repository.getNoteById(createdNote.id)

            // Then
            assertThat(foundNote).isNotNull()
            assertThat(foundNote?.title).isEqualTo("Find Me")
            assertThat(foundNote?.details).isEqualTo("My details")
            assertThat(foundNote?.id).isEqualTo(createdNote.id)
        }

    @Test
    fun getNoteById_returnsNullWhenNoteDoesNotExist() =
        runBlocking {
            // When
            val foundNote = repository.getNoteById("nonexistent-id")

            // Then
            assertThat(foundNote).isNull()
        }

    @Test
    fun getNotesByDimension_filtersNotesByLifeIntentionCategory() =
        runBlocking {
            // Given
            val careerNote =
                repository.createNote(
                    createTestNoteInput("Career Note", "Career details").copy(lifeIntentionCategory = "Career & Work"),
                )
            val healthNote =
                repository.createNote(
                    createTestNoteInput("Health Note", "Health details").copy(lifeIntentionCategory = "Health & Wellness"),
                )

            // When
            val careerNotes = repository.getNotesByDimension("Career & Work").first()
            val healthNotes = repository.getNotesByDimension("Health & Wellness").first()

            // Then
            assertThat(careerNotes).hasSize(1)
            assertThat(careerNotes.first().title).isEqualTo("Career Note")
            assertThat(healthNotes).hasSize(1)
            assertThat(healthNotes.first().title).isEqualTo("Health Note")
        }

    @Test
    fun updateNote_modifiesExistingNote() =
        runBlocking {
            // Given
            val createdNote = repository.createNote(createTestNoteInput("Original Title", "Original details"))
            val updateInput = createTestNoteInput("Updated Title", "Updated details")

            // When
            val updatedNote = repository.updateNote(createdNote.id, updateInput)

            // Then
            assertThat(updatedNote.id).isEqualTo(createdNote.id)
            assertThat(updatedNote.title).isEqualTo("Updated Title")
            assertThat(updatedNote.details).isEqualTo("Updated details")
            assertThat(updatedNote.updatedAt).isAtLeast(updatedNote.createdAt)
        }

    @Test
    fun deleteNote_removesNoteFromDatabase() =
        runBlocking {
            // Given
            val note = repository.createNote(createTestNoteInput("Delete Me", "Delete details"))
            val noteId = note.id

            // Verify note exists
            assertThat(repository.getNoteById(noteId)).isNotNull()

            // When
            repository.deleteNote(noteId)

            // Then
            assertThat(repository.getNoteById(noteId)).isNull()
        }

    private fun createTestNoteInput(
        title: String,
        details: String,
    ): NoteInput =
        NoteInput(
            title = title,
            details = details,
            lifeIntentionCategory = "Career & Work",
        )
}
