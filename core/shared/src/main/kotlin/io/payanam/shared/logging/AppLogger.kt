//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.logging

/**
 * Process-wide holder for the platform's real [UnifiedLogger].
 *
 * Platform-neutral modules (`core/domain`, `core/scoring`) are libraries: they must not reach for a
 * platform singleton, and they must not require a logger to exist. They read this holder instead.
 * It starts as [NoOpUnifiedLogger], so a read before an app installs its logger is a silent no-op
 * rather than a crash.
 *
 * The app installs once, early, from its startup path, so the write happens-before every read. This
 * is deliberately a single mutable field rather than constructor injection: the `object` singletons
 * in `core/scoring` need a logger across many internal methods, and threading a parameter through
 * them would change their public API and every one of their call sites.
 */
object AppLogger {
    /**
     * The logger used by platform-neutral modules. Defaults to [NoOpUnifiedLogger]; install a real
     * one with [install] so those modules' diagnostics reach the app's log sink.
     */
    @Volatile
    var current: UnifiedLogger = NoOpUnifiedLogger
        private set

    /** Installs [logger] process-wide. Call once from the app's startup path. */
    fun install(logger: UnifiedLogger) {
        current = logger
    }

    /**
     * Restores the [NoOpUnifiedLogger] default.
     *
     * Test-only. Test classes in a module share one JVM (`forkEvery` is unset in this build), so a
     * test that installs a logger must reset it in **both** `@Before` and `@After`: the `@Before`
     * guard stops an assertion about a real logger from passing on state leaked out of an earlier
     * class, and the `@After` guard stops this class from leaking into the next one.
     */
    fun resetForTests() {
        current = NoOpUnifiedLogger
    }
}
