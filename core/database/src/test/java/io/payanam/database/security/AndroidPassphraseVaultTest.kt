//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.VaultAvailability
import io.payanam.shared.security.VaultAvailability.AVAILABLE
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the Android side of the passphrase-vault contract.
 *
 * Robolectric because the manager behind it is SharedPreferences-backed, and the point of these tests is
 * that the ADAPTER's behaviour matches the contract the desktop also satisfies: the shared policy answers
 * before storage is touched, and a wrong passphrase is false rather than an exception.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidPassphraseVaultTest {
    private val validPassphrase = "Str0ng-Passphrase!"

    private fun vault() = AndroidPassphraseVault(ApplicationProvider.getApplicationContext())

    @Test
    fun `a fresh vault is available and has nothing configured`() {
        val vault = vault()

        assertThat(vault.availability()).isEqualTo(AVAILABLE)
        assertThat(vault.hasPassphraseConfigured()).isFalse()
    }

    @Test
    fun `configuring then verifying round-trips`() {
        val vault = vault()

        assertThat(vault.configurePassphrase(validPassphrase.toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.CONFIGURED)
        assertThat(vault.hasPassphraseConfigured()).isTrue()
        assertThat(vault.verifyPassphrase(validPassphrase.toCharArray())).isTrue()
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase?".toCharArray())).isFalse()
    }

    @Test
    fun `a passphrase the shared policy rejects is rejected on Android too`() {
        val vault = vault()

        // The same input must produce the same outcome on both platforms; that is the property the shared
        // policy exists for, and the reason the adapter asks it before the manager.
        assertThat(vault.configurePassphrase("short".toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.REJECTED_BY_POLICY)
        assertThat(vault.hasPassphraseConfigured()).isFalse()
    }

    @Test
    fun `clearing forgets the credential`() {
        val vault = vault()
        vault.configurePassphrase(validPassphrase.toCharArray())

        vault.clearPassphrase()

        assertThat(vault.hasPassphraseConfigured()).isFalse()
        assertThat(vault.availability()).isEqualTo(VaultAvailability.AVAILABLE)
    }
}
