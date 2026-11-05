//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.repository.NotificationRepository
import io.payanam.domain.repository.TaskRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * Proves the Android-scheduling clause of the reminder-absence gate: Android
 * continues to schedule reminders through the constructed sink. Constructs a real [NotificationScheduler] with mock
 * repositories and verifies that scheduling a task with a future reminder
 * persists a [io.payanam.domain.repository.ScheduledNotification] row via
 * [NotificationRepository.scheduleNotification].
 */
@RunWith(RobolectricTestRunner::class)
class TaskReminderSchedulerTest {
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var taskRepository: TaskRepository

    @Before
    fun setUp() {
        UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        notificationRepository = mock()
        taskRepository = mock()
        // cancelForTask() iterates this result before scheduling; an unstubbed mock returns null and
        // the forEach NPEs (measured: "$this$forEach is null" at NotificationScheduler.cancelForTask).
        // It is a SUSPEND fun, so the stub must go through wheneverBlocking (whenever + suspend is a
        // compile error: "Suspend function cannot be called inside whenever").
        org.mockito.kotlin.wheneverBlocking {
            notificationRepository.getNotificationsForTask(org.mockito.kotlin.any())
        }.thenReturn(emptyList())
        // The schedule path reads tasks through this flow (NotificationScheduler:55); same null rule.
        whenever(taskRepository.getAllTasks())
            .thenReturn(kotlinx.coroutines.flow.flowOf(emptyList()))
    }

    @Test
    fun scheduleForTask_persistsScheduledNotificationForFutureReminder() = runTest {
        val taskId = "task-future-reminder"
        val dueDate = LocalDateTime.now().plusHours(2)
        val task = Task(
            id = taskId,
            title = "Future reminder task",
            status = "pending",
            dueDate = dueDate,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
        )
        val expectedScheduledAt = dueDate.minusMinutes(15)
        val expectedNotificationId = "notif-123"
        whenever(
            notificationRepository.scheduleNotification(
                taskId = org.mockito.kotlin.eq(taskId),
                scheduledAt = org.mockito.kotlin.eq(expectedScheduledAt),
                notificationType = org.mockito.kotlin.eq("task_reminder"),
                title = org.mockito.kotlin.any(),
                body = org.mockito.kotlin.any(),
            ),
        ).thenReturn(expectedNotificationId)

        val scheduler = NotificationScheduler(
            context = ApplicationProvider.getApplicationContext<Application>(),
            taskRepository = taskRepository,
            notificationRepository = notificationRepository,
        )

        scheduler.scheduleForTask(task)

        verify(notificationRepository).scheduleNotification(
            taskId = org.mockito.kotlin.eq(taskId),
            scheduledAt = org.mockito.kotlin.eq(expectedScheduledAt),
            notificationType = org.mockito.kotlin.eq("task_reminder"),
            title = org.mockito.kotlin.any(),
            body = org.mockito.kotlin.any(),
        )
    }

    @Test
    fun scheduleForTask_withOverrideScheduledAt_usesOverrideTime() = runTest {
        val taskId = "task-override"
        val overrideTime = LocalDateTime.now().plusMinutes(30)
        val task = Task(
            id = taskId,
            title = "Override schedule task",
            status = "pending",
            dueDate = LocalDateTime.now().plusHours(3),
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
        )
        val expectedNotificationId = "notif-override"
        whenever(
            notificationRepository.scheduleNotification(
                taskId = org.mockito.kotlin.eq(taskId),
                scheduledAt = org.mockito.kotlin.eq(overrideTime),
                notificationType = org.mockito.kotlin.eq("task_reminder"),
                title = org.mockito.kotlin.any(),
                body = org.mockito.kotlin.any(),
            ),
        ).thenReturn(expectedNotificationId)

        val scheduler = NotificationScheduler(
            context = ApplicationProvider.getApplicationContext<Application>(),
            taskRepository = taskRepository,
            notificationRepository = notificationRepository,
        )

        scheduler.scheduleForTask(task, overrideScheduledAt = overrideTime)

        verify(notificationRepository).scheduleNotification(
            taskId = org.mockito.kotlin.eq(taskId),
            scheduledAt = org.mockito.kotlin.eq(overrideTime),
            notificationType = org.mockito.kotlin.eq("task_reminder"),
            title = org.mockito.kotlin.any(),
            body = org.mockito.kotlin.any(),
        )
    }

    @Test
    fun scheduleForTask_recurringTask_usesHabitTrackingType() = runTest {
        val taskId = "habit-daily"
        val dueDate = LocalDateTime.now().plusHours(1)
        val task = Task(
            id = taskId,
            title = "Daily habit",
            status = "pending",
            dueDate = dueDate,
            recurrenceEnabled = true,
            recurrenceRule = "FREQ=DAILY",
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
        )
        val expectedScheduledAt = dueDate.minusMinutes(15)
        val expectedNotificationId = "notif-habit"
        whenever(
            notificationRepository.scheduleNotification(
                taskId = org.mockito.kotlin.eq(taskId),
                scheduledAt = org.mockito.kotlin.eq(expectedScheduledAt),
                notificationType = org.mockito.kotlin.eq("habit_tracking"),
                title = org.mockito.kotlin.any(),
                body = org.mockito.kotlin.any(),
            ),
        ).thenReturn(expectedNotificationId)

        val scheduler = NotificationScheduler(
            context = ApplicationProvider.getApplicationContext<Application>(),
            taskRepository = taskRepository,
            notificationRepository = notificationRepository,
        )

        scheduler.scheduleForTask(task)

        verify(notificationRepository).scheduleNotification(
            taskId = org.mockito.kotlin.eq(taskId),
            scheduledAt = org.mockito.kotlin.eq(expectedScheduledAt),
            notificationType = org.mockito.kotlin.eq("habit_tracking"),
            title = org.mockito.kotlin.any(),
            body = org.mockito.kotlin.any(),
        )
    }

    @Test
    fun scheduleForTask_ineligibleStatus_skipsAndCancelsWithoutScheduling() = runTest {
        val taskId = "task-completed"
        val task = Task(
            id = taskId,
            title = "Completed task",
            status = "completed",
            dueDate = LocalDateTime.now().plusHours(2),
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
        )
        val scheduler = NotificationScheduler(
            context = ApplicationProvider.getApplicationContext<Application>(),
            taskRepository = taskRepository,
            notificationRepository = notificationRepository,
        )

        scheduler.scheduleForTask(task)

        verify(notificationRepository).cancelNotificationsForTask(taskId)
        verify(notificationRepository, never()).scheduleNotification(
            taskId = org.mockito.kotlin.any(),
            scheduledAt = org.mockito.kotlin.any(),
            notificationType = org.mockito.kotlin.any(),
            title = org.mockito.kotlin.any(),
            body = org.mockito.kotlin.any(),
        )
    }
}
