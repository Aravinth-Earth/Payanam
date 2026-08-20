//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.FeatureFlags
import io.payanam.MainActivity
import io.payanam.PayanamApp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.common.util.DateTimeUtil
import io.payanam.common.util.PersistedDateTime
import io.payanam.domain.repository.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
/**
 * TaskReminderReceiver.
 */
class TaskReminderReceiver : BroadcastReceiver() {

    @Inject
    /** Notification repository. */
    lateinit var notificationRepository: NotificationRepository

    private val logger = UnifiedLogger.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        /** Pending result. */
        val pendingResult = goAsync()
        scope.launch {
            try {
                /** If. */
                if (!FeatureFlags.remindersEnabled) {
                    logger.i("TaskReminderReceiver.onReceive", "Ignoring reminder broadcast because reminders are disabled")
                    return@launch
                }
                /** Notification id. */
                val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
                /** Task id. */
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                /** Task title. */
                val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE)
                /** Due at raw. */
                val dueAtRaw = intent.getStringExtra(EXTRA_DUE_AT)
                /** Notification type. */
                val notificationType = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE) ?: TYPE_TASK_REMINDER
                /** Is snoozed. */
                val isSnoozed = intent.getBooleanExtra(EXTRA_IS_SNOOZED, false)

                /** If. */
                if (notificationId.isNullOrBlank() || taskId.isNullOrBlank() || taskTitle.isNullOrBlank()) {
                    logger.w(
                        "TaskReminderReceiver.onReceive",
                        "Missing notification data",
                        /** Map of. */
                        mapOf(
                            "notificationId" to (notificationId ?: "null"),
                            "taskId" to (taskId ?: "null"),
                            "taskTitle" to (taskTitle ?: "null"),
                        ),
                    )
                    return@launch
                }

                /** Due at. */
                val dueAt = PersistedDateTime.parseOrNull(dueAtRaw)
                /** Notification id int. */
                val notificationIdInt = notificationIdToInt(notificationId)

                /** Show notification. */
                showNotification(
                    context = context,
                    notificationIdInt = notificationIdInt,
                    notificationId = notificationId,
                    taskId = taskId,
                    taskTitle = taskTitle,
                    dueAt = dueAt,
                    notificationType = notificationType,
                    isSnoozed = isSnoozed,
                )

