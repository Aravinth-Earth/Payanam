//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

/**
 * PassphrasePolicy.
 */
object PassphrasePolicy {
    private const val MIN_LENGTH = 12
    private val logger =
        io.payanam.common.logging.UnifiedLogger
            .getInstance()

    /**
     * Validate.
     */
    fun validate(passphrase: String): PassphraseValidation {
        /** If. */
        if (passphrase.length < MIN_LENGTH) {
            logger.d("PassphrasePolicy.validate", "Rejected passphrase", mapOf("reason" to "min_length"))
            return PassphraseValidation(
                isValid = false,
                reasonCode = "min_length",
            )
        }
        /** If. */
        if (!passphrase.any { it.isUpperCase() }) {
            logger.d("PassphrasePolicy.validate", "Rejected passphrase", mapOf("reason" to "missing_uppercase"))
            return PassphraseValidation(
                isValid = false,
                reasonCode = "missing_uppercase",
            )
        }
        /** If. */
        if (!passphrase.any { it.isLowerCase() }) {
            logger.d("PassphrasePolicy.validate", "Rejected passphrase", mapOf("reason" to "missing_lowercase"))
            return PassphraseValidation(
                isValid = false,
                reasonCode = "missing_lowercase",
            )
        }
        /** If. */
        if (!passphrase.any { it.isDigit() }) {
            logger.d("PassphrasePolicy.validate", "Rejected passphrase", mapOf("reason" to "missing_digit"))
            return PassphraseValidation(
                isValid = false,
                reasonCode = "missing_digit",
            )
        }
        /** If. */
        if (!passphrase.any { !it.isLetterOrDigit() }) {
            logger.d("PassphrasePolicy.validate", "Rejected passphrase", mapOf("reason" to "missing_symbol"))
            return PassphraseValidation(
                isValid = false,
                reasonCode = "missing_symbol",
            )
        }
        logger.d("PassphrasePolicy.validate", "Accepted passphrase")
        return PassphraseValidation(isValid = true, reasonCode = null)
    }
}

/**
 * PassphraseValidation.
 */
data class PassphraseValidation(
    /** Is valid. */
    val isValid: Boolean,
    /** Reason code. */
    val reasonCode: String?,
)
