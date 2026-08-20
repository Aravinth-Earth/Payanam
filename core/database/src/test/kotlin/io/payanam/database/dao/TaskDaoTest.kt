//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.TaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * TaskDaoTest.
 */
class TaskDaoTest {
    private lateinit var database: PayanamDatabase
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
     * Insert and get task by id.
     */
    fun insert_and_getTaskById() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1", "Test Task")
            taskDao.insert(task)

            /** Retrieved. */
            val retrieved = taskDao.getTaskById("task-1")
            /** Assert that. */
            assertThat(retrieved).isNotNull()
            /** Assert that. */
            assertThat(retrieved?.id).isEqualTo("task-1")
            /** Assert that. */
            assertThat(retrieved?.title).isEqualTo("Test Task")
        }

    @Test
    /**
     * Get all tasks returns archived and non archived tasks.
     */
    fun getAllTasks_returnsArchivedAndNonArchivedTasks() {
        runBlocking {
            /** Active task. */
            val activeTask = createTestTask("task-1", "Active", status = "pending")
            /** Archived task. */
            val archivedTask = createTestTask("task-2", "Archived", status = "archived")

            taskDao.insert(activeTask)
            taskDao.insert(archivedTask)

            /** Tasks. */
            val tasks = taskDao.getAllTasks().first()
            /** Assert that. */
            assertThat(tasks).hasSize(2)
            /** Assert that. */
            assertThat(tasks.map { it.id }).containsExactly("task-1", "task-2")
        }
    }

    @Test
    /**
     * Get tasks by status filters correctly.
     */
    fun getTasksByStatus_filtersCorrectly() =
        runBlocking {
            /** Pending task. */
            val pendingTask = createTestTask("task-1", "Pending", status = "pending")
            /** Completed task. */
            val completedTask = createTestTask("task-2", "Completed", status = "completed")

            taskDao.insert(pendingTask)
            taskDao.insert(completedTask)

            /** Pending tasks. */
            val pendingTasks = taskDao.getTasksByStatus("pending").first()
            /** Assert that. */
            assertThat(pendingTasks).hasSize(1)
            /** Assert that. */
            assertThat(pendingTasks[0].status).isEqualTo("pending")
        }

    @Test
    /**
     * Update status updates correctly.
     */
    fun updateStatus_updatesCorrectly() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1", "Test")
            taskDao.insert(task)

            taskDao.updateStatus("task-1", "completed", "2026-02-02T10:00:00Z", "2026-02-02T10:00:00Z")

            /** Updated. */
            val updated = taskDao.getTaskById("task-1")
            /** Assert that. */
            assertThat(updated?.status).isEqualTo("completed")
            /** Assert that. */
            assertThat(updated?.completedAt).isEqualTo("2026-02-02T10:00:00Z")
        }

    @Test
    /**
     * Delete removes task.
     */
    fun delete_removesTask() =
        runBlocking {
            /** Task. */
            val task = createTestTask("task-1", "Test")
            taskDao.insert(task)

            taskDao.delete(task)

            /** Retrieved. */
            val retrieved = taskDao.getTaskById("task-1")
            /** Assert that. */
            assertThat(retrieved).isNull()
        }

    @Test
    /**
     * Get todays tasks includes due today and recurring.
     */
    fun getTodaysTasks_includesDueTodayAndRecurring() =
        runBlocking {
            /** Due today. */
            val dueToday = createTestTask("task-1", "Due Today", dueDate = "2026-02-02T10:00:00Z")
            /** Recurring. */
            val recurring = createTestTask("task-2", "Recurring", recurrenceEnabled = 1)

            taskDao.insert(dueToday)
            taskDao.insert(recurring)

            /** Todays tasks. */
            val todaysTasks = taskDao.getTodaysTasks("2026-02-02").first()
            /** Assert that. */
            assertThat(todaysTasks).hasSize(2)
        }

    private fun createTestTask(
        /** Id. */
        id: String,
        /** Title. */
        title: String,
        status: String = "pending",
        dueDate: String? = null,
        recurrenceEnabled: Int = 0,
    ) = TaskEntity(
        id = id,
        title = title,
        description = "Test description",
        status = status,
        dueDate = dueDate,
        createdAt = "2026-02-01T09:00:00Z",
        updatedAt = "2026-02-01T09:00:00Z",
        completedAt = null,
        archivedAt = null,
        recurrenceEnabled = recurrenceEnabled,
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
