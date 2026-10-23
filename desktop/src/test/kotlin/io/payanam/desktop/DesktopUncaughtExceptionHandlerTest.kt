//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.CrashBreadcrumbStore
import io.payanam.shared.logging.CrashBreadcrumbs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * The two halves of the crash handler's promise, and the order between them.
 *
 * The halves are only ever visible together - a breadcrumb the NEXT process replays, and a process that
 * still dies - and neither is observable from outside once the real default handler has run, because the
 * thing under test would be the test JVM dying with it. That is why the handler is built by
 * [desktopUncaughtExceptionHandler] as a value a test can be handed: the environment that would end this
 * JVM is injected as a fake `previous` handler that records the call instead, and the breadcrumb half is
 * observed through an in-memory [CrashBreadcrumbStore].
 *
 * The ORDERING assertion is the part neither half alone can catch: the breadcrumb must be recorded BEFORE
 * the re-dispatch, because the trail is the record that survives the death - a swap would still pass both
 * half-tests and lose the one line the next start exists to replay.
 */
class DesktopUncaughtExceptionHandlerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    /** In-memory trail plus a shared order log, so the ordering across both halves is observable. */
    private class RecordingStore(
        private val events: MutableList<String>,
    ) : CrashBreadcrumbStore {
        val entries = mutableListOf<String>()
        var lastData: Map<String, Any?> = emptyMap()

        override val location: String = "test-in-memory"

        override fun record(
            source: String,
            stage: String,
            data: Map<String, Any?>,
        ) {
            entries += "$source | $stage"
            lastData = data
            events += "breadcrumb"
        }

        override fun readAndClear(): List<String> = entries.toList().also { entries.clear() }
    }

    private lateinit var events: MutableList<String>
    private lateinit var store: RecordingStore

    @Before
    fun setUp() {
        // Both holders are process-wide and the module runs every test class in one JVM, so a store or a
        // logger installed here would otherwise leak into the next class - the same reason the sibling
        // DesktopCrashBreadcrumbStoreTest resets both.
        UnifiedLogger.resetForTests()
        UnifiedLogger.initialize(
            logsDirectory = tempFolder.newFolder("logs").toPath(),
            clock = { Instant.parse("2026-03-04T05:06:07.008Z") },
            debugEnabled = false,
        )
        CrashBreadcrumbs.resetForTests()
        events = mutableListOf()
        store = RecordingStore(events)
        CrashBreadcrumbs.install(store)
    }

    @After
    fun tearDown() {
        CrashBreadcrumbs.resetForTests()
        UnifiedLogger.resetForTests()
    }

    @Test
    fun `an uncaught throwable writes the breadcrumb and still re-dispatches to the previous handler`() {
        val previousCalls = mutableListOf<Pair<Thread, Throwable>>()
        val previous =
            Thread.UncaughtExceptionHandler { thread, throwable ->
                previousCalls += thread to throwable
                events += "previous"
            }
        val crash = IllegalStateException("boom")
        val thread = Thread.currentThread()

        desktopUncaughtExceptionHandler(previous, UnifiedLogger.getInstance())
            .uncaughtException(thread, crash)

        // Half 1: the trail the NEXT process replays. Byte-exact, because the stage string is what a
        // human greps the replayed trail for - a wrong stage must go red, not merely be non-empty.
        assertThat(store.entries).containsExactly("DesktopMain.uncaughtException | unhandled_crash")
        assertThat(store.lastData["errorType"]).isEqualTo("java.lang.IllegalStateException")
        assertThat(store.lastData["errorMessage"]).isEqualTo("boom")

        // Half 2: the process still dies. The re-dispatch to the previous handler is that death when the
        // previous handler is the JVM's default one - same thread and the same throwable INSTANCE, so a
        // handler that dropped the re-dispatch or re-dispatched the wrong throwable fails here.
        assertThat(previousCalls).containsExactly(thread to crash)

        // The ordering, which neither half alone can see: recorded BEFORE the re-dispatch.
        assertThat(events).containsExactly("breadcrumb", "previous").inOrder()
    }

    @Test
    fun `a process with no previous handler still records the breadcrumb and does not throw`() {
        val crash = IllegalStateException("boom")

        desktopUncaughtExceptionHandler(previous = null, sessionLogger = UnifiedLogger.getInstance())
            .uncaughtException(Thread.currentThread(), crash)

        // A host JVM with no handler at all is a real case: the trail must still be written, and nothing
        // may be thrown in place of the re-dispatch.
        assertThat(store.entries).containsExactly("DesktopMain.uncaughtException | unhandled_crash")
    }
}
