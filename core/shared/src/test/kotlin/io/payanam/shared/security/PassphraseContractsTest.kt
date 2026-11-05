//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PassphraseContractsTest {
    @Test
    fun `validate rejects passphrase shorter than minimum`() {
        val result = SharedPassphrasePolicy.validate("Ab1!")
        assertThat(result.isValid).isFalse()
        assertThat(result.reasonCode).isEqualTo("min_length")
    }

    @Test
    fun `validate rejects passphrase missing uppercase`() {
        val result = SharedPassphrasePolicy.validate("lowercase12!")
        assertThat(result.isValid).isFalse()
        assertThat(result.reasonCode).isEqualTo("missing_uppercase")
    }

    @Test
    fun `validate accepts strong passphrase`() {
        val result = SharedPassphrasePolicy.validate("S3cure!Passphrase")
        assertThat(result.isValid).isTrue()
        assertThat(result.reasonCode).isNull()
    }

    @Test
    fun `validate rejects passphrase missing lowercase`() {
        val result = SharedPassphrasePolicy.validate("ABCDEFGHIJ1!")
        assertThat(result.isValid).isFalse()
        assertThat(result.reasonCode).isEqualTo("missing_lowercase")
    }

    @Test
    fun `validate rejects passphrase missing digit`() {
        val result = SharedPassphrasePolicy.validate("Abcdefghijkl!")
        assertThat(result.isValid).isFalse()
        assertThat(result.reasonCode).isEqualTo("missing_digit")
    }

    @Test
    fun `validate rejects passphrase missing symbol`() {
        val result = SharedPassphrasePolicy.validate("Abcdefghijkl1")
        assertThat(result.isValid).isFalse()
        assertThat(result.reasonCode).isEqualTo("missing_symbol")
    }

    @Test
    fun `validate reports the first failing rule when multiple are violated`() {
        // Too short AND missing uppercase: min_length is checked first, so it wins.
        val result = SharedPassphrasePolicy.validate("ab1!")
        assertThat(result.isValid).isFalse()
        assertThat(result.reasonCode).isEqualTo("min_length")
    }

    @Test
    fun `validate accepts a passphrase at exactly the minimum length`() {
        // 12 characters with all four classes: the boundary of the length rule.
        val result = SharedPassphrasePolicy.validate("Abcdefghij1!")
        assertThat(result.isValid).isTrue()
        assertThat(result.reasonCode).isNull()
    }

    @Test
    fun `validate rejects a passphrase one below the minimum length`() {
        // 11 characters with all four classes: still too short.
        val result = SharedPassphrasePolicy.validate("Abcdefghij1")
        assertThat(result.isValid).isFalse()
        assertThat(result.reasonCode).isEqualTo("min_length")
    }

    @Test
    fun `delay seconds for attempt matches progressive lockout policy`() {
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(1)).isEqualTo(0L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(2)).isEqualTo(0L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(3)).isEqualTo(30L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(4)).isEqualTo(60L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(5)).isEqualTo(120L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(8)).isEqualTo(300L)
    }

    @Test
    fun `delay seconds for attempt is zero before the first punished failure`() {
        // Attempt 0 (no failures yet) and attempt 2 (the last free one) both cost nothing.
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(0)).isEqualTo(0L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(2)).isEqualTo(0L)
    }

    @Test
    fun `delay seconds for attempt caps at 300 for high attempt counts`() {
        // The else branch: any attempt beyond 5 costs the maximum.
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(6)).isEqualTo(300L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(100)).isEqualTo(300L)
    }
}
