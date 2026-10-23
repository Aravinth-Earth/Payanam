//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import android.content.Context
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.SharedPassphraseLockoutPolicy
import io.payanam.shared.security.SharedPassphrasePolicy
import io.payanam.shared.security.VaultAvailability
import java.io.File

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
 * `PassphraseLockoutPolicy` copy of that table; [AndroidVaultLockoutParity] is what keeps the two from
 * drifting, since deleting the copy is a change to a class this fence does not cover.
 *
 * AND WHY [verifyPassphrase] COUNTS ITS OWN FAILURES: the desktop store counts inside its verification,
 * because it must consult the lockout before it can compare a passphrase. The manager now does the same,
 * which is what makes [recordFailedUnlockAttempt] a re-read here rather than an increment — the asymmetry
 * this class previously carried meant the shared policy's schedule was enforced on one platform and not
 * the other.
 */
class AndroidPassphraseVault(
    context: Context,
    private val encryptionManager: DatabaseEncryptionManager = DatabaseEncryptionManager(context),
    /**
     * The probe and the file it should read, so the encryption answer comes from the FILE rather than from
     * the stored mode flag - the same shape the desktop vault already has. The production vault is built
     * with both (the probe from the Hilt graph, the path from the context); the defaults exist for the test
     * constructions that are about the store's lockout or refusal behaviour and have no file to answer
     * about - for those, "no file to detect" is reported as not encrypted, which is the honest answer and
     * the same one a plaintext file gets.
     */
    private val encryptionProbe: DatabaseEncryptionProbe? = null,
    private val databaseFile: () -> File? = { context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME) },
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

    /**
     * Whether the database file is detectably encrypted - answered by the FILE, not by the stored mode.
     *
     * The mode flag answers a different question ("which mode is this installation in"), and it was the
     * wrong one to put behind this member: the member's callers ask about the bytes they are about to act
     * on. The answer now comes from the same probe every other file question on this platform uses, so the
     * two platforms cannot disagree about what "encrypted" means, and a file that is not detectably
     * encrypted still reads `false` whatever the flag says.
     */
    override fun isEncryptionEnabled(): Boolean {
        val file = databaseFile() ?: return false
        return encryptionProbe?.isDetectablyEncrypted(file, "AndroidPassphraseVault.isEncryptionEnabled") ?: false
    }

    /** Reads the persisted lockout-until timestamp; the counter survives a process restart. */
    override fun unlockLockoutSecondsRemaining(): Long = encryptionManager.getUnlockRemainingSeconds()

    /**
     * Re-reads the lockout the manager already recorded during [verifyPassphrase], rather than
     * incrementing a second time.
     *
     * Symmetric with the desktop vault, and for the same reason: [verifyPassphrase] counts a wrong
     * passphrase itself, so a caller that follows the contract - verify, and only then record a failure
     * it observed - would otherwise count every wrong passphrase twice and lock the owner out on attempt
     * 2 instead of the attempt 3 the shared policy documents. The contract's rule that a failure MUST be
     * recorded is still satisfied here, because it already was, inside the verification.
     */
    override fun recordFailedUnlockAttempt(): Long = unlockLockoutSecondsRemaining()

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
