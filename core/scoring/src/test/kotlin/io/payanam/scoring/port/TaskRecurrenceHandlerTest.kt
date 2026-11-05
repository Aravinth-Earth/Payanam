//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring.port

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.Task
import io.payanam.scoring.CompletionStats
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The recurrence port's contract as the shared ViewModels see it.
 *
 * The properties worth pinning are the ones an implementation cannot express for itself. Kotlin forbids default
 * values on an override, so the defaults that used to sit on `RecurrenceManager` now live on this interface —
 * and "calling through the interface supplies null for an omitted note/reason/strategy" is exactly what every
 * shared ViewModel will rely on. A fake records what it receives, so the test fails the moment a default moves
 * or changes, instead of the behaviour shifting silently inside a 700-line manager.
 *
 * `getCompletionStats` is pinned for a second reason: it returns a `CompletionStats` from the scoring module
 * itself, which is the whole reason this port is declared here rather than in `:core:domain`.
 */
class TaskRecurrenceHandlerTest {
    @Test
    fun `an omitted note, reason and strategy reach the handler as nulls`() = runBlocking<Unit> {
        val recorder = RecordingHandler()
        // Deliberately held as the port, not the class: a ViewModel depends on the interface, and only an
        // interface-typed call routes through the generated default-argument path being pinned here.
        val handler: TaskRecurrenceHandler = recorder
        val task = task()

        handler.onTaskCompleted(task)
        handler.onTaskSkipped(task)
        handler.onTaskMissed(task)

        assertThat(recorder.completed).containsExactly(task, null, null, null)
        assertThat(recorder.skipped).containsExactly(task, null, null, null)
        assertThat(recorder.missed).containsExactly(task, null, null, null)
    }

    @Test
    fun `supplied arguments are passed through in order`() = runBlocking<Unit> {
        val recorder = RecordingHandler()
        val handler: TaskRecurrenceHandler = recorder

        handler.onTaskCompleted(task(), note = "n", reason = "r", nextDueStrategy = "same_day")

        assertThat(recorder.completed).containsExactly(task(), "n", "r", "same_day")
    }

    @Test
    fun `the completion stats round-trip typed as the scoring module owns them`() = runBlocking<Unit> {
        val handler = RecordingHandler()

        val stats = handler.getCompletionStats(task())

        assertThat(stats).isEqualTo(handler.stats)
        assertThat(stats.completionRate7Days).isEqualTo(0.5)
        assertThat(stats.currentStreak).isEqualTo(3)
    }

    @Test
    fun `isFrequencyHabit answers through the port`() {
        val handler = RecordingHandler()

        assertThat(handler.isFrequencyHabit(task())).isTrue()
    }

    @Test
    fun `every omitted-argument position falls back to null, for all three transitions`() = runBlocking<Unit> {
        val recorder = RecordingHandler()
        val handler: TaskRecurrenceHandler = recorder
        val task = task()

        // Each call omits a different suffix of arguments, which is what the interface's generated
        // default-argument paths have to resolve. Calling each transition with 0, 1, 2 and 3 arguments is the
        // whole matrix: "note only" and "note + reason" are the calls shared ViewModels actually make.
        handler.onTaskCompleted(task)
        handler.onTaskCompleted(task, note = "n")
        handler.onTaskCompleted(task, note = "n", reason = "r")
        assertThat(recorder.completed).containsExactly(task, "n", "r", null)

        handler.onTaskSkipped(task)
        handler.onTaskSkipped(task, note = "n")
        handler.onTaskSkipped(task, note = "n", reason = "r")
        assertThat(recorder.skipped).containsExactly(task, "n", "r", null)

        handler.onTaskMissed(task)
        handler.onTaskMissed(task, note = "n")
        handler.onTaskMissed(task, note = "n", reason = "r")
        assertThat(recorder.missed).containsExactly(task, "n", "r", null)
    }

    private fun task() =
        Task(
            id = "t1",
            title = "Read",
            createdAt = LocalDateTime.of(2026, 9, 21, 6, 0),
            updatedAt = LocalDateTime.of(2026, 9, 21, 6, 0),
        )

    private class RecordingHandler : TaskRecurrenceHandler {
        var completed: List<Any?>? = null
        var skipped: List<Any?>? = null
        var missed: List<Any?>? = null
        val stats =
            CompletionStats(
                completionRate7Days = 0.5,
                completionRate30Days = 0.4,
                completionRate90Days = 0.3,
                allTimeRate = 0.2,
                currentStreak = 3,
                longestStreak = 9,
            )

        override fun isFrequencyHabit(task: Task): Boolean = true

        override suspend fun onTaskCompleted(task: Task, note: String?, reason: String?, nextDueStrategy: String?) {
            completed = listOf(task, note, reason, nextDueStrategy)
        }

        override suspend fun onTaskSkipped(task: Task, note: String?, reason: String?, nextDueStrategy: String?) {
            skipped = listOf(task, note, reason, nextDueStrategy)
        }

        override suspend fun onTaskMissed(task: Task, note: String?, reason: String?, nextDueStrategy: String?) {
            missed = listOf(task, note, reason, nextDueStrategy)
        }

        override suspend fun getCompletionStats(task: Task): CompletionStats = stats
    }
}
