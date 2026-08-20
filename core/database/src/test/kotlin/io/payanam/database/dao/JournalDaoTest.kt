//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.DayJournalEntryEntity
import io.payanam.database.entity.DayJournalResponseEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * JournalDaoTest.
 */
class JournalDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var journalDao: JournalDao

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
        journalDao = database.journalDao()
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
     * Insert entry and get entry for date.
     */
    fun insertEntry_and_getEntryForDate() {
        runBlocking {
            /** Entry. */
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)

            /** Retrieved. */
            val retrieved = journalDao.getEntryForDate("2026-02-02")
            /** Assert that. */
            assertThat(retrieved).isNotNull()
            /** Assert that. */
            assertThat(retrieved?.id).isEqualTo("entry-1")
            /** Assert that. */
            assertThat(retrieved?.entryDate).isEqualTo("2026-02-02")
        }
    }

    @Test
    /**
     * Observe entry for date emits entry.
     */
    fun observeEntryForDate_emitsEntry() {
        runBlocking {
            /** Entry. */
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)

            /** Observed. */
            val observed = journalDao.observeEntryForDate("2026-02-02").first()
            /** Assert that. */
            assertThat(observed).isNotNull()
            /** Assert that. */
            assertThat(observed?.id).isEqualTo("entry-1")
        }
    }

    @Test
    /**
     * Get entry for date returns null when no entry.
     */
    fun getEntryForDate_returnsNullWhenNoEntry() {
        runBlocking {
            /** Retrieved. */
            val retrieved = journalDao.getEntryForDate("2026-02-02")
            /** Assert that. */
            assertThat(retrieved).isNull()
        }
    }

    @Test
    /**
     * Insert response and get response.
     */
    fun insertResponse_and_getResponse() {
        runBlocking {
            /** Entry. */
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)

            /** Response. */
            val response = createTestJournalResponse("response-1", "entry-1", "OVERALL", null, "mood")
            journalDao.insertResponse(response)

            /** Retrieved. */
            val retrieved = journalDao.getResponse("entry-1", "OVERALL", null, "mood")
            /** Assert that. */
            assertThat(retrieved).isNotNull()
            /** Assert that. */
            assertThat(retrieved?.id).isEqualTo("response-1")
            /** Assert that. */
            assertThat(retrieved?.responseText).isEqualTo("Feeling good")
        }
    }

    @Test
    /**
     * Get responses for entry returns all responses.
     */
    fun getResponsesForEntry_returnsAllResponses() {
        runBlocking {
            /** Entry. */
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)

            /** Response1. */
            val response1 = createTestJournalResponse("response-1", "entry-1", "OVERALL", null, "mood")
            /** Response2. */
            val response2 = createTestJournalResponse("response-2", "entry-1", "DIMENSION", "health", "exercise")

            journalDao.insertResponse(response1)
            journalDao.insertResponse(response2)

            /** Responses. */
            val responses = journalDao.getResponsesForEntry("entry-1").first()
            /** Assert that. */
            assertThat(responses).hasSize(2)
            /** Assert that. */
            assertThat(responses.map { it.id }).containsExactly("response-1", "response-2")
        }
    }

    @Test
    /**
     * Update response modifies response.
     */
    fun updateResponse_modifiesResponse() {
        runBlocking {
            /** Entry. */
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)

            /** Response. */
            val response = createTestJournalResponse("response-1", "entry-1", "OVERALL", null, "mood")
            journalDao.insertResponse(response)

            /** Updated response. */
            val updatedResponse = response.copy(responseText = "Feeling great", updatedAt = "2026-02-02T11:00:00Z")
            journalDao.updateResponse(updatedResponse)

            /** Retrieved. */
            val retrieved = journalDao.getResponse("entry-1", "OVERALL", null, "mood")
            /** Assert that. */
            assertThat(retrieved?.responseText).isEqualTo("Feeling great")
        }
    }

    @Test
    /**
     * Delete response removes response.
     */
    fun deleteResponse_removesResponse() {
        runBlocking {
            /** Entry. */
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)

            /** Response. */
            val response = createTestJournalResponse("response-1", "entry-1", "OVERALL", null, "mood")
            journalDao.insertResponse(response)

            journalDao.deleteResponse("response-1")

            /** Retrieved. */
            val retrieved = journalDao.getResponse("entry-1", "OVERALL", null, "mood")
            /** Assert that. */
            assertThat(retrieved).isNull()
        }
    }

    @Test
    /**
     * Get all entries returns all entries.
     */
    fun getAllEntries_returnsAllEntries() {
        runBlocking {
            /** Entry1. */
            val entry1 = createTestJournalEntry("entry-1", "2026-02-01")
            /** Entry2. */
            val entry2 = createTestJournalEntry("entry-2", "2026-02-02")

            journalDao.insertEntry(entry1)
            journalDao.insertEntry(entry2)

            /** Entries. */
            val entries = journalDao.getAllEntries().first()
            /** Assert that. */
            assertThat(entries).hasSize(2)
        }
    }

    private fun createTestJournalEntry(
        /** Id. */
        id: String,
        /** Date. */
        date: String,
    ) = DayJournalEntryEntity(
        id = id,
        entryDate = date,
        createdAt = "2026-02-01T09:00:00Z",
        updatedAt = "2026-02-01T09:00:00Z",
    )

    private fun createTestJournalResponse(
        /** Id. */
        id: String,
        /** Entry id. */
        entryId: String,
        /** Scope. */
        scope: String,
        dimensionKey: String?,
        /** Prompt key. */
        promptKey: String,
    ) = DayJournalResponseEntity(
        id = id,
        entryId = entryId,
        scope = scope,
        dimensionKey = dimensionKey,
        promptKey = promptKey,
        responseText = "Feeling good",
        createdAt = "2026-02-01T09:00:00Z",
        updatedAt = "2026-02-01T09:00:00Z",
    )
}
