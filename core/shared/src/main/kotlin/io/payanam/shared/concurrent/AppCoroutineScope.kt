//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.concurrent

import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Creates the [CoroutineScope] every background scope in Payanam should use: one whose uncaught
 * failure is REPORTED rather than killing the process.
 *
 * **Why this exists.** `SupervisorJob()` on its own isolates SIBLINGS - a failing child stops its own
 * siblings from being cancelled - but it does not isolate the PROCESS. A `launch` whose body throws
 * still reaches the default uncaught-exception handler, and on Android that is the app dying. This has
 * already happened once: a `BiometricPrompt.authenticate()` call made off the main thread crashed the
 * app on every launch that had biometric unlock enabled. Every scope in the app was hand-rolled, and
 * not one of them carried a `CoroutineExceptionHandler`.
 *
 * **What the handler does.** It logs the failure at ERROR through [AppLogger] - the shared, platform
 * neutral logging seam, so the line reaches the Android session log AND the desktop session log
 * without either platform being referenced from here - and it records a crash-safe breadcrumb through
 * [CrashBreadcrumbs], which is a shared seam too: both platforms install a real store at startup, so
 * the trail survives the crash and is replayed into the next session's log. The [name] is what
 * identifies the site, so the log says which scope died (`AppScope.UnifiedLogger`), not just that some
 * scope died.
 *
 * **Cancellation is not a failure.** A cancelled scope is teardown, so a [CancellationException] is
 * ignored here rather than logged. It is deliberately NOT rethrown either: this handler is a terminal
 * sink, and throwing out of it reaches the process-level uncaught-exception handler - which is the
 * exact crash this factory exists to prevent.
 *
 * **The handler itself never throws.** A `CoroutineExceptionHandler` is invoked from the coroutine
 * machinery, and a handler that throws is handed straight to the process-level uncaught-exception
 * handler. So the reporting path is fully guarded: a failure to report is written to stderr rather
 * than raised.
 *
 * The returned scope is an ordinary [CoroutineScope] - `launch`, `async` and `cancel` behave as they
 * would on a hand-rolled one. Note that only `launch` failures reach this handler: a root `async`
 * keeps its failure in the Deferred and reports it to whoever awaits it.
 *
 * @param name identifies the owning site in the log line, e.g. `"UnifiedLogger"`.
 * @param dispatcher the dispatcher the scope's coroutines run on.
 * @return a scope whose uncaught failures are logged and recorded rather than crashing the process.
 */
fun appCoroutineScope(
    name: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): CoroutineScope =
    CoroutineScope(
        SupervisorJob() +
            dispatcher +
            CoroutineExceptionHandler { _, failure -> reportUncaughtFailure(name, failure) },
    )

/**
 * Reports one uncaught scope failure: a crash-safe breadcrumb first, then the ERROR log line.
 *
 * Both steps are guarded, and a failure to report is written to stderr rather than raised, because
 * this runs inside a [CoroutineExceptionHandler] where a throw is fatal to the process.
 */
private fun reportUncaughtFailure(
    name: String,
    failure: Throwable,
) {
    if (failure is CancellationException) {
        // Teardown, not a failure: ignored, and deliberately not rethrown (see [appCoroutineScope]).
        return
    }

    val source = "$LOG_SOURCE_PREFIX$name"
    try {
        CrashBreadcrumbs.record(
            source = source,
            stage = UNCAUGHT_FAILURE_STAGE,
            data =
                mapOf(
                    "scope" to name,
                    "error" to (failure.message ?: failure::class.java.name),
                ),
        )
        AppLogger.current.e(
            source = source,
            message = "Uncaught failure in scope '$name'",
            error = failure,
            data = mapOf("scope" to name),
        )
    } catch (cancellation: CancellationException) {
        // The same rule one level down: a terminal sink must not throw.
        System.err.println(
            "AppCoroutineScope: cancellation while reporting a failure in scope '$name': ${cancellation.message}",
        )
    } catch (
        @Suppress("TooGenericExceptionCaught") reportingFailure: Exception,
    ) {
        // The reporting path itself failed. There is nothing left to report it to, so it goes to
        // stderr rather than into a log sink that has just proved itself broken.
        System.err.println(
            "AppCoroutineScope: could not report a failure in scope '$name': ${reportingFailure.message}",
        )
    }
}

/** Prefix for the log source, so a scope failure is greppable as `AppScope.<site>`. */
private const val LOG_SOURCE_PREFIX = "AppScope."

/** Breadcrumb stage recorded alongside the log line for an uncaught scope failure. */
private const val UNCAUGHT_FAILURE_STAGE = "uncaught-failure"
