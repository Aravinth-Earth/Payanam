//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.Task
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.scoring.RecurrenceManager
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class NotificationActionCoordinatorTest {
    private lateinit var taskRepository: TaskRepository
    private lateinit var taskOccurrenceRepository: TaskOccurrenceRepository
    private lateinit var notificationScheduler: NotificationScheduler
    private lateinit var recurrenceManager: RecurrenceManager
    private lateinit var sessionManager: DatabaseSessionManager
    private lateinit var pendingActions: PendingNotificationActionStore

    @Before
    fun setUp() {
        UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        taskRepository = mock()
        taskOccurrenceRepository = mock()
        notificationScheduler = mock()
        recurrenceManager = mock()
        sessionManager = mock()
        pendingActions = mock()
    }

    @Test
    fun execute_serializesTwoConcurrentDeliveriesOfTheSameAction() {
        val now = LocalDateTime.of(2026, 11, 7, 9, 0)
        val task = Task(id = TASK_ID, title = "Reminder", createdAt = now, updatedAt = now)
        wheneverBlocking { taskRepository.getTaskById(TASK_ID) }.thenReturn(task)
        wheneverBlocking { taskRepository.completeTask(TASK_ID, null) }.thenReturn(task)
        wheneverBlocking { notificationScheduler.cancelForTask(TASK_ID) }.thenReturn(Unit)

        val firstCheckEntered = CountDownLatch(1)
        val releaseFirstCheck = CountDownLatch(1)
        val checks = AtomicInteger(0)
        val marks = AtomicInteger(0)
        val marksAtSecondCheck = AtomicInteger(-1)
        whenever(pendingActions.markHandled(any())).thenAnswer {
            marks.incrementAndGet()
            Unit
        }
        whenever(pendingActions.isHandled(any(), any())).thenAnswer {
            val check = checks.incrementAndGet()
            if (check == FIRST_CHECK) {
                firstCheckEntered.countDown()
                releaseFirstCheck.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } else {
                marksAtSecondCheck.set(marks.get())
            }
            // Mirrors the real store: the handled token the first delivery wrote is what the
            // second delivery reads back.
            marks.get() > 0
        }

        val coordinator =
            NotificationActionCoordinator(
                context = ApplicationProvider.getApplicationContext(),
                taskRepository = taskRepository,
                taskOccurrenceRepository = taskOccurrenceRepository,
                notificationScheduler = notificationScheduler,
                recurrenceManager = recurrenceManager,
                sessionManager = sessionManager,
                pendingActions = pendingActions,
            )
        val dispatcher = Executors.newFixedThreadPool(THREADS).asCoroutineDispatcher()
        val outcomes = Collections.synchronizedList(mutableListOf<NotificationActionOutcome>())
        try {
            runBlocking {
                val first = launch(dispatcher) { outcomes.add(coordinator.execute(action())) }
                assertTrue(
                    "first delivery never reached the handled check",
                    firstCheckEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                val second = launch(dispatcher) { outcomes.add(coordinator.execute(action())) }
                // Releasing the first check lets the first delivery finish its writes; a serialized
                // second delivery only reaches its own check after the first marked the action.
                releaseFirstCheck.countDown()
                first.join()
                second.join()
            }
        } finally {
            dispatcher.close()
        }

        assertEquals(SECOND_CHECK, checks.get())
        assertEquals(FIRST_CHECK, marks.get())
        assertEquals(FIRST_CHECK, marksAtSecondCheck.get())
        assertEquals(
            setOf(NotificationActionOutcome.APPLIED, NotificationActionOutcome.ALREADY_HANDLED),
            outcomes.toSet(),
        )
    }

    private fun action(): PendingNotificationAction = PendingNotificationAction(
        actionType = NotificationActionCoordinator.ACTION_TYPE_COMPLETE,
        taskId = TASK_ID,
        notificationId = NOTIFICATION_ID,
        recordedAtMillis = NOW_MILLIS,
    )

    private companion object {
        private const val TASK_ID = "task-reminder"
        private const val NOTIFICATION_ID = 7
        private const val FIRST_CHECK = 1
        private const val SECOND_CHECK = 2
        private const val THREADS = 2
        private const val TIMEOUT_SECONDS = 10L
        private const val NOW_MILLIS = 1_760_000_000_000L
    }
}
