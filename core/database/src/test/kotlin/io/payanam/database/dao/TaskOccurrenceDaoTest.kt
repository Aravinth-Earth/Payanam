//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.entity.TaskRescheduleEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * TaskOccurrenceDaoTest.
 */
class TaskOccurrenceDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var taskOccurrenceDao: TaskOccurrenceDao
    private lateinit var taskRescheduleDao: TaskRescheduleDao
    private lateinit var taskDao: TaskDao

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
        taskOccurrenceDao = database.taskOccurrenceDao()
        taskRescheduleDao = database.taskRescheduleDao()
        taskDao = database.taskDao()
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
     * Insert and get occurrences for task.
     */
    fun insert_and_getOccurrencesForTask() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            /** Occurrence. */
            val occurrence = createTestTaskOccurrence("occ-1", "task-1", "2026-02-02")
            taskOccurrenceDao.insert(occurrence)

            /** Occurrences. */
            val occurrences = taskOccurrenceDao.getOccurrencesForTask("task-1").first()
            /** Assert that. */
            assertThat(occurrences).hasSize(1)
            /** Assert that. */
            assertThat(occurrences[0].id).isEqualTo("occ-1")
        }

    @Test
    /**
     * Get occurrences for date filters by date.
     */
    fun getOccurrencesForDate_filtersByDate() =
        runBlocking {
            /** Task1. */
            val task1 = createTestTask("task-1")
            /** Task2. */
            val task2 = createTestTask("task-2")
            taskDao.insert(task1)
            taskDao.insert(task2)

            /** Occurrence1. */
            val occurrence1 = createTestTaskOccurrence("occ-1", "task-1", "2026-02-01")
            /** Occurrence2. */
            val occurrence2 = createTestTaskOccurrence("occ-2", "task-2", "2026-02-02")

            taskOccurrenceDao.insert(occurrence1)
            taskOccurrenceDao.insert(occurrence2)

            /** Todays occurrences. */
            val todaysOccurrences = taskOccurrenceDao.getOccurrencesForDate("2026-02-02").first()
            /** Assert that. */
            assertThat(todaysOccurrences).hasSize(1)
            /** Assert that. */
            assertThat(todaysOccurrences[0].id).isEqualTo("occ-2")
        }

    @Test
    /**
     * Get occurrence for task on date returns specific occurrence.
     */
    fun getOccurrenceForTaskOnDate_returnsSpecificOccurrence() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            /** Occurrence. */
            val occurrence = createTestTaskOccurrence("occ-1", "task-1", "2026-02-02")
            taskOccurrenceDao.insert(occurrence)

            /** Retrieved. */
            val retrieved = taskOccurrenceDao.getOccurrenceForTaskOnDate("task-1", "2026-02-02")
            /** Assert that. */
            assertThat(retrieved).isNotNull()
            /** Assert that. */
            assertThat(retrieved?.id).isEqualTo("occ-1")
        }

    @Test
    /**
     * Get occurrences for task in range filters by date range.
     */
    fun getOccurrencesForTaskInRange_filtersByDateRange() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            /** Occurrence1. */
            val occurrence1 = createTestTaskOccurrence("occ-1", "task-1", "2026-01-30")
            /** Occurrence2. */
            val occurrence2 = createTestTaskOccurrence("occ-2", "task-1", "2026-02-02")
            /** Occurrence3. */
            val occurrence3 = createTestTaskOccurrence("occ-3", "task-1", "2026-02-10")

            taskOccurrenceDao.insert(occurrence1)
            taskOccurrenceDao.insert(occurrence2)
            taskOccurrenceDao.insert(occurrence3)

            /** Range occurrences. */
            val rangeOccurrences = taskOccurrenceDao.getOccurrencesForTaskInRange("task-1", "2026-02-01", "2026-02-05")
            /** Assert that. */
            assertThat(rangeOccurrences).hasSize(1)
            /** Assert that. */
            assertThat(rangeOccurrences[0].id).isEqualTo("occ-2")
        }

    @Test
    /**
     * Update occurrence modifies status.
     */
    fun updateOccurrence_modifiesStatus() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            /** Occurrence. */
            val occurrence = createTestTaskOccurrence("occ-1", "task-1", "2026-02-02", status = "pending")
            taskOccurrenceDao.insert(occurrence)

            taskOccurrenceDao.updateOccurrence(
                id = "occ-1",
                status = "completed",
                statusReason = null,
                note = "Completed successfully",
                completedAt = "2026-02-02T10:00:00Z",
                actualCompletedAt = "2026-02-02T10:30:00Z",
                actualDurationMinutes = 30,
            )

            /** Retrieved. */
            val retrieved = taskOccurrenceDao.getOccurrenceForTaskOnDate("task-1", "2026-02-02")
            /** Assert that. */
            assertThat(retrieved?.status).isEqualTo("completed")
            /** Assert that. */
            assertThat(retrieved?.note).isEqualTo("Completed successfully")
            /** Assert that. */
            assertThat(retrieved?.actualDurationMinutes).isEqualTo(30)
        }

    @Test
    /**
     * Delete by id removes occurrence.
     */
    fun deleteById_removesOccurrence() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            /** Occurrence. */
            val occurrence = createTestTaskOccurrence("occ-1", "task-1", "2026-02-02")
            taskOccurrenceDao.insert(occurrence)

            taskOccurrenceDao.deleteById("occ-1")

            /** Retrieved. */
            val retrieved = taskOccurrenceDao.getOccurrenceForTaskOnDate("task-1", "2026-02-02")
            /** Assert that. */
            assertThat(retrieved).isNull()
        }

    @Test
    /**
     * Get occurrences for tasks in range bulk loads occurrences.
     */
    fun getOccurrencesForTasksInRange_bulkLoadsOccurrences() =
        runBlocking {
            /** Task1. */
            val task1 = createTestTask("task-1")
            /** Task2. */
            val task2 = createTestTask("task-2")
            taskDao.insert(task1)
            taskDao.insert(task2)

            /** Occurrence1. */
            val occurrence1 = createTestTaskOccurrence("occ-1", "task-1", "2026-02-02")
            /** Occurrence2. */
            val occurrence2 = createTestTaskOccurrence("occ-2", "task-2", "2026-02-02")

            taskOccurrenceDao.insert(occurrence1)
            taskOccurrenceDao.insert(occurrence2)

            /** Bulk occurrences. */
            val bulkOccurrences =
                taskOccurrenceDao.getOccurrencesForTasksInRange(
                    taskIds = listOf("task-1", "task-2"),
                    startDate = "2026-02-01",
                    endDate = "2026-02-03",
                )
            /** Assert that. */
            assertThat(bulkOccurrences).hasSize(2)
        }

    @Test
    /**
     * Get all occurrences returns all rows.
     */
    fun getAllOccurrences_returnsAllRows() =
        runBlocking {
            /** Task1. */
            val task1 = createTestTask("task-1")
            /** Task2. */
            val task2 = createTestTask("task-2")
            taskDao.insert(task1)
            taskDao.insert(task2)

            taskOccurrenceDao.insert(createTestTaskOccurrence("occ-1", "task-1", "2026-02-02"))
            taskOccurrenceDao.insert(createTestTaskOccurrence("occ-2", "task-2", "2026-02-03"))

            /** All occurrences. */
            val allOccurrences = taskOccurrenceDao.getAllOccurrences()
            /** Assert that. */
            assertThat(allOccurrences).hasSize(2)
        }

    @Test
    /**
     * Get all reschedules returns all rows.
     */
    fun getAllReschedules_returnsAllRows() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            taskRescheduleDao.insert(createTestReschedule("res-1", "task-1", "2026-02-01", "2026-02-02"))
            taskRescheduleDao.insert(createTestReschedule("res-2", "task-1", "2026-02-02", "2026-02-03"))

            /** All reschedules. */
            val allReschedules = taskRescheduleDao.getAllReschedules()
            /** Assert that. */
            assertThat(allReschedules).hasSize(2)
        }

    @Test
    /**
     * Deleting task cascades occurrence and reschedule rows.
     */
    fun deletingTask_cascadesOccurrenceAndRescheduleRows() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)
            taskOccurrenceDao.insert(createTestTaskOccurrence("occ-1", "task-1", "2026-02-02"))
            taskRescheduleDao.insert(createTestReschedule("res-1", "task-1", "2026-02-01", "2026-02-02"))

            taskDao.deleteById("task-1")

            /** Assert that. */
            assertThat(taskOccurrenceDao.getAllOccurrences()).isEmpty()
            /** Assert that. */
            assertThat(taskRescheduleDao.getAllReschedules()).isEmpty()
        }

    private fun createTestTaskOccurrence(
        /** Id. */
        id: String,
        /** Task id. */
        taskId: String,
        /** Due date. */
        dueDate: String,
        status: String = "pending",
    ) = TaskOccurrenceEntity(
        id = id,
        taskId = taskId,
        dueDate = dueDate,
        completedAt = null,
        actualCompletedAt = null,
        actualDurationMinutes = null,
        status = status,
        createdAt = "2026-02-01T09:00:00Z",
        completionRate = null,
        note = null,
    )

    private fun createTestReschedule(
        /** Id. */
        id: String,
        /** Task id. */
        taskId: String,
        /** Previous due date. */
        previousDueDate: String,
        /** New due date. */
        newDueDate: String,
    ) = TaskRescheduleEntity(
        id = id,
        taskId = taskId,
        previousDueDate = previousDueDate,
        newDueDate = newDueDate,
        rescheduledAt = "2026-02-01T09:30:00Z",
        wasOverdue = 0,
    )

    private fun createTestTask(id: String) =
        /** Task entity. */
        TaskEntity(
            id = id,
            title = "Test Task",
            description = "Test description",
            status = "pending",
            dueDate = null,
            createdAt = "2026-02-01T09:00:00Z",
            updatedAt = "2026-02-01T09:00:00Z",
            completedAt = null,
            archivedAt = null,
            recurrenceEnabled = 0,
            recurrenceRule = null,
            durationMinutes = 30,
            impactLevel = "medium",
            goalAlignment = "personal",
            energyLevel = "medium",
            controlLevel = "high",
            lifeIntentionCategory = "health",
            explicitUrgency = null,
            focusRequired = null,
            blockedReason = null,
            completionRate = null,
            externalDependency = null,
            notificationMode = "default",
            customNotificationMinutes = null,
            taskScore = 0.5,
            lastOccurrenceDate = null,
        )
}
