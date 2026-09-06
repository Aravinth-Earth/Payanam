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
 * Provides the journal repository integrity test.
 */
class JournalRepositoryIntegrityTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: JournalRepositoryImpl

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
        repository = JournalRepositoryImpl(sessionManager)
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
     * Returns the or create entry returns existing when present.
     */
    fun getOrCreateEntry_returnsExistingWhenPresent() =
        runBlocking {
            val first = repository.getOrCreateEntry(LocalDate.of(2026, 2, 20))
            val second = repository.getOrCreateEntry(LocalDate.of(2026, 2, 20))
            assertThat(first.id).isEqualTo(second.id)
            assertThat(first.entryDate).isEqualTo("2026-02-20")
            assertThat(repository.getAllJournalEntries().first()).hasSize(1)
        }

    @Test
    /**
     * Writes the save response inserts then updates by natural key.
     */
    fun saveResponse_insertsThenUpdatesByNaturalKey() =
        runBlocking {
            val entry = repository.getOrCreateEntry(LocalDate.of(2026, 2, 21))
            val inserted =
                repository.saveResponse(
                    entryId = entry.id,
                    input =
                        DayJournalResponseInput(
                            scope = JournalPromptScope.OVERALL,
                            promptKey = "gratitude",
                            responseText = "first",
                        ),
                )
            val updated =
                repository.saveResponse(
                    entryId = entry.id,
                    input =
                        DayJournalResponseInput(
                            scope = JournalPromptScope.OVERALL,
                            promptKey = "gratitude",
                            responseText = "second",
                        ),
                )
            val responses = repository.getResponsesByEntryId(entry.id)
            assertThat(responses).hasSize(1)
            assertThat(inserted.id).isEqualTo(updated.id)
            assertThat(responses.single().responseText).isEqualTo("second")
        }

    @Test
    /**
     * Performs the upsert response updates existing and inserts missing.
     */
    fun upsertResponse_updatesExistingAndInsertsMissing() =
        runBlocking {
            val entry = repository.getOrCreateEntry(LocalDate.of(2026, 2, 22))
            repository.saveResponse(
                entryId = entry.id,
                input =
                    DayJournalResponseInput(
                        scope = JournalPromptScope.OVERALL,
                        promptKey = "reflection",
                        responseText = "old",
                    ),
            )

            repository.upsertResponse(
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
                DayJournalResponse(
                    id = "response-dimension",
                    entryId = entry.id,
                    scope = "DIMENSION",
                    dimensionKey = "career_work",
                    promptKey = "wins",
                    responseText = "added",
                ),
            )
            val responses = repository.getResponsesByEntryId(entry.id)
            assertThat(responses).hasSize(2)
            assertThat(responses.first { it.promptKey == "reflection" }.responseText).isEqualTo("new")
            assertThat(responses.first { it.promptKey == "wins" }.responseText).isEqualTo("added")
        }

    @Test
    /**
     * Returns the response and total count are consistent.
     */
    fun getResponse_and_totalCount_areConsistent() =
        runBlocking {
            val entry = repository.getOrCreateEntry(LocalDate.of(2026, 2, 23))
            repository.saveResponse(
                entryId = entry.id,
                input =
                    DayJournalResponseInput(
                        scope = JournalPromptScope.DIMENSION,
                        dimensionKey = "career_work",
                        promptKey = "focus",
                        responseText = "good",
                    ),
            )
            val response =
                repository.getResponse(
                    entryId = entry.id,
                    scope = JournalPromptScope.DIMENSION,
                    dimensionKey = "career_work",
                    promptKey = "focus",
                )
            assertThat(response).isNotNull()
            assertThat(response?.responseText).isEqualTo("good")
            assertThat(repository.getTotalResponseCount().first()).isEqualTo(1)
        }
}
