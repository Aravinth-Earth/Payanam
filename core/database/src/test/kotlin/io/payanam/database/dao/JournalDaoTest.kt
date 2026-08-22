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
 * Provides the journal dao test.
 */
class JournalDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var journalDao: JournalDao

    @Before
    /**
     * Updates the setup.
     */
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        journalDao = database.journalDao()
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
     * Performs the insert entry and get entry for date.
     */
    fun insertEntry_and_getEntryForDate() {
        runBlocking {
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)
            val retrieved = journalDao.getEntryForDate("2026-02-02")
            assertThat(retrieved).isNotNull()
            assertThat(retrieved?.id).isEqualTo("entry-1")
            assertThat(retrieved?.entryDate).isEqualTo("2026-02-02")
        }
    }

    @Test
    /**
     * Registers the observe entry for date emits entry.
     */
    fun observeEntryForDate_emitsEntry() {
        runBlocking {
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)
            val observed = journalDao.observeEntryForDate("2026-02-02").first()
            assertThat(observed).isNotNull()
            assertThat(observed?.id).isEqualTo("entry-1")
        }
    }

    @Test
    /**
     * Returns the entry for date returns null when no entry.
     */
    fun getEntryForDate_returnsNullWhenNoEntry() {
        runBlocking {
            val retrieved = journalDao.getEntryForDate("2026-02-02")
            assertThat(retrieved).isNull()
        }
    }

    @Test
    /**
     * Performs the insert response and get response.
     */
    fun insertResponse_and_getResponse() {
        runBlocking {
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)
            val response = createTestJournalResponse("response-1", "entry-1", "OVERALL", null, "mood")
            journalDao.insertResponse(response)
            val retrieved = journalDao.getResponse("entry-1", "OVERALL", null, "mood")
            assertThat(retrieved).isNotNull()
            assertThat(retrieved?.id).isEqualTo("response-1")
            assertThat(retrieved?.responseText).isEqualTo("Feeling good")
        }
    }

    @Test
    /**
     * Returns the responses for entry returns all responses.
     */
    fun getResponsesForEntry_returnsAllResponses() {
        runBlocking {
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)
            val response1 = createTestJournalResponse("response-1", "entry-1", "OVERALL", null, "mood")
            val response2 = createTestJournalResponse("response-2", "entry-1", "DIMENSION", "health", "exercise")

            journalDao.insertResponse(response1)
            journalDao.insertResponse(response2)
            val responses = journalDao.getResponsesForEntry("entry-1").first()
            assertThat(responses).hasSize(2)
            assertThat(responses.map { it.id }).containsExactly("response-1", "response-2")
        }
    }

    @Test
    /**
     * Updates the update response modifies response.
     */
    fun updateResponse_modifiesResponse() {
        runBlocking {
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)
            val response = createTestJournalResponse("response-1", "entry-1", "OVERALL", null, "mood")
            journalDao.insertResponse(response)
            val updatedResponse = response.copy(responseText = "Feeling great", updatedAt = "2026-02-02T11:00:00Z")
            journalDao.updateResponse(updatedResponse)
            val retrieved = journalDao.getResponse("entry-1", "OVERALL", null, "mood")
            assertThat(retrieved?.responseText).isEqualTo("Feeling great")
        }
    }

    @Test
    /**
     * Removes the delete response removes response.
     */
    fun deleteResponse_removesResponse() {
        runBlocking {
            val entry = createTestJournalEntry("entry-1", "2026-02-02")
            journalDao.insertEntry(entry)
            val response = createTestJournalResponse("response-1", "entry-1", "OVERALL", null, "mood")
            journalDao.insertResponse(response)

            journalDao.deleteResponse("response-1")
            val retrieved = journalDao.getResponse("entry-1", "OVERALL", null, "mood")
            assertThat(retrieved).isNull()
        }
    }

    @Test
    /**
     * Returns the all entries returns all entries.
     */
    fun getAllEntries_returnsAllEntries() {
        runBlocking {
            val entry1 = createTestJournalEntry("entry-1", "2026-02-01")
            val entry2 = createTestJournalEntry("entry-2", "2026-02-02")

            journalDao.insertEntry(entry1)
            journalDao.insertEntry(entry2)
            val entries = journalDao.getAllEntries().first()
            assertThat(entries).hasSize(2)
        }
    }

    private fun createTestJournalEntry(
        id: String,
        date: String,
    ) = DayJournalEntryEntity(
        id = id,
        entryDate = date,
        createdAt = "2026-02-01T09:00:00Z",
        updatedAt = "2026-02-01T09:00:00Z",
    )

    private fun createTestJournalResponse(
        id: String,
        entryId: String,
        scope: String,
        dimensionKey: String?,
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
