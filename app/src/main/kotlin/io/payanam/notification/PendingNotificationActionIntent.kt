//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.content.Intent
import io.payanam.receiver.TaskReminderReceiver

/**
 * The reminder action a notification-tap intent describes, or null when the intent is not one.
 *
 * Reminder action taps are `getActivity` intents that open MainActivity, not broadcasts to
 * NotificationActionReceiver. Reading the action here - including the due instant - is what keeps the
 * occurrence on the day the reminder was shown for rather than on the day the tap landed.
 */
fun pendingNotificationActionFrom(
    intent: Intent,
    recordedAtMillis: Long = System.currentTimeMillis(),
): PendingNotificationAction? {
    val actionType = intent.getStringExtra(TaskReminderReceiver.EXTRA_REMINDER_ACTION_TYPE) ?: return null
    val taskId = intent.getStringExtra(TaskReminderReceiver.EXTRA_TASK_ID) ?: return null
    return PendingNotificationAction(
        actionType = actionType,
        taskId = taskId,
        notificationId = intent.getIntExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID_INT, NO_NOTIFICATION_ID),
        recordedAtMillis = recordedAtMillis,
        dueAt = intent.getStringExtra(TaskReminderReceiver.EXTRA_DUE_AT),
    )
}

private const val NO_NOTIFICATION_ID = -1
