//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.port.TimeTrackingController
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.scoring.port.TaskRecurrenceHandler
import io.payanam.shared.logging.AppLogger
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The desktop's [TimeTrackingController]: two honest refusals and one real implementation.
 *
 * [startTracking] and [stopTracking] refuse — no foreground service, notification or widget host exists here.
 * [stopTrackingAndCompleteTask] does real work; refusing it reports a stop that never happened. The injected
 * [recurrenceHandler] is the platform's only recurrence owner; do not construct a second one.
 */
internal class DesktopTimeTrackingController(
    private val timeEntries: TimeEntryRepository,
    private val tasks: TaskRepository,
    private val taskOccurrences: TaskOccurrenceRepository,
    private val recurrenceHandler: TaskRecurrenceHandler,
) : TimeTrackingController {
    private val logger = AppLogger.current

    /**
     * No platform session is started here; logged at `warn` because the caller cannot tell a refusal from a success.
     */
    override suspend fun startTracking(
        taskId: String?,
        taskTitle: String?,
        dimension: String,
        startTime: LocalDateTime,
    ) {
        logger.w(
            "DesktopTimeTrackingController.startTracking",
            "No platform tracking session was started: this platform has no foreground service. The " +
                "time entry itself is stored by the caller, so the time shown is unaffected",
            mapOf(
                "taskId" to (taskId ?: "none"),
                "dimension" to dimension,
                "startTime" to startTime.toString(),
            ),
        )
    }

    /**
     * Refusal: no platform session was ever started. Logged at `debug`, since routine edits and deletes reach it.
     */
    override fun stopTracking() {
        logger.d(
            "DesktopTimeTrackingController.stopTracking",
            "No platform tracking session to stop: none was ever started on this platform",
        )
    }

    /**
     * Stops the shared store's active entry and completes its task; `null` means nothing was active, not a refusal.
     */
    override suspend fun stopTrackingAndCompleteTask(
        focusRating: Double,
        focusNote: String?,
    ): TimeEntry? {
        val safeFocusRating = focusRating.coerceIn(0.0, 1.0)
        val normalizedFocusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() }
        logger.i(
            "DesktopTimeTrackingController.stopTrackingAndCompleteTask",
            "Stopping the active time entry in the shared store",
            mapOf(
                "focusRating" to safeFocusRating.toString(),
                "hasFocusNote" to (normalizedFocusNote != null).toString(),
            ),
        )
        val stoppedEntry =
            timeEntries.stopActiveTimeEntryWithFocus(
                focusRating = safeFocusRating,
                focusNote = normalizedFocusNote,
            )
        if (stoppedEntry == null) {
            logger.w(
                "DesktopTimeTrackingController.stopTrackingAndCompleteTask",
                "There was no active time entry to stop; no entry was stopped and no task was completed",
            )
            return null
        }
        stoppedEntry.taskId?.let { completeTaskFromTimeEntry(it, stoppedEntry) }
        logger.i(
            "DesktopTimeTrackingController.stopTrackingAndCompleteTask",
            "Active time entry stopped",
            mapOf(
                "entryId" to stoppedEntry.id,
                "taskCompleted" to (stoppedEntry.taskId != null).toString(),
            ),
        )
        return stoppedEntry
    }

    /**
     * Completes the task a stopped entry was attached to; a throw is swallowed because the stop already landed.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: any repo call can throw, and the stop must survive it
    private suspend fun completeTaskFromTimeEntry(
        taskId: String,
        timeEntry: TimeEntry,
    ) {
        logger.i(
            "DesktopTimeTrackingController.completeTaskFromTimeEntry",
            "Completing the task the stopped time entry was attached to",
            mapOf("taskId" to taskId, "timeEntryId" to timeEntry.id),
        )
        try {
            val task = tasks.getTaskById(taskId)
            if (task == null) {
                logger.w(
                    "DesktopTimeTrackingController.completeTaskFromTimeEntry",
                    "The attached task no longer exists; the time entry was stopped but no task was completed",
                    mapOf("taskId" to taskId),
                )
                return
            }
            // The platform's own answer, on the same rule Android applies: a frequency habit's completion is
            // handled by advancing its schedule rather than by closing it out.
            val isFrequencyHabit = task.recurrenceEnabled && recurrenceHandler.isFrequencyHabit(task)
            if (!isFrequencyHabit) {
                tasks.completeTask(taskId, "Completed via time tracking")
            }
            if (task.recurrenceEnabled) {
                recordOccurrenceForTask(task, timeEntry)
                recurrenceHandler.onTaskCompleted(task, "Completed via time tracking", null, null)
            }
            logger.i(
                "DesktopTimeTrackingController.completeTaskFromTimeEntry",
                "Task completion finished for the stopped time entry",
                mapOf("taskId" to taskId, "isRecurring" to task.recurrenceEnabled.toString()),
            )
        } catch (error: Exception) {
            logger.e(
                "DesktopTimeTrackingController.completeTaskFromTimeEntry",
                "The time entry was stopped but the attached task could not be completed",
                error,
                mapOf("taskId" to taskId),
            )
        }
    }

    /**
     * Records the occurrence of a recurring task, with the same field mapping Android's use case uses.
     */
    private suspend fun recordOccurrenceForTask(
        task: Task,
        timeEntry: TimeEntry,
    ) {
        logger.d(
            "DesktopTimeTrackingController.recordOccurrenceForTask",
            "Recording the recurring task's occurrence",
            mapOf("taskId" to task.id),
        )
        val occurrenceDate = task.dueDate?.toLocalDate() ?: LocalDate.now()
        val now = LocalDateTime.now()
        taskOccurrences.recordOccurrence(
            TaskOccurrence(
                id = UUID.randomUUID().toString(),
                taskId = task.id,
                occurrenceDate = occurrenceDate.toString(),
                status = "completed",
                statusNote = "Completed via time tracking",
                completedAt = now.toString(),
                actualCompletedAt = timeEntry.endedAt,
                actualDurationMinutes = timeEntry.durationMinutes().toInt(),
                dueDate = occurrenceDate.atStartOfDay(),
                createdAt = now,
            ),
        )
    }
}
