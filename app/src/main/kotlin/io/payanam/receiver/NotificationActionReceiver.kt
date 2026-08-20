//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.FeatureFlags
import io.payanam.MainActivity
import io.payanam.common.logging.UnifiedLogger
import io.payanam.common.util.PersistedDateTime
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.usecase.RecurrenceManager
import io.payanam.widget.TimeTrackingWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles notification action button clicks.
 */
@AndroidEntryPoint
/**
 * NotificationActionReceiver.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        /** Action stop tracking. */
        const val ACTION_STOP_TRACKING = "io.payanam.action.NOTIFICATION_STOP_TRACKING"
        /** Action complete task. */
        const val ACTION_COMPLETE_TASK = "io.payanam.action.NOTIFICATION_COMPLETE_TASK"
        /** Action skip task. */
        const val ACTION_SKIP_TASK = "io.payanam.action.NOTIFICATION_SKIP_TASK"
        /** Action miss task. */
        const val ACTION_MISS_TASK = "io.payanam.action.NOTIFICATION_MISS_TASK"
        /** Action snooze task. */
        const val ACTION_SNOOZE_TASK = "io.payanam.action.NOTIFICATION_SNOOZE_TASK"
    }

    @Inject
    /** Task repository. */
    lateinit var taskRepository: TaskRepository

    @Inject
    /** Task occurrence repository. */
    lateinit var taskOccurrenceRepository: TaskOccurrenceRepository

    @Inject
    /** Notification scheduler. */
    lateinit var notificationScheduler: NotificationScheduler

    @Inject
    /** Recurrence manager. */
    lateinit var recurrenceManager: RecurrenceManager

    private val logger = UnifiedLogger.getInstance()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        /** Pending result. */
        val pendingResult = goAsync()
        /** If. */
        if (!FeatureFlags.remindersEnabled && intent.action != ACTION_STOP_TRACKING) {
            logger.i(
                "NotificationActionReceiver.onReceive",
                "Ignoring reminder action because reminders are disabled",
                /** Map of. */
                mapOf("action" to (intent.action ?: "null")),
            )
            pendingResult.finish()
            /** Return. */
            return
        }
        /** When. */
        when (intent.action) {
            ACTION_STOP_TRACKING -> handleStopTracking(context, pendingResult)
            ACTION_COMPLETE_TASK -> handleTaskAction(context, intent, "completed", pendingResult)
            ACTION_SKIP_TASK -> handleTaskAction(context, intent, "skipped", pendingResult)
            ACTION_MISS_TASK -> handleTaskAction(context, intent, "missed", pendingResult)
            ACTION_SNOOZE_TASK -> handleSnooze(context, intent, pendingResult)
            else -> pendingResult.finish()
        }
    }

    private fun handleStopTracking(context: Context, pendingResult: BroadcastReceiver.PendingResult) {
        try {
            /** Launch intent. */
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                /** Put extra. */
                putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
                /** Put extra. */
                putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, false)
                /** Put extra. */
                putExtra(MainActivity.EXTRA_OPEN_TIME_STOP_TRACKING, true)
                /** Put extra. */
                putExtra(MainActivity.EXTRA_NAV_SOURCE, "tracking_notification")
            }
            context.startActivity(launchIntent)
            logger.i("NotificationActionReceiver.handleStopTracking", "Redirected stop action to in-app focus dialog")
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("NotificationActionReceiver.handleStopTracking", "Failed to open stop-tracking dialog", e)
        } finally {
            TimeTrackingWidgetProvider.requestUpdate(context)
            pendingResult.finish()
        }
    }

    private fun handleTaskAction(
        /** Context. */
        context: Context,
        /** Intent. */
        intent: Intent,
        /** Status. */
        status: String,
        pendingResult: BroadcastReceiver.PendingResult,
    ) {
        /** Task id. */
        val taskId = intent.getStringExtra(TaskReminderReceiver.EXTRA_TASK_ID)
        /** Notification id int. */
        val notificationIdInt = intent.getIntExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID_INT, -1)
        /** If. */
        if (taskId.isNullOrBlank()) {
            logger.w(
                "NotificationActionReceiver.handleTaskAction",
                "Missing taskId for action",
                /** Map of. */
                mapOf(
                    "status" to status,
                ),
            )
            pendingResult.finish()
            /** Return. */
            return
        }

        scope.launch {
            try {
                /** Task. */
                val task = taskRepository.getTaskById(taskId)
                /** If. */
                if (task == null) {
                    logger.w(
                        "NotificationActionReceiver.handleTaskAction",
                        "Task not found",
                        /** Map of. */
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                    return@launch
                }

                /** Is frequency habit. */
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
                /** If. */
                if (!isFrequencyHabit) {
                    /** When. */
                    when (status) {
                        "completed" -> taskRepository.completeTask(taskId)

                        "skipped" -> taskRepository.skipTask(taskId)

                        "missed" -> taskRepository.missTask(taskId)

                        else -> {
                            logger.w(
                                "NotificationActionReceiver.handleTaskAction",
                                "Unknown action status",
                                /** Map of. */
                                mapOf(
                                    "status" to status,
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                }

                /** If. */
                if (task.recurrenceEnabled) {
                    /** Occurrence. */
                    val occurrence = TaskOccurrence(
                        id = java.util.UUID.randomUUID().toString(),
                        taskId = taskId,
                        occurrenceDate = java.time.LocalDate.now().toString(),
                        status = status,
                        statusNote = null,
                        statusReason = "notification_action",
                        completedAt = if (status == "completed") System.currentTimeMillis().toString() else null,
                        skippedAt = if (status == "skipped" || status == "missed") {
                            System.currentTimeMillis().toString()
                        } else {
                            /** Null. */
                            null
                        },
                    )
                    taskOccurrenceRepository.recordOccurrence(occurrence)

                    /** If. */
                    if (FeatureFlags.minimalModeEnabled && task.recurrenceEnabled) {
                        logger.i(
                            "NotificationActionReceiver.handleTaskAction",
                            "Minimal mode: skipping recurrence advancement for recurring task",
                            /** Map of. */
                            mapOf("taskId" to taskId, "status" to status),
                        )
                    } else {
                        /** When. */
                        when (status) {
                            "completed" -> recurrenceManager.onTaskCompleted(task, note = null, reason = "notification_action")
                            "skipped" -> recurrenceManager.onTaskSkipped(task, note = null, reason = "notification_action")
                            "missed" -> recurrenceManager.onTaskMissed(task, note = null, reason = "notification_action")
                        }
                        /** Updated task. */
                        val updatedTask = taskRepository.getTaskById(taskId)
                        /** If. */
                        if (updatedTask != null && updatedTask.recurrenceEnabled) {
                            notificationScheduler.scheduleForTask(updatedTask)
                        }
                    }
                } else {
                    notificationScheduler.cancelForTask(taskId)
                }

                /** Cancel displayed notification. */
                cancelDisplayedNotification(context, notificationIdInt)

                logger.i(
                    "NotificationActionReceiver.handleTaskAction",
                    "Task action handled",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "status" to status,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "NotificationActionReceiver.handleTaskAction",
                    "Failed to handle task action",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "status" to status,
                    ),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleSnooze(context: Context, intent: Intent, pendingResult: BroadcastReceiver.PendingResult) {
        /** Task id. */
        val taskId = intent.getStringExtra(TaskReminderReceiver.EXTRA_TASK_ID)
        /** Due at raw. */
        val dueAtRaw = intent.getStringExtra(TaskReminderReceiver.EXTRA_DUE_AT)
        /** Notification id int. */
        val notificationIdInt = intent.getIntExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID_INT, -1)
        /** If. */
        if (taskId.isNullOrBlank()) {
            logger.w("NotificationActionReceiver.handleSnooze", "Missing taskId for snooze")
            pendingResult.finish()
            /** Return. */
            return
        }

        scope.launch {
            try {
                /** Task. */
                val task = taskRepository.getTaskById(taskId)
                /** If. */
                if (task == null) {
                    logger.w(
                        "NotificationActionReceiver.handleSnooze",
                        "Task not found",
                        /** Map of. */
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                    return@launch
                }

                /** Due at. */
                val dueAt = PersistedDateTime.parseOrNull(dueAtRaw)
                notificationScheduler.scheduleSnooze(task, dueAt)
                /** Cancel displayed notification. */
                cancelDisplayedNotification(context, notificationIdInt)

                logger.i(
                    "NotificationActionReceiver.handleSnooze",
                    "Snoozed task reminder",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "NotificationActionReceiver.handleSnooze",
                    "Failed to snooze reminder",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun cancelDisplayedNotification(context: Context, notificationIdInt: Int) {
        /** If. */
        if (notificationIdInt <= 0) return
        /** Notification manager. */
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(notificationIdInt)
    }
}
