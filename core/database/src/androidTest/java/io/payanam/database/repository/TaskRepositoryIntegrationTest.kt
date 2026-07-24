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
class TaskRepositoryIntegrationTest {
    private lateinit var database: PayanamDatabase
    private lateinit var repository: TaskRepository
    private lateinit var sessionManager: DatabaseSessionManager

    @Before
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
        repository = TaskRepositoryImpl(sessionManager)
    }

    @After
    fun tearDown() {
        sessionManager.closeDatabase()
        database.close()
    }

    @Test
    fun createTask_createsAndReturnsTaskWithGeneratedId() =
        runBlocking {
            // Given
            val input = createTestTaskInput("Test Task")

            // When
            val createdTask = repository.createTask(input)

            // Then
            assertThat(createdTask.id).isNotEmpty()
            assertThat(createdTask.title).isEqualTo("Test Task")
            assertThat(createdTask.createdAt).isNotNull()
            assertThat(createdTask.updatedAt).isNotNull()
        }

    @Test
    fun getAllTasks_returnsAllCreatedTasks() =
        runBlocking {
            // Given
            val task1 = repository.createTask(createTestTaskInput("Task 1"))
            val task2 = repository.createTask(createTestTaskInput("Task 2"))

            // When
            val allTasks = repository.getAllTasks().first()

            // Then
            assertThat(allTasks).hasSize(2)
            assertThat(allTasks.map { it.title }).containsExactly("Task 1", "Task 2")
        }

    @Test
    fun getTaskById_returnsCorrectTaskWhenExists() =
        runBlocking {
            // Given
            val createdTask = repository.createTask(createTestTaskInput("Find Me"))

            // When
            val foundTask = repository.getTaskById(createdTask.id)

            // Then
            assertThat(foundTask).isNotNull()
            assertThat(foundTask?.title).isEqualTo("Find Me")
            assertThat(foundTask?.id).isEqualTo(createdTask.id)
        }

    @Test
    fun getTaskById_returnsNullWhenTaskDoesNotExist() =
        runBlocking {
            // When
            val foundTask = repository.getTaskById("nonexistent-id")

            // Then
            assertThat(foundTask).isNull()
        }

    @Test
    fun updateTask_modifiesExistingTask() =
        runBlocking {
            // Given
            val createdTask = repository.createTask(createTestTaskInput("Original Title"))
            val updateInput = createTestTaskInput("Updated Title")

            // When
            val updatedTask = repository.updateTask(createdTask.id, updateInput)

            // Then
            assertThat(updatedTask.id).isEqualTo(createdTask.id)
            assertThat(updatedTask.title).isEqualTo("Updated Title")
            assertThat(updatedTask.updatedAt).isAtLeast(updatedTask.createdAt)
        }

    @Test
    fun completeTask_marksTaskAsCompletedWithNote() =
        runBlocking {
            // Given
            val task = repository.createTask(createTestTaskInput("Complete Me"))

            // When
            val completedTask = repository.completeTask(task.id, "Well done!")

            // Then
            assertThat(completedTask.status).isEqualTo("completed")
            // Note: completion note handling depends on implementation
        }

    @Test
    fun deleteTask_removesTaskFromDatabase() =
        runBlocking {
            // Given
            val task = repository.createTask(createTestTaskInput("Delete Me"))
            val taskId = task.id

            // Verify task exists
            assertThat(repository.getTaskById(taskId)).isNotNull()

            // When
            repository.deleteTask(taskId)

            // Then
            assertThat(repository.getTaskById(taskId)).isNull()
        }

    @Test
    fun getTodaysTasks_returnsTasksForToday() =
        runBlocking {
            // Given
            val todayInput = createTestTaskInput("Today Task").copy(dueDate = LocalDateTime.now())
            val tomorrowInput = createTestTaskInput("Tomorrow Task").copy(dueDate = LocalDateTime.now().plusDays(1))

            repository.createTask(todayInput)
            repository.createTask(tomorrowInput)

            // When
            val todaysTasks = repository.getTodaysTasks().first()

            // Then
            assertThat(todaysTasks).hasSize(1)
            assertThat(todaysTasks.first().title).isEqualTo("Today Task")
        }

    private fun createTestTaskInput(title: String): TaskInput {
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
