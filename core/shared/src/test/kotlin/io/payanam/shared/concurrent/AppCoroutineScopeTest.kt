//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.concurrent

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Captures what the shared logging seam was asked to write, so a scope failure can be asserted on.
 */
private class RecordingLogger : UnifiedLogger {
    val errors = CopyOnWriteArrayList<Triple<String, String, Throwable?>>()

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
    ) = Unit

    override fun e(
        source: String,
        message: String,
        error: Throwable?,
        data: Map<String, Any?>,
    ) {
        errors.add(Triple(source, message, error))
    }

    override fun eSync(
        source: String,
        message: String,
        error: Throwable?,
        data: Map<String, Any?>,
    ) {
        errors.add(Triple(source, message, error))
    }
}

/**
 * Unit tests for the shared scope factory.
 *
 * The claim these pin is the one the whole factory exists for: a failure inside a scope is REPORTED
 * rather than killing the process. `SupervisorJob()` alone does not deliver that - a failing `launch`
 * still reaches the process-level uncaught-exception handler, which on Android is the app dying.
 */
class AppCoroutineScopeTest {
    private val logger = RecordingLogger()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        // The shared-JVM guard AppLogger's own KDoc asks for: reset first, so an assertion cannot be
        // satisfied by a logger leaked out of an earlier test class, then install this class's one.
        AppLogger.resetForTests()
        AppLogger.install(logger)

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught.add(throwable) }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        AppLogger.resetForTests()
    }

    @Test
    fun `a failing launch is reported through the app logger, naming the scope`() {
        val reported = CountDownLatch(1)
        val scope = appCoroutineScope("TestScope", Dispatchers.Default)
        val failure = IllegalStateException("boom")

        scope.launch {
            try {
                throw failure
            } finally {
                reported.countDown()
            }
        }

        assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue()
        waitForErrorCount(1)

        val (source, message, error) = logger.errors.single()
        assertThat(source).isEqualTo("AppScope.TestScope")
        assertThat(message).contains("TestScope")
        assertThat(error).isSameInstanceAs(failure)
    }

    @Test
    fun `a failing launch does not reach the process-level uncaught exception handler`() {
        val reported = CountDownLatch(1)
        val scope = appCoroutineScope("TestScope", Dispatchers.Default)

        scope.launch {
            try {
                throw IllegalStateException("boom")
            } finally {
                reported.countDown()
            }
        }

        assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue()
        waitForErrorCount(1)

        // This is the whole point: the old hand-rolled scope reached this handler, and on Android
        // reaching it IS the crash.
        assertThat(uncaught).isEmpty()
    }

    @Test
    fun `cancellation is not reported as a failure`() {
        val reported = CountDownLatch(1)
        val scope = appCoroutineScope("TestScope", Dispatchers.Default)

        scope.launch {
            try {
                throw CancellationException("teardown, not a failure")
            } finally {
                reported.countDown()
            }
        }

        assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue()
        // Give the coroutine machinery a moment to route anything it is going to route.
        Thread.sleep(SETTLE_MILLIS)

        assertThat(logger.errors).isEmpty()
        assertThat(uncaught).isEmpty()
    }

    @Test
    fun `cancelling the scope is not reported as a failure`() {
        val scope = appCoroutineScope("TestScope", Dispatchers.Default)
        scope.launch { awaitCancellation() }

        scope.cancel()
        Thread.sleep(SETTLE_MILLIS)

        assertThat(logger.errors).isEmpty()
        assertThat(uncaught).isEmpty()
    }

    @Test
    fun `a failing sibling does not cancel the scope's other work`() {
        val siblingRan = CountDownLatch(1)
        val scope = appCoroutineScope("TestScope", Dispatchers.Default)

        scope.launch { throw IllegalStateException("boom") }
        scope.launch {
            siblingRan.countDown()
        }

        assertThat(siblingRan.await(5, TimeUnit.SECONDS)).isTrue()
        waitForErrorCount(1)
    }

    /** Waits, briefly, for the scope's handler to have delivered [expected] error entries. */
    private fun waitForErrorCount(expected: Int) {
        val deadline = System.currentTimeMillis() + SETTLE_MILLIS
        while (System.currentTimeMillis() < deadline && logger.errors.size < expected) {
            Thread.sleep(10)
        }
    }

    private companion object {
        /** Long enough for a handler to run on a busy CI machine, short enough not to stall the suite. */
        private const val SETTLE_MILLIS = 2_000L
    }
}
