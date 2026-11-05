//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Tests for the reminder contract, through an in-memory sink.
 *
 * The behaviour worth pinning is the REPLACE semantic. A task's reminder moves when its due time moves, and
 * a sink that added instead of replaced would leave the old reminder alive — the user would be told about
 * the same task twice, and the second time with stale copy.
 */
class ReminderContractsTest {
    private class FakeSink : ReminderSink {
        val scheduled = linkedMapOf<String, ReminderRequest>()

        override suspend fun schedule(request: ReminderRequest) {
            scheduled[request.id] = request
        }

        override suspend fun cancel(id: String) {
            scheduled.remove(id)
        }

        override suspend fun cancelAll() {
            scheduled.clear()
        }
    }

    private fun request(
        id: String,
        at: Long = 1_000L,
        title: String = "Task due",
    ) = ReminderRequest(id = id, atEpochMillis = at, title = title, body = "body")

    @Test
    fun `scheduling the same id replaces rather than adds`(): Unit =
        runBlocking {
            val sink = FakeSink()

            sink.schedule(request("task-1", at = 1_000L, title = "first"))
            sink.schedule(request("task-1", at = 2_000L, title = "moved"))

            assertThat(sink.scheduled).hasSize(1)
            assertThat(sink.scheduled.getValue("task-1").atEpochMillis).isEqualTo(2_000L)
            assertThat(sink.scheduled.getValue("task-1").title).isEqualTo("moved")
        }

    @Test
    fun `cancelling an unknown id is not an error`(): Unit =
        runBlocking {
            val sink = FakeSink()
            sink.schedule(request("task-1"))

            sink.cancel("never-scheduled")

            assertThat(sink.scheduled).hasSize(1)
        }

    @Test
    fun `cancelling removes only the named reminder`(): Unit =
        runBlocking {
            val sink = FakeSink()
            sink.schedule(request("task-1"))
            sink.schedule(request("task-2"))

            sink.cancel("task-1")

            assertThat(sink.scheduled.keys).containsExactly("task-2")
        }

    @Test
    fun `cancelAll clears everything`(): Unit =
        runBlocking {
            val sink = FakeSink()
            sink.schedule(request("task-1"))
            sink.schedule(request("task-2"))

            sink.cancelAll()

            assertThat(sink.scheduled).isEmpty()
        }

    @Test
    fun `a reminder carries the task it is about when there is one`() {
        val reminder =
            ReminderRequest(
                id = "task-1",
                atEpochMillis = 1_000L,
                title = "title",
                body = "body",
                relatedTaskId = "task-1",
            )

        assertThat(reminder.relatedTaskId).isEqualTo("task-1")
        // Both capabilities exist so a platform that accepts reminders without surfacing them can say so,
        // instead of the UI offering a toggle that silently does nothing.
        assertThat(ReminderCapability.entries)
            .containsExactly(ReminderCapability.SUPPORTED, ReminderCapability.NOT_SURFACED)
        assertThat(ReminderCapability.valueOf("SUPPORTED")).isEqualTo(ReminderCapability.SUPPORTED)
    }
}
