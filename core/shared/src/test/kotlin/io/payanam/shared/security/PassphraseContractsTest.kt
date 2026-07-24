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
    fun `delay seconds for attempt matches progressive lockout policy`() {
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(1)).isEqualTo(0L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(2)).isEqualTo(0L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(3)).isEqualTo(30L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(4)).isEqualTo(60L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(5)).isEqualTo(120L)
        assertThat(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(8)).isEqualTo(300L)
    }
}
