//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * NoteDaoTest.
 */
class NoteDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var noteDao: NoteDao

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            /** Room. */
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        noteDao = database.noteDao()
    }

    @After
    /**
     * Tear down.
     */
    fun tearDown() {
        database.close()
    }

    @Test
    /**
     * Insert and get note by id.
     */
    fun insert_and_getNoteById() {
        runBlocking {
            /** Note. */
            val note = createTestNote("note-1", "Test Note", "health")
            noteDao.insert(note)

            /** Retrieved. */
            val retrieved = noteDao.getNoteById("note-1")
            /** Assert that. */
            assertThat(retrieved).isNotNull()
            /** Assert that. */
            assertThat(retrieved?.id).isEqualTo("note-1")
            /** Assert that. */
            assertThat(retrieved?.title).isEqualTo("Test Note")
        }
    }

    @Test
    /**
     * Get all notes returns all notes.
     */
    fun getAllNotes_returnsAllNotes() {
        runBlocking {
            /** Note1. */
            val note1 = createTestNote("note-1", "Note 1", "health")
            /** Note2. */
            val note2 = createTestNote("note-2", "Note 2", "career")

            noteDao.insert(note1)
            noteDao.insert(note2)

            /** Notes. */
            val notes = noteDao.getAllNotes().first()
            /** Assert that. */
            assertThat(notes).hasSize(2)
            /** Assert that. */
            assertThat(notes.map { it.id }).containsExactly("note-2", "note-1") // Ordered by updatedAt DESC
        }
    }

    @Test
    /**
     * Get notes by dimension filters correctly.
     */
    fun getNotesByDimension_filtersCorrectly() {
        runBlocking {
            /** Health note. */
            val healthNote = createTestNote("note-1", "Health Note", "health")
            /** Career note. */
            val careerNote = createTestNote("note-2", "Career Note", "career")
            /** Another health note. */
            val anotherHealthNote = createTestNote("note-3", "Another Health", "health")

            noteDao.insert(healthNote)
            noteDao.insert(careerNote)
            noteDao.insert(anotherHealthNote)

            /** Health notes. */
            val healthNotes = noteDao.getNotesByDimension("health").first()
            /** Assert that. */
            assertThat(healthNotes).hasSize(2)
            /** Assert that. */
            assertThat(healthNotes.map { it.id }).containsExactly("note-3", "note-1")
        }
    }

    @Test
    /**
     * Update modifies note.
     */
    fun update_modifiesNote() {
        runBlocking {
            /** Note. */
            val note = createTestNote("note-1", "Original Title", "health")
            noteDao.insert(note)

            /** Updated note. */
            val updatedNote = note.copy(title = "Updated Title", updatedAt = "2026-02-02T11:00:00Z")
            noteDao.update(updatedNote)

            /** Retrieved. */
            val retrieved = noteDao.getNoteById("note-1")
            /** Assert that. */
            assertThat(retrieved?.title).isEqualTo("Updated Title")
        }
    }

    @Test
    /**
     * Delete removes note.
     */
    fun delete_removesNote() {
        runBlocking {
            /** Note. */
            val note = createTestNote("note-1", "Test Note", "health")
            noteDao.insert(note)

            noteDao.delete(note)

            /** Retrieved. */
            val retrieved = noteDao.getNoteById("note-1")
            /** Assert that. */
            assertThat(retrieved).isNull()
        }
    }

    @Test
    /**
     * Delete by id removes note.
     */
    fun deleteById_removesNote() {
        runBlocking {
            /** Note. */
            val note = createTestNote("note-1", "Test Note", "health")
            noteDao.insert(note)

            noteDao.deleteById("note-1")

            /** Retrieved. */
            val retrieved = noteDao.getNoteById("note-1")
            /** Assert that. */
            assertThat(retrieved).isNull()
        }
    }

    private fun createTestNote(
        /** Id. */
        id: String,
        /** Title. */
        title: String,
        /** Dimension. */
        dimension: String,
        details: String? = "Test details",
    ) = NoteEntity(
        id = id,
        title = title,
        details = details,
        lifeIntentionCategory = dimension,
        createdAt = "2026-02-01T09:00:00Z",
        updatedAt = "2026-02-01T09:00:00Z",
    )
}
