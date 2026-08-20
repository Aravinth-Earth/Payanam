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
 * NoteRepositoryIntegrityTest.
 */
class NoteRepositoryIntegrityTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: NoteRepositoryImpl

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
        database =
            /** Room. */
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        /** Encryption manager. */
        val encryptionManager = DatabaseEncryptionManager(context)
        /** Session manager. */
        val sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(database)
        repository = NoteRepositoryImpl(sessionManager)
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
     * Create and update note syncs journal and legacy shadow tables.
     */
    fun createAndUpdateNote_syncsJournalAndLegacyShadowTables() =
        runBlocking {
            /** Created. */
            val created =
                repository.createNote(
                    /** Note input. */
                    NoteInput(
                        title = "Original title",
                        details = "Original details",
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )

            /** Legacy after create. */
            val legacyAfterCreate = database.noteDao().getNoteById(created.id)
            /** Journal after create. */
            val journalAfterCreate = database.journalDao().getNoteById(created.id)
            /** Assert that. */
            assertThat(legacyAfterCreate).isNotNull()
            /** Assert that. */
            assertThat(journalAfterCreate).isNotNull()
            /** Assert that. */
            assertThat(legacyAfterCreate?.title).isEqualTo("Original title")
            /** Assert that. */
            assertThat(journalAfterCreate?.title).isEqualTo("Original title")

            repository.updateNote(
                id = created.id,
                input =
                    /** Note input. */
                    NoteInput(
                        title = "Updated title",
                        details = "Updated details",
                        lifeIntentionCategory = "Unmapped Category",
                    ),
            )

            /** Legacy after update. */
            val legacyAfterUpdate = database.noteDao().getNoteById(created.id)
            /** Journal after update. */
            val journalAfterUpdate = database.journalDao().getNoteById(created.id)
            /** Assert that. */
            assertThat(legacyAfterUpdate?.title).isEqualTo("Updated title")
            /** Assert that. */
            assertThat(legacyAfterUpdate?.details).isEqualTo("Updated details")
            /** Assert that. */
            assertThat(journalAfterUpdate?.title).isEqualTo("Updated title")
            /** Assert that. */
            assertThat(journalAfterUpdate?.details).isEqualTo("Updated details")
        }

    @Test
    /**
     * Delete note removes from journal and legacy shadow tables.
     */
    fun deleteNote_removesFromJournalAndLegacyShadowTables() =
        runBlocking {
            /** Created. */
            val created =
                repository.createNote(
                    /** Note input. */
                    NoteInput(
                        title = "Delete me",
                        details = "To be removed",
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )

            repository.deleteNote(created.id)

            /** Legacy. */
            val legacy = database.noteDao().getNoteById(created.id)
            /** Journal. */
            val journal = database.journalDao().getNoteById(created.id)
            /** Assert that. */
            assertThat(legacy).isNull()
            /** Assert that. */
            assertThat(journal).isNull()
            /** Assert that. */
            assertThat(repository.getAllNotes().first()).isEmpty()
        }
}
