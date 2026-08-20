//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

/**
 * SharedPassphraseValidation.

 */
data class SharedPassphraseValidation(
    /** Is valid. */
    val isValid: Boolean,
    /** Reason code. */
    val reasonCode: String?,
)

/**
 * SharedPassphrasePolicy.
 */
object SharedPassphrasePolicy {
    private const val MIN_LENGTH = 12

    /**
     * Validate.
     */
    fun validate(passphrase: String): SharedPassphraseValidation {
        /** If. */
        if (passphrase.length < MIN_LENGTH) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "min_length")
        }
        /** If. */
        if (!passphrase.any { it.isUpperCase() }) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "missing_uppercase")
        }
        /** If. */
        if (!passphrase.any { it.isLowerCase() }) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "missing_lowercase")
        }
        /** If. */
        if (!passphrase.any { it.isDigit() }) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "missing_digit")
        }
        /** If. */
        if (!passphrase.any { !it.isLetterOrDigit() }) {
            return SharedPassphraseValidation(isValid = false, reasonCode = "missing_symbol")
        }
        return SharedPassphraseValidation(isValid = true, reasonCode = null)
    }
}

/**
 * SharedPassphraseLockoutPolicy.
 */
@Suppress("MagicNumber")
object SharedPassphraseLockoutPolicy {
    /**
     * Delay seconds for attempt.
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
