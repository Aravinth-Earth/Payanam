//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the passphrase-vault contract, driven through an in-memory implementation.
 *
 * There is no production implementation in this module — Android's is Keystore-backed and the desktop's
 * lives in its own store — so what is asserted here is the CONTRACT: that it is satisfiable honestly, and
 * the two rules that a careless implementation gets wrong. The first is that "unreadable" and "not
 * configured" are different answers, because the desktop shipped the bug where a corrupt store looked like
 * a fresh install and sent the user to a setup screen that then overwrote it. The second is that clearing
 * the credential never touches data, so a routine-sounding reset cannot be an unrecoverable one.
 */
class PassphraseVaultContractTest {
    /** The smallest honest vault: it can be read, and it remembers one passphrase. */
    private class FakeVault(
        private var stored: String? = null,
        private val readable: Boolean = true,
        private val encryptionEnabled: Boolean = false,
    ) : PassphraseVault {
        var clearCount = 0
            private set

        override fun hasPassphraseConfigured(): Boolean = stored != null

        override fun availability(): VaultAvailability = if (readable) VaultAvailability.AVAILABLE else VaultAvailability.UNAVAILABLE

        override fun verifyPassphrase(passphrase: CharArray): Boolean = readable && stored != null && stored == passphrase.concatToString()

        override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome {
            val text = passphrase.concatToString()
            if (!SharedPassphrasePolicy.validate(text).isValid) {
                return PassphraseConfigureOutcome.REJECTED_BY_POLICY
            }
            if (!readable) return PassphraseConfigureOutcome.STORE_UNAVAILABLE
            stored = text
            return PassphraseConfigureOutcome.CONFIGURED
        }

        override fun updatePassphrase(
            current: CharArray,
            new: CharArray,
        ): PassphraseUpdateOutcome {
            if (!SharedPassphrasePolicy.validate(new.concatToString()).isValid) {
                return PassphraseUpdateOutcome.REJECTED_BY_POLICY
            }
            if (!readable) return PassphraseUpdateOutcome.STORE_UNAVAILABLE
            if (stored == null || stored != current.concatToString()) {
                return PassphraseUpdateOutcome.CURRENT_PASSPHRASE_WRONG
            }
            stored = new.concatToString()
            return PassphraseUpdateOutcome.UPDATED
        }

        override fun isEncryptionEnabled(): Boolean = encryptionEnabled

        private var failedAttempts = 0

        // The contract tells callers to record a failure they observed, and the schedule is the SHARED one,
        // so this fake cannot drift from the platforms the way a second hand-written table would. Counting in
        // `verifyPassphrase` instead would be the other honest shape (both platform stores do that), but the
        // contract's own wording is that the caller records - so the test double takes the caller's side.
        override fun recordFailedUnlockAttempt(): Long {
            failedAttempts++
            return unlockLockoutSecondsRemaining()
        }

        override fun unlockLockoutSecondsRemaining(): Long =
            SharedPassphraseLockoutPolicy.delaySecondsForAttempt(failedAttempts)

        override fun resetUnlockAttempts() {
            failedAttempts = 0
        }

        override fun clearPassphrase() {
            clearCount++
            stored = null
        }
    }

    @Test
    fun `a fresh vault is readable and has nothing configured`() {
        val vault = FakeVault()

        assertThat(vault.availability()).isEqualTo(VaultAvailability.AVAILABLE)
        assertThat(vault.hasPassphraseConfigured()).isFalse()
        assertThat(vault.verifyPassphrase("anything".toCharArray())).isFalse()
    }

