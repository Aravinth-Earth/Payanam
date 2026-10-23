//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.platform.ReminderCapability
import io.payanam.shared.platform.ReminderRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.io.IOException

/**
 * The delivery hook's outcomes, driven through its injected spawn - the unit form of the gate's "a Linux
 * session with no notification daemon logs the miss and does not crash".
 *
 * WHY THE MISS ARM GETS TWO CASES: a session with no daemon arrives in two shapes - the spawn itself
 * failing (no `notify-send` on PATH, or no bus socket to connect to) and the process running and
 * answering with a non-zero exit (a bus that exists but has no owner for the name). A hook that handled
 * only one would claim SUPPORTED in the other, which is the lie `NOT_SURFACED` exists to prevent.
 *
 * The last case is the end-to-end arm: the REAL hook behind the sink, whose capability may only flip
 * after a delivery that actually succeeded.
 */
class DesktopFreedesktopReminderDeliveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Captures warnings so "logs the miss" is an assertion rather than a hope. */
    private class RecordingLogger : UnifiedLogger {
        val warnings = mutableListOf<Triple<String, String, Map<String, Any?>>>()

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = Unit

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = Unit

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) {
            warnings += Triple(source, message, data)
        }

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = Unit

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = Unit
    }

    private fun reminder() = ReminderRequest(id = "task-1", atEpochMillis = 0L, title = "t", body = "b")

    @Test
    fun `a spawn that fails logs the miss and returns false without throwing`() {
        val logger = RecordingLogger()
        val delivery = desktopFreedesktopReminderDelivery(logger) { throw IOException("no bus") }

        val shown = delivery(reminder())

        assertThat(shown).isFalse()
        assertThat(logger.warnings).hasSize(1)
        val (source, message, data) = logger.warnings.single()
        assertThat(source).isEqualTo("DesktopFreedesktopReminderDelivery")
        assertThat(message).contains("no notification daemon")
        assertThat(data["failure"]).isEqualTo("IOException")
        assertThat(data["reminderId"]).isEqualTo("task-1")
    }

    @Test
    fun `a non-zero exit logs the miss and returns false`() {
        val logger = RecordingLogger()
        val delivery = desktopFreedesktopReminderDelivery(logger) { 1 }

        val shown = delivery(reminder())

        assertThat(shown).isFalse()
        val (_, _, data) = logger.warnings.single()
        assertThat(data["failure"]).isEqualTo("nonZeroExit")
        assertThat(data["exitCode"]).isEqualTo(1)
    }

    @Test
    fun `a zero exit returns true and the command is the one notify-send expects`() {
        val logger = RecordingLogger()
        val commands = mutableListOf<List<String>>()
        val delivery =
            desktopFreedesktopReminderDelivery(logger) { command ->
                commands += command
                0
            }

        val shown = delivery(reminder())

        assertThat(shown).isTrue()
        assertThat(logger.warnings).isEmpty()
        assertThat(commands.single())
            .containsExactly("notify-send", "--app-name=Payanam", "--", "t", "b")
            .inOrder()
    }

    @Test
    fun `the real hook behind the sink flips the capability only after a delivery that succeeded`(): Unit =
        runBlocking {
            val logger = RecordingLogger()
            val delivered = CompletableDeferred<ReminderRequest>()
            val hook =
                desktopFreedesktopReminderDelivery(logger) { _ ->
                    delivered.complete(reminder())
                    0
                }
            val sink = DesktopReminderSink(scope, hook)
            assertThat(sink.capability).isEqualTo(ReminderCapability.NOT_SURFACED)

            sink.schedule(ReminderRequest(id = "task-1", atEpochMillis = 0L, title = "t", body = "b"))
            withTimeout(2_000) { delivered.await() }
            // Await the PUBLISHED state, not the delivery itself - the same handoff the seams suite
            // documents: the callback returning is not the same moment as the sink recording it.
            withTimeout(2_000) { sink.surfacedState.first { it } }

            assertThat(sink.capability).isEqualTo(ReminderCapability.SUPPORTED)
        }
}
