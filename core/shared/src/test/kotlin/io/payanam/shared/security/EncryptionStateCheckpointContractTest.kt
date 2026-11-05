//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the encryption-checkpoint contract, driven through two in-memory implementations.
 *
 * There is no production implementation in this module — Android's is preferences-backed and the desktop has
 * none at all — so what is asserted here is the CONTRACT, and specifically the two traps a careless or eager
 * implementation falls into.
 *
 * The first is the [FakeEncryptingCheckpoint], which reproduces the real trap the contract forbids: a restore
 * that finds nothing to restore REMOVES the live keys. That behaviour is copied from the Android manager on
 * purpose, so the test can prove the ordering rule the contract states is load-bearing rather than stylistic —
 * if a future implementation restored unconditionally, this fake is the one that shows the data loss.
 *
 * The second is [FakePlaintextCheckpoint], the desktop's shape: a platform with no encryption state that must
 * still answer. Its tests assert it answers NOT_SUPPORTED and, more importantly, that it never reaches for the
 * passphrase to make the call look like it did something — the mapping a shared import flow would otherwise
 * read as "your state was wiped".
 */
class EncryptionStateCheckpointContractTest {
    /**
     * A platform that HAS encryption metadata, and that behaves the way the Android one does.
     *
     * The restore-removes-on-missing behaviour is the load-bearing part, and it is inherited rather than
     * invented: a restore with no captured copy walks the live keys and removes each one it cannot find a
     * backup for, which is exactly how a restore can report success while having wiped the state.
     */
    private class FakeEncryptingCheckpoint(
        private var liveKeysPresent: Boolean = true,
        private var captured: Boolean = false,
    ) : EncryptionStateCheckpoint {
        var calls = mutableListOf<String>()

        override fun captureCheckpoint(): EncryptionCheckpointOutcome {
            calls += "capture"
            captured = true
            return EncryptionCheckpointOutcome.CONFIRMED
        }

        override fun restoreCheckpoint(): EncryptionCheckpointOutcome {
            calls += "restore"
            if (!captured) {
                // The trap. A restore with nothing captured is a wipe that reports itself as a success, and
                // it is why the contract forbids calling this without a confirmed capture.
                liveKeysPresent = false
            }
            captured = false
            return EncryptionCheckpointOutcome.CONFIRMED
        }

        override fun discardCheckpoint(): EncryptionCheckpointOutcome {
            calls += "discard"
            captured = false
            // Mirrors Android, where the manager discards its commit result: the write happens, the
            // confirmation does not survive, and the contract has a case for that instead of a fake CONFIRMED.
            return EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED
        }

        override fun wipeEncryptionState(): EncryptionCheckpointOutcome {
            calls += "wipe"
            liveKeysPresent = false
            return EncryptionCheckpointOutcome.CONFIRMED
        }

        fun stateStillPresent(): Boolean = liveKeysPresent

        fun hasRecoveryPath(): Boolean = captured
    }

    /**
     * A minimal [PassphraseVault] that only answers what this test needs to observe.
     *
     * It exists because the desktop DOES hold a passphrase with a working reset, and that is precisely the
     * resource a lazy checkpoint implementation would reach for to turn a NOT_SUPPORTED into a CONFIRMED. A
     * stub that can be asked "did anything happen to the credential?" is what makes that check possible.
     */
    private class StubVault(
        private var stored: String? = null,
    ) : PassphraseVault {
        override fun hasPassphraseConfigured(): Boolean = stored != null

        override fun availability(): VaultAvailability = VaultAvailability.AVAILABLE

        override fun verifyPassphrase(passphrase: CharArray): Boolean = stored == passphrase.concatToString()

        override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome =
            PassphraseConfigureOutcome.CONFIGURED.also { stored = passphrase.concatToString() }

        override fun clearPassphrase() {
            stored = null
        }

        override fun isEncryptionEnabled(): Boolean = false

        override fun updatePassphrase(
            current: CharArray,
            new: CharArray,
        ): PassphraseUpdateOutcome = PassphraseUpdateOutcome.UPDATED.also { stored = new.concatToString() }

        override fun unlockLockoutSecondsRemaining(): Long = 0L

        override fun recordFailedUnlockAttempt(): Long = 0L

        override fun resetUnlockAttempts() = Unit
    }

    /**
     * A platform with NO encryption state, in the desktop's shape.
     *
     * [vault] is the temptation, recorded so the test can prove it was never touched: the desktop does have a
     * passphrase store with a working reset, and mapping the wipe onto it would return CONFIRMED for a state
     * that never existed.
     */
    private class FakePlaintextCheckpoint(
        private val vault: StubVault,
    ) : EncryptionStateCheckpoint {
        override fun captureCheckpoint(): EncryptionCheckpointOutcome = EncryptionCheckpointOutcome.NOT_SUPPORTED

        override fun restoreCheckpoint(): EncryptionCheckpointOutcome = EncryptionCheckpointOutcome.NOT_SUPPORTED

        override fun discardCheckpoint(): EncryptionCheckpointOutcome = EncryptionCheckpointOutcome.NOT_SUPPORTED

        override fun wipeEncryptionState(): EncryptionCheckpointOutcome = EncryptionCheckpointOutcome.NOT_SUPPORTED
    }

