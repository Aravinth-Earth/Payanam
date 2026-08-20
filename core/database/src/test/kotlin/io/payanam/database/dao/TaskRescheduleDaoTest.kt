//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.TaskEntity
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
 * TaskRescheduleDaoTest.
 */
class TaskRescheduleDaoTest {
    private lateinit var database: PayanamDatabase
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
     * Insert and get reschedules for task.
     */
    fun insert_and_getReschedulesForTask() {
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            /** Reschedule. */
            val reschedule = createTestTaskReschedule("res-1", "task-1")
            taskRescheduleDao.insert(reschedule)

            /** Reschedules. */
            val reschedules = taskRescheduleDao.getReschedulesForTask("task-1").first()
            /** Assert that. */
            assertThat(reschedules).hasSize(1)
            /** Assert that. */
            assertThat(reschedules[0].id).isEqualTo("res-1")
            /** Assert that. */
            assertThat(reschedules[0].newDueDate).isEqualTo("2026-02-03")
        }
    }

    @Test
    /**
     * Get reschedules for task empty.
     */
    fun getReschedulesForTask_empty() {
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            /** Reschedules. */
            val reschedules = taskRescheduleDao.getReschedulesForTask("task-1").first()
            /** Assert that. */
            assertThat(reschedules).isEmpty()
        }
    }

    @Test
    /**
     * Insert multiple reschedules.
     */
    fun insert_multiple_reschedules() {
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            /** Reschedule1. */
            val reschedule1 = createTestTaskReschedule("res-1", "task-1")
            /** Reschedule2. */
            val reschedule2 = createTestTaskReschedule("res-2", "task-1")
            taskRescheduleDao.insert(reschedule1)
            taskRescheduleDao.insert(reschedule2)

            /** Reschedules. */
            val reschedules = taskRescheduleDao.getReschedulesForTask("task-1").first()
            /** Assert that. */
            assertThat(reschedules).hasSize(2)
            /** Assert that. */
            assertThat(reschedules.map { it.id }).containsExactly("res-1", "res-2")
        }
    }

    @Test
    /**
     * Insert replace on conflict.
     */
    fun insert_replace_on_conflict() {
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1")
            taskDao.insert(task)

            /** Reschedule1. */
            val reschedule1 = createTestTaskReschedule("res-1", "task-1")
            /** Reschedule2. */
            val reschedule2 = reschedule1.copy(newDueDate = "2026-02-04") // Same ID, different data
            taskRescheduleDao.insert(reschedule1)
            taskRescheduleDao.insert(reschedule2) // Should replace due to OnConflictStrategy.REPLACE

            /** Reschedules. */
            val reschedules = taskRescheduleDao.getReschedulesForTask("task-1").first()
            /** Assert that. */
            assertThat(reschedules).hasSize(1)
            /** Assert that. */
            assertThat(reschedules[0].newDueDate).isEqualTo("2026-02-04")
        }
    }

    private fun createTestTaskReschedule(
        /** Id. */
        id: String,
        /** Task id. */
        taskId: String,
    ) = TaskRescheduleEntity(
        id = id,
        taskId = taskId,
        previousDueDate = "2026-02-02",
        newDueDate = "2026-02-03",
        rescheduledAt = "2026-02-02T10:00:00Z",
        wasOverdue = 1,
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