                notificationRepository.markDelivered(notificationId)
                logger.i(
                    "TaskReminderReceiver.onReceive",
                    "Notification delivered",
                    /** Map of. */
                    mapOf(
                        "notificationId" to notificationId,
                        "taskId" to taskId,
                        "type" to notificationType,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskReminderReceiver.onReceive", "Failed to deliver reminder", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showNotification(
        /** Context. */
        context: Context,
        /** Notification id int. */
        notificationIdInt: Int,
        /** Notification id. */
        notificationId: String,
        /** Task id. */
        taskId: String,
        /** Task title. */
        taskTitle: String,
        dueAt: LocalDateTime?,
        /** Notification type. */
        notificationType: String,
        /** Is snoozed. */
        isSnoozed: Boolean,
    ) {
        /** Notification manager. */
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        /** Channel id. */
        val channelId = when (notificationType) {
            TYPE_HABIT_TRACKING -> PayanamApp.CHANNEL_HABIT_TRACKING
            TYPE_MISSED_TASK -> PayanamApp.CHANNEL_MISSED_TASKS
            else -> PayanamApp.CHANNEL_TASK_REMINDERS
        }

        /** Open app intent. */
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        /** Open app pending intent. */
        val openAppPendingIntent = PendingIntent.getActivity(
            /** Context. */
            context,
            /** Notification id int. */
            notificationIdInt,
            /** Open app intent. */
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Action extras. */
        val actionExtras = Intent().apply {
            /** Put extra. */
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            /** Put extra. */
            putExtra(EXTRA_NOTIFICATION_ID_INT, notificationIdInt)
            /** Put extra. */
            putExtra(EXTRA_TASK_ID, taskId)
            /** Put extra. */
            putExtra(EXTRA_TASK_TITLE, taskTitle)
            /** Put extra. */
            putExtra(EXTRA_DUE_AT, dueAt?.toString())
            /** Put extra. */
            putExtra(EXTRA_NOTIFICATION_TYPE, notificationType)
            /** Put extra. */
            putExtra(EXTRA_IS_SNOOZED, isSnoozed)
        }

        /** Complete intent. */
        val completeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COMPLETE_TASK
            /** Put extras. */
            putExtras(actionExtras)
        }
        /** Skip intent. */
        val skipIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SKIP_TASK
            /** Put extras. */
            putExtras(actionExtras)
        }
        /** Snooze intent. */
        val snoozeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SNOOZE_TASK
            /** Put extras. */
            putExtras(actionExtras)
        }
        /** Miss intent. */
        val missIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MISS_TASK
            /** Put extras. */
            putExtras(actionExtras)
        }

        /** Complete pending intent. */
        val completePendingIntent = PendingIntent.getBroadcast(
            /** Context. */
            context,
            notificationIdInt + 1,
            /** Complete intent. */
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        /** Skip pending intent. */
        val skipPendingIntent = PendingIntent.getBroadcast(
            /** Context. */
            context,
            notificationIdInt + 2,
            /** Skip intent. */
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        /** Snooze pending intent. */
        val snoozePendingIntent = PendingIntent.getBroadcast(
            /** Context. */
            context,
            notificationIdInt + 3,
            /** Snooze intent. */
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        /** Miss pending intent. */
        val missPendingIntent = PendingIntent.getBroadcast(
            /** Context. */
            context,
            notificationIdInt + 4,
            /** Miss intent. */
            missIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Time text. */
        val timeText = dueAt?.let { DateTimeUtil.formatTime(it.toLocalTime(), use24Hour = false) }
        /** Body. */
        val body = when {
            isSnoozed && timeText != null -> context.getString(
                R.string.task_notification_snoozed_until,
                /** Time text. */
                timeText,
            )

            timeText != null -> context.getString(
                R.string.task_notification_due_at,
                /** Time text. */
                timeText,
            )

            else -> context.getString(R.string.task_notification_reminder)
        }

        /** Builder. */
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(taskTitle)
            .setContentText(body)
            .setSubText(context.getString(R.string.task_notification_reminder))
            .setContentIntent(openAppPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(
                android.R.drawable.checkbox_on_background,
                context.getString(R.string.task_notification_action_complete),
                /** Complete pending intent. */
                completePendingIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.task_notification_action_skip),
                /** Skip pending intent. */
                skipPendingIntent,
            )

        /** If. */
        if (!isSnoozed) {
            builder.addAction(
                android.R.drawable.ic_lock_idle_alarm,
                context.getString(R.string.task_notification_action_snooze),
                /** Snooze pending intent. */
                snoozePendingIntent,
            )
        }

        /** If. */
        if (notificationType == TYPE_HABIT_TRACKING) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.loc_miss),
                /** Miss pending intent. */
                missPendingIntent,
            )
        }

        notificationManager.notify(notificationIdInt, builder.build())
    }

    private fun notificationIdToInt(notificationId: String): Int {
        /** Hash. */
        val hash = notificationId.hashCode()
        return when (hash) {
            Int.MIN_VALUE -> 0
            else -> abs(hash)
        }
    }

    companion object {
        /** Extra notification id. */
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        /** Extra notification id int. */
        const val EXTRA_NOTIFICATION_ID_INT = "extra_notification_id_int"
        /** Extra task id. */
        const val EXTRA_TASK_ID = "extra_task_id"
        /** Extra task title. */
        const val EXTRA_TASK_TITLE = "extra_task_title"
        /** Extra due at. */
        const val EXTRA_DUE_AT = "extra_due_at"
        /** Extra notification type. */
        const val EXTRA_NOTIFICATION_TYPE = "extra_notification_type"
        /** Extra is snoozed. */
        const val EXTRA_IS_SNOOZED = "extra_is_snoozed"

        /** Type task reminder. */
        const val TYPE_TASK_REMINDER = "task_reminder"
        /** Type habit tracking. */
        const val TYPE_HABIT_TRACKING = "habit_tracking"
        /** Type missed task. */
        const val TYPE_MISSED_TASK = "missed_task"
    }
}