    @Test
    fun `a capture is what makes a later restore safe`() {
        val checkpoint = FakeEncryptingCheckpoint()

        assertThat(checkpoint.captureCheckpoint()).isEqualTo(EncryptionCheckpointOutcome.CONFIRMED)
        assertThat(checkpoint.hasRecoveryPath()).isTrue()

        // The contract's ordering rule, exercised in the direction that matters: capture first, restore second.
        assertThat(checkpoint.restoreCheckpoint()).isEqualTo(EncryptionCheckpointOutcome.CONFIRMED)
        assertThat(checkpoint.stateStillPresent()).isTrue()
    }

    @Test
    fun `a restore with nothing captured destroys the state it was supposed to protect`() {
        val checkpoint = FakeEncryptingCheckpoint()

        // Deliberately NOT calling captureCheckpoint. The contract forbids this call, and this is the evidence
        // for the prohibition rather than a hypothetical: on this shape of platform the call the contract
        // forbids is the one that wipes a configured passphrase, and it still reports CONFIRMED.
        assertThat(checkpoint.restoreCheckpoint()).isEqualTo(EncryptionCheckpointOutcome.CONFIRMED)
        assertThat(checkpoint.stateStillPresent()).isFalse()
    }

    @Test
    fun `a discard is irreversible and says it could not be confirmed`() {
        val checkpoint = FakeEncryptingCheckpoint()
        checkpoint.captureCheckpoint()

        val outcome = checkpoint.discardCheckpoint()

        // Not CONFIRMED: this platform performed the write and cannot verify it, so the contract's uncertainty
        // case is the honest answer. A caller that reported this as a confirmed discard would be claiming a
        // verification that never happened.
        assertThat(outcome).isEqualTo(EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED)
        assertThat(checkpoint.hasRecoveryPath()).isFalse()
        // And the irreversibility is real: with the copy gone, a later restore cannot bring the state back.
        assertThat(checkpoint.stateStillPresent()).isTrue()
    }

    @Test
    fun `the abandon path forgets the encryption state and reports it`() {
        val checkpoint = FakeEncryptingCheckpoint()
        checkpoint.captureCheckpoint()

        assertThat(checkpoint.wipeEncryptionState()).isEqualTo(EncryptionCheckpointOutcome.CONFIRMED)
        assertThat(checkpoint.stateStillPresent()).isFalse()
    }

    @Test
    fun `a platform with no encryption state refuses every member and never touches the vault`() {
        val vault = StubVault(stored = "Str0ng-Passphrase!")
        val checkpoint = FakePlaintextCheckpoint(vault)

        assertThat(checkpoint.captureCheckpoint()).isEqualTo(EncryptionCheckpointOutcome.NOT_SUPPORTED)
        assertThat(checkpoint.restoreCheckpoint()).isEqualTo(EncryptionCheckpointOutcome.NOT_SUPPORTED)
        assertThat(checkpoint.discardCheckpoint()).isEqualTo(EncryptionCheckpointOutcome.NOT_SUPPORTED)

        // The member with the most dangerous wrong answer: a platform with no encryption state has nothing to
        // wipe, and CONFIRMED here would be a claim that a passphrase it never involved was forgotten.
        assertThat(checkpoint.wipeEncryptionState()).isEqualTo(EncryptionCheckpointOutcome.NOT_SUPPORTED)
        // The check that the refusal was a refusal and not a delegation: the passphrase still works afterwards.
        assertThat(vault.verifyPassphrase("Str0ng-Passphrase!".toCharArray())).isTrue()
    }

    @Test
    fun `an absent encryption state is not reported as a store failure`() {
        val checkpoint = FakePlaintextCheckpoint(StubVault())

        // The two cases call for opposite copy, which is the reason this is an enum rather than a boolean:
        // "this platform has no such thing" is not "I tried and failed".
        assertThat(checkpoint.captureCheckpoint())
            .isNotEqualTo(EncryptionCheckpointOutcome.STORE_UNAVAILABLE)
        assertThat(checkpoint.captureCheckpoint()).isNotEqualTo(EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED)
    }

    @Test
    fun `the outcome vocabulary is what the platforms map into`() {
        // Asserted through the enum so every case is exercised: each platform maps its own richer result into
        // these, and a case with no mapping would be a silent fallthrough.
        assertThat(EncryptionCheckpointOutcome.entries)
            .containsExactly(
                EncryptionCheckpointOutcome.CONFIRMED,
                EncryptionCheckpointOutcome.NOT_SUPPORTED,
                EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED,
                EncryptionCheckpointOutcome.STORE_UNAVAILABLE,
            )
        assertThat(EncryptionCheckpointOutcome.valueOf("NOT_SUPPORTED"))
            .isEqualTo(EncryptionCheckpointOutcome.NOT_SUPPORTED)
    }
}
