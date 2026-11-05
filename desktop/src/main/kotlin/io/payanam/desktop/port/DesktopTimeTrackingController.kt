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
 * WHY IT IS NOT ONE OF THE `DesktopUnsupportedPorts` NO-OPS: the port is three methods with two different
 * answers, and only the first two are platform work. On Android [startTracking] and [stopTracking] drive
 * `TrackingService`, a foreground service, and the home-screen widget; Linux has neither an ongoing-session
 * notification nor a launcher widget host, and this module has no tray icon to stand in for them. Those two
 * methods therefore refuse, and refuse out loud.
 *
 * [stopTrackingAndCompleteTask] is the opposite case, and answering it with a refusal would be the exact
 * defect this project removed: it is not platform work. It stops the SHARED store's active entry and
 * completes the attached task — both plain repository writes that [TimeEntryRepositoryJvm] and
 * [TaskRepositoryJvm] perform over the same connection every other desktop repository uses. Worse, its
 * caller cannot tell a refusal from the truth: `TimeViewModel.stopTracking` logs
 * "Tracking stopped successfully" on whatever this returns, so a `null` here would report a stop that
 * never happened and never stopped the entry sitting in the database — the "reminder sink" shape, one
 * layer up. The active entry is written by the shared ViewModel itself rather than by
 * [startTracking], so an entry can be active on this platform even though no platform session was ever
 * begun, and Stop has to work.
 *
 * The recurrence question is answered by the INJECTED [TaskRecurrenceHandler], which on this platform is
 * `DesktopUnsupportedPorts.recurrenceHandler`. That is deliberate: the desktop has exactly one owner of
 * recurrence answers, and a second `RecurrenceManager` inside this class would reintroduce the duplicate
 * the seam was built to remove. Its honest "not a frequency habit" answer therefore means the attached task
 * IS completed here and the recurrence schedule is NOT advanced — which is the desktop's already-recorded
 * divergence (Android advances recurrence and the desktop reads back what it wrote), not a new one. It is
 * reported at `warn` here rather than left to be inferred from a silent schedule that did not move.
 *
 * @param timeEntries the shared store's time entries; the active one is stopped here.
 * @param tasks completes the task a stopped entry was attached to.
 * @param taskOccurrences records the occurrence of a recurring task, as Android does.
 * @param recurrenceHandler the platform's recurrence answer, not a locally constructed one.
 */
internal class DesktopTimeTrackingController(
    private val timeEntries: TimeEntryRepository,
    private val tasks: TaskRepository,
    private val taskOccurrences: TaskOccurrenceRepository,
    private val recurrenceHandler: TaskRecurrenceHandler,
) : TimeTrackingController {
    private val logger = AppLogger.current

    /**
     * No platform tracking session is started: this platform has no foreground service.
     *
     * Logged at `warn`, not `debug` and not silent, for the reason the whole class exists. A caller cannot
     * tell a refusal from a success — both return [Unit] — so the only place this can be legible is the log,
     * and a user who starts a timer here and then suspends their machine should find that no session was
     * held for them. The entry itself is written by the shared ViewModel and is unaffected by this refusal,
     * which is stated in the message rather than left for a reader to infer.
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

    /** Refusal: there was no platform session to stop, and none was ever started here. */
    override fun stopTracking() {
        logger.w(
            "DesktopTimeTrackingController.stopTracking",
            "No platform tracking session to stop: none was ever started on this platform",
        )
    }

    /**
     * Stops the shared store's active entry and completes the task it was attached to.
     *
     * Real work on this platform, for the reason in this class's documentation. Returns the stopped entry,
     * or `null` when nothing was active — which is the port's own answer for that case, not a refusal.
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
     * Completes the task a stopped time entry was attached to, mirroring Android's use case step for step.
     *
     * The task read is a nullable lookup, so a task deleted between the entry and this call is reported
     * rather than treated as a failure. Anything thrown from here is logged and swallowed, because the
     * caller has already stopped the entry and a thrown exception would discard the stop it did achieve —
     * the entry would stay running on screen while the log claims the stop failed.
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
