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
 * Provides the task reminder receiver.
 */
class TaskReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notificationRepository: NotificationRepository

    private val logger = UnifiedLogger.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Handles the on receive.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (!FeatureFlags.remindersEnabled) {
                    logger.i("TaskReminderReceiver.onReceive", "Ignoring reminder broadcast because reminders are disabled")
                    return@launch
                }
                val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE)
                val dueAtRaw = intent.getStringExtra(EXTRA_DUE_AT)
                val notificationType = intent.getStringExtra(EXTRA_NOTIFICATION_TYPE) ?: TYPE_TASK_REMINDER
                val isSnoozed = intent.getBooleanExtra(EXTRA_IS_SNOOZED, false)
                if (notificationId.isNullOrBlank() || taskId.isNullOrBlank() || taskTitle.isNullOrBlank()) {
                    logger.w(
                        "TaskReminderReceiver.onReceive",
                        "Missing notification data",
                        mapOf(
                            "notificationId" to (notificationId ?: "null"),
                            "taskId" to (taskId ?: "null"),
                            "taskTitle" to (taskTitle ?: "null"),
                        ),
                    )
                    return@launch
                }
                val dueAt = PersistedDateTime.parseOrNull(dueAtRaw)
                val notificationIdInt = notificationIdToInt(notificationId)
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
        context: Context,
        notificationIdInt: Int,
        notificationId: String,
        taskId: String,
        taskTitle: String,
        dueAt: LocalDateTime?,
        notificationType: String,
        isSnoozed: Boolean,
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = when (notificationType) {
            TYPE_HABIT_TRACKING -> PayanamApp.CHANNEL_HABIT_TRACKING
            TYPE_MISSED_TASK -> PayanamApp.CHANNEL_MISSED_TASKS
            else -> PayanamApp.CHANNEL_TASK_REMINDERS
        }
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            notificationIdInt,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val actionExtras = Intent().apply {
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_NOTIFICATION_ID_INT, notificationIdInt)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TASK_TITLE, taskTitle)
            putExtra(EXTRA_DUE_AT, dueAt?.toString())
            putExtra(EXTRA_NOTIFICATION_TYPE, notificationType)
            putExtra(EXTRA_IS_SNOOZED, isSnoozed)
        }
        val completeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COMPLETE_TASK
            putExtras(actionExtras)
        }
        val skipIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SKIP_TASK
            putExtras(actionExtras)
        }
        val snoozeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SNOOZE_TASK
            putExtras(actionExtras)
        }
        val missIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MISS_TASK
            putExtras(actionExtras)
        }
        val completePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationIdInt + 1,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skipPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationIdInt + 2,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationIdInt + 3,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val missPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationIdInt + 4,
            missIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val timeText = dueAt?.let { DateTimeUtil.formatTime(it.toLocalTime(), use24Hour = false) }
        val body = when {
            isSnoozed && timeText != null -> context.getString(
                R.string.task_notification_snoozed_until,
                timeText,
            )

            timeText != null -> context.getString(
                R.string.task_notification_due_at,
                timeText,
            )

            else -> context.getString(R.string.task_notification_reminder)
        }
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
                completePendingIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.task_notification_action_skip),
                skipPendingIntent,
            )
        if (!isSnoozed) {
            builder.addAction(
                android.R.drawable.ic_lock_idle_alarm,
                context.getString(R.string.task_notification_action_snooze),
                snoozePendingIntent,
            )
        }
        if (notificationType == TYPE_HABIT_TRACKING) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.loc_miss),
                missPendingIntent,
            )
        }

        notificationManager.notify(notificationIdInt, builder.build())
    }

    private fun notificationIdToInt(notificationId: String): Int {
        val hash = notificationId.hashCode()
        return when (hash) {
            Int.MIN_VALUE -> 0
            else -> abs(hash)
        }
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_NOTIFICATION_ID_INT = "extra_notification_id_int"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_DUE_AT = "extra_due_at"
        const val EXTRA_NOTIFICATION_TYPE = "extra_notification_type"
        const val EXTRA_IS_SNOOZED = "extra_is_snoozed"
        const val TYPE_TASK_REMINDER = "task_reminder"
        const val TYPE_HABIT_TRACKING = "habit_tracking"
        const val TYPE_MISSED_TASK = "missed_task"
    }
}
