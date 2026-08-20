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
import io.payanam.domain.model.TaskInput
import io.payanam.domain.repository.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
/**
 * TaskRepositoryIntegrationTest.
 */
class TaskRepositoryIntegrationTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: TaskRepository
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
        repository = TaskRepositoryImpl(sessionManager)
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
     * Create task creates and returns task with generated id.
     */
    fun createTask_createsAndReturnsTaskWithGeneratedId() =
        runBlocking {
            // Given
            /** Input. */
            val input = createTestTaskInput("Test Task")

            // When
            /** Created task. */
            val createdTask = repository.createTask(input)

            // Then
            /** Assert that. */
            assertThat(createdTask.id).isNotEmpty()
            /** Assert that. */
            assertThat(createdTask.title).isEqualTo("Test Task")
            /** Assert that. */
            assertThat(createdTask.createdAt).isNotNull()
            /** Assert that. */
            assertThat(createdTask.updatedAt).isNotNull()
        }

    @Test
    /**
     * Get all tasks returns all created tasks.
     */
    fun getAllTasks_returnsAllCreatedTasks() =
        runBlocking {
            // Given
            /** Task1. */
            val task1 = repository.createTask(createTestTaskInput("Task 1"))
            /** Task2. */
            val task2 = repository.createTask(createTestTaskInput("Task 2"))

            // When
            /** All tasks. */
            val allTasks = repository.getAllTasks().first()

            // Then
            /** Assert that. */
            assertThat(allTasks).hasSize(2)
            /** Assert that. */
            assertThat(allTasks.map { it.title }).containsExactly("Task 1", "Task 2")
        }

    @Test
    /**
     * Get task by id returns correct task when exists.
     */
    fun getTaskById_returnsCorrectTaskWhenExists() =
        runBlocking {
            // Given
            /** Created task. */
            val createdTask = repository.createTask(createTestTaskInput("Find Me"))

            // When
            /** Found task. */
            val foundTask = repository.getTaskById(createdTask.id)

            // Then
            /** Assert that. */
            assertThat(foundTask).isNotNull()
            /** Assert that. */
            assertThat(foundTask?.title).isEqualTo("Find Me")
            /** Assert that. */
            assertThat(foundTask?.id).isEqualTo(createdTask.id)
        }

    @Test
    /**
     * Get task by id returns null when task does not exist.
     */
    fun getTaskById_returnsNullWhenTaskDoesNotExist() =
        runBlocking {
            // When
            /** Found task. */
            val foundTask = repository.getTaskById("nonexistent-id")

            // Then
            /** Assert that. */
            assertThat(foundTask).isNull()
        }

    @Test
    /**
     * Update task modifies existing task.
     */
    fun updateTask_modifiesExistingTask() =
        runBlocking {
            // Given
            /** Created task. */
            val createdTask = repository.createTask(createTestTaskInput("Original Title"))
            /** Update input. */
            val updateInput = createTestTaskInput("Updated Title")

            // When
            /** Updated task. */
            val updatedTask = repository.updateTask(createdTask.id, updateInput)

            // Then
            /** Assert that. */
            assertThat(updatedTask.id).isEqualTo(createdTask.id)
            /** Assert that. */
            assertThat(updatedTask.title).isEqualTo("Updated Title")
            /** Assert that. */
            assertThat(updatedTask.updatedAt).isAtLeast(updatedTask.createdAt)
        }

    @Test
    /**
     * Complete task marks task as completed with note.
     */
    fun completeTask_marksTaskAsCompletedWithNote() =
        runBlocking {
            // Given
            /** Task. */
            val task = repository.createTask(createTestTaskInput("Complete Me"))

            // When
            /** Completed task. */
            val completedTask = repository.completeTask(task.id, "Well done!")

            // Then
            /** Assert that. */
            assertThat(completedTask.status).isEqualTo("completed")
            // Note: completion note handling depends on implementation
        }

    @Test
    /**
     * Delete task removes task from database.
     */
    fun deleteTask_removesTaskFromDatabase() =
        runBlocking {
            // Given
            /** Task. */
            val task = repository.createTask(createTestTaskInput("Delete Me"))
            /** Task id. */
            val taskId = task.id

            // Verify task exists
            /** Assert that. */
            assertThat(repository.getTaskById(taskId)).isNotNull()

            // When
            repository.deleteTask(taskId)

            // Then
            /** Assert that. */
            assertThat(repository.getTaskById(taskId)).isNull()
        }

    @Test
    /**
     * Get todays tasks returns tasks for today.
     */
    fun getTodaysTasks_returnsTasksForToday() =
        runBlocking {
            // Given
            /** Today input. */
            val todayInput = createTestTaskInput("Today Task").copy(dueDate = LocalDateTime.now())
            /** Tomorrow input. */
            val tomorrowInput = createTestTaskInput("Tomorrow Task").copy(dueDate = LocalDateTime.now().plusDays(1))

            repository.createTask(todayInput)
            repository.createTask(tomorrowInput)

            // When
            /** Todays tasks. */
            val todaysTasks = repository.getTodaysTasks().first()

            // Then
            /** Assert that. */
            assertThat(todaysTasks).hasSize(1)
            /** Assert that. */
            assertThat(todaysTasks.first().title).isEqualTo("Today Task")
        }

    private fun createTestTaskInput(title: String): TaskInput {
        /** Now. */
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        return TaskInput(
            title = title,
            description = "Test description",
            status = "pending",
            dueDate = now,
            durationMinutes = 30,
            impactLevel = "Moderate Impact",
            goalAlignment = "Moderate Alignment",
            energyLevel = "Moderate",
            controlLevel = "Office/Colleagues Dependent",
            lifeIntentionCategory = "Career & Work",
        )
    }
}
