//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooGenericExceptionCaught", "SwallowedException")
package io.payanam.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.port.TimeTrackingController
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.scoring.RecurrenceManager
import io.payanam.service.TrackingService
import io.payanam.widget.TimeTrackingWidgetProvider
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for time tracking operations that include task completion logic.
 */
@Singleton
class TimeTrackingUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeEntryRepository: TimeEntryRepository,
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val recurrenceManager: RecurrenceManager,
) : TimeTrackingController {
    private val logger = UnifiedLogger.getInstance()

    /**
     * Start a platform tracking session: launch the foreground service, then refresh the
     * home-screen widget so it reflects the new session.
     */
    override suspend fun startTracking(
        taskId: String?,
        taskTitle: String?,
        dimension: String,
        startTime: LocalDateTime,
    ) {
        logger.i(
            "TimeTrackingUseCase.startTracking",
            "Starting platform tracking session",
            mapOf(
                "taskId" to (taskId ?: "none"),
                "dimension" to dimension,
                "startTime" to startTime.toString(),
            ),
        )
        TrackingService.startTracking(
            context = context,
            taskId = taskId,
            taskTitle = taskTitle,
            dimension = dimension,
            startTime = startTime.toString(),
        )
        TimeTrackingWidgetProvider.requestUpdate(context)
    }

    /**
     * Stop the platform tracking session: send the stop action so the foreground service ends the
     * session and removes its notification.
     */
    override fun stopTracking() {
        logger.i(
            "TimeTrackingUseCase.stopTracking",
            "Stopping platform tracking session",
        )
        TrackingService.stopTracking(context)
    }

    /**
     * Stop the active time entry and complete the associated task if any.
     * Returns the stopped time entry.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; broad catch intentional
    override suspend fun stopTrackingAndCompleteTask(
        focusRating: Double,
        focusNote: String?,
    ): TimeEntry? {
        val safeFocusRating = focusRating.coerceIn(0.0, 1.0)
        val normalizedFocusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() }
        logger.i(
            "TimeTrackingUseCase.stopTrackingAndCompleteTask",
            "Stopping tracking and checking for task completion",
            mapOf(
                "focusRating" to safeFocusRating.toString(),
                "hasFocusNote" to (normalizedFocusNote != null).toString(),
            ),
        )
        val stoppedEntry = timeEntryRepository.stopActiveTimeEntryWithFocus(
            focusRating = safeFocusRating,
            focusNote = normalizedFocusNote,
        )

        stoppedEntry?.taskId?.let { taskId ->
            completeTaskFromTimeEntry(taskId, stoppedEntry)
        }

        logger.i(
            "TimeTrackingUseCase.stopTrackingAndCompleteTask",
            "Tracking stopped",
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
            mapOf(
                "taskId" to taskId,
                "timeEntryId" to timeEntry.id,
                "durationMinutes" to timeEntry.durationMinutes().toString(),
            ),
        )

        try {
            val task = taskRepository.getTaskById(taskId)
            if (task == null) {
                logger.w("TimeTrackingUseCase.completeTaskFromTimeEntry", "Task not found", mapOf("taskId" to taskId))
                return
            }
            val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
            if (!isFrequencyHabit) {
                taskRepository.completeTask(taskId, "Completed via time tracking")
            }

            // For recurring tasks, record occurrence and update recurrence state
            if (task.recurrenceEnabled) {
                val occurrenceDate = task.dueDate?.toLocalDate() ?: LocalDate.now()
                val actualDurationMinutes = timeEntry.durationMinutes().toInt()

                // Record occurrence (simplified - using existing logic from TimeViewModel)
                recordOccurrenceForTask(
                    taskId = taskId,
                    dueDate = occurrenceDate,
                    status = "completed",
                    note = "Completed via time tracking",
                    actualCompletedAt = timeEntry.endedAt,
                    actualDurationMinutes = actualDurationMinutes,
                )

                // Update recurrence state
                recurrenceManager.onTaskCompleted(task, "Completed via time tracking", null, null)
            }

            logger.i(
                "TimeTrackingUseCase.completeTaskFromTimeEntry",
                "Task completed from time entry",
                mapOf(
                    "taskId" to taskId,
                    "isRecurring" to task.recurrenceEnabled.toString(),
                ),
            )
        } catch (e: Exception) {
            logger.e(
                "TimeTrackingUseCase.completeTaskFromTimeEntry",
                "Failed to complete task from time entry",
                e,
                mapOf(
                    "taskId" to taskId,
                ),
            )
        }
    }

    /**
     * Record occurrence for a task (adapted from timeViewModel logic)
     */
    private suspend fun recordOccurrenceForTask(
        taskId: String,
        dueDate: LocalDate,
        status: String,
        note: String?,
        actualCompletedAt: LocalDateTime? = null,
        actualDurationMinutes: Int? = null,
    ) {
        logger.d(
            "TimeTrackingUseCase.recordOccurrenceForTask",
            "Recording occurrence",
            mapOf(
                "taskId" to taskId,
                "dueDate" to dueDate.toString(),
                "status" to status,
                "actualCompletedAt" to (actualCompletedAt?.toString() ?: "null"),
                "actualDurationMinutes" to (actualDurationMinutes?.toString() ?: "null"),
            ),
        )
        val now = LocalDateTime.now()
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
            mapOf(
                "occurrenceId" to occurrence.id,
                "taskId" to taskId,
            ),
        )
    }
}
