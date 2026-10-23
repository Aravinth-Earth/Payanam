//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [PassphraseKdf].
 *
 * The fixed vectors are the point. A test that only asserted "the same inputs give the same answer" would pass
 * at ANY iteration count - which is exactly how the desktop came to derive at 10,000 while Android derived at
 * 210,000: each side was self-consistent, and neither was equal to the other. So [EXPECTED_VERIFIER] is a real
 * PBKDF2-HMAC-SHA256 output, and a change to the algorithm, the iteration count or the key length breaks the
 * assertion rather than sliding through.
 */
class PassphraseKdfTest {
    private val salt = ByteArray(32) { it.toByte() }

    private fun verifierHex(passphrase: String): String =
        PassphraseKdf.derive(passphrase.toCharArray(), salt).joinToString("") { "%02X".format(it) }

    @Test
    fun `derive matches the fixed 210k vector`() {
        assertThat(verifierHex("Passw0rd!123")).isEqualTo(EXPECTED_VERIFIER)
    }

    @Test
    fun `derive does not reproduce the desktop s former 10k verifier`() {
        // The regression this whole change exists for: at 10,000 iterations the same passphrase and salt give a
        // different (and 21x cheaper to brute-force) verifier. If this ever matches again, the iteration count
        // has drifted back down.
        assertThat(verifierHex("Passw0rd!123")).isNotEqualTo(FORMER_DESKTOP_VERIFIER)
    }

    @Test
    fun `derive is deterministic for the same passphrase and salt`() {
        assertThat(verifierHex("Passw0rd!123")).isEqualTo(verifierHex("Passw0rd!123"))
    }

    @Test
    fun `derive differs when only the salt differs`() {
        val other = ByteArray(32) { (it + 1).toByte() }
        val a = PassphraseKdf.derive("Passw0rd!123".toCharArray(), salt)
        val b = PassphraseKdf.derive("Passw0rd!123".toCharArray(), other)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `derive returns the declared key length`() {
        assertThat(PassphraseKdf.derive("Passw0rd!123".toCharArray(), salt)).hasLength(PassphraseKdf.KEY_BITS / 8)
    }

    @Test
    fun `derive rejects an empty salt`() {
        val failure =
            runCatching { PassphraseKdf.derive("Passw0rd!123".toCharArray(), ByteArray(0)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the declared parameters are the agreed ones`() {
        // A lock, not a tautology: these values are the agreement between the platforms, so changing one has to
        // be a deliberate edit here rather than a private constant on one side.
        assertThat(PassphraseKdf.ALGORITHM).isEqualTo("PBKDF2WithHmacSHA256")
        assertThat(PassphraseKdf.ITERATIONS).isEqualTo(210_000)
        assertThat(PassphraseKdf.KEY_BITS).isEqualTo(256)
        assertThat(PassphraseKdf.SALT_BYTES).isEqualTo(32)
    }

    private companion object {
        /** PBKDF2-HMAC-SHA256, "Passw0rd!123", salt `bytes(range(32))`, 210,000 iterations, 256-bit output. */
        const val EXPECTED_VERIFIER = "76317531DBCE58E540EE45C61E3954D3DFEB3E7C8E72262EFFE5DEB1117DE624"

        /** The same inputs at the desktop's former 10,000 iterations - kept as a named anti-regression value. */
        const val FORMER_DESKTOP_VERIFIER = "6E6A6A79B901B990852A8855461B8C95657FD42910A69181421EBF1C01C5A1DC"
    }
}
