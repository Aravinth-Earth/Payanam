//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.FeatureFlags
import io.payanam.MainActivity
import io.payanam.PayanamApp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.common.util.DateTimeUtil
import io.payanam.common.util.PersistedDateTime
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.Task
import io.payanam.domain.repository.NotificationRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.notification.ASSUMED_VISIBLE_ACTIONS
import io.payanam.notification.NotificationActionCoordinator
import io.payanam.notification.ReminderActionSlot
import io.payanam.notification.reminderActionSlots
import io.payanam.shared.platform.NotificationDecision
import io.payanam.shared.platform.ReminderDecider
import io.payanam.shared.platform.ReminderFacts
import io.payanam.shared.platform.ReminderSkipReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.abs

/**
 * Alarm-fired task reminder: validates extras, checks the task is still remindable,
 * posts the notification on the right channel, and marks it delivered.
 */
@AndroidEntryPoint
class TaskReminderReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notificationRepository: NotificationRepository

    @Inject
    lateinit var taskRepository: TaskRepository

    @Inject
    lateinit var sessionManager: DatabaseSessionManager

    private val logger = UnifiedLogger.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Delivers the reminder asynchronously (goAsync) when reminders are on.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-system-API catch in background component
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
                if (deliveryGate(taskId, notificationId) == DeliveryGate.DROP) {
                    notificationRepository.cancelNotification(notificationId)
                    return@launch
                }
                showNotification(
                    context = context,
                    notificationIdInt = notificationIdInt,
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
            } catch (e: Exception) {
                logger.e("TaskReminderReceiver.onReceive", "Failed to deliver reminder", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Delivery gate outcome: show it, drop it, or the gate could not be read. */
    private enum class DeliveryGate { DELIVER, DROP, NOT_EVALUATED }

    /** Delivery-time verdict from the stored task, whose status may have moved since the alarm was armed. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a repository read is the one thing that can throw here
    private suspend fun deliveryGate(taskId: String, notificationId: String): DeliveryGate {
        if (!sessionManager.isDbOpen()) {
            logger.w(
                "TaskReminderReceiver.deliveryGate",
                "Session closed; gate not evaluated, delivering as armed",
                mapOf("notificationId" to notificationId, "taskId" to taskId),
            )
            return DeliveryGate.NOT_EVALUATED
        }
        return try {
            val task = taskRepository.getTaskById(taskId)
            if (task != null && isStillRemindable(task)) {
                DeliveryGate.DELIVER
            } else {
                logger.i(
                    "TaskReminderReceiver.deliveryGate",
                    "Dropping reminder: task is no longer remindable",
                    mapOf(
                        "notificationId" to notificationId,
                        "taskId" to taskId,
                        "status" to (task?.status ?: "missing"),
                    ),
                )
                DeliveryGate.DROP
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(
                "TaskReminderReceiver.deliveryGate",
                "Gate could not read the task; delivering as armed",
                mapOf(
                    "notificationId" to notificationId,
                    "taskId" to taskId,
                    "error" to (e.message ?: e.javaClass.simpleName),
                ),
            )
            DeliveryGate.NOT_EVALUATED
        }
    }

    /** Whether [task] may still be reminded about, asked of [ReminderDecider] so this is the arming rule. */
    private fun isStillRemindable(task: Task): Boolean {
        // No due time on purpose: only the status verdict is read here.
        val facts = ReminderFacts(taskId = task.id, taskTitle = task.title, status = task.status)
        val decision = ReminderDecider.decide(facts, LocalDateTime.now())
        return (decision as? NotificationDecision.Skip)?.reason != ReminderSkipReason.STATUS_INELIGIBLE
    }

    private fun showNotification(
        context: Context,
        notificationIdInt: Int,
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
            notificationIdInt + REQUEST_CODE_CONTENT_OFFSET,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Action taps are getActivity, not getBroadcast: a broadcast tap carries no
        // activity-launch allowance, so the system drops the launch on Android 14+ while the
        // database is locked. An activity tap reaches MainActivity, which owns the action.
        val completeIntent = reminderActionIntent(
            context = context,
            intentAction = NotificationActionReceiver.ACTION_COMPLETE_TASK,
            actionType = NotificationActionCoordinator.ACTION_TYPE_COMPLETE,
            notificationIdInt = notificationIdInt,
            taskId = taskId,
            dueAt = dueAt,
        )
        val missIntent = reminderActionIntent(
            context = context,
            intentAction = NotificationActionReceiver.ACTION_MISS_TASK,
            actionType = NotificationActionCoordinator.ACTION_TYPE_MISS,
            notificationIdInt = notificationIdInt,
            taskId = taskId,
            dueAt = dueAt,
        )
        val completePendingIntent = PendingIntent.getActivity(
            context,
            notificationIdInt + REQUEST_CODE_COMPLETE_OFFSET,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val missPendingIntent = PendingIntent.getActivity(
            context,
            notificationIdInt + REQUEST_CODE_MISS_OFFSET,
            missIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // More carries the task id in its own extra so the activity opens that task's status dialog.
        // It never carries EXTRA_TASK_ID: that extra marks a reminder action, and an action writes.
        val moreIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ACTION_TASK_ID, taskId)
        }
        val morePendingIntent = PendingIntent.getActivity(
            context,
            notificationIdInt + REQUEST_CODE_MORE_OFFSET,
            moreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val actionSlots = reminderActionSlots()
        logger.i(
            "TaskReminderReceiver.showNotification",
            "Reminder action set selected",
            mapOf(
                "sdkInt" to Build.VERSION.SDK_INT,
                "assumedVisibleActions" to ASSUMED_VISIBLE_ACTIONS,
                "actions" to actionSlots.joinToString(",") { it.name },
                "type" to notificationType,
                "isSnoozed" to isSnoozed,
            ),
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(taskTitle)
            .setContentText(body)
            .setSubText(context.getString(R.string.task_notification_reminder))
            .setContentIntent(openAppPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        actionSlots.forEach { slot ->
            when (slot) {
                ReminderActionSlot.COMPLETE -> builder.addAction(
                    android.R.drawable.checkbox_on_background,
                    context.getString(R.string.task_notification_action_complete),
                    completePendingIntent,
                )

                ReminderActionSlot.MISS -> builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.loc_miss),
                    missPendingIntent,
                )

                ReminderActionSlot.MORE -> builder.addAction(
                    android.R.drawable.ic_menu_more,
                    context.getString(R.string.task_notification_action_more),
                    morePendingIntent,
                )
            }
        }

        notificationManager.notify(notificationIdInt, builder.build())
    }

    /**
     * Builds one action-tap intent.
     *
     * SINGLE_TOP|CLEAR_TOP delivers a second tap on the same reminder through
     * [MainActivity.onNewIntent] instead of stacking a second activity. Carries ids only: the
     * activity path needs no task title.
     */
    private fun reminderActionIntent(
        context: Context,
        intentAction: String,
        actionType: String,
        notificationIdInt: Int,
        taskId: String,
        dueAt: LocalDateTime?,
    ): Intent = Intent(context, MainActivity::class.java).apply {
        action = intentAction
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_REMINDER_ACTION_TYPE, actionType)
        putExtra(EXTRA_NOTIFICATION_ID_INT, notificationIdInt)
        putExtra(EXTRA_TASK_ID, taskId)
        // The occurrence belongs to the reminder's day, not the tap's. Carried through the activity
        // intent because the tap path opens MainActivity rather than the broadcast receiver.
        dueAt?.let { putExtra(EXTRA_DUE_AT, it.toString()) }
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

        /** Reminder action carried by an activity-tap intent, one of the coordinator's action types. */
        const val EXTRA_REMINDER_ACTION_TYPE = "extra_reminder_action_type"

        /** Carried by a More tap: the task whose status dialog the activity opens. Never an action. */
        const val EXTRA_OPEN_ACTION_TASK_ID = "extra_open_action_task_id"

        const val TYPE_TASK_REMINDER = "task_reminder"
        const val TYPE_HABIT_TRACKING = "habit_tracking"
        const val TYPE_MISSED_TASK = "missed_task"

        // One distinct request code per PendingIntent of the same notification: two PendingIntents
        // built from equal Intents share one slot unless their request codes differ.
        private const val REQUEST_CODE_CONTENT_OFFSET = 0
        private const val REQUEST_CODE_COMPLETE_OFFSET = 1
        private const val REQUEST_CODE_MISS_OFFSET = 2
        private const val REQUEST_CODE_MORE_OFFSET = 3
    }
}
