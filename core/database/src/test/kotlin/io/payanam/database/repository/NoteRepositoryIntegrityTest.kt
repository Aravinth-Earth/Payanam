//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.NoteInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * Provides the note repository integrity test.
 */
class NoteRepositoryIntegrityTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: NoteRepositoryImpl

    @Before
    /**
     * Updates the setup.
     */
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        val encryptionManager = DatabaseEncryptionManager(context)
        val sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(database)
        repository = NoteRepositoryImpl(sessionManager)
    }

    @After
    /**
     * Performs the tear down.
     */
    fun tearDown() {
        database.close()
    }

    @Test
    /**
     * Create and update note syncs journal and legacy shadow tables.
     */
    fun createAndUpdateNote_syncsJournalAndLegacyShadowTables() =
        runBlocking {
            val created =
                repository.createNote(
                    NoteInput(
                        title = "Original title",
                        details = "Original details",
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )
            val legacyAfterCreate = database.noteDao().getNoteById(created.id)
            val journalAfterCreate = database.journalDao().getNoteById(created.id)
            assertThat(legacyAfterCreate).isNotNull()
            assertThat(journalAfterCreate).isNotNull()
            assertThat(legacyAfterCreate?.title).isEqualTo("Original title")
            assertThat(journalAfterCreate?.title).isEqualTo("Original title")

            repository.updateNote(
                id = created.id,
                input =
                    NoteInput(
                        title = "Updated title",
                        details = "Updated details",
                        lifeIntentionCategory = "Unmapped Category",
                    ),
            )
            val legacyAfterUpdate = database.noteDao().getNoteById(created.id)
            val journalAfterUpdate = database.journalDao().getNoteById(created.id)
            assertThat(legacyAfterUpdate?.title).isEqualTo("Updated title")
            assertThat(legacyAfterUpdate?.details).isEqualTo("Updated details")
            assertThat(journalAfterUpdate?.title).isEqualTo("Updated title")
            assertThat(journalAfterUpdate?.details).isEqualTo("Updated details")
        }

    @Test
    /**
     * Removes the delete note removes from journal and legacy shadow tables.
     */
    fun deleteNote_removesFromJournalAndLegacyShadowTables() =
        runBlocking {
            val created =
                repository.createNote(
                    NoteInput(
                        title = "Delete me",
                        details = "To be removed",
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )

            repository.deleteNote(created.id)
            val legacy = database.noteDao().getNoteById(created.id)
            val journal = database.journalDao().getNoteById(created.id)
            assertThat(legacy).isNull()
            assertThat(journal).isNull()
            assertThat(repository.getAllNotes().first()).isEmpty()
        }
}
