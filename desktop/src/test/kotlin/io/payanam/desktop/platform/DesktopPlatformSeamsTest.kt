//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.NoOpUnifiedLogger
import io.payanam.shared.platform.BackupBackgroundCapability
import io.payanam.shared.platform.ReminderCapability
import io.payanam.shared.platform.ReminderRequest
import io.payanam.shared.platform.SupportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/**
 * Tests for the desktop platform seams.
 *
 * These exist because the desktop's seams are where a capability the platform lacks could quietly become a
 * silent no-op. Each one is asserted in both directions: the seam does what it says when the platform can,
 * and REPORTS the limitation when it cannot, so a screen can explain itself instead of offering a control
 * that does nothing.
 */
class DesktopPlatformSeamsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun reminder(
        id: String,
        atMillis: Long = System.currentTimeMillis() + 30L,
    ) = ReminderRequest(id = id, atEpochMillis = atMillis, title = "t", body = "b")

    // ---- reminders ----

    @Test
    fun `a due reminder is delivered and flips the capability to supported`(): Unit =
        runBlocking {
            val delivered = CompletableDeferred<ReminderRequest>()
            val sink =
                DesktopReminderSink(scope) { request ->
                    delivered.complete(request)
                    true
                }
            assertThat(sink.capability).isEqualTo(ReminderCapability.NOT_SURFACED)

            sink.schedule(reminder("task-1"))
            val fired = withTimeout(2_000) { delivered.await() }

            assertThat(fired.id).isEqualTo("task-1")
            // The capability is a claim about what actually happened, not about what the class intends.
            assertThat(sink.capability).isEqualTo(ReminderCapability.SUPPORTED)
        }

    @Test
    fun `a reminder the platform declines to show leaves the capability unclaimed`(): Unit =
        runBlocking {
            val attempted = CompletableDeferred<Unit>()
            val sink =
                DesktopReminderSink(scope) { _ ->
                    attempted.complete(Unit)
                    false
                }

            sink.schedule(reminder("task-1"))
            withTimeout(2_000) { attempted.await() }

            assertThat(sink.capability).isEqualTo(ReminderCapability.NOT_SURFACED)
        }

    @Test
    fun `rescheduling the same id replaces the pending reminder`(): Unit =
        runBlocking {
            val delivered = mutableListOf<String>()
            val sink =
                DesktopReminderSink(scope) { request ->
                    delivered.add(request.id)
                    true
                }

            // A long first delay that the second schedule must supersede.
            sink.schedule(reminder("task-1", atMillis = System.currentTimeMillis() + 60_000L))
            sink.schedule(reminder("task-1"))
            assertThat(sink.pendingCount()).isEqualTo(1)

            withTimeout(2_000) {
                while (delivered.isEmpty()) delay(10)
            }
            assertThat(delivered).containsExactly("task-1")
        }

    @Test
    fun `cancelling a reminder prevents its delivery`(): Unit =
        runBlocking {
            var delivered = false
            val sink =
                DesktopReminderSink(scope) {
                    delivered = true
                    true
                }

            sink.schedule(reminder("task-1"))
            sink.cancel("task-1")
            delay(200)

            assertThat(delivered).isFalse()
            assertThat(sink.pendingCount()).isEqualTo(0)
        }

    @Test
    fun `cancelAll clears every pending reminder`(): Unit =
        runBlocking {
            val sink = DesktopReminderSink(scope) { true }

            sink.schedule(reminder("a", atMillis = System.currentTimeMillis() + 60_000L))
            sink.schedule(reminder("b", atMillis = System.currentTimeMillis() + 60_000L))
            assertThat(sink.pendingCount()).isEqualTo(2)

            sink.cancelAll()

            assertThat(sink.pendingCount()).isEqualTo(0)
        }

    // ---- backups ----

    @Test
    fun `a non-positive interval is rejected rather than treated as off`(): Unit =
        runBlocking {
            val scheduler = DesktopBackupScheduler(scope, NoOpUnifiedLogger, runBackup = {})

            // Silently treating a bad interval as "disabled" would leave the user believing backups run.
            val zero = runCatching { scheduler.schedule(0) }
            val negative = runCatching { scheduler.schedule(-5) }

            assertThat(zero.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(negative.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(scheduler.intervalMinutes).isNull()
        }

    @Test
    fun `scheduling records the interval and cancelling clears it`(): Unit =
        runBlocking {
            val scheduler = DesktopBackupScheduler(scope, NoOpUnifiedLogger, runBackup = {})

            scheduler.schedule(60)

            assertThat(scheduler.intervalMinutes).isEqualTo(60)
            assertThat(scheduler.capability).isEqualTo(BackupBackgroundCapability.FOREGROUND_ONLY)

            scheduler.cancel()

            assertThat(scheduler.intervalMinutes).isNull()
        }

    @Test
    fun `a changed interval replaces the previous schedule`(): Unit =
        runBlocking {
            val scheduler = DesktopBackupScheduler(scope, NoOpUnifiedLogger, runBackup = {})

            scheduler.schedule(60)
            scheduler.schedule(15)

            assertThat(scheduler.intervalMinutes).isEqualTo(15)
        }

    // ---- capabilities and debug flags ----

    @Test
    fun `the desktop reports every capability it lacks`() {
        val capabilities = desktopCapabilities()

        assertThat(capabilities.biometrics).isEqualTo(SupportState.NOT_APPLICABLE)
        assertThat(capabilities.supportsDynamicColor).isFalse()
        assertThat(capabilities.supportsHaptics).isFalse()
        assertThat(capabilities.reminders).isEqualTo(SupportState.NOT_SURFACED)
        assertThat(capabilities.backupBackground).isEqualTo(SupportState.IN_PROCESS_ONLY)
        // The UI needs one flag to know it must explain itself somewhere.
        assertThat(capabilities.hasLimitations).isTrue()
    }

    @Test
    fun `debug logging defaults to off and reports the value in force`() {
        val flags = DesktopDebugFlags()

        // Unlike Android there is no build flag to follow, so an unconfigured install is off.
        assertThat(flags.debugLoggingEnabled).isFalse()
        assertThat(flags.setDebugLoggingEnabled(true)).isTrue()
        assertThat(flags.debugLoggingEnabled).isTrue()
    }
}
