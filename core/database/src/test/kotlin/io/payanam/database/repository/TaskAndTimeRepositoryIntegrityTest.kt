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
 * TaskAndTimeRepositoryIntegrityTest.
 */
class TaskAndTimeRepositoryIntegrityTest {
    private lateinit var database: PayanamDatabase
    private lateinit var taskRepository: TaskRepositoryImpl
    private lateinit var timeEntryRepository: TimeEntryRepositoryImpl

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
        database =
            /** Room. */
            Room
                .inMemoryDatabaseBuilder(context, PayanamDatabase::class.java)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        /** Encryption manager. */
        val encryptionManager = DatabaseEncryptionManager(context)
        /** Session manager. */
        val sessionManager = DatabaseSessionManager(context, encryptionManager)
        sessionManager.openWithTestDatabase(database)
        taskRepository = TaskRepositoryImpl(sessionManager)
        timeEntryRepository = TimeEntryRepositoryImpl(sessionManager)
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
     * Task repository status transitions persist expected fields.
     */
    fun taskRepository_statusTransitionsPersistExpectedFields() =
        runBlocking {
            /** Completed task. */
            val completedTask =
                taskRepository.createTask(
                    /** Task input. */
                    TaskInput(
                        title = "Complete me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 21, 9, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )
            /** Skipped task. */
            val skippedTask =
                taskRepository.createTask(
                    /** Task input. */
                    TaskInput(
                        title = "Skip me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 21, 10, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )
            /** Missed task. */
            val missedTask =
                taskRepository.createTask(
                    /** Task input. */
                    TaskInput(
                        title = "Miss me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 21, 11, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )
            /** Archived task. */
            val archivedTask =
                taskRepository.createTask(
                    /** Task input. */
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

            /** Completed. */
            val completed = taskRepository.getTaskById(completedTask.id)
            /** Skipped. */
            val skipped = taskRepository.getTaskById(skippedTask.id)
            /** Missed. */
            val missed = taskRepository.getTaskById(missedTask.id)
            /** Archived. */
            val archived = taskRepository.getTaskById(archivedTask.id)

            /** Assert that. */
            assertThat(completed?.status).isEqualTo("completed")
            /** Assert that. */
            assertThat(completed?.completedAt).isNotNull()

            /** Assert that. */
            assertThat(skipped?.status).isEqualTo("skipped")
            /** Assert that. */
            assertThat(skipped?.completedAt).isNull()

            /** Assert that. */
            assertThat(missed?.status).isEqualTo("missed")
            /** Assert that. */
            assertThat(missed?.completedAt).isNull()

            /** Assert that. */
            assertThat(archived?.status).isEqualTo("archived")
            /** Assert that. */
            assertThat(archived?.archivedAt).isNotNull()
        }

    @Test
    /**
     * Task repository delete task removes row.
     */
    fun taskRepository_deleteTask_removesRow() =
        runBlocking {
            /** Task. */
            val task =
                taskRepository.createTask(
                    /** Task input. */
                    TaskInput(
                        title = "Delete me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 22, 9, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )

            taskRepository.deleteTask(task.id)

            /** Assert that. */
            assertThat(taskRepository.getTaskById(task.id)).isNull()
        }

    @Test
    /**
     * Task repository update task updates due date and status.
     */
    fun taskRepository_updateTask_updatesDueDateAndStatus() =
        runBlocking {
            /** Task. */
            val task =
                taskRepository.createTask(
                    /** Task input. */
                    TaskInput(
                        title = "Update me",
                        status = "pending",
                        dueDate = LocalDateTime.of(2026, 2, 20, 9, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )

            /** Updated. */
            val updated =
                taskRepository.updateTask(
                    task.id,
                    /** Task input. */
                    TaskInput(
                        title = "Updated title",
                        status = "completed",
                        dueDate = LocalDateTime.of(2026, 2, 23, 14, 0),
                        lifeIntentionCategory = "Unmapped Category",
                    ),
                )

            /** Assert that. */
            assertThat(updated.title).isEqualTo("Updated title")
            /** Assert that. */
            assertThat(updated.status).isEqualTo("completed")
            /** Assert that. */
            assertThat(updated.dueDate).isEqualTo(LocalDateTime.of(2026, 2, 23, 14, 0))
        }

    @Test
    /**
     * Time entry repository start entry stops previous active entry.
     */
    fun timeEntryRepository_startEntry_stopsPreviousActiveEntry() =
        runBlocking {
            /** First start. */
            val firstStart = LocalDateTime.of(2026, 2, 20, 9, 0)
            /** Second start. */
            val secondStart = LocalDateTime.of(2026, 2, 20, 10, 0)

            /** First. */
            val first =
                timeEntryRepository.startTimeEntry(
                    /** Time entry input. */
                    TimeEntryInput(
                        lifeIntentionCategory = "Unmapped Category",
                        startedAt = firstStart,
                    ),
                )
            /** Second. */
            val second =
                timeEntryRepository.startTimeEntry(
                    /** Time entry input. */
                    TimeEntryInput(
                        lifeIntentionCategory = "Unmapped Category",
                        startedAt = secondStart,
                    ),
                )

            /** First row. */
            val firstRow = database.timeEntryDao().getById(first.id)
            /** Second row. */
            val secondRow = database.timeEntryDao().getById(second.id)
            /** Active. */
            val active = database.timeEntryDao().getActiveTimeEntry()

            /** Assert that. */
            assertThat(firstRow?.endedAt).isNotNull()
            /** Assert that. */
            assertThat(secondRow?.endedAt).isNull()
            /** Assert that. */
            assertThat(active?.id).isEqualTo(second.id)
        }

    @Test
    /**
     * Time entry repository stop active with focus persists trimmed focus note.
     */
    fun timeEntryRepository_stopActiveWithFocus_persistsTrimmedFocusNote() =
        runBlocking {
            /** Started. */
            val started =
                timeEntryRepository.startTimeEntry(
                    /** Time entry input. */
                    TimeEntryInput(
                        lifeIntentionCategory = "Unmapped Category",
                        startedAt = LocalDateTime.of(2026, 2, 20, 9, 0),
                    ),
                )

            /** Stopped. */
            val stopped =
                timeEntryRepository.stopActiveTimeEntryWithFocus(
                    focusRating = 0.75,
                    focusNote = "  deep session  ",
                )

            /** Row. */
            val row = database.timeEntryDao().getById(started.id)
            /** Assert that. */
            assertThat(stopped?.id).isEqualTo(started.id)
            /** Assert that. */
            assertThat(row?.endedAt).isNotNull()
            /** Assert that. */
            assertThat(row?.focusRating).isEqualTo(0.75)
            /** Assert that. */
            assertThat(row?.focusNote).isEqualTo("deep session")
            /** Assert that. */
            assertThat(row?.focusRatedAt).isNotNull()
        }

    @Test
    /**
     * Time entry repository update and delete time entry persists then removes.
     */
    fun timeEntryRepository_updateAndDeleteTimeEntry_persistsThenRemoves() =
        runBlocking {
            /** Task. */
            val task =
                /** Task entity. */
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

            /** Created. */
            val created =
                timeEntryRepository.createTimeEntry(
                    /** Time entry input. */
                    TimeEntryInput(
                        lifeIntentionCategory = "Unmapped Category",
                        startedAt = LocalDateTime.of(2026, 2, 20, 9, 0),
                        endedAt = LocalDateTime.of(2026, 2, 20, 9, 30),
                    ),
                )

            /** Updated. */
            val updated =
                timeEntryRepository.updateTimeEntry(
                    id = created.id,
                    input =
                        /** Time entry input. */
                        TimeEntryInput(
                            lifeIntentionCategory = "Unmapped Category",
                            taskId = "task-link",
                            startedAt = LocalDateTime.of(2026, 2, 21, 10, 0),
                            endedAt = LocalDateTime.of(2026, 2, 21, 10, 45),
                            focusRating = 0.6,
                            focusNote = "updated note",
                        ),
                )

            /** Assert that. */
            assertThat(updated.taskId).isEqualTo("task-link")
            /** Assert that. */
            assertThat(updated.startedAt).isEqualTo(LocalDateTime.of(2026, 2, 21, 10, 0))
            /** Assert that. */
            assertThat(updated.focusNote).isEqualTo("updated note")

            timeEntryRepository.deleteTimeEntry(created.id)

            /** Assert that. */
            assertThat(database.timeEntryDao().getById(created.id)).isNull()
        }

    @Test
    /**
     * Resolve persisted dimension id canonicalizes legacy ids and blank task ids normalize to null.
     */
    fun resolvePersistedDimensionId_canonicalizesLegacyIds_and_blankTaskIdsNormalizeToNull() {
        /** Assert that. */
        assertThat(
            /** Resolve persisted dimension id. */
            resolvePersistedDimensionId(
                dimensionId = "dim_learning",
                lifeIntentionCategory = "Learning & Growth",
            ),
        ).isNull()
        /** Assert that. */
        assertThat(
            /** Resolve persisted dimension id. */
            resolvePersistedDimensionId(
                dimensionId = null,
                lifeIntentionCategory = "Community & Service",
            ),
        ).isNull()
        /** Assert that. */
        assertThat(normalizeOptionalIdentifier("   ")).isNull()
    }
}
