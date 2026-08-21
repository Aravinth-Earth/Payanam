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
 * Provides the notification action receiver.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_TRACKING = "io.payanam.action.NOTIFICATION_STOP_TRACKING"
        const val ACTION_COMPLETE_TASK = "io.payanam.action.NOTIFICATION_COMPLETE_TASK"
        const val ACTION_SKIP_TASK = "io.payanam.action.NOTIFICATION_SKIP_TASK"
        const val ACTION_MISS_TASK = "io.payanam.action.NOTIFICATION_MISS_TASK"
        const val ACTION_SNOOZE_TASK = "io.payanam.action.NOTIFICATION_SNOOZE_TASK"
    }

    @Inject
    lateinit var taskRepository: TaskRepository

    @Inject
    lateinit var taskOccurrenceRepository: TaskOccurrenceRepository

    @Inject
    lateinit var notificationScheduler: NotificationScheduler

    @Inject
    lateinit var recurrenceManager: RecurrenceManager

    private val logger = UnifiedLogger.getInstance()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Handles the on receive.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        if (!FeatureFlags.remindersEnabled && intent.action != ACTION_STOP_TRACKING) {
            logger.i(
                "NotificationActionReceiver.onReceive",
                "Ignoring reminder action because reminders are disabled",
                mapOf("action" to (intent.action ?: "null")),
            )
            pendingResult.finish()
            return
        }
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
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
                putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, false)
                putExtra(MainActivity.EXTRA_OPEN_TIME_STOP_TRACKING, true)
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
        context: Context,
        intent: Intent,
        status: String,
        pendingResult: BroadcastReceiver.PendingResult,
    ) {
        val taskId = intent.getStringExtra(TaskReminderReceiver.EXTRA_TASK_ID)
        val notificationIdInt = intent.getIntExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID_INT, -1)
        if (taskId.isNullOrBlank()) {
            logger.w(
                "NotificationActionReceiver.handleTaskAction",
                "Missing taskId for action",
                mapOf(
                    "status" to status,
                ),
            )
            pendingResult.finish()
            return
        }

        scope.launch {
            try {
                val task = taskRepository.getTaskById(taskId)
                if (task == null) {
                    logger.w(
                        "NotificationActionReceiver.handleTaskAction",
                        "Task not found",
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                    return@launch
                }
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
                if (!isFrequencyHabit) {
                    when (status) {
                        "completed" -> taskRepository.completeTask(taskId)

                        "skipped" -> taskRepository.skipTask(taskId)

                        "missed" -> taskRepository.missTask(taskId)

                        else -> {
                            logger.w(
                                "NotificationActionReceiver.handleTaskAction",
                                "Unknown action status",
                                mapOf(
                                    "status" to status,
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                }
                if (task.recurrenceEnabled) {
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
                            null
                        },
                    )
                    taskOccurrenceRepository.recordOccurrence(occurrence)
                    if (FeatureFlags.minimalModeEnabled && task.recurrenceEnabled) {
                        logger.i(
                            "NotificationActionReceiver.handleTaskAction",
                            "Minimal mode: skipping recurrence advancement for recurring task",
                            mapOf("taskId" to taskId, "status" to status),
                        )
                    } else {
                        when (status) {
                            "completed" -> recurrenceManager.onTaskCompleted(task, note = null, reason = "notification_action")
                            "skipped" -> recurrenceManager.onTaskSkipped(task, note = null, reason = "notification_action")
                            "missed" -> recurrenceManager.onTaskMissed(task, note = null, reason = "notification_action")
                        }
                        val updatedTask = taskRepository.getTaskById(taskId)
                        if (updatedTask != null && updatedTask.recurrenceEnabled) {
                            notificationScheduler.scheduleForTask(updatedTask)
                        }
                    }
                } else {
                    notificationScheduler.cancelForTask(taskId)
                }
                cancelDisplayedNotification(context, notificationIdInt)

                logger.i(
                    "NotificationActionReceiver.handleTaskAction",
                    "Task action handled",
                    mapOf(
                        "taskId" to taskId,
                        "status" to status,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "NotificationActionReceiver.handleTaskAction",
                    "Failed to handle task action",
                    e,
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
        val taskId = intent.getStringExtra(TaskReminderReceiver.EXTRA_TASK_ID)
        val dueAtRaw = intent.getStringExtra(TaskReminderReceiver.EXTRA_DUE_AT)
        val notificationIdInt = intent.getIntExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID_INT, -1)
        if (taskId.isNullOrBlank()) {
            logger.w("NotificationActionReceiver.handleSnooze", "Missing taskId for snooze")
            pendingResult.finish()
            return
        }

        scope.launch {
            try {
                val task = taskRepository.getTaskById(taskId)
                if (task == null) {
                    logger.w(
                        "NotificationActionReceiver.handleSnooze",
                        "Task not found",
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                    return@launch
                }
                val dueAt = PersistedDateTime.parseOrNull(dueAtRaw)
                notificationScheduler.scheduleSnooze(task, dueAt)
                cancelDisplayedNotification(context, notificationIdInt)

                logger.i(
                    "NotificationActionReceiver.handleSnooze",
                    "Snoozed task reminder",
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "NotificationActionReceiver.handleSnooze",
                    "Failed to snooze reminder",
                    e,
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
        if (notificationIdInt <= 0) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(notificationIdInt)
    }
}
