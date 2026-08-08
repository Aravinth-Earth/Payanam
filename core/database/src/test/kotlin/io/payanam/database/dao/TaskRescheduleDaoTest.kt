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
class TaskRescheduleDaoTest {
    private lateinit var database: PayanamDatabase
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
        taskRescheduleDao = database.taskRescheduleDao()
        taskDao = database.taskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_and_getReschedulesForTask() {
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

            val reschedule = createTestTaskReschedule("res-1", "task-1")
            taskRescheduleDao.insert(reschedule)

            val reschedules = taskRescheduleDao.getReschedulesForTask("task-1").first()
            assertThat(reschedules).hasSize(1)
            assertThat(reschedules[0].id).isEqualTo("res-1")
            assertThat(reschedules[0].newDueDate).isEqualTo("2026-02-03")
        }
    }

    @Test
    fun getReschedulesForTask_empty() {
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

            val reschedules = taskRescheduleDao.getReschedulesForTask("task-1").first()
            assertThat(reschedules).isEmpty()
        }
    }

    @Test
    fun insert_multiple_reschedules() {
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

            val reschedule1 = createTestTaskReschedule("res-1", "task-1")
            val reschedule2 = createTestTaskReschedule("res-2", "task-1")
            taskRescheduleDao.insert(reschedule1)
            taskRescheduleDao.insert(reschedule2)

            val reschedules = taskRescheduleDao.getReschedulesForTask("task-1").first()
            assertThat(reschedules).hasSize(2)
            assertThat(reschedules.map { it.id }).containsExactly("res-1", "res-2")
        }
    }

    @Test
    fun insert_replace_on_conflict() {
        runBlocking {
            val task = createTestTask("task-1")
            taskDao.insert(task)

            val reschedule1 = createTestTaskReschedule("res-1", "task-1")
            val reschedule2 = reschedule1.copy(newDueDate = "2026-02-04") // Same ID, different data
            taskRescheduleDao.insert(reschedule1)
            taskRescheduleDao.insert(reschedule2) // Should replace due to OnConflictStrategy.REPLACE

            val reschedules = taskRescheduleDao.getReschedulesForTask("task-1").first()
            assertThat(reschedules).hasSize(1)
            assertThat(reschedules[0].newDueDate).isEqualTo("2026-02-04")
        }
    }

    private fun createTestTaskReschedule(
        id: String,
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
