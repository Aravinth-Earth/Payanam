//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.scoring.RecurrenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** The terminal result of applying one reminder action. */
enum class NotificationActionOutcome {
    /** The database write, the recurrence advance and the notification dismissal all ran. */
    APPLIED,

    /** Nothing could be applied and a retry cannot change that (task gone, unknown action type). */
    NOT_APPLICABLE,

    /** This action was already applied today; the re-delivered intent wrote nothing. */
    ALREADY_HANDLED,

    /** The attempt threw; the action is kept so the next unlock can retry it. */
    FAILED,
}

/**
 * Applies reminder actions: the ones tapped with the database open, and the ones recorded while it
 * was locked.
 *
 * [execute] is the single code path for both callers, so a deferred action cannot diverge from a
 * live one. [startObservingUnlocks] listens to the session's own open state rather than to the
 * unlock screens, which covers every way into an open database (typed passphrase, biometric,
 * first-run init, inactivity re-unlock).
 */
@Singleton
class NotificationActionCoordinator
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val notificationScheduler: NotificationScheduler,
    private val recurrenceManager: RecurrenceManager,
    private val sessionManager: DatabaseSessionManager,
    private val pendingActions: PendingNotificationActionStore,
) {
    private val logger = UnifiedLogger.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observing = AtomicBoolean(false)
    private val executeMutex = Mutex()

    /**
     * Starts the unlock listener once per process; later calls return immediately.
     *
     * The session flow emits its current value on subscription, so a session that is already
     * open drains on the same path as one opened later.
     */
    fun startObservingUnlocks() {
        if (!observing.compareAndSet(false, true)) return
        logger.i(LOG_SOURCE, "Observing database unlocks for deferred reminder actions")
        scope.launch {
            sessionManager.isOpen.collect { open ->
                if (!open) return@collect
                logger.i(
                    LOG_SOURCE,
                    "Database session opened; applying deferred reminder actions",
                    mapOf("sessionOpen" to true),
                )
                applyPendingActions()
            }
        }
    }

    /**
     * Applies every pending action still valid today, oldest first.
     *
     * Ordering is by recorded timestamp; the store answers it already sorted.
     */
    suspend fun applyPendingActions() {
        val pending = pendingActions.readPending()
        if (pending.isEmpty()) {
            logger.i(LOG_SOURCE, "No deferred reminder actions to apply")
            return
        }
        logger.i(
            LOG_SOURCE,
            "Applying deferred reminder actions",
            mapOf("count" to pending.size),
        )
        for (action in pending) {
            if (!sessionManager.isDbOpen()) {
                logger.w(
                    LOG_SOURCE,
                    "Stopped applying deferred reminder actions; session closed again",
                    mapOf("actionType" to action.actionType, "taskId" to action.taskId),
                )
                return
            }
            when (execute(action)) {
                NotificationActionOutcome.APPLIED -> {
                    pendingActions.clear(action.taskId)
                    logger.i(
                        LOG_SOURCE,
                        "Applied deferred reminder action",
                        mapOf(
                            "actionType" to action.actionType,
                            "taskId" to action.taskId,
                            "recordedDay" to action.recordedDay().toString(),
                        ),
                    )
                }

                NotificationActionOutcome.NOT_APPLICABLE -> {
                    pendingActions.clear(action.taskId)
                    logger.w(
                        LOG_SOURCE,
                        "Deferred reminder action cannot be applied; dropped",
                        mapOf("actionType" to action.actionType, "taskId" to action.taskId),
                    )
                }

                NotificationActionOutcome.ALREADY_HANDLED -> {
                    logger.i(
                        LOG_SOURCE,
                        "Deferred reminder action was already handled; nothing applied",
                        mapOf("actionType" to action.actionType, "taskId" to action.taskId),
                    )
                }

                NotificationActionOutcome.FAILED -> {
                    logger.w(
                        LOG_SOURCE,
                        "Deferred reminder action kept for the next unlock",
                        mapOf("actionType" to action.actionType, "taskId" to action.taskId),
                    )
                }
            }
        }
    }

    /**
     * Applies one action, then dismisses its notification only if it succeeded.
     *
     * The handled-token check, the writes and the handled marker run under one lock, so a live
     * tap and the unlock drain cannot both pass the check.
     */
    suspend fun execute(action: PendingNotificationAction): NotificationActionOutcome = executeMutex.withLock { executeLocked(action) }

    @Suppress("TooGenericExceptionCaught") // Background component; an escaping throw is an unhandled crash
    private suspend fun executeLocked(action: PendingNotificationAction): NotificationActionOutcome {
        if (pendingActions.isHandled(action.taskId, action.notificationId)) {
            return NotificationActionOutcome.ALREADY_HANDLED
        }
        logger.i(
            LOG_SOURCE,
            "Executing reminder action",
            mapOf(
                "actionType" to action.actionType,
                "taskId" to action.taskId,
                "notificationId" to action.notificationId,
            ),
        )
        val outcome =
            try {
                when (action.actionType) {
                    ACTION_TYPE_COMPLETE, ACTION_TYPE_SKIP, ACTION_TYPE_MISS -> applyTaskAction(action)

                    ACTION_TYPE_SNOOZE -> applySnooze(action)

                    else -> {
                        logger.w(
                            LOG_SOURCE,
                            "Unknown reminder action type",
                            mapOf("actionType" to action.actionType, "taskId" to action.taskId),
                        )
                        NotificationActionOutcome.NOT_APPLICABLE
                    }
                }
            } catch (e: Exception) {
                logger.e(
                    LOG_SOURCE,
                    "Failed to apply reminder action",
                    e,
                    mapOf("actionType" to action.actionType, "taskId" to action.taskId),
                )
                NotificationActionOutcome.FAILED
            }
        if (outcome == NotificationActionOutcome.APPLIED) {
            pendingActions.markHandled(action)
        }
        return outcome
    }

    /** Complete / skip / miss: the task write, the occurrence row, the recurrence advance, then the dismissal. */
    private suspend fun applyTaskAction(action: PendingNotificationAction): NotificationActionOutcome {
        val taskId = action.taskId
        val status = action.actionType
        val task =
            taskRepository.getTaskById(taskId)
                ?: run {
                    logger.w(LOG_SOURCE, "Task not found", mapOf("taskId" to taskId, "actionType" to status))
                    return NotificationActionOutcome.NOT_APPLICABLE
                }
        val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
        if (!isFrequencyHabit) {
            when (status) {
                ACTION_TYPE_COMPLETE -> taskRepository.completeTask(taskId)
                ACTION_TYPE_SKIP -> taskRepository.skipTask(taskId)
                ACTION_TYPE_MISS -> taskRepository.missTask(taskId)
            }
        }
        if (task.recurrenceEnabled) {
            recordOccurrenceForDay(taskId, status)
            if (FeatureFlags.minimalModeEnabled) {
                logger.i(
                    LOG_SOURCE,
                    "Minimal mode: skipping recurrence advancement for recurring task",
                    mapOf("taskId" to taskId, "actionType" to status),
                )
            } else {
                // The advance stamps lastOccurrenceDate with the wall clock, so a value at or
                // after this action's tap time means an earlier attempt already advanced it.
                val recordedAt =
                    Instant.ofEpochMilli(action.recordedAtMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
                val alreadyAdvanced = task.lastOccurrenceDate?.let { !it.isBefore(recordedAt) } ?: false
                if (alreadyAdvanced) {
                    logger.i(
                        LOG_SOURCE,
                        "Recurrence already advanced for this action; not advancing again",
                        mapOf(
                            "taskId" to taskId,
                            "actionType" to status,
                            "lastOccurrenceDate" to (task.lastOccurrenceDate?.toString() ?: "none"),
                            "recordedAt" to recordedAt.toString(),
                        ),
                    )
                } else {
                    when (status) {
                        ACTION_TYPE_COMPLETE ->
                            recurrenceManager.onTaskCompleted(task, note = null, reason = "notification_action", nextDueStrategy = null)

                        ACTION_TYPE_SKIP ->
                            recurrenceManager.onTaskSkipped(task, note = null, reason = "notification_action", nextDueStrategy = null)

                        ACTION_TYPE_MISS ->
                            recurrenceManager.onTaskMissed(task, note = null, reason = "notification_action", nextDueStrategy = null)
                    }
                }
                val updatedTask = taskRepository.getTaskById(taskId)
                if (updatedTask != null && updatedTask.recurrenceEnabled) {
                    notificationScheduler.scheduleForTask(
                        task = updatedTask,
                        overrideScheduledAt = null,
                        overrideDueAt = null,
                        isSnoozed = false,
                    )
                }
            }
        } else {
            notificationScheduler.cancelForTask(taskId)
        }
        cancelDisplayedNotification(action.notificationId)
        logger.i(
            LOG_SOURCE,
            "Task action handled",
            mapOf("taskId" to taskId, "actionType" to status),
        )
        return NotificationActionOutcome.APPLIED
    }

    /**
     * Sets today's occurrence status for [taskId] on the (task, day) row, never appending a second
     * row for the same day.
     *
     * A day already carrying [status] is left untouched, so re-applying "completed" cannot move
     * that row's `completedAt`. The row's tracked actual-completion fields and note are carried
     * over unchanged.
     */
    private suspend fun recordOccurrenceForDay(
        taskId: String,
        status: String,
    ) {
        val today = LocalDate.now()
        val existing = taskOccurrenceRepository.getOccurrenceForDate(taskId, today)
        if (existing?.status == status) {
            logger.i(
                LOG_SOURCE,
                "Occurrence already has this status; nothing written",
                mapOf("taskId" to taskId, "status" to status, "date" to today.toString()),
            )
            return
        }
        taskOccurrenceRepository.toggleOccurrence(
            taskId = taskId,
            date = today,
            newStatus = status,
            note = existing?.note,
            reason = "notification_action",
            actualCompletedAt = existing?.actualCompletedAt,
            actualDurationMinutes = existing?.actualDurationMinutes,
        )
    }

    /** Snooze: reschedules through the scheduler, then dismisses the current reminder. */
    private suspend fun applySnooze(action: PendingNotificationAction): NotificationActionOutcome {
        val task =
            taskRepository.getTaskById(action.taskId)
                ?: run {
                    logger.w(
                        LOG_SOURCE,
                        "Task not found",
                        mapOf("taskId" to action.taskId, "actionType" to ACTION_TYPE_SNOOZE),
                    )
                    return NotificationActionOutcome.NOT_APPLICABLE
                }
        // A recorded action carries no due time, so the scheduler's own default window applies.
        notificationScheduler.scheduleSnooze(task, dueAt = null)
        cancelDisplayedNotification(action.notificationId)
        logger.i(
            LOG_SOURCE,
            "Snoozed task reminder",
            mapOf("taskId" to action.taskId),
        )
        return NotificationActionOutcome.APPLIED
    }

    private fun cancelDisplayedNotification(notificationIdInt: Int) {
        if (notificationIdInt <= 0) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationIdInt)
    }

    companion object {
        /** Completion status; also the occurrence status recorded for it. */
        const val ACTION_TYPE_COMPLETE = "completed"

        /** Skip status; also the occurrence status recorded for it. */
        const val ACTION_TYPE_SKIP = "skipped"

        /** Miss status; also the occurrence status recorded for it. */
        const val ACTION_TYPE_MISS = "missed"

        /** Snooze reschedules the reminder and records no occurrence status. */
        const val ACTION_TYPE_SNOOZE = "snooze"

        private const val LOG_SOURCE = "NotificationActionCoordinator"
    }
}