    @Test
    fun `a configured passphrase verifies and a different one does not`() {
        val vault = FakeVault()

        assertThat(vault.configurePassphrase("Str0ng-Passphrase!".toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.CONFIGURED)

        assertThat(vault.hasPassphraseConfigured()).isTrue()
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase!".toCharArray())).isTrue()
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase?".toCharArray())).isFalse()
    }

    @Test
    fun `a passphrase the shared policy rejects is rejected before anything is stored`() {
        val vault = FakeVault()

        val outcome = vault.configurePassphrase("short".toCharArray())

        assertThat(outcome).isEqualTo(PassphraseConfigureOutcome.REJECTED_BY_POLICY)
        // The distinction that matters: a rejection by policy leaves the vault exactly as it was, so a user
        // retrying with a valid passphrase does not find a half-configured store.
        assertThat(vault.hasPassphraseConfigured()).isFalse()
    }

    @Test
    fun `an unreadable vault is distinguishable from one with nothing configured`() {
        val vault = FakeVault(readable = false)

        // Both report "not configured" or "cannot verify" - and that is exactly why availability exists.
        assertThat(vault.availability()).isEqualTo(VaultAvailability.UNAVAILABLE)
        assertThat(vault.verifyPassphrase("anything".toCharArray())).isFalse()

        // A caller that refuses to treat this as a fresh install is the point of the contract.
        assertThat(vault.configurePassphrase("Str0ng-Passphrase!".toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.STORE_UNAVAILABLE)
    }

    @Test
    fun `clearing the passphrase forgets the credential and nothing else`() {
        val vault = FakeVault()
        vault.configurePassphrase("Str0ng-Passphrase!".toCharArray())

        vault.clearPassphrase()

        assertThat(vault.clearCount).isEqualTo(1)
        assertThat(vault.hasPassphraseConfigured()).isFalse()
        // The contract's promise: an implementation that also deleted the database would be an unrecoverable
        // operation behind a routine name, so the vault has no API that can reach the data at all.
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase!".toCharArray())).isFalse()
    }

    @Test
    fun `a wrong current passphrase is refused and the old one keeps working`() {
        val vault = FakeVault(stored = "Str0ng-Passphrase!")

        val outcome = vault.updatePassphrase("Str0ng-Passphrase?".toCharArray(), "N3w-Passphrase!".toCharArray())

        assertThat(outcome).isEqualTo(PassphraseUpdateOutcome.CURRENT_PASSPHRASE_WRONG)
        // The contract's promise for a refused change: the owner is not locked out of their own data.
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase!".toCharArray())).isTrue()
        assertThat(vault.verifyPassphrase("N3w-Passphrase!".toCharArray())).isFalse()
    }

    @Test
    fun `an accepted change replaces the passphrase and the old one stops working`() {
        val vault = FakeVault(stored = "Str0ng-Passphrase!")

        val outcome = vault.updatePassphrase("Str0ng-Passphrase!".toCharArray(), "N3w-Passphrase!".toCharArray())

        assertThat(outcome).isEqualTo(PassphraseUpdateOutcome.UPDATED)
        assertThat(vault.verifyPassphrase("N3w-Passphrase!".toCharArray())).isTrue()
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase!".toCharArray())).isFalse()
    }

    @Test
    fun `a change to a passphrase the policy rejects leaves the original working`() {
        val vault = FakeVault(stored = "Str0ng-Passphrase!")

        val outcome = vault.updatePassphrase("Str0ng-Passphrase!".toCharArray(), "short".toCharArray())

        assertThat(outcome).isEqualTo(PassphraseUpdateOutcome.REJECTED_BY_POLICY)
        // Policy is checked before the current passphrase is even considered, so a rejected new passphrase
        // cannot be a way to probe whether `current` was right.
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase!".toCharArray())).isTrue()
    }

    @Test
    fun `the lockout escalates on recorded failures and clears after a successful unlock`() {
        val vault = FakeVault(stored = "Str0ng-Passphrase!")

        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(0L)
        assertThat(vault.recordFailedUnlockAttempt()).isEqualTo(0L)
        assertThat(vault.recordFailedUnlockAttempt()).isEqualTo(0L)
        // The shared schedule: the third failure is the first that costs anything.
        assertThat(vault.recordFailedUnlockAttempt()).isEqualTo(30L)
        assertThat(vault.recordFailedUnlockAttempt()).isEqualTo(60L)

        vault.resetUnlockAttempts()

        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(0L)
        // And a reset is not a way to reach the credential: the passphrase still verifies afterwards.
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase!".toCharArray())).isTrue()
    }

    @Test
    fun `the availability and outcome vocabularies are what the platforms map into`() {
        // Asserted through the enums so that every case is exercised: each platform maps its own richer
        // result type into these, and a case with no mapping would be a silent fallthrough.
        assertThat(VaultAvailability.entries)
            .containsExactly(VaultAvailability.AVAILABLE, VaultAvailability.UNAVAILABLE)
        assertThat(PassphraseConfigureOutcome.entries)
            .containsExactly(
                PassphraseConfigureOutcome.CONFIGURED,
                PassphraseConfigureOutcome.REJECTED_BY_POLICY,
                PassphraseConfigureOutcome.STORE_UNAVAILABLE,
            )
        assertThat(PassphraseConfigureOutcome.valueOf("CONFIGURED"))
            .isEqualTo(PassphraseConfigureOutcome.CONFIGURED)
        assertThat(PassphraseUpdateOutcome.entries)
            .containsExactly(
                PassphraseUpdateOutcome.UPDATED,
                PassphraseUpdateOutcome.CURRENT_PASSPHRASE_WRONG,
                PassphraseUpdateOutcome.REJECTED_BY_POLICY,
                PassphraseUpdateOutcome.STORE_UNAVAILABLE,
            )
        assertThat(VaultAvailability.valueOf("AVAILABLE")).isEqualTo(VaultAvailability.AVAILABLE)
    }
}
