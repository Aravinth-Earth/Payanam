//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.SharedPassphraseLockoutPolicy
import io.payanam.shared.security.SharedPassphrasePolicy
import io.payanam.shared.security.VaultAvailability

/**
 * The Android side of the passphrase-vault contract, over [DatabaseEncryptionManager].
 *
 * Like its desktop counterpart, the adapter exists because the platform type is richer than the contract:
 * Android also tracks biometric wrapping and an unlock lockout, which the contract does not model. What the
 * contract DOES model is the distinction that matters to every caller — configured versus unreadable — and
 * Android's manager has no equivalent of the desktop's "present but blank" state, so its availability maps
 * to [VaultAvailability.AVAILABLE] unconditionally. That asymmetry is real and recorded rather than papered
 * over: Android's preferences cannot present as unreadable, so it has nothing to report.
 *
 * Validity is decided by the SHARED policy before storage is touched, so a passphrase rejected here is
 * rejected the same way on the desktop.
 *
 * WHY THE LOCKOUT MEMBERS ARE HERE AND NOT A NEW PORT: the attempt counter and the lockout timestamp are
 * fields of the same preferences blob that holds the verifier, and the thing that applies the escalation is
 * the thing that stores the count. The thresholds come from
 * [io.payanam.shared.security.SharedPassphraseLockoutPolicy] — the SAME object the desktop store uses — so
 * the two platforms cannot disagree about what attempt 3 costs. Android's manager has a private
 * `PassphraseLockoutPolicy` copy of that table; [AndroidVaultLockoutParityTest] is what keeps the two from
 * drifting, since deleting the copy is a change to a class this fence does not cover.
 */
class AndroidPassphraseVault(
    context: Context,
    private val encryptionManager: DatabaseEncryptionManager = DatabaseEncryptionManager(context),
) : PassphraseVault {
    override fun hasPassphraseConfigured(): Boolean = encryptionManager.hasPassphraseConfigured()

    /**
     * Android has no unreadable-vault state: a missing or corrupt preferences file reads as a fresh
     * install, which is a legitimate configured-or-not answer rather than an unknown.
     */
    override fun availability(): VaultAvailability = VaultAvailability.AVAILABLE

    override fun verifyPassphrase(passphrase: CharArray): Boolean =
        encryptionManager.verifyPassphrase(passphrase.concatToString())

    override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome {
        if (!SharedPassphrasePolicy.validate(passphrase.concatToString()).isValid) {
            return PassphraseConfigureOutcome.REJECTED_BY_POLICY
        }
        return if (encryptionManager.configurePassphrase(passphrase.concatToString())) {
            PassphraseConfigureOutcome.CONFIGURED
        } else {
            PassphraseConfigureOutcome.STORE_UNAVAILABLE
        }
    }

    /**
     * Replaces the passphrase, and says which of the four things went wrong.
     *
     * The manager's boolean is unpacked here, against the checks the contract can distinguish, in the order
     * that makes each answer unambiguous: the new passphrase is judged against the SHARED policy first (so a
     * rejected passphrase is never compared against the old one and never consumes a failure), then the
     * manager verifies the current one, and only then does a `false` mean the store itself failed. A
     * `false` after both checks is a write that did not stick, which is the one case where the original
     * passphrase might no longer work — so it is reported as [PassphraseUpdateOutcome.STORE_UNAVAILABLE]
     * rather than folded into "wrong passphrase", which would tell the user to retype a passphrase that is
     * still correct.
     */
    override fun updatePassphrase(
        current: CharArray,
        new: CharArray,
    ): PassphraseUpdateOutcome {
        if (!SharedPassphrasePolicy.validate(new.concatToString()).isValid) {
            return PassphraseUpdateOutcome.REJECTED_BY_POLICY
        }
        if (!encryptionManager.verifyPassphrase(current.concatToString())) {
            return PassphraseUpdateOutcome.CURRENT_PASSPHRASE_WRONG
        }
        return if (encryptionManager.updatePassphrase(current.concatToString(), new.concatToString())) {
            PassphraseUpdateOutcome.UPDATED
        } else {
            PassphraseUpdateOutcome.STORE_UNAVAILABLE
        }
    }

    override fun isEncryptionEnabled(): Boolean = encryptionManager.isEncryptionEnabled()

    /** Reads the persisted lockout-until timestamp; the counter survives a process restart. */
    override fun unlockLockoutSecondsRemaining(): Long = encryptionManager.getUnlockRemainingSeconds()

    /**
     * Bumps the persisted attempt counter and returns the delay the shared policy assigns to it.
     *
     * The manager's own table is authoritative here rather than re-derived: it is the one that wrote the
     * timestamp this method's sibling reads back, and a caller that recomputed the delay could disagree with
     * the countdown already on screen.
     */
    override fun recordFailedUnlockAttempt(): Long = encryptionManager.recordFailedUnlockAttempt()

    override fun resetUnlockAttempts() = encryptionManager.resetUnlockAttempts()

    /** Clears the unlock credential. The database file is untouched, as on the desktop. */
    override fun clearPassphrase() {
        encryptionManager.resetEncryptionState()
    }
}

/**
 * Pins the Android lockout table to the shared one, and reports the shared table's own thresholds.
 *
 * WHY THIS EXISTS AS CODE RATHER THAN A TEST: the shared policy is the cross-platform authority, and the
 * Android copy is the one a future change to the shared file would silently stop consulting. [parityIsHeld]
 * is the assertion in a form a caller can check, and the constant is the schedule the shared policy
 * documents — attempt 3 costs 30 seconds, attempt 6 costs 300.
 */
object AndroidVaultLockoutParity {
    /** Attempt 3, and the delay the shared policy assigns to it. */
    const val THIRD_ATTEMPT_SECONDS = 30L

    /** Attempt 6, and the delay the shared policy assigns to it. */
    const val SIXTH_ATTEMPT_SECONDS = 300L

    /**
     * Whether Android's table and the shared table agree on every attempt from 1 to [lastAttempt].
     *
     * Called by the parity test; exposed so the check is readable at the call site rather than buried in a
     * test file.
     */
    fun parityIsHeld(lastAttempt: Int = 12): Boolean =
        (1..lastAttempt).all { attempt ->
            PassphraseLockoutPolicy.delaySecondsForAttempt(attempt) ==
                SharedPassphraseLockoutPolicy.delaySecondsForAttempt(attempt)
        }
}
