//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * Records the performance-baseline marks the screens emit.
 *
 * The implementation reads the platform's monotonic clock and writes to the log, neither of which is available
 * where the ViewModels now live. Nothing branches on these calls: dropping them changes what is measured, never
 * what a screen shows, which is why this is a port and not a feature flag.
 */
interface PerformanceTracer {
    /**
     * Records one mark for [screen]/[event].
     */
    fun markEvent(screen: String, event: String, data: Map<String, Any?> = emptyMap())

    /**
     * Like [markEvent], but records [key] only the first time it is seen per run.
     */
    fun markEventOnce(key: String, screen: String, event: String, data: Map<String, Any?> = emptyMap())

    /**
     * Adds to a per-screen query counter and returns the running total.
     */
    fun incrementQuery(screen: String, source: String, amount: Int = 1): Int

    /**
     * Counts a recomposition for one screen section and returns the running total.
     */
    fun incrementRecomposition(screen: String, section: String): Int
}
