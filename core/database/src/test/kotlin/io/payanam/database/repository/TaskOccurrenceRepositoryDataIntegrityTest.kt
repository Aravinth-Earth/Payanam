//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.TaskEntity
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.TaskOccurrence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
/**
 * TaskOccurrenceRepositoryDataIntegrityTest.
 */
class TaskOccurrenceRepositoryDataIntegrityTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: TaskOccurrenceRepositoryImpl

    @Before
    /**
     * Setup.
     */
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        val encryptionManager = DatabaseEncryptionManager(context)
        val sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(database)
        repository = TaskOccurrenceRepositoryImpl(sessionManager)
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
     * Record occurrence persists actual completion fields.
     */
    fun recordOccurrence_persistsActualCompletionFields() =
        runBlocking {
            val day = LocalDate.of(2026, 2, 21)
            val now = day.atStartOfDay()
            val taskId = "habit_integrity_1"
            database.taskDao().insert(
                TaskEntity(
                    id = taskId,
                    title = "Hydration",
                    status = "pending",
                    dueDate = day.atTime(9, 0).toString(),
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                    recurrenceEnabled = 1,
                    durationMinutes = 10,
                    lifeIntentionCategory = "Health & Wellness",
                    dimensionId = null,
                    dayKey = day.toString(),
                ),
            )
            val actualCompletedAt = LocalDateTime.of(2026, 2, 21, 9, 12)
            repository.recordOccurrence(
                TaskOccurrence(
                    id = "occ_integrity_1",
                    taskId = taskId,
                    occurrenceDate = day.toString(),
                    status = "completed",
                    statusNote = "done",
                    actualCompletedAt = actualCompletedAt,
                    actualDurationMinutes = 7,
                ),
            )
            val stored = database.taskOccurrenceDao().getOccurrenceForTaskOnDate(taskId, day.toString())
            assertThat(stored).isNotNull()
            val storedCompletedAt = stored?.actualCompletedAt?.let(LocalDateTime::parse)
            assertThat(storedCompletedAt).isEqualTo(actualCompletedAt)
            assertThat(stored?.actualDurationMinutes).isEqualTo(7)
        }

    @Test
    /**
     * Toggle occurrence completed clears stale status reason when not provided.
     */
    fun toggleOccurrence_completed_clearsStaleStatusReasonWhenNotProvided() =
        runBlocking {
            val day = LocalDate.of(2026, 2, 22)
            val now = day.atStartOfDay()
            val taskId = "habit_integrity_2"
            database.taskDao().insert(
                TaskEntity(
                    id = taskId,
                    title = "Walk",
                    status = "pending",
                    dueDate = day.atTime(8, 0).toString(),
                    createdAt = now.toString(),
                    updatedAt = now.toString(),
                    recurrenceEnabled = 1,
                    durationMinutes = 20,
                    lifeIntentionCategory = "Health & Wellness",
                    dayKey = day.toString(),
                ),
            )
            val existingId = UUID.randomUUID().toString()
            database.taskOccurrenceDao().insert(
                io.payanam.database.entity.TaskOccurrenceEntity(
                    id = existingId,
                    taskId = taskId,
                    dueDate = day.toString(),
                    completedAt = null,
                    actualCompletedAt = null,
                    actualDurationMinutes = null,
                    status = "missed",
                    statusReason = "NO_TIME",
                    createdAt = now.toString(),
                    completionRate = null,
                    note = null,
                ),
            )

            repository.toggleOccurrence(
                taskId = taskId,
                date = day,
                newStatus = "completed",
                note = null,
                reason = null,
                actualCompletedAt = null,
                actualDurationMinutes = null,
            )
            val stored = database.taskOccurrenceDao().getOccurrenceForTaskOnDate(taskId, day.toString())
            assertThat(stored).isNotNull()
            assertThat(stored?.status).isEqualTo("completed")
            assertThat(stored?.statusReason).isNull()
        }
}
