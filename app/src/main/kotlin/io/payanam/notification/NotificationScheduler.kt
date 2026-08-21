//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.FeatureFlags
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.common.util.DateTimeUtil
import io.payanam.domain.model.Task
import io.payanam.domain.repository.NotificationRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.receiver.TaskReminderReceiver
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
/**
 * Provides the notification scheduler.
 */
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val notificationRepository: NotificationRepository,
) {

    private val logger = UnifiedLogger.getInstance()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val zoneId = ZoneId.systemDefault()
    /**
     * Performs the schedule all pending tasks.
     */
    suspend fun scheduleAllPendingTasks() {
        if (!FeatureFlags.remindersEnabled) {
            logger.i("NotificationScheduler.scheduleAllPendingTasks", "Reminder scheduling disabled by feature flag")
            return
        }
        val tasks = taskRepository.getAllTasks().first()
        val eligible = tasks.filter { isEligibleForScheduling(it) }
        logger.i(
            "NotificationScheduler.scheduleAllPendingTasks",
            "Scheduling pending tasks",
            mapOf(
                "total" to tasks.size,
                "eligible" to eligible.size,
            ),
        )
        eligible.forEach { task ->
            scheduleForTask(task)
        }
    }
    /**
     * Performs the schedule for task.
     */
    suspend fun scheduleForTask(
        task: Task,
        overrideScheduledAt: LocalDateTime? = null,
        overrideDueAt: LocalDateTime? = null,
        isSnoozed: Boolean = false,
    ) {
        if (!FeatureFlags.remindersEnabled) {
            cancelForTask(task.id)
            logger.d(
                "NotificationScheduler.scheduleForTask",
                "Reminder scheduling disabled by feature flag",
                mapOf(
                    "taskId" to task.id,
                ),
            )
            return
        }
        val dueAt = overrideDueAt ?: task.dueDate
        if (!isEligibleForScheduling(task)) {
            cancelForTask(task.id)
            logger.d(
                "NotificationScheduler.scheduleForTask",
                "Task not eligible for scheduling",
                mapOf(
                    "taskId" to task.id,
                    "status" to task.status,
                    "mode" to (task.notificationMode ?: "auto"),
                ),
            )
            return
        }
        if (dueAt == null) {
            cancelForTask(task.id)
            logger.d(
                "NotificationScheduler.scheduleForTask",
                "Skipping schedule - no due date",
                mapOf(
                    "taskId" to task.id,
                ),
            )
            return
        }
        val scheduledAt = overrideScheduledAt ?: run {
            val advanceMinutes = resolveAdvanceMinutes(task) ?: run {
                cancelForTask(task.id)
                logger.d(
                    "NotificationScheduler.scheduleForTask",
                    "Notifications disabled for task",
                    mapOf(
                        "taskId" to task.id,
                    ),
                )
                return
            }
            dueAt.minusMinutes(advanceMinutes)
        }
        if (!scheduledAt.isAfter(LocalDateTime.now())) {
            cancelForTask(task.id)
            logger.d(
                "NotificationScheduler.scheduleForTask",
                "Skipping schedule - time is in the past",
                mapOf(
                    "taskId" to task.id,
                    "scheduledAt" to scheduledAt.toString(),
                ),
            )
            return
        }
        cancelForTask(task.id)
        val notificationType = if (task.recurrenceEnabled) {
            TaskReminderReceiver.TYPE_HABIT_TRACKING
        } else {
            TaskReminderReceiver.TYPE_TASK_REMINDER
        }
        val title = context.getString(R.string.task_notification_title, task.title)
        val body = buildNotificationBody(task, dueAt, scheduledAt, isSnoozed)
        val notificationId = try {
            notificationRepository.scheduleNotification(
                taskId = task.id,
                scheduledAt = scheduledAt,
                notificationType = notificationType,
                title = title,
                body = body,
            )
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "NotificationScheduler.scheduleForTask",
                "Failed to persist scheduled notification",
                e,
                mapOf(
                    "taskId" to task.id,
                ),
            )
            return
        }

        try {
            scheduleAlarm(
                notificationId = notificationId,
                task = task,
                scheduledAt = scheduledAt,
                dueAt = dueAt,
                notificationType = notificationType,
                isSnoozed = isSnoozed,
            )
            logger.i(
                "NotificationScheduler.scheduleForTask",
                "Alarm scheduled",
                mapOf(
                    "taskId" to task.id,
                    "notificationId" to notificationId,
                    "scheduledAt" to scheduledAt.toString(),
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "NotificationScheduler.scheduleForTask",
                "Failed to schedule alarm",
                e,
                mapOf(
                    "taskId" to task.id,
                    "notificationId" to notificationId,
                ),
            )
            notificationRepository.cancelNotification(notificationId)
        }
    }
    /**
     * Performs the schedule snooze.
     */
    suspend fun scheduleSnooze(task: Task, dueAt: LocalDateTime?) {
        val now = LocalDateTime.now()
        val target = dueAt?.takeIf { it.isAfter(now) } ?: now.plusMinutes(DEFAULT_SNOOZE_MINUTES)
        scheduleForTask(
            task = task,
            overrideScheduledAt = target,
            overrideDueAt = dueAt ?: task.dueDate,
            isSnoozed = true,
        )
    }
    /**
     * Returns true when the cancel for task.
     */
    suspend fun cancelForTask(taskId: String) {
        val notifications = notificationRepository.getNotificationsForTask(taskId)
        notifications.forEach { notification ->
            val intent = Intent(context, TaskReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCodeFor(notification.id),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pendingIntent)
            notificationManager.cancel(requestCodeFor(notification.id))
        }
        notificationRepository.cancelNotificationsForTask(taskId)
        logger.d(
            "NotificationScheduler.cancelForTask",
            "Cancelled notifications and visible reminders for task",
            mapOf(
                "taskId" to taskId,
                "count" to notifications.size,
            ),
        )
    }

    private fun scheduleAlarm(
        notificationId: String,
        task: Task,
        scheduledAt: LocalDateTime,
        dueAt: LocalDateTime,
        notificationType: String,
        isSnoozed: Boolean,
    ) {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            putExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(TaskReminderReceiver.EXTRA_TASK_ID, task.id)
            putExtra(TaskReminderReceiver.EXTRA_TASK_TITLE, task.title)
            putExtra(TaskReminderReceiver.EXTRA_DUE_AT, dueAt.toString())
            putExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_TYPE, notificationType)
            putExtra(TaskReminderReceiver.EXTRA_IS_SNOOZED, isSnoozed)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeFor(notificationId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAtMillis = scheduledAt.atZone(zoneId).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                logger.w(
                    "NotificationScheduler.scheduleAlarm",
                    "Exact alarm permission not granted, using inexact",
                    mapOf(
                        "taskId" to task.id,
                    ),
                )
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun buildNotificationBody(
        task: Task,
        dueAt: LocalDateTime,
        scheduledAt: LocalDateTime,
        isSnoozed: Boolean,
    ): String {
        val timeText = DateTimeUtil.formatTime(dueAt.toLocalTime(), use24Hour = false)
        if (isSnoozed) {
            return context.getString(R.string.task_notification_snoozed_until, timeText)
        }
        val minutesUntilDue = Duration.between(scheduledAt, dueAt).toMinutes()
        return if (minutesUntilDue > 0) {
            context.getString(
                R.string.task_notification_due_in_at,
                minutesUntilDue,
                timeText,
            )
        } else {
            context.getString(R.string.task_notification_due_at, timeText)
        }
    }

    private fun resolveAdvanceMinutes(task: Task): Long? {
        val mode = task.notificationMode?.lowercase() ?: "auto"
        return when (mode) {
            "off" -> null

            "custom" -> {
                task.customNotificationMinutes?.takeIf { it > 0 }?.toLong()
                    ?: DEFAULT_ADVANCE_MINUTES
            }

            else -> DEFAULT_ADVANCE_MINUTES
        }
    }

    private fun isEligibleForScheduling(task: Task): Boolean {
        val status = task.status.lowercase()
        if (status != "pending" && status != "active") return false
        if ((task.notificationMode ?: "auto").lowercase() == "off") return false
        if (FeatureFlags.minimalModeEnabled && task.recurrenceEnabled) {
            logger.d(
                "NotificationScheduler.isEligibleForScheduling",
                "Minimal mode: skipping notification for recurring task",
                mapOf("taskId" to task.id),
            )
            return false
        }
        return true
    }

    private fun requestCodeFor(notificationId: String): Int {
        val hash = notificationId.hashCode()
        return when (hash) {
            Int.MIN_VALUE -> 0
            else -> abs(hash)
        }
    }

    companion object {
        private const val DEFAULT_ADVANCE_MINUTES = 15L
        private const val DEFAULT_SNOOZE_MINUTES = 15L
    }
}
