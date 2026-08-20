//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.usecase

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for time tracking operations that include task completion logic.
 */
@Singleton
/**
 * TimeTrackingUseCase.
 */
class TimeTrackingUseCase @Inject constructor(
    private val timeEntryRepository: TimeEntryRepository,
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val recurrenceManager: RecurrenceManager,
) {
    private val logger = UnifiedLogger.getInstance()

    /**
     * Stop the active time entry and complete the associated task if any.
     * Returns the stopped time entry.
     */
    suspend fun stopTrackingAndCompleteTask(
        focusRating: Double = 0.0,
        focusNote: String? = null,
    ): TimeEntry? {
        /** Safe focus rating. */
        val safeFocusRating = focusRating.coerceIn(0.0, 1.0)
        /** Normalized focus note. */
        val normalizedFocusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() }
        logger.i(
            "TimeTrackingUseCase.stopTrackingAndCompleteTask",
            "Stopping tracking and checking for task completion",
            /** Map of. */
            mapOf(
                "focusRating" to safeFocusRating.toString(),
                "hasFocusNote" to (normalizedFocusNote != null).toString(),
            ),
        )

        /** Stopped entry. */
        val stoppedEntry = timeEntryRepository.stopActiveTimeEntryWithFocus(
            focusRating = safeFocusRating,
            focusNote = normalizedFocusNote,
        )

        stoppedEntry?.taskId?.let { taskId ->
            /** Complete task from time entry. */
            completeTaskFromTimeEntry(taskId, stoppedEntry)
        }

        logger.i(
            "TimeTrackingUseCase.stopTrackingAndCompleteTask",
            "Tracking stopped",
            /** Map of. */
            mapOf(
                "entryId" to (stoppedEntry?.id ?: "none"),
                "taskCompleted" to (stoppedEntry?.taskId != null).toString(),
                "focusRating" to safeFocusRating.toString(),
            ),
        )

        return stoppedEntry
    }

    /**
     * Complete a task that was tracked via time tracking.
     */
    private suspend fun completeTaskFromTimeEntry(taskId: String, timeEntry: TimeEntry) {
        logger.i(
            "TimeTrackingUseCase.completeTaskFromTimeEntry",
            "Completing task from time entry",
            /** Map of. */
            mapOf(
                "taskId" to taskId,
                "timeEntryId" to timeEntry.id,
                "durationMinutes" to timeEntry.durationMinutes().toString(),
            ),
        )

        try {
            /** Task. */
            val task = taskRepository.getTaskById(taskId)
            /** If. */
            if (task == null) {
                logger.w("TimeTrackingUseCase.completeTaskFromTimeEntry", "Task not found", mapOf("taskId" to taskId))
                /** Return. */
                return
            }

            /** Is frequency habit. */
            val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
            /** If. */
            if (!isFrequencyHabit) {
                taskRepository.completeTask(taskId, "Completed via time tracking")
            }

            // For recurring tasks, record occurrence and update recurrence state
            /** If. */
            if (task.recurrenceEnabled) {
                /** Occurrence date. */
                val occurrenceDate = task.dueDate?.toLocalDate() ?: LocalDate.now()
                /** Actual duration minutes. */
                val actualDurationMinutes = timeEntry.durationMinutes().toInt()

                // Record occurrence (simplified - using existing logic from TimeViewModel)
                /** Record occurrence for task. */
                recordOccurrenceForTask(
                    taskId = taskId,
                    dueDate = occurrenceDate,
                    status = "completed",
                    note = "Completed via time tracking",
                    actualCompletedAt = timeEntry.endedAt,
                    actualDurationMinutes = actualDurationMinutes,
                )

                // Update recurrence state
                recurrenceManager.onTaskCompleted(task, "Completed via time tracking", null)
            }

            logger.i(
                "TimeTrackingUseCase.completeTaskFromTimeEntry",
                "Task completed from time entry",
                /** Map of. */
                mapOf(
                    "taskId" to taskId,
                    "isRecurring" to task.recurrenceEnabled.toString(),
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "TimeTrackingUseCase.completeTaskFromTimeEntry",
                "Failed to complete task from time entry",
                /** E. */
                e,
                /** Map of. */
                mapOf(
                    "taskId" to taskId,
                ),
            )
        }
    }

    /**
     * Record occurrence for a task (adapted from TimeViewModel logic)
     */
    private suspend fun recordOccurrenceForTask(
        /** Task id. */
        taskId: String,
        /** Due date. */
        dueDate: LocalDate,
        /** Status. */
        status: String,
        note: String?,
        actualCompletedAt: LocalDateTime? = null,
        actualDurationMinutes: Int? = null,
    ) {
        logger.d(
            "TimeTrackingUseCase.recordOccurrenceForTask",
            "Recording occurrence",
            /** Map of. */
            mapOf(
                "taskId" to taskId,
                "dueDate" to dueDate.toString(),
                "status" to status,
                "actualCompletedAt" to (actualCompletedAt?.toString() ?: "null"),
                "actualDurationMinutes" to (actualDurationMinutes?.toString() ?: "null"),
            ),
        )

        /** Now. */
        val now = LocalDateTime.now()
        /** Occurrence. */
        val occurrence = TaskOccurrence(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            occurrenceDate = dueDate.toString(),
            status = status,
            statusNote = note,
            completedAt = if (status == "completed") now.toString() else null,
            actualCompletedAt = actualCompletedAt,
            actualDurationMinutes = actualDurationMinutes,
            dueDate = dueDate.atStartOfDay(),
            createdAt = now,
        )

        taskOccurrenceRepository.recordOccurrence(occurrence)

        logger.d(
            "TimeTrackingUseCase.recordOccurrenceForTask",
            "Occurrence recorded",
            /** Map of. */
            mapOf(
                "occurrenceId" to occurrence.id,
                "taskId" to taskId,
            ),
        )
    }
}
