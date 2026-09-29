//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The one key-derivation configuration both platforms use for the passphrase verifier.
 *
 * It lives in the shared module because keeping it per platform is how the two drifted: Android derived at
 * 210,000 iterations over a 32-byte salt, the desktop at 10,000 over 16. A verifier is an
 * offline-crackable artifact by construction - its cost IS the security - so the cheaper side silently
 * became the real strength of the user's passphrase on that platform, and neither side could tell. Numbers
 * that must agree belong in one place.
 *
 * These are Android's values: it shipped first, verifiers derived from them are already stored on devices,
 * and Android's database is the cross-platform reference.
 */
object PassphraseKdf {
    /**
     * PBKDF2 with HMAC-SHA256 - the algorithm both platforms already used, so this names the existing choice
     * rather than changing it.
     */
    const val ALGORITHM: String = "PBKDF2WithHmacSHA256"

    /** Iteration count. Android's value; the desktop was 21x lower. */
    const val ITERATIONS: Int = 210_000

    /** Derived key length in bits. */
    const val KEY_BITS: Int = 256

    /** Salt length in bytes, filled from a [java.security.SecureRandom] by the caller. */
    const val SALT_BYTES: Int = 32

    /**
     * Derives the verifier for [passphrase] and [salt].
     *
     * The caller keeps ownership of [passphrase]: it is not cleared here, because the call sites differ in
     * what they do with the array afterwards (Android zeroes its own copy in a `finally`, the desktop passes
     * a throwaway). Clearing someone else's buffer would be a surprise, not a courtesy.
     */
    fun derive(
        passphrase: CharArray,
        salt: ByteArray,
    ): ByteArray {
        require(salt.isNotEmpty()) { "salt must not be empty" }
        val keySpec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(keySpec).encoded
        } finally {
            keySpec.clearPassword()
        }
    }
}
