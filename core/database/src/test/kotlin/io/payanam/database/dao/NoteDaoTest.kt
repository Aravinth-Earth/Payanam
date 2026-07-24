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
class NoteDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var noteDao: NoteDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        noteDao = database.noteDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_and_getNoteById() {
        runBlocking {
            val note = createTestNote("note-1", "Test Note", "health")
            noteDao.insert(note)

            val retrieved = noteDao.getNoteById("note-1")
            assertThat(retrieved).isNotNull()
            assertThat(retrieved?.id).isEqualTo("note-1")
            assertThat(retrieved?.title).isEqualTo("Test Note")
        }
    }

    @Test
    fun getAllNotes_returnsAllNotes() {
        runBlocking {
            val note1 = createTestNote("note-1", "Note 1", "health")
            val note2 = createTestNote("note-2", "Note 2", "career")

            noteDao.insert(note1)
            noteDao.insert(note2)

            val notes = noteDao.getAllNotes().first()
            assertThat(notes).hasSize(2)
            assertThat(notes.map { it.id }).containsExactly("note-2", "note-1") // Ordered by updatedAt DESC
        }
    }

    @Test
    fun getNotesByDimension_filtersCorrectly() {
        runBlocking {
            val healthNote = createTestNote("note-1", "Health Note", "health")
            val careerNote = createTestNote("note-2", "Career Note", "career")
            val anotherHealthNote = createTestNote("note-3", "Another Health", "health")

            noteDao.insert(healthNote)
            noteDao.insert(careerNote)
            noteDao.insert(anotherHealthNote)

            val healthNotes = noteDao.getNotesByDimension("health").first()
            assertThat(healthNotes).hasSize(2)
            assertThat(healthNotes.map { it.id }).containsExactly("note-3", "note-1")
        }
    }

    @Test
    fun update_modifiesNote() {
        runBlocking {
            val note = createTestNote("note-1", "Original Title", "health")
            noteDao.insert(note)

            val updatedNote = note.copy(title = "Updated Title", updatedAt = "2026-02-02T11:00:00Z")
            noteDao.update(updatedNote)

            val retrieved = noteDao.getNoteById("note-1")
            assertThat(retrieved?.title).isEqualTo("Updated Title")
        }
    }

    @Test
    fun delete_removesNote() {
        runBlocking {
            val note = createTestNote("note-1", "Test Note", "health")
            noteDao.insert(note)

            noteDao.delete(note)

            val retrieved = noteDao.getNoteById("note-1")
            assertThat(retrieved).isNull()
        }
    }

    @Test
    fun deleteById_removesNote() {
        runBlocking {
            val note = createTestNote("note-1", "Test Note", "health")
            noteDao.insert(note)

            noteDao.deleteById("note-1")

            val retrieved = noteDao.getNoteById("note-1")
            assertThat(retrieved).isNull()
        }
    }

    private fun createTestNote(
        id: String,
        title: String,
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
