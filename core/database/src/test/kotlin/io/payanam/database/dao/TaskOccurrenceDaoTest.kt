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
class TaskOccurrenceDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var taskOccurrenceDao: TaskOccurrenceDao
    private lateinit var taskRescheduleDao: TaskRescheduleDao
    private lateinit var taskDao: TaskDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
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
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_and_getOccurrencesForTask() =
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

            val occurrence = createTestTaskOccurrence("occ-1", "task-1", "2026-02-02")
            taskOccurrenceDao.insert(occurrence)

            val occurrences = taskOccurrenceDao.getOccurrencesForTask("task-1").first()
            assertThat(occurrences).hasSize(1)
            assertThat(occurrences[0].id).isEqualTo("occ-1")
        }

    @Test
    fun getOccurrencesForDate_filtersByDate() =
        runBlocking {
            val task1 = createTestTask("task-1")
            val task2 = createTestTask("task-2")
            taskDao.insert(task1)
            taskDao.insert(task2)

            val occurrence1 = createTestTaskOccurrence("occ-1", "task-1", "2026-02-01")
            val occurrence2 = createTestTaskOccurrence("occ-2", "task-2", "2026-02-02")

            taskOccurrenceDao.insert(occurrence1)
            taskOccurrenceDao.insert(occurrence2)

            val todaysOccurrences = taskOccurrenceDao.getOccurrencesForDate("2026-02-02").first()
            assertThat(todaysOccurrences).hasSize(1)
            assertThat(todaysOccurrences[0].id).isEqualTo("occ-2")
        }

    @Test
    fun getOccurrenceForTaskOnDate_returnsSpecificOccurrence() =
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

            val occurrence = createTestTaskOccurrence("occ-1", "task-1", "2026-02-02")
            taskOccurrenceDao.insert(occurrence)

            val retrieved = taskOccurrenceDao.getOccurrenceForTaskOnDate("task-1", "2026-02-02")
            assertThat(retrieved).isNotNull()
            assertThat(retrieved?.id).isEqualTo("occ-1")
        }

    @Test
    fun getOccurrencesForTaskInRange_filtersByDateRange() =
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

            val occurrence1 = createTestTaskOccurrence("occ-1", "task-1", "2026-01-30")
            val occurrence2 = createTestTaskOccurrence("occ-2", "task-1", "2026-02-02")
            val occurrence3 = createTestTaskOccurrence("occ-3", "task-1", "2026-02-10")

            taskOccurrenceDao.insert(occurrence1)
            taskOccurrenceDao.insert(occurrence2)
            taskOccurrenceDao.insert(occurrence3)

            val rangeOccurrences = taskOccurrenceDao.getOccurrencesForTaskInRange("task-1", "2026-02-01", "2026-02-05")
            assertThat(rangeOccurrences).hasSize(1)
            assertThat(rangeOccurrences[0].id).isEqualTo("occ-2")
        }

    @Test
    fun updateOccurrence_modifiesStatus() =
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

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

            val retrieved = taskOccurrenceDao.getOccurrenceForTaskOnDate("task-1", "2026-02-02")
            assertThat(retrieved?.status).isEqualTo("completed")
            assertThat(retrieved?.note).isEqualTo("Completed successfully")
            assertThat(retrieved?.actualDurationMinutes).isEqualTo(30)
        }

    @Test
    fun deleteById_removesOccurrence() =
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

            val occurrence = createTestTaskOccurrence("occ-1", "task-1", "2026-02-02")
            taskOccurrenceDao.insert(occurrence)

            taskOccurrenceDao.deleteById("occ-1")

            val retrieved = taskOccurrenceDao.getOccurrenceForTaskOnDate("task-1", "2026-02-02")
            assertThat(retrieved).isNull()
        }

    @Test
    fun getOccurrencesForTasksInRange_bulkLoadsOccurrences() =
        runBlocking {
            val task1 = createTestTask("task-1")
            val task2 = createTestTask("task-2")
            taskDao.insert(task1)
            taskDao.insert(task2)

            val occurrence1 = createTestTaskOccurrence("occ-1", "task-1", "2026-02-02")
            val occurrence2 = createTestTaskOccurrence("occ-2", "task-2", "2026-02-02")

            taskOccurrenceDao.insert(occurrence1)
            taskOccurrenceDao.insert(occurrence2)

            val bulkOccurrences =
                taskOccurrenceDao.getOccurrencesForTasksInRange(
                    taskIds = listOf("task-1", "task-2"),
                    startDate = "2026-02-01",
                    endDate = "2026-02-03",
                )
            assertThat(bulkOccurrences).hasSize(2)
        }

    @Test
    fun getAllOccurrences_returnsAllRows() =
        runBlocking {
            val task1 = createTestTask("task-1")
            val task2 = createTestTask("task-2")
            taskDao.insert(task1)
            taskDao.insert(task2)

            taskOccurrenceDao.insert(createTestTaskOccurrence("occ-1", "task-1", "2026-02-02"))
            taskOccurrenceDao.insert(createTestTaskOccurrence("occ-2", "task-2", "2026-02-03"))

            val allOccurrences = taskOccurrenceDao.getAllOccurrences()
            assertThat(allOccurrences).hasSize(2)
        }

    @Test
    fun getAllReschedules_returnsAllRows() =
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

            taskRescheduleDao.insert(createTestReschedule("res-1", "task-1", "2026-02-01", "2026-02-02"))
            taskRescheduleDao.insert(createTestReschedule("res-2", "task-1", "2026-02-02", "2026-02-03"))

            val allReschedules = taskRescheduleDao.getAllReschedules()
            assertThat(allReschedules).hasSize(2)
        }

    @Test
    fun deletingTask_cascadesOccurrenceAndRescheduleRows() =
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)
            taskOccurrenceDao.insert(createTestTaskOccurrence("occ-1", "task-1", "2026-02-02"))
            taskRescheduleDao.insert(createTestReschedule("res-1", "task-1", "2026-02-01", "2026-02-02"))

            taskDao.deleteById("task-1")

            assertThat(taskOccurrenceDao.getAllOccurrences()).isEmpty()
            assertThat(taskRescheduleDao.getAllReschedules()).isEmpty()
        }

    private fun createTestTaskOccurrence(
        id: String,
        taskId: String,
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
        id: String,
        taskId: String,
        previousDueDate: String,
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
            currentScore = 0.5,
            lastOccurrenceDate = null,
        )
}
