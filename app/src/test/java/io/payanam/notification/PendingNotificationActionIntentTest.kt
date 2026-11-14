//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.receiver.NotificationActionReceiver
import io.payanam.receiver.TaskReminderReceiver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * The reminder tap opens MainActivity, so the due instant has to survive that intent. These pin the
 * mapper the activity uses; the coordinator test covers what happens once the action exists.
 */
@RunWith(RobolectricTestRunner::class)
class PendingNotificationActionIntentTest {
    @Test
    fun `a reminder tap intent carries the due day into the pending action`() {
        val dueAt = LocalDateTime.of(2026, 11, 6, 9, 0)
        val intent =
            Intent(NotificationActionReceiver.ACTION_COMPLETE_TASK).apply {
                putExtra(TaskReminderReceiver.EXTRA_REMINDER_ACTION_TYPE, NotificationActionCoordinator.ACTION_TYPE_COMPLETE)
                putExtra(TaskReminderReceiver.EXTRA_TASK_ID, TASK_ID)
                putExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID_INT, NOTIFICATION_ID)
                putExtra(TaskReminderReceiver.EXTRA_DUE_AT, dueAt.toString())
            }

        val action = pendingNotificationActionFrom(intent, recordedAtMillis = RECORDED_AT_MILLIS)

        assertThat(action).isNotNull()
        assertThat(action?.taskId).isEqualTo(TASK_ID)
        assertThat(action?.notificationId).isEqualTo(NOTIFICATION_ID)
        assertThat(action?.dueDay()).isEqualTo(dueAt.toLocalDate())
    }

    @Test
    fun `the notification builder puts the due day on both action intents`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = TaskReminderReceiver()
        val dueAt = LocalDateTime.of(2026, 11, 6, 9, 0)

        listOf(
            NotificationActionReceiver.ACTION_COMPLETE_TASK to NotificationActionCoordinator.ACTION_TYPE_COMPLETE,
            NotificationActionReceiver.ACTION_MISS_TASK to NotificationActionCoordinator.ACTION_TYPE_MISS,
        ).forEach { (intentAction, actionType) ->
            val intent =
                receiver.reminderActionIntent(
                    context = context,
                    intentAction = intentAction,
                    actionType = actionType,
                    notificationIdInt = NOTIFICATION_ID,
                    taskId = TASK_ID,
                    dueAt = dueAt,
                )

            assertThat(intent.action).isEqualTo(intentAction)
            assertThat(intent.getStringExtra(TaskReminderReceiver.EXTRA_REMINDER_ACTION_TYPE)).isEqualTo(actionType)
            assertThat(intent.getStringExtra(TaskReminderReceiver.EXTRA_TASK_ID)).isEqualTo(TASK_ID)
            assertThat(intent.getIntExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID_INT, NO_NOTIFICATION_ID))
                .isEqualTo(NOTIFICATION_ID)
            assertThat(intent.getStringExtra(TaskReminderReceiver.EXTRA_DUE_AT)).isEqualTo(dueAt.toString())
            // The reader the activity uses agrees with what the builder wrote.
            assertThat(pendingNotificationActionFrom(intent)?.dueDay()).isEqualTo(dueAt.toLocalDate())
        }
    }

    @Test
    fun `an action intent built without a due time omits the extra`() {
        val intent =
            TaskReminderReceiver().reminderActionIntent(
                context = ApplicationProvider.getApplicationContext(),
                intentAction = NotificationActionReceiver.ACTION_COMPLETE_TASK,
                actionType = NotificationActionCoordinator.ACTION_TYPE_COMPLETE,
                notificationIdInt = NOTIFICATION_ID,
                taskId = TASK_ID,
                dueAt = null,
            )

        assertThat(intent.hasExtra(TaskReminderReceiver.EXTRA_DUE_AT)).isFalse()
        assertThat(pendingNotificationActionFrom(intent)?.dueDay()).isNull()
    }

    @Test
    fun `an intent without a due time still yields an action with no due day`() {
        val intent =
            Intent(NotificationActionReceiver.ACTION_MISS_TASK).apply {
                putExtra(TaskReminderReceiver.EXTRA_REMINDER_ACTION_TYPE, NotificationActionCoordinator.ACTION_TYPE_MISS)
                putExtra(TaskReminderReceiver.EXTRA_TASK_ID, TASK_ID)
            }

        val action = pendingNotificationActionFrom(intent, recordedAtMillis = RECORDED_AT_MILLIS)

        assertThat(action).isNotNull()
        assertThat(action?.dueDay()).isNull()
    }

    @Test
    fun `an intent without an action or task id is not a reminder action`() {
        assertThat(pendingNotificationActionFrom(Intent())).isNull()
        assertThat(
            pendingNotificationActionFrom(
                Intent().putExtra(TaskReminderReceiver.EXTRA_TASK_ID, TASK_ID),
            ),
        ).isNull()
    }

    private companion object {
        private const val TASK_ID = "task-reminder"
        private const val NOTIFICATION_ID = 7
        private const val NO_NOTIFICATION_ID = -1
        private const val RECORDED_AT_MILLIS = 1_760_000_000_000L
    }
}
