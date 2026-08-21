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
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TimeEntryInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * Provides the task and time repository integrity test.
 */
class TaskAndTimeRepositoryIntegrityTest {
    private lateinit var database: PayanamDatabase
    private lateinit var taskRepository: TaskRepositoryImpl
    private lateinit var timeEntryRepository: TimeEntryRepositoryImpl

    @Before
    /**
     * Updates the setup.
     */
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
        database =
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        val encryptionManager = DatabaseEncryptionManager(context)
        val sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(database)
        taskRepository = TaskRepositoryImpl(sessionManager)
        timeEntryRepository = TimeEntryRepositoryImpl(sessionManager)
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
     * Performs the task repository status transitions persist expected fields.
     */
    fun taskRepository_statusTransitionsPersistExpectedFields() =
        runBlocking {
            val completedTask =
                taskRepository.createTask(
                    TaskInput(
                        title = "Complete me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 21, 9, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )
            val skippedTask =
                taskRepository.createTask(
                    TaskInput(
                        title = "Skip me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 21, 10, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )
            val missedTask =
                taskRepository.createTask(
                    TaskInput(
                        title = "Miss me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 21, 11, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )
            val archivedTask =
                taskRepository.createTask(
                    TaskInput(
                        title = "Archive me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 21, 12, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )

            taskRepository.completeTask(completedTask.id)
            taskRepository.skipTask(skippedTask.id)
            taskRepository.missTask(missedTask.id)
            taskRepository.archiveTask(archivedTask.id)
            val completed = taskRepository.getTaskById(completedTask.id)
            val skipped = taskRepository.getTaskById(skippedTask.id)
            val missed = taskRepository.getTaskById(missedTask.id)
            val archived = taskRepository.getTaskById(archivedTask.id)
            assertThat(completed?.status).isEqualTo("completed")
            assertThat(completed?.completedAt).isNotNull()
            assertThat(skipped?.status).isEqualTo("skipped")
            assertThat(skipped?.completedAt).isNull()
            assertThat(missed?.status).isEqualTo("missed")
            assertThat(missed?.completedAt).isNull()
            assertThat(archived?.status).isEqualTo("archived")
            assertThat(archived?.archivedAt).isNotNull()
        }

    @Test
    /**
     * Performs the task repository delete task removes row.
     */
    fun taskRepository_deleteTask_removesRow() =
        runBlocking {
            val task =
                taskRepository.createTask(
                    TaskInput(
                        title = "Delete me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 22, 9, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )

            taskRepository.deleteTask(task.id)
            assertThat(taskRepository.getTaskById(task.id)).isNull()
        }

    @Test
    /**
     * Performs the task repository update task updates due date and status.
     */
    fun taskRepository_updateTask_updatesDueDateAndStatus() =
        runBlocking {
            val task =
                taskRepository.createTask(
                    TaskInput(
                        title = "Update me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 20, 9, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )
            val updated =
                taskRepository.updateTask(
                    task.id,
                    TaskInput(
                        title = "Updated title",
                        status = "completed",
                        dueDate = LocalDateTime.of(2026, 2, 23, 14, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )
            assertThat(updated.title).isEqualTo("Updated title")
            assertThat(updated.status).isEqualTo("completed")
            assertThat(updated.dueDate).isEqualTo(LocalDateTime.of(2026, 2, 23, 14, 0))
        }

    @Test
    /**
     * Time entry repository start entry stops previous active entry.
     */
    fun timeEntryRepository_startEntry_stopsPreviousActiveEntry() =
        runBlocking {
            val firstStart = LocalDateTime.of(2026, 2, 20, 9, 0)
            val secondStart = LocalDateTime.of(2026, 2, 20, 10, 0)
            val first =
                timeEntryRepository.startTimeEntry(
                    TimeEntryInput(
                        lifeIntentionCategory = "Unmapped Category",
                        startedAt = firstStart,
                    ),
                )
            val second =
                timeEntryRepository.startTimeEntry(
                    TimeEntryInput(
                        lifeIntentionCategory = "Unmapped Category",
                        startedAt = secondStart,
                    ),
                )
            val firstRow = database.timeEntryDao().getById(first.id)
            val secondRow = database.timeEntryDao().getById(second.id)
            val active = database.timeEntryDao().getActiveTimeEntry()
            assertThat(firstRow?.endedAt).isNotNull()
            assertThat(secondRow?.endedAt).isNull()
            assertThat(active?.id).isEqualTo(second.id)
        }

    @Test
    /**
     * Time entry repository stop active with focus persists trimmed focus note.
     */
    fun timeEntryRepository_stopActiveWithFocus_persistsTrimmedFocusNote() =
        runBlocking {
            val started =
                timeEntryRepository.startTimeEntry(
                    TimeEntryInput(
                        lifeIntentionCategory = "Unmapped Category",
                        startedAt = LocalDateTime.of(2026, 2, 20, 9, 0),
                    ),
                )
            val stopped =
                timeEntryRepository.stopActiveTimeEntryWithFocus(
                    focusRating = 0.75,
                    focusNote = "  deep session  ",
                )
            val row = database.timeEntryDao().getById(started.id)
            assertThat(stopped?.id).isEqualTo(started.id)
            assertThat(row?.endedAt).isNotNull()
            assertThat(row?.focusRating).isEqualTo(0.75)
            assertThat(row?.focusNote).isEqualTo("deep session")
            assertThat(row?.focusRatedAt).isNotNull()
        }

    @Test
    /**
     * Time entry repository update and delete time entry persists then removes.
     */
    fun timeEntryRepository_updateAndDeleteTimeEntry_persistsThenRemoves() =
        runBlocking {
            val task =
                TaskEntity(
                    id = "task-link",
                    title = "Linked task",
                    description = "desc",
                    status = "pending",
                    dueDate = null,
                    createdAt = "2026-02-20T08:00:00",
                    updatedAt = "2026-02-20T08:00:00",
                    completedAt = null,
                    archivedAt = null,
                    recurrenceEnabled = 0,
                    recurrenceRule = null,
                    durationMinutes = 30,
                    impactLevel = "medium",
                    goalAlignment = "personal",
                    energyLevel = "medium",
                    controlLevel = "high",
                    lifeIntentionCategory = "Unmapped Category",
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
            database.taskDao().insert(task)
            val created =
                timeEntryRepository.createTimeEntry(
                    TimeEntryInput(
                        lifeIntentionCategory = "Unmapped Category",
                        startedAt = LocalDateTime.of(2026, 2, 20, 9, 0),
                        endedAt = LocalDateTime.of(2026, 2, 20, 9, 30),
                    ),
                )
            val updated =
                timeEntryRepository.updateTimeEntry(
                    id = created.id,
                    input =
                        TimeEntryInput(
                            lifeIntentionCategory = "Unmapped Category",
                            taskId = "task-link",
                            startedAt = LocalDateTime.of(2026, 2, 21, 10, 0),
                            endedAt = LocalDateTime.of(2026, 2, 21, 10, 45),
                            focusRating = 0.6,
                            focusNote = "updated note",
                        ),
                )
            assertThat(updated.taskId).isEqualTo("task-link")
            assertThat(updated.startedAt).isEqualTo(LocalDateTime.of(2026, 2, 21, 10, 0))
            assertThat(updated.focusNote).isEqualTo("updated note")

            timeEntryRepository.deleteTimeEntry(created.id)
            assertThat(database.timeEntryDao().getById(created.id)).isNull()
        }

    @Test
    /**
     * Resolve persisted dimension id canonicalizes legacy ids and blank task ids normalize to null.
     */
    fun resolvePersistedDimensionId_canonicalizesLegacyIds_and_blankTaskIdsNormalizeToNull() {
        assertThat(
            resolvePersistedDimensionId(
                dimensionId = "dim_learning",
                lifeIntentionCategory = "Learning & Growth",
            ),
        ).isNull()
        assertThat(
            resolvePersistedDimensionId(
                dimensionId = null,
                lifeIntentionCategory = "Community & Service",
            ),
        ).isNull()
        assertThat(normalizeOptionalIdentifier("   ")).isNull()
    }
}
