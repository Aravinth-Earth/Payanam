//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * Retry policy for a backup attempt, promoted from the Android coordinator per #691 slice 1.
 *
 * @param maxAttempts maximum number of snapshot attempts before giving up.
 * @param retryDelayMs delay between attempts in milliseconds.
 */
public data class BackupRetryPolicy(
    val maxAttempts: Int,
    val retryDelayMs: Long,
)

/**
 * Returns the retry policy for the given [trigger], promoted from the Android coordinator per #691 slice 1.
 */
public fun retryPolicyFor(trigger: BackupTrigger): BackupRetryPolicy =
    when (trigger) {
        BackupTrigger.AUTO -> BackupRetryPolicy(maxAttempts = 10, retryDelayMs = 3_000L)
        BackupTrigger.MANUAL -> BackupRetryPolicy(maxAttempts = 5, retryDelayMs = 1_500L)
        BackupTrigger.EXPORT -> BackupRetryPolicy(maxAttempts = 5, retryDelayMs = 1_500L)
    }
