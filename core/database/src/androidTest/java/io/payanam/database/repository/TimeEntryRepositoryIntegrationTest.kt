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
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.TimeEntryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
/**
 * TimeEntryRepositoryIntegrationTest.
 */
class TimeEntryRepositoryIntegrationTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: TimeEntryRepository
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
        repository = TimeEntryRepositoryImpl(sessionManager)
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
     * Start time entry creates new time entry and returns it.
     */
    fun startTimeEntry_createsNewTimeEntryAndReturnsIt() =
        runBlocking {
            // Given
            /** Input. */
            val input = createTestTimeEntryInput("task-1")

            // When
            /** Created entry. */
            val createdEntry = repository.startTimeEntry(input)

            // Then
            /** Assert that. */
            assertThat(createdEntry.id).isNotEmpty()
            /** Assert that. */
            assertThat(createdEntry.taskId).isEqualTo("task-1")
            /** Assert that. */
            assertThat(createdEntry.startedAt).isNotNull()
            /** Assert that. */
            assertThat(createdEntry.endedAt).isNull() // Active entry
        }

    @Test
    /**
     * Get active time entry returns currently active entry.
     */
    fun getActiveTimeEntry_returnsCurrentlyActiveEntry() =
        runBlocking {
            // Given
            /** Active entry. */
            val activeEntry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            repository.startTimeEntry(createTestTimeEntryInput("task-2")) // This should become active

            // When
            /** Active. */
            val active = repository.getActiveTimeEntry()

            // Then
            /** Assert that. */
            assertThat(active).isNotNull()
            /** Assert that. */
            assertThat(active?.taskId).isEqualTo("task-2")
            /** Assert that. */
            assertThat(active?.endedAt).isNull()
        }

    @Test
    /**
     * Get active time entry returns null when no active entry.
     */
    fun getActiveTimeEntry_returnsNullWhenNoActiveEntry() =
        runBlocking {
            // Given - no entries

            // When
            /** Active. */
            val active = repository.getActiveTimeEntry()

            // Then
            /** Assert that. */
            assertThat(active).isNull()
        }

    @Test
    /**
     * Stop active time entry stops current active entry.
     */
    fun stopActiveTimeEntry_stopsCurrentActiveEntry() =
        runBlocking {
            // Given
            /** Active entry. */
            val activeEntry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))

            // When
            /** Stopped entry. */
            val stoppedEntry = repository.stopActiveTimeEntry()

            // Then
            /** Assert that. */
            assertThat(stoppedEntry).isNotNull()
            /** Assert that. */
            assertThat(stoppedEntry?.id).isEqualTo(activeEntry.id)
            /** Assert that. */
            assertThat(stoppedEntry?.endedAt).isNotNull()

            // Verify no active entry remains
            /** Assert that. */
            assertThat(repository.getActiveTimeEntry()).isNull()
        }

    @Test
    /**
     * Stop active time entry returns null when no active entry.
     */
    fun stopActiveTimeEntry_returnsNullWhenNoActiveEntry() =
        runBlocking {
            // Given - no active entry

            // When
            /** Stopped entry. */
            val stoppedEntry = repository.stopActiveTimeEntry()

            // Then
            /** Assert that. */
            assertThat(stoppedEntry).isNull()
        }

    @Test
    /**
     * Get time entries for date returns entries for specific date.
     */
    fun getTimeEntriesForDate_returnsEntriesForSpecificDate() =
        runBlocking {
            // Given
            /** Today. */
            val today = LocalDate.now()
            /** Yesterday. */
            val yesterday = today.minusDays(1)

            /** Today entry. */
            val todayEntry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            repository.stopActiveTimeEntry() // Stop it

            /** Yesterday entry. */
            val yesterdayEntry =
                repository.createTimeEntry(
                    /** Create test time entry input. */
                    createTestTimeEntryInput("task-2").copy(
                        startedAt = yesterday.atStartOfDay(),
                        endedAt = yesterday.atStartOfDay().plusHours(1),
                    ),
                )

            // When
            /** Today entries. */
            val todayEntries = repository.getTimeEntriesForDate(today).first()
            /** Yesterday entries. */
            val yesterdayEntries = repository.getTimeEntriesForDate(yesterday).first()

            // Then
            /** Assert that. */
            assertThat(todayEntries).hasSize(1)
            /** Assert that. */
            assertThat(todayEntries.first().taskId).isEqualTo("task-1")
            /** Assert that. */
            assertThat(yesterdayEntries).hasSize(1)
            /** Assert that. */
            assertThat(yesterdayEntries.first().taskId).isEqualTo("task-2")
        }

    @Test
    /**
     * Get time entries for date includes entry spanning midnight on both days.
     */
    fun getTimeEntriesForDate_includesEntrySpanningMidnightOnBothDays() =
        runBlocking {
            /** Today. */
            val today = LocalDate.now()
            /** Yesterday. */
            val yesterday = today.minusDays(1)
            repository.createTimeEntry(
                /** Create test time entry input. */
                createTestTimeEntryInput("task-overnight").copy(
                    startedAt = yesterday.atTime(23, 50),
                    endedAt = today.atTime(0, 20),
                ),
            )

            /** Today entries. */
            val todayEntries = repository.getTimeEntriesForDate(today).first()
            /** Yesterday entries. */
            val yesterdayEntries = repository.getTimeEntriesForDate(yesterday).first()

            /** Assert that. */
            assertThat(todayEntries.map { it.taskId }).contains("task-overnight")
            /** Assert that. */
            assertThat(yesterdayEntries.map { it.taskId }).contains("task-overnight")
        }

    @Test
    /**
     * Get all time entries returns all entries.
     */
    fun getAllTimeEntries_returnsAllEntries() =
        runBlocking {
            // Given
            repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            repository.stopActiveTimeEntry()
            repository.startTimeEntry(createTestTimeEntryInput("task-2"))
            repository.stopActiveTimeEntry()

            // When
            /** All entries. */
            val allEntries = repository.getAllTimeEntries().first()

            // Then
            /** Assert that. */
            assertThat(allEntries).hasSize(2)
            /** Assert that. */
            assertThat(allEntries.map { it.taskId }).containsExactly("task-1", "task-2")
        }

    @Test
    /**
     * Get active time entries returns only active entries.
     */
    fun getActiveTimeEntries_returnsOnlyActiveEntries() =
        runBlocking {
            // Given
            repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            repository.stopActiveTimeEntry() // Stop first entry
            repository.startTimeEntry(createTestTimeEntryInput("task-2")) // Start second (active)

            // When
            /** Active entries. */
            val activeEntries = repository.getActiveTimeEntries().first()

            // Then
            /** Assert that. */
            assertThat(activeEntries).hasSize(1)
            /** Assert that. */
            assertThat(activeEntries.first().taskId).isEqualTo("task-2")
            /** Assert that. */
            assertThat(activeEntries.first().endedAt).isNull()
        }

    @Test
    /**
     * Update time entry modifies existing entry.
     */
    fun updateTimeEntry_modifiesExistingEntry() =
        runBlocking {
            // Given
            /** Created entry. */
            val createdEntry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            /** Update input. */
            val updateInput = createTestTimeEntryInput("task-2")

            // When
            /** Updated entry. */
            val updatedEntry = repository.updateTimeEntry(createdEntry.id, updateInput)

            // Then
            /** Assert that. */
            assertThat(updatedEntry.id).isEqualTo(createdEntry.id)
            /** Assert that. */
            assertThat(updatedEntry.taskId).isEqualTo("task-2")
        }

    @Test
    /**
     * Delete time entry removes entry from database.
     */
    fun deleteTimeEntry_removesEntryFromDatabase() =
        runBlocking {
            // Given
            /** Entry. */
            val entry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            /** Entry id. */
            val entryId = entry.id

            // Verify entry exists
            /** All entries before. */
            val allEntriesBefore = repository.getAllTimeEntries().first()
            /** Assert that. */
            assertThat(allEntriesBefore).hasSize(1)

            // When
            repository.deleteTimeEntry(entryId)

            // Then
            /** All entries after. */
            val allEntriesAfter = repository.getAllTimeEntries().first()
            /** Assert that. */
            assertThat(allEntriesAfter).isEmpty()
        }

    private fun createTestTimeEntryInput(taskId: String): TimeEntryInput {
        /** Now. */
        val now = LocalDateTime.now()
        return TimeEntryInput(
            lifeIntentionCategory = "Career & Work",
            taskId = taskId,
            startedAt = now,
            endedAt = now.plusMinutes(30),
        )
    }
}
