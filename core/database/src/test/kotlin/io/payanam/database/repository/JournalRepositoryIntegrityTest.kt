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
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DayJournalResponseInput
import io.payanam.domain.model.JournalPromptScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
/**
 * JournalRepositoryIntegrityTest.
 */
class JournalRepositoryIntegrityTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: JournalRepositoryImpl

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
        repository = JournalRepositoryImpl(sessionManager)
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
     * Get or create entry returns existing when present.
     */
    fun getOrCreateEntry_returnsExistingWhenPresent() =
        runBlocking {
            /** First. */
            val first = repository.getOrCreateEntry(LocalDate.of(2026, 2, 20))
            /** Second. */
            val second = repository.getOrCreateEntry(LocalDate.of(2026, 2, 20))

            /** Assert that. */
            assertThat(first.id).isEqualTo(second.id)
            /** Assert that. */
            assertThat(first.entryDate).isEqualTo("2026-02-20")
            /** Assert that. */
            assertThat(repository.getAllJournalEntries().first()).hasSize(1)
        }

    @Test
    /**
     * Save response inserts then updates by natural key.
     */
    fun saveResponse_insertsThenUpdatesByNaturalKey() =
        runBlocking {
            /** Entry. */
            val entry = repository.getOrCreateEntry(LocalDate.of(2026, 2, 21))

            /** Inserted. */
            val inserted =
                repository.saveResponse(
                    entryId = entry.id,
                    input =
                        /** Day journal response input. */
                        DayJournalResponseInput(
                            scope = JournalPromptScope.OVERALL,
                            promptKey = "gratitude",
                            responseText = "first",
                        ),
                )
            /** Updated. */
            val updated =
                repository.saveResponse(
                    entryId = entry.id,
                    input =
                        /** Day journal response input. */
                        DayJournalResponseInput(
                            scope = JournalPromptScope.OVERALL,
                            promptKey = "gratitude",
                            responseText = "second",
                        ),
                )

            /** Responses. */
            val responses = repository.getResponsesByEntryId(entry.id)
            /** Assert that. */
            assertThat(responses).hasSize(1)
            /** Assert that. */
            assertThat(inserted.id).isEqualTo(updated.id)
            /** Assert that. */
            assertThat(responses.single().responseText).isEqualTo("second")
        }

    @Test
    /**
     * Upsert response updates existing and inserts missing.
     */
    fun upsertResponse_updatesExistingAndInsertsMissing() =
        runBlocking {
            /** Entry. */
            val entry = repository.getOrCreateEntry(LocalDate.of(2026, 2, 22))
            repository.saveResponse(
                entryId = entry.id,
                input =
                    /** Day journal response input. */
                    DayJournalResponseInput(
                        scope = JournalPromptScope.OVERALL,
                        promptKey = "reflection",
                        responseText = "old",
                    ),
            )

            repository.upsertResponse(
                /** Day journal response. */
                DayJournalResponse(
                    id = "response-fixed-id",
                    entryId = entry.id,
                    scope = "OVERALL",
                    dimensionKey = null,
                    promptKey = "reflection",
                    responseText = "new",
                ),
            )
            repository.upsertResponse(
                /** Day journal response. */
                DayJournalResponse(
                    id = "response-dimension",
                    entryId = entry.id,
                    scope = "DIMENSION",
                    dimensionKey = "career_work",
                    promptKey = "wins",
                    responseText = "added",
                ),
            )

            /** Responses. */
            val responses = repository.getResponsesByEntryId(entry.id)
            /** Assert that. */
            assertThat(responses).hasSize(2)
            /** Assert that. */
            assertThat(responses.first { it.promptKey == "reflection" }.responseText).isEqualTo("new")
            /** Assert that. */
            assertThat(responses.first { it.promptKey == "wins" }.responseText).isEqualTo("added")
        }

    @Test
    /**
     * Get response and total count are consistent.
     */
    fun getResponse_and_totalCount_areConsistent() =
        runBlocking {
            /** Entry. */
            val entry = repository.getOrCreateEntry(LocalDate.of(2026, 2, 23))
            repository.saveResponse(
                entryId = entry.id,
                input =
                    /** Day journal response input. */
                    DayJournalResponseInput(
                        scope = JournalPromptScope.DIMENSION,
                        dimensionKey = "career_work",
                        promptKey = "focus",
                        responseText = "good",
                    ),
            )

            /** Response. */
            val response =
                repository.getResponse(
                    entryId = entry.id,
                    scope = JournalPromptScope.DIMENSION,
                    dimensionKey = "career_work",
                    promptKey = "focus",
                )

            /** Assert that. */
            assertThat(response).isNotNull()
            /** Assert that. */
            assertThat(response?.responseText).isEqualTo("good")
            /** Assert that. */
            assertThat(repository.getTotalResponseCount().first()).isEqualTo(1)
        }
}
