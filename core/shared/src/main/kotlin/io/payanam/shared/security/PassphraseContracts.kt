//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

/**
 * Result of passphrase validation: whether it passed and the failure reason code
 * (min_length / missing_uppercase / missing_lowercase / missing_digit / missing_symbol).
 */
data class SharedPassphraseValidation(
    val isValid: Boolean,
    val reasonCode: String?,
)
object SharedPassphrasePolicy {
    private const val MIN_LENGTH = 12
    /**
     * Validates a passphrase against length + character-class rules; returns the
     * failure [reasonCode] when invalid.
     */
    fun validate(passphrase: String): SharedPassphraseValidation {
        if (passphrase.length < MIN_LENGTH) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "min_length")
        }
        if (!passphrase.any { it.isUpperCase() }) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "missing_uppercase")
        }
        if (!passphrase.any { it.isLowerCase() }) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "missing_lowercase")
        }
        if (!passphrase.any { it.isDigit() }) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "missing_digit")
        }
        if (!passphrase.any { !it.isLetterOrDigit() }) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "missing_symbol")
        }
        return SharedPassphraseValidation(isValid = true, reasonCode = null)
    }
}
@Suppress("MagicNumber")
object SharedPassphraseLockoutPolicy {
    /**
     * Cool-down seconds before the next unlock attempt, escalating with [attemptCount]
     * (0 until attempt 3, then 30/60/120/300).
     */
    fun delaySecondsForAttempt(attemptCount: Int): Long =
        when {
            attemptCount <= 2 -> 0L
            attemptCount == 3 -> 30L
            attemptCount == 4 -> 60L
            attemptCount == 5 -> 120L
            else -> 300L
        }
}
