//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Turns a one-shot JDBC read into the [Flow] the `:core:domain` repository interfaces promise.
 *
 * WHY THIS EXISTS:
 *
 * Every Android repository returns `Flow` because Room emits again whenever a table it read is written to.
 * JDBC has no such notification: a raw connection cannot know that a row changed, so a desktop repository
 * that simply returned the current value would hand the UI a list that never updates - add a note and the
 * screen would not show it.
 *
 * So a subscription re-reads on an interval and emits only when the mapped result actually differs. On a
 * local, already-open SQLite file that read is cheap, and comparing the mapped value means an unchanged
 * table produces no recomposition at all. It is deliberately NOT a write-notification bus: the database
 * file is an interchange artifact, so a row can also change under us - the interval is what makes the
 * desktop correct when the file is replaced, not only when the desktop itself writes.
 *
 * The interval is a parameter, not a constant, so tests can drive it without waiting.
 */
internal object JdbcFlows {
    /** One second: fast enough to feel live, slow enough that an idle screen is not a busy loop. */
    const val DEFAULT_POLL_INTERVAL_MS: Long = 1_000L

    /** Log-source tag for the single line a closed stream produces. */
    private const val SOURCE = "JdbcFlows"

    /**
     * Emits [read]'s value immediately, then again whenever it changes.
     *
     * [read] runs on [Dispatchers.IO] because JDBC is blocking and the session serialises access, so it
     * must not run on the caller's dispatcher (in Compose, that is the main thread).
     *
     * Only equal values are suppressed; a change back to a previously-seen value IS emitted, because the
     * UI has to be told about it.
     *
     * A session that closes under the subscription COMPLETES the stream; see the catch below.
     */
    fun <T> observe(
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        read: () -> T,
    ): Flow<T> =
        flow {
            var hasEmitted = false
            var last: T? = null
            while (true) {
                val value =
                    try {
                        withContext(Dispatchers.IO) { read() }
                    } catch (closed: PayanamDatabaseSessionClosedException) {
                        // Closed session = normal end of stream: its owner is gone and this handle never reopens.
                        // Only this type is caught; any other IllegalStateException still reaches the collector.
                        AppLogger.current.d(
                            SOURCE,
                            "Observation stream ended because the session was closed",
                            mapOf("reason" to closed.message),
                        )
                        return@flow
                    }
                if (!hasEmitted || value != last) {
                    hasEmitted = true
                    last = value
                    emit(value)
                }
                delay(pollIntervalMs)
            }
        }

    /** Runs a blocking JDBC read on the IO dispatcher. */
    suspend fun <T> onIo(read: () -> T): T = withContext(Dispatchers.IO) { read() }
}
