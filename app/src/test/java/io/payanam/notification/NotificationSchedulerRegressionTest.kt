//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.NotificationRepository
import io.payanam.domain.repository.ScheduledNotification
import io.payanam.domain.repository.TaskRepository
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDateTime
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
class NotificationSchedulerRegressionTest {
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        notificationRepository = mock()
        taskRepository = mock()
        notificationManager = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(NotificationManager::class.java)
    }

    @Test
    fun cancelForTask_clearsVisibleDeliveredNotification() = runTest {
        val notificationId = "habit-reminder-1"
        val taskId = "habit-1"
        whenever(notificationRepository.getNotificationsForTask(taskId)).thenReturn(
            listOf(
                ScheduledNotification(
                    id = notificationId,
                    taskId = taskId,
                    scheduledAt = LocalDateTime.of(2026, 4, 9, 9, 0),
                    notificationType = "habit_tracking",
                    title = "Hydrate",
                    body = "Reminder",
                    isDelivered = true,
                ),
            ),
        )
        val visibleId = requestCodeFor(notificationId)
        notificationManager.notify(visibleId, Notification())
        assertTrue(shadowOf(notificationManager).allNotifications.isNotEmpty())
        val scheduler = NotificationScheduler(
            context = ApplicationProvider.getApplicationContext(),
            taskRepository = taskRepository,
            notificationRepository = notificationRepository,
        )

        scheduler.cancelForTask(taskId)
        verify(notificationRepository).cancelNotificationsForTask(taskId)
        assertFalse(shadowOf(notificationManager).allNotifications.isNotEmpty())
    }

    private fun requestCodeFor(notificationId: String): Int {
        val hash = notificationId.hashCode()
        return if (hash == Int.MIN_VALUE) 0 else abs(hash)
    }
}
