//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.UnifiedLogger
import io.payanam.desktop.platform.DesktopReminderSink
import io.payanam.domain.model.Task
import io.payanam.shared.platform.ReminderCapability
import io.payanam.shared.platform.ReminderRequest
import io.payanam.shared.platform.ReminderSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The desktop's reminder PRODUCER, pinned against Android's arming rule.
 *
 * WHY THIS FILE EXISTS: the desktop had a delivery surface and no producer - the data layer bound the
 * log-only no-op, so no reminder could ever reach the sink. What matters for that defect is not "a call
 * happened" but WHICH rule decided it: the same eligibility, the same advance (15 minutes by default, the
 * custom override honoured), the same "not after now" skip, and cancel-before-arm under the task's own id.
 * The last case proves the point of the whole chain: a delivery that succeeds is what flips the capability
 * from NOT_SURFACED to SUPPORTED - derived, never asserted.
 */
class DesktopTaskReminderSchedulerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        // The scheduler's default logger is the process-wide singleton, so it must exist. The directory is
        // deliberately NOT the rule's folder and is never deleted: the module runs every class in one JVM,
        // and a reset here would drop the singleton that later classes in the same JVM rely on - while a
        // folder that outlives this class keeps any leaked instance writable instead of pointing it at a
        // deleted path.
        UnifiedLogger.resetForTests()
        UnifiedLogger.initialize(logsDirectory = Files.createTempDirectory("payanam-reminder-scheduler-test"))
    }

    @After
    fun tearDown() {
        scope.cancel()
        // Leave a VALID singleton behind: this module runs every class in one JVM, and several classes rely
        // on a leaked instance. Resetting to nothing here would break them; pointing the leak at a persistent
        // directory keeps it writable.
        UnifiedLogger.resetForTests()
        UnifiedLogger.initialize(logsDirectory = Files.createTempDirectory("payanam-reminder-scheduler-after"))
    }

    /** A sink that records instead of delivering - the producer's contract is the REQUEST it hands over. */
    private class RecordingSink : ReminderSink {
        val scheduled = mutableListOf<ReminderRequest>()
        val cancelled = mutableListOf<String>()

        override suspend fun schedule(request: ReminderRequest) {
            scheduled += request
        }

        override suspend fun cancel(id: String) {
            cancelled += id
        }

        override suspend fun cancelAll() = Unit
    }

    private fun task(
        id: String = "task-1",
        title: String = "Pay the bill",
        status: String = "pending",
        dueDate: LocalDateTime? = LocalDateTime.now().plusMinutes(60),
        notificationMode: String? = "auto",
        customNotificationMinutes: Int? = null,
    ) = Task(
        id = id,
        title = title,
        status = status,
        dueDate = dueDate,
        notificationMode = notificationMode,
        customNotificationMinutes = customNotificationMinutes,
        createdAt = LocalDateTime.parse("2026-03-04T05:06:07"),
        updatedAt = LocalDateTime.parse("2026-03-04T05:06:07"),
    )

    private fun epochOf(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `an eligible task is armed with Android's own default advance and cancel-before-arm`(): Unit =
        runBlocking {
            val sink = RecordingSink()
            val now = LocalDateTime.now()
            val scheduler = DesktopTaskReminderScheduler(clock = { now }, sink = { sink })

            scheduler.scheduleForTask(task(dueDate = now.plusMinutes(60)))

            val request = sink.scheduled.single()
            assertThat(request.id).isEqualTo("task-1")
            assertThat(request.title).isEqualTo("Pay the bill")
            assertThat(request.relatedTaskId).isEqualTo("task-1")
            // 15 minutes before the due time - the same default Android arms with.
            assertThat(request.atEpochMillis).isEqualTo(epochOf(now.plusMinutes(45)))
            // Replace, not add: the previous reminder under the same id is cancelled first.
            assertThat(sink.cancelled).containsExactly("task-1")
        }

    @Test
    fun `a custom advance is honoured`(): Unit =
        runBlocking {
            val sink = RecordingSink()
            val now = LocalDateTime.now()
            val scheduler = DesktopTaskReminderScheduler(clock = { now }, sink = { sink })

            scheduler.scheduleForTask(
                task(
                    dueDate = now.plusMinutes(60),
                    notificationMode = "custom",
                    customNotificationMinutes = 30,
                ),
            )

            assertThat(sink.scheduled.single().atEpochMillis).isEqualTo(epochOf(now.plusMinutes(30)))
        }

    @Test
    fun `an off task, a completed task and a task with no due date arm nothing`(): Unit =
        runBlocking {
            val sink = RecordingSink()
            val now = LocalDateTime.now()
            val scheduler = DesktopTaskReminderScheduler(clock = { now }, sink = { sink })

            scheduler.scheduleForTask(task(notificationMode = "off"))
            scheduler.scheduleForTask(task(status = "completed"))
            scheduler.scheduleForTask(task(dueDate = null))

            assertThat(sink.scheduled).isEmpty()
        }

    @Test
    fun `a reminder whose fire time is already past arms nothing`(): Unit =
        runBlocking {
            val sink = RecordingSink()
            val now = LocalDateTime.now()
            val scheduler = DesktopTaskReminderScheduler(clock = { now }, sink = { sink })

            // Due in five minutes with the default 15-minute advance: the fire time is ten minutes past.
            scheduler.scheduleForTask(task(dueDate = now.plusMinutes(5)))

            assertThat(sink.scheduled).isEmpty()
        }

    @Test
    fun `a missing sink refuses and does not throw`(): Unit =
        runBlocking {
            val scheduler = DesktopTaskReminderScheduler(clock = { LocalDateTime.now() }, sink = { null })

            // Both must return quietly: a reminder that cannot be armed must never take a task write down.
            scheduler.scheduleForTask(task())
            scheduler.cancelForTask("task-1")
        }

    @Test
    fun `a delivered reminder flips the capability through the real sink`(): Unit =
        runBlocking {
            val delivered = CompletableDeferred<ReminderRequest>()
            val sink = DesktopReminderSink(scope) { request -> delivered.complete(request); true }
            val now = LocalDateTime.now()
            val scheduler = DesktopTaskReminderScheduler(clock = { now }, sink = { sink })
            assertThat(sink.capability).isEqualTo(ReminderCapability.NOT_SURFACED)

            scheduler.scheduleForTask(task(dueDate = now), overrideScheduledAt = now.plusSeconds(1))

            val request = withTimeout(5_000) { delivered.await() }
            assertThat(request.id).isEqualTo("task-1")
            // Await the PUBLISHED state, not the callback: the two are separate moments.
            withTimeout(2_000) { sink.surfacedState.first { it } }
            assertThat(sink.capability).isEqualTo(ReminderCapability.SUPPORTED)
        }
}
