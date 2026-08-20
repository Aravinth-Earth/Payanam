//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import io.payanam.common.logging.UnifiedLogger

/**
 * PassphraseLockoutPolicy.
 */
object PassphraseLockoutPolicy {
    private val logger = UnifiedLogger.getInstance()

    /**
     * Delay seconds for attempt.
     */
    fun delaySecondsForAttempt(attemptCount: Int): Long {
        /** Delay. */
        val delay =
            when {
                attemptCount <= 2 -> 0L
                attemptCount == 3 -> 30L
                attemptCount == 4 -> 60L
                attemptCount == 5 -> 120L
                else -> 300L
            }
        logger.d(
            "PassphraseLockoutPolicy.delaySecondsForAttempt",
            "Computed lockout delay",
            /** Map of. */
            mapOf("attemptCount" to attemptCount, "delaySeconds" to delay),
        )
        return delay
    }
}
