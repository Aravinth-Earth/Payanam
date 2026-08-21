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
 * Provides the time entry repository integration test.
 */
class TimeEntryRepositoryIntegrationTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: TimeEntryRepository
    private lateinit var sessionManager: DatabaseSessionManager

    @Before
    /**
     * Updates the setup.
     */
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
        repository = TimeEntryRepositoryImpl(sessionManager)
    }

    @After
    /**
     * Performs the tear down.
     */
    fun tearDown() {
        sessionManager.closeDatabase()
        database.close()
    }

    @Test
    /**
     * Performs the start time entry creates new time entry and returns it.
     */
    fun startTimeEntry_createsNewTimeEntryAndReturnsIt() =
        runBlocking {
            // Given
            val input = createTestTimeEntryInput("task-1")

            // When
            val createdEntry = repository.startTimeEntry(input)

            // Then
            assertThat(createdEntry.id).isNotEmpty()
            assertThat(createdEntry.taskId).isEqualTo("task-1")
            assertThat(createdEntry.startedAt).isNotNull()
            assertThat(createdEntry.endedAt).isNull() // Active entry
        }

    @Test
    /**
     * Returns the active time entry returns currently active entry.
     */
    fun getActiveTimeEntry_returnsCurrentlyActiveEntry() =
        runBlocking {
            // Given
            val activeEntry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            repository.startTimeEntry(createTestTimeEntryInput("task-2")) // This should become active

            // When
            val active = repository.getActiveTimeEntry()

            // Then
            assertThat(active).isNotNull()
            assertThat(active?.taskId).isEqualTo("task-2")
            assertThat(active?.endedAt).isNull()
        }

    @Test
    /**
     * Returns the active time entry returns null when no active entry.
     */
    fun getActiveTimeEntry_returnsNullWhenNoActiveEntry() =
        runBlocking {
            // Given - no entries

            // When
            val active = repository.getActiveTimeEntry()

            // Then
            assertThat(active).isNull()
        }

    @Test
    /**
     * Performs the stop active time entry stops current active entry.
     */
    fun stopActiveTimeEntry_stopsCurrentActiveEntry() =
        runBlocking {
            // Given
            val activeEntry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))

            // When
            val stoppedEntry = repository.stopActiveTimeEntry()

            // Then
            assertThat(stoppedEntry).isNotNull()
            assertThat(stoppedEntry?.id).isEqualTo(activeEntry.id)
            assertThat(stoppedEntry?.endedAt).isNotNull()

            // Verify no active entry remains
            assertThat(repository.getActiveTimeEntry()).isNull()
        }

    @Test
    /**
     * Performs the stop active time entry returns null when no active entry.
     */
    fun stopActiveTimeEntry_returnsNullWhenNoActiveEntry() =
        runBlocking {
            // Given - no active entry

            // When
            val stoppedEntry = repository.stopActiveTimeEntry()

            // Then
            assertThat(stoppedEntry).isNull()
        }

    @Test
    /**
     * Get time entries for date returns entries for specific date.
     */
    fun getTimeEntriesForDate_returnsEntriesForSpecificDate() =
        runBlocking {
            // Given
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val todayEntry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            repository.stopActiveTimeEntry() // Stop it
            val yesterdayEntry =
                repository.createTimeEntry(
                    createTestTimeEntryInput("task-2").copy(
                        startedAt = yesterday.atStartOfDay(),
                        endedAt = yesterday.atStartOfDay().plusHours(1),
                    ),
                )

            // When
            val todayEntries = repository.getTimeEntriesForDate(today).first()
            val yesterdayEntries = repository.getTimeEntriesForDate(yesterday).first()

            // Then
            assertThat(todayEntries).hasSize(1)
            assertThat(todayEntries.first().taskId).isEqualTo("task-1")
            assertThat(yesterdayEntries).hasSize(1)
            assertThat(yesterdayEntries.first().taskId).isEqualTo("task-2")
        }

    @Test
    /**
     * Get time entries for date includes entry spanning midnight on both days.
     */
    fun getTimeEntriesForDate_includesEntrySpanningMidnightOnBothDays() =
        runBlocking {
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            repository.createTimeEntry(
                createTestTimeEntryInput("task-overnight").copy(
                    startedAt = yesterday.atTime(23, 50),
                    endedAt = today.atTime(0, 20),
                ),
            )
            val todayEntries = repository.getTimeEntriesForDate(today).first()
            val yesterdayEntries = repository.getTimeEntriesForDate(yesterday).first()
            assertThat(todayEntries.map { it.taskId }).contains("task-overnight")
            assertThat(yesterdayEntries.map { it.taskId }).contains("task-overnight")
        }

    @Test
    /**
     * Returns the all time entries returns all entries.
     */
    fun getAllTimeEntries_returnsAllEntries() =
        runBlocking {
            // Given
            repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            repository.stopActiveTimeEntry()
            repository.startTimeEntry(createTestTimeEntryInput("task-2"))
            repository.stopActiveTimeEntry()

            // When
            val allEntries = repository.getAllTimeEntries().first()

            // Then
            assertThat(allEntries).hasSize(2)
            assertThat(allEntries.map { it.taskId }).containsExactly("task-1", "task-2")
        }

    @Test
    /**
     * Returns the active time entries returns only active entries.
     */
    fun getActiveTimeEntries_returnsOnlyActiveEntries() =
        runBlocking {
            // Given
            repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            repository.stopActiveTimeEntry() // Stop first entry
            repository.startTimeEntry(createTestTimeEntryInput("task-2")) // Start second (active)

            // When
            val activeEntries = repository.getActiveTimeEntries().first()

            // Then
            assertThat(activeEntries).hasSize(1)
            assertThat(activeEntries.first().taskId).isEqualTo("task-2")
            assertThat(activeEntries.first().endedAt).isNull()
        }

    @Test
    /**
     * Updates the update time entry modifies existing entry.
     */
    fun updateTimeEntry_modifiesExistingEntry() =
        runBlocking {
            // Given
            val createdEntry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            val updateInput = createTestTimeEntryInput("task-2")

            // When
            val updatedEntry = repository.updateTimeEntry(createdEntry.id, updateInput)

            // Then
            assertThat(updatedEntry.id).isEqualTo(createdEntry.id)
            assertThat(updatedEntry.taskId).isEqualTo("task-2")
        }

    @Test
    /**
     * Removes the delete time entry removes entry from database.
     */
    fun deleteTimeEntry_removesEntryFromDatabase() =
        runBlocking {
            // Given
            val entry = repository.startTimeEntry(createTestTimeEntryInput("task-1"))
            val entryId = entry.id

            // Verify entry exists
            val allEntriesBefore = repository.getAllTimeEntries().first()
            assertThat(allEntriesBefore).hasSize(1)

            // When
            repository.deleteTimeEntry(entryId)

            // Then
            val allEntriesAfter = repository.getAllTimeEntries().first()
            assertThat(allEntriesAfter).isEmpty()
        }

    private fun createTestTimeEntryInput(taskId: String): TimeEntryInput {
        val now = LocalDateTime.now()
        return TimeEntryInput(
            lifeIntentionCategory = "Career & Work",
            taskId = taskId,
            startedAt = now,
            endedAt = now.plusMinutes(30),
        )
    }
}
