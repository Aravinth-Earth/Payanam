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
 * Provides the task dao test.
 */
class TaskDaoTest {
    private lateinit var database: PayanamDatabase
    private lateinit var taskDao: TaskDao

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
        taskDao = database.taskDao()
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
     * Performs the insert and get task by id.
     */
    fun insert_and_getTaskById() =
        runBlocking {
            val task = createTestTask("task-1", "Test Task")
            taskDao.insert(task)
            val retrieved = taskDao.getTaskById("task-1")
            assertThat(retrieved).isNotNull()
            assertThat(retrieved?.id).isEqualTo("task-1")
            assertThat(retrieved?.title).isEqualTo("Test Task")
        }

    @Test
    /**
     * Returns the get all tasks returns archived and non archived tasks.
     */
    fun getAllTasks_returnsArchivedAndNonArchivedTasks() {
        runBlocking {
            val activeTask = createTestTask("task-1", "Active", status = "pending")
            val archivedTask = createTestTask("task-2", "Archived", status = "archived")

            taskDao.insert(activeTask)
            taskDao.insert(archivedTask)
            val tasks = taskDao.getAllTasks().first()
            assertThat(tasks).hasSize(2)
            assertThat(tasks.map { it.id }).containsExactly("task-1", "task-2")
        }
    }

    @Test
    /**
     * Returns the get tasks by status filters correctly.
     */
    fun getTasksByStatus_filtersCorrectly() =
        runBlocking {
            val pendingTask = createTestTask("task-1", "Pending", status = "pending")
            val completedTask = createTestTask("task-2", "Completed", status = "completed")

            taskDao.insert(pendingTask)
            taskDao.insert(completedTask)
            val pendingTasks = taskDao.getTasksByStatus("pending").first()
            assertThat(pendingTasks).hasSize(1)
            assertThat(pendingTasks[0].status).isEqualTo("pending")
        }

    @Test
    /**
     * Updates the update status updates correctly.
     */
    fun updateStatus_updatesCorrectly() =
        runBlocking {
            val task = createTestTask("task-1", "Test")
            taskDao.insert(task)

            taskDao.updateStatus("task-1", "completed", "2026-02-02T10:00:00Z", "2026-02-02T10:00:00Z")
            val updated = taskDao.getTaskById("task-1")
            assertThat(updated?.status).isEqualTo("completed")
            assertThat(updated?.completedAt).isEqualTo("2026-02-02T10:00:00Z")
        }

    @Test
    /**
     * Removes the delete removes task.
     */
    fun delete_removesTask() =
        runBlocking {
            val task = createTestTask("task-1", "Test")
            taskDao.insert(task)

            taskDao.delete(task)
            val retrieved = taskDao.getTaskById("task-1")
            assertThat(retrieved).isNull()
        }

    @Test
    /**
     * Returns the get todays tasks includes due today and recurring.
     */
    fun getTodaysTasks_includesDueTodayAndRecurring() =
        runBlocking {
            val dueToday = createTestTask("task-1", "Due Today", dueDate = "2026-02-02T10:00:00Z")
            val recurring = createTestTask("task-2", "Recurring", recurrenceEnabled = 1)

            taskDao.insert(dueToday)
            taskDao.insert(recurring)
            val todaysTasks = taskDao.getTodaysTasks("2026-02-02").first()
            assertThat(todaysTasks).hasSize(2)
        }

    private fun createTestTask(
        id: String,
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
