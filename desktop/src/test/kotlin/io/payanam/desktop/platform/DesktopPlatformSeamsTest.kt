//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.platform

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.DesktopBuildInfo
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests for the desktop platform seams.
 *
 * These exist because the desktop's seams are where a capability the platform lacks could quietly become a
 * silent no-op. Each one is asserted in both directions: the seam does what it says when the platform can,
 * and REPORTS the limitation when it cannot, so a screen can explain itself instead of offering a control
 * that does nothing.
 *
 * **Cross-thread handoff in the reminder cases.** A delivery runs on the sink's own coroutine, so anything
 * the callback writes is read by the test thread across a thread boundary. Awaiting the delivery callback
 * and then asserting the capability races the sink's own publication of that fact: the two are separate
 * moments, so the assertion could read NOT_SURFACED from a delivery that had in fact succeeded. The wait is
 * on the published state itself; two sibling cases shared the gap in weaker form, polling a list another
 * thread appends to and reading a captured var the delivery sets, and now hand off through a completion.
 *
 * **The capability is asserted through the PUBLISHED flow, not through the helper.** `desktopCapabilities()`
 * with no arguments returns `NOT_SURFACED` for `reminders` whether or not a sink is installed, so a case
 * built that way cannot tell the two apart — and the app never reads that helper, it reads
 * `DesktopPlatformSeams.capabilities`. The cases below install a real sink, hand one a hook, and read the
 * flow, which is the shape production uses.
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
            // Await the PUBLISHED state, not the delivery itself. The delivery callback returning is
            // not the same moment as the sink recording that it surfaced: a delivery that completes
            // its deferred can be observed by this thread before the sink's own write lands, so
            // awaiting the callback alone is a race the assertion below loses intermittently.
            withTimeout(2_000) { sink.surfacedState.first { it } }

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
            // Written from the sink's own coroutine and read here, so the handoff is a
            // CompletableDeferred rather than a plain list: a list is only as visible as the last write
            // that happened to land, and this assertion would read it mid-write.
            val delivered = CompletableDeferred<ReminderRequest>()
            val sink =
                DesktopReminderSink(scope) { request ->
                    delivered.complete(request)
                    true
                }

            // A long first delay that the second schedule must supersede.
            sink.schedule(reminder("task-1", atMillis = System.currentTimeMillis() + 60_000L))
            sink.schedule(reminder("task-1"))
            assertThat(sink.pendingCount()).isEqualTo(1)

            val fired = withTimeout(2_000) { delivered.await() }
            assertThat(fired.id).isEqualTo("task-1")
        }

    @Test
    fun `cancelling a reminder prevents its delivery`(): Unit =
        runBlocking {
            // The delivery runs on another thread, so the flag is atomic rather than a captured var:
            // a plain one has no guaranteed visibility and would make this pass for the wrong reason
            // as often as it fails.
            val delivered = AtomicBoolean(false)
            val sink =
                DesktopReminderSink(scope) {
                    delivered.set(true)
                    true
                }

            sink.schedule(reminder("task-1"))
            sink.cancel("task-1")
            delay(200)

            assertThat(delivered.get()).isFalse()
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

    // ---- the published capability, read the way the running app reads it ----

    @Test
    fun `the published capabilities follow the sink that is actually installed`(): Unit =
        runBlocking {
            val seams =
                DesktopPlatformSeams(
                    scope = scope,
                    logger = NoOpUnifiedLogger,
                    runBackup = {},
                    backupRows = { emptyMap() },
                    deliverReminder = { true },
                )

            // Taken from the PUBLISHED flow, not from [desktopCapabilities]: the helper can be correct
            // while the flow publishes something else, and the flow is what the app consumes. A
            // scheduler is installed by construction, so `backupBackground` is asserted here too -
            // IN_PROCESS_ONLY is the honest answer WITH a scheduler, not only without one.
            assertThat(seams.capabilities.value.reminders).isEqualTo(SupportState.NOT_SURFACED)
            assertThat(seams.capabilities.value.backupBackground).isEqualTo(SupportState.IN_PROCESS_ONLY)
            assertThat(seams.capabilities.value.hasLimitations).isTrue()

            seams.reminderSink.schedule(reminder("task-1"))

            // Await the published value, not the delivery callback: the two are separate moments, so
            // awaiting the callback alone is a race this assertion would lose intermittently.
            val published =
                withTimeout(2_000) {
                    seams.capabilities.first { it.reminders == SupportState.SUPPORTED }
                }

            assertThat(published.reminders).isEqualTo(SupportState.SUPPORTED)
            // DERIVED, not re-asked: the same object now says so with no setter anywhere in sight.
            assertThat(seams.capabilities.value.reminders).isEqualTo(SupportState.SUPPORTED)
        }

    @Test
    fun `a delivery the platform declines does not claim a channel through the seams`(): Unit =
        runBlocking {
            val attempted = CompletableDeferred<Unit>()
            val seams =
                DesktopPlatformSeams(
                    scope = scope,
                    logger = NoOpUnifiedLogger,
                    runBackup = {},
                    backupRows = { emptyMap() },
                    deliverReminder = { _ ->
                        attempted.complete(Unit)
                        false
                    },
                )

            seams.reminderSink.schedule(reminder("task-1"))
            // The delivery really ran; without this the case would pass because nothing was scheduled.
            withTimeout(2_000) { attempted.await() }

            assertThat(seams.capabilities.value.reminders).isEqualTo(SupportState.NOT_SURFACED)
        }

    @Test
    fun `the app's factory forwards the delivery hook it is handed`(): Unit =
        runBlocking {
            val attempted = CompletableDeferred<Unit>()
            // The seam the RUNNING app builds (DesktopApp.kt -> desktopPlatformSeams), not a direct
            // construction: a factory that dropped the hook would still pass every other case here,
            // because the other cases construct the class themselves.
            val seams =
                desktopPlatformSeams(
                    scope = scope,
                    logger = NoOpUnifiedLogger,
                    runBackup = {},
                    backupRows = { emptyMap() },
                    deliverReminder = { _ ->
                        attempted.complete(Unit)
                        true
                    },
                )

            seams.reminderSink.schedule(reminder("task-1"))
            withTimeout(2_000) { attempted.await() }

            withTimeout(2_000) {
                seams.capabilities.first { it.reminders == SupportState.SUPPORTED }
            }
            assertThat(seams.capabilities.value.reminders).isEqualTo(SupportState.SUPPORTED)
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
    fun `debug logging starts at the build type's default and reports the value in force`() {
        val flags = DesktopDebugFlags()

        // The build type seeds the starting value, the way Android's BuildConfig.DEBUG does for
        // DebugLoggingPrefs - a debug build logs DEBUG entries, an unconfigured one does not.
        assertThat(flags.debugLoggingEnabled).isEqualTo(DesktopBuildInfo.IS_DEBUG_BUILD)
        assertThat(flags.setDebugLoggingEnabled(true)).isTrue()
        assertThat(flags.debugLoggingEnabled).isTrue()
    }
}
