//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.Task
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDateTime

/**
 * The desktop's reminder re-arm, which exists because nothing called the bound scheduler at all: a task's
 * reminder was armed only by the action that created it and never re-armed across a restart, where Android
 * re-arms every eligible task on each start.
 *
 * The producer's contract is the set of tasks it hands over, so the scheduler is a recorder here. Whether a
 * given task is actually armed is the scheduler's own decision, tested next to it in
 * `DesktopTaskReminderSchedulerTest`; filtering again here would be the second copy of that decision.
 */
class DesktopReminderReplayerTest {
    /** Records the tasks it is handed instead of arming anything. */
    private class RecordingScheduler : TaskReminderScheduler {
        val scheduled = mutableListOf<Task>()

        override suspend fun scheduleForTask(
            task: Task,
            overrideScheduledAt: LocalDateTime?,
            overrideDueAt: LocalDateTime?,
            isSnoozed: Boolean,
        ) {
            scheduled += task
        }

        override suspend fun cancelForTask(taskId: String) = Unit
    }

    private fun task(id: String, status: String = "pending") =
        Task(
            id = id,
            title = "Pay the bill",
            status = status,
            dueDate = LocalDateTime.now().plusMinutes(60),
            notificationMode = "auto",
            createdAt = LocalDateTime.parse("2026-03-04T05:06:07"),
            updatedAt = LocalDateTime.parse("2026-03-04T05:06:07"),
        )

    @Test
    fun `every task read is handed to the scheduler, in the order read`(): Unit =
        runBlocking {
            val scheduler = RecordingScheduler()

            replayDesktopReminders(
                readTasks = { listOf(task("task-1"), task("task-2"), task("task-3")) },
                scheduler = scheduler,
                logger = NoOpUnifiedLogger,
            )

            assertThat(scheduler.scheduled.map { it.id }).containsExactly("task-1", "task-2", "task-3").inOrder()
        }

    @Test
    fun `an ineligible task is still handed over, because the scheduler owns that decision`(): Unit =
        runBlocking {
            val scheduler = RecordingScheduler()

            replayDesktopReminders(
                readTasks = { listOf(task("done-task", status = "completed"), task("open-task")) },
                scheduler = scheduler,
                logger = NoOpUnifiedLogger,
            )

            // Both arrive: the producer must not pre-filter with a second copy of the eligibility rule.
            assertThat(scheduler.scheduled.map { it.id }).containsExactly("done-task", "open-task")
        }

    @Test
    fun `no tasks leaves the scheduler untouched`(): Unit =
        runBlocking {
            val scheduler = RecordingScheduler()

            replayDesktopReminders(
                readTasks = { emptyList() },
                scheduler = scheduler,
                logger = NoOpUnifiedLogger,
            )

            assertThat(scheduler.scheduled).isEmpty()
        }

    @Test
    fun `a failing read is logged, not thrown, so maintenance around it still runs`(): Unit =
        runBlocking {
            val scheduler = RecordingScheduler()

            // The call must return normally: a reminder that cannot be armed must not take down the whole
            // startup maintenance run, which is the contract the replayer's own KDoc states.
            replayDesktopReminders(
                readTasks = { error("the session went away") },
                scheduler = scheduler,
                logger = NoOpUnifiedLogger,
            )

            assertThat(scheduler.scheduled).isEmpty()
        }
}
