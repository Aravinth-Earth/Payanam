//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.VaultAvailability
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the desktop's passphrase-vault adapter, against a real [DesktopSecurityStore] in a per-test
 * directory.
 *
 * Per-test rather than a shared temp path: the store keeps its state on disk, so a shared directory lets a
 * previous test's passphrase satisfy a later one's assertion — the failure that already bit this store's
 * logging tests.
 */
class DesktopPassphraseVaultTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val validPassphrase = "Str0ng-Passphrase!"

    private fun vault(): DesktopPassphraseVault =
        DesktopPassphraseVault(
            DesktopSecurityStore(securityDirectory = tempFolder.newFolder().toPath()),
        )

    @Test
    fun `a fresh vault is available and has nothing configured`() {
        val vault = vault()

        assertThat(vault.availability()).isEqualTo(VaultAvailability.AVAILABLE)
        assertThat(vault.hasPassphraseConfigured()).isFalse()
    }

    @Test
    fun `configuring then verifying round-trips through the shared policy`() {
        val vault = vault()

        assertThat(vault.configurePassphrase(validPassphrase.toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.CONFIGURED)
        assertThat(vault.hasPassphraseConfigured()).isTrue()
        assertThat(vault.verifyPassphrase(validPassphrase.toCharArray())).isTrue()
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase?".toCharArray())).isFalse()
    }

    @Test
    fun `a passphrase the shared policy rejects never reaches the store`() {
        val vault = vault()

        // Below MIN_LENGTH, so the shared policy answers before the store is asked. The observable promise
        // is that the vault is left exactly as it was.
        assertThat(vault.configurePassphrase("short".toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.REJECTED_BY_POLICY)
        assertThat(vault.hasPassphraseConfigured()).isFalse()
    }

    @Test
    fun `clearing forgets the credential and leaves the vault readable`() {
        val vault = vault()
        vault.configurePassphrase(validPassphrase.toCharArray())

        vault.clearPassphrase()

        assertThat(vault.hasPassphraseConfigured()).isFalse()
        assertThat(vault.availability()).isEqualTo(VaultAvailability.AVAILABLE)
        assertThat(vault.verifyPassphrase(validPassphrase.toCharArray())).isFalse()
    }
}
