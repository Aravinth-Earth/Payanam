//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.Task
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.receiver.NotificationActionReceiver
import io.payanam.receiver.TaskReminderReceiver
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        whenever(sessionManager.isDbOpen()).thenReturn(true)
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

    @Test
    fun execute_whenTheSessionDiesMidWrite_returnsFailedAndKeepsTheActionForRetry() {
        wheneverBlocking { taskRepository.getTaskById(TASK_ID) }
            .thenThrow(IllegalStateException("DatabaseSessionManager: DB not open"))
        val coordinator = coordinator()

        assertEquals(NotificationActionOutcome.FAILED, runBlocking { coordinator.execute(action()) })
        verify(pendingActions, never()).markHandled(any())

        // The drain's own rule: a FAILED outcome leaves the action on disk for the next unlock.
        whenever(pendingActions.readPending()).thenReturn(listOf(action()))
        runBlocking { coordinator.applyPendingActions() }

        verify(pendingActions, never()).clear(TASK_ID)
        verify(pendingActions, never()).markHandled(any())
    }

    @Test
    fun execute_whenTheSessionIsClosed_returnsFailedWithoutTouchingARepository() {
        whenever(sessionManager.isDbOpen()).thenReturn(false)
        val coordinator = coordinator()

        assertEquals(NotificationActionOutcome.FAILED, runBlocking { coordinator.execute(action()) })

        verifyBlocking(taskRepository, never()) { getTaskById(any()) }
        verify(pendingActions, never()).markHandled(any())
    }

    @Test
    fun liveTap_whenExecuteFails_recordsTheActionForTheNextUnlock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PendingNotificationActionStore(context)
        val coordinator = mock<NotificationActionCoordinator>()
        wheneverBlocking { coordinator.execute(any()) }.thenReturn(NotificationActionOutcome.FAILED)
        val receiver = NotificationActionReceiver()
        val filter =
            IntentFilter().apply {
                addAction(NEUTRAL_ACTION)
                addAction(NotificationActionReceiver.ACTION_COMPLETE_TASK)
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // The @AndroidEntryPoint base injects on the first delivery and would replace the mocks, so a
        // neutral broadcast is delivered first and the mocks are assigned after it.
        context.sendBroadcast(Intent(NEUTRAL_ACTION))
        shadowOf(Looper.getMainLooper()).idle()
        receiver.sessionManager = sessionManager
        receiver.coordinator = coordinator
        receiver.pendingActions = store
        val intent = Intent(NotificationActionReceiver.ACTION_COMPLETE_TASK).apply {
            putExtra(TaskReminderReceiver.EXTRA_TASK_ID, TASK_ID)
            putExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID_INT, NOTIFICATION_ID)
        }

        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()

        val recorded = awaitRecordedAction(store)
        verifyBlocking(coordinator) { execute(any()) }
        assertEquals(1, recorded.size)
        assertEquals(TASK_ID, recorded.first().taskId)
        assertEquals(NotificationActionCoordinator.ACTION_TYPE_COMPLETE, recorded.first().actionType)
        assertEquals(NOTIFICATION_ID, recorded.first().notificationId)
    }

    @Test
    fun execute_complete_recordsTheOccurrenceOnTheRemindersDueDay() {
        val dueAt = LocalDateTime.of(2026, 11, 6, 9, 0)
        val task =
            Task(
                id = TASK_ID,
                title = "Reminder",
                createdAt = dueAt,
                updatedAt = dueAt,
                recurrenceEnabled = true,
                dueDate = dueAt,
            )
        wheneverBlocking { taskRepository.getTaskById(TASK_ID) }.thenReturn(task)
        whenever(recurrenceManager.isFrequencyHabit(task)).thenReturn(false)
        wheneverBlocking { taskRepository.completeTask(TASK_ID, null) }.thenReturn(task)
        wheneverBlocking { recurrenceManager.onTaskCompleted(task, null, "notification_action", null) }.thenReturn(Unit)
        wheneverBlocking { notificationScheduler.scheduleForTask(any(), anyOrNull(), anyOrNull(), any()) }.thenReturn(Unit)

        val outcome = runBlocking { coordinator().execute(action(dueAt = dueAt)) }

        assertEquals(NotificationActionOutcome.APPLIED, outcome)
        verifyBlocking(taskOccurrenceRepository) {
            toggleOccurrence(
                taskId = eq(TASK_ID),
                date = eq(dueAt.toLocalDate()),
                newStatus = eq("completed"),
                note = anyOrNull(),
                reason = eq("notification_action"),
                actualCompletedAt = anyOrNull(),
                actualDurationMinutes = anyOrNull(),
            )
        }
    }

    @Test
    fun store_roundTripsTheDueDayForADeferredAction() {
        val store = PendingNotificationActionStore(ApplicationProvider.getApplicationContext())
        store.clear(TASK_ID)
        val dueAt = LocalDateTime.of(2026, 11, 6, 9, 0)
        val outcome =
            store.record(
                PendingNotificationAction(
                    actionType = NotificationActionCoordinator.ACTION_TYPE_COMPLETE,
                    taskId = TASK_ID,
                    notificationId = NOTIFICATION_ID,
                    recordedAtMillis = System.currentTimeMillis(),
                    dueAt = dueAt.toString(),
                ),
            )

        assertEquals(PendingActionRecordOutcome.RECORDED, outcome)
        assertEquals(dueAt.toLocalDate(), store.readPending().single().dueDay())
        store.clear(TASK_ID)
    }

    private fun coordinator(): NotificationActionCoordinator = NotificationActionCoordinator(
        context = ApplicationProvider.getApplicationContext(),
        taskRepository = taskRepository,
        taskOccurrenceRepository = taskOccurrenceRepository,
        notificationScheduler = notificationScheduler,
        recurrenceManager = recurrenceManager,
        sessionManager = sessionManager,
        pendingActions = pendingActions,
    )

    /** The receiver records on a background scope, so the store is polled until it answers or time runs out. */
    private fun awaitRecordedAction(store: PendingNotificationActionStore): List<PendingNotificationAction> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val pending = store.readPending()
            if (pending.isNotEmpty()) return pending
            Thread.sleep(POLL_MILLIS)
        }
        return store.readPending()
    }

    private fun action(dueAt: LocalDateTime? = null): PendingNotificationAction = PendingNotificationAction(
        actionType = NotificationActionCoordinator.ACTION_TYPE_COMPLETE,
        taskId = TASK_ID,
        notificationId = NOTIFICATION_ID,
        recordedAtMillis = NOW_MILLIS,
        dueAt = dueAt?.toString(),
    )

    private companion object {
        private const val TASK_ID = "task-reminder"
        private const val NOTIFICATION_ID = 7
        private const val FIRST_CHECK = 1
        private const val SECOND_CHECK = 2
        private const val THREADS = 2
        private const val TIMEOUT_SECONDS = 10L
        private const val POLL_MILLIS = 20L
        private const val NOW_MILLIS = 1_760_000_000_000L
        private const val NEUTRAL_ACTION = "io.payanam.action.TEST_NEUTRAL"
    }
}
