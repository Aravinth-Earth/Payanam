//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

data class SharedPassphraseValidation(
    val isValid: Boolean,
    val reasonCode: String?,
)

object SharedPassphrasePolicy {
    private const val MIN_LENGTH = 12

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

object SharedPassphraseLockoutPolicy {
    fun delaySecondsForAttempt(attemptCount: Int): Long =
        when {
            attemptCount <= 2 -> 0L
            attemptCount == 3 -> 30L
            attemptCount == 4 -> 60L
            attemptCount == 5 -> 120L
            else -> 300L
        }
}
