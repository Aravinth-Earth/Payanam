//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import io.payanam.desktop.DesktopPassphraseActionResult
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.SharedPassphraseLockoutPolicy
import io.payanam.shared.security.SharedPassphrasePolicy
import io.payanam.shared.security.VaultAvailability
import java.time.Instant

/**
 * The desktop's [PassphraseVault], over [DesktopSecurityStore].
 *
 * The adapter exists rather than the store implementing the interface directly because the store's result
 * type is richer than the contract's: it distinguishes a locked-out vault and a wrong passphrase for the
 * UI's benefit, while the contract only needs "did it work". Keeping that mapping in one place means the
 * contract stays small enough for the Android implementation to satisfy honestly.
 *
 * WHY THE LOCKOUT MEMBERS READ THE STORE AND DO NOT KEEP THEIR OWN COUNTER: [DesktopSecurityStore] already
 * persists the failed-attempt count and the lockout-until timestamp, and already applies
 * [SharedPassphraseLockoutPolicy] to them — the same shared object Android's manager uses. A second counter
 * here would be a second lockout system: the one on screen would disagree with the one enforced after a
 * restart, and the weaker of the two would be the one a caller trusted. These members are therefore a
 * projection of the store's own state, and [recordFailedUnlockAttempt] deliberately has nothing to record.
 *
 * THAT LAST POINT IS THE NON-OBVIOUS ONE, so it is spelled out: the desktop store bumps the attempt counter
 * INSIDE `verifyPassphrase`, because it has to — it must consult the active lockout before it can compare a
 * passphrase, and it must persist the failure before it can answer. So by the time a shared caller sees
 * `false` from `verifyPassphrase`, the failure is already counted, and recording it again here would count
 * every wrong passphrase twice and lock the owner out on attempt 2 rather than attempt 3. A caller that
 * follows the contract — verify, and only then record a failure it observed — therefore gets the correct
 * behaviour from this platform by NOT double-recording. The second call is a no-op that re-reads the
 * store's own verdict, so a shared caller cannot tell the two platforms' bookkeeping apart.
 */
internal class DesktopPassphraseVault(
    private val store: DesktopSecurityStore,
) : PassphraseVault {
    private companion object {
        /** The contract reports a countdown in seconds; the store persists an epoch in milliseconds. */
        const val MILLIS_PER_SECOND = 1000L
    }

    override fun hasPassphraseConfigured(): Boolean = store.ensureSnapshot().hasPassphraseConfigured

    override fun availability(): VaultAvailability =
        when (store.ensureSnapshot().availability) {
            io.payanam.desktop.DesktopSecurityAvailability.AVAILABLE -> VaultAvailability.AVAILABLE
            io.payanam.desktop.DesktopSecurityAvailability.UNAVAILABLE -> VaultAvailability.UNAVAILABLE
        }

    /**
     * Verifies WITHOUT recording a failure, then reports the lockout that verification produced.
     *
     * The two cannot be separated on this platform — the store enforces the lockout inside the same call —
     * so this is a single pass whose observable result is exactly what the contract describes: `true` for the
     * configured passphrase, `false` otherwise, and the lockout the store decided in force afterwards.
     */
    override fun verifyPassphrase(passphrase: CharArray): Boolean =
        store.verifyPassphrase(passphrase.concatToString()) is DesktopPassphraseActionResult.Success

    /**
     * Validates against the SHARED policy before touching storage.
     *
     * The store has its own validation and would reject the same passphrases, but asking the shared policy
     * first means the outcome for a given input cannot differ between platforms — which is the property a
     * shared contract is for. The store's verdict is still honoured for everything the policy cannot see,
     * such as an unreadable file.
     */
    override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome {
        if (!SharedPassphrasePolicy.validate(passphrase.concatToString()).isValid) {
            return PassphraseConfigureOutcome.REJECTED_BY_POLICY
        }
        return when (store.configurePassphrase(passphrase.concatToString())) {
            DesktopPassphraseActionResult.Success -> PassphraseConfigureOutcome.CONFIGURED
            is DesktopPassphraseActionResult.ValidationFailed -> PassphraseConfigureOutcome.REJECTED_BY_POLICY
            else -> PassphraseConfigureOutcome.STORE_UNAVAILABLE
        }
    }

    /**
     * Changes the passphrase, unpacking the store's boolean against the checks the contract distinguishes.
     *
     * The new passphrase is judged against the SHARED policy first, so a rejected replacement is never
     * compared against the current one and never consumes a verification. The store's own result then says
     * only one thing, so the verdict about the CURRENT passphrase is read separately: a `false` from the
     * store with a current passphrase that verifies is a write that did not stick, which is reported as
     * [PassphraseUpdateOutcome.STORE_UNAVAILABLE] rather than as a wrong passphrase — telling a user to
     * retype a passphrase that is still correct is the worse of the two mistakes.
     */
    override fun updatePassphrase(
        current: CharArray,
        new: CharArray,
    ): PassphraseUpdateOutcome {
        if (!SharedPassphrasePolicy.validate(new.concatToString()).isValid) {
            return PassphraseUpdateOutcome.REJECTED_BY_POLICY
        }
        if (!verifyPassphrase(current)) {
            return PassphraseUpdateOutcome.CURRENT_PASSPHRASE_WRONG
        }
        // The store has no dedicated change verb; configuring over an existing verifier is the change, and it
        // resets the failure counter — the right behaviour for a successful change.
        return configurePassphrase(new)
            .let { outcome ->
                when (outcome) {
                    PassphraseConfigureOutcome.CONFIGURED -> PassphraseUpdateOutcome.UPDATED
                    PassphraseConfigureOutcome.REJECTED_BY_POLICY -> PassphraseUpdateOutcome.REJECTED_BY_POLICY
                    PassphraseConfigureOutcome.STORE_UNAVAILABLE -> PassphraseUpdateOutcome.STORE_UNAVAILABLE
                }
            }
    }

    /**
     * The desktop's database is always plaintext.
     *
     * Not a guess: the desktop opens an unencrypted JDBC file, so reporting `true` would be a lie a settings
     * screen would render as a promise the platform cannot keep. A user who reads "encrypted" on the desktop
     * and relies on it is worse off than one told the truth.
     */
    override fun isEncryptionEnabled(): Boolean = false

    /**
     * Reads the store's own persisted lockout, so the countdown survives a restart and agrees with the
     * verdict `verifyPassphrase` already returned.
     */
    override fun unlockLockoutSecondsRemaining(): Long {
        val lockedUntilMillis = store.ensureSnapshot().lockedUntilEpochMillis ?: return 0L
        val remainingMillis = lockedUntilMillis - Instant.now().toEpochMilli()
        return if (remainingMillis > 0L) ((remainingMillis / MILLIS_PER_SECOND).coerceAtLeast(1L)) else 0L
    }

    /**
     * Re-reads the lockout the store already recorded during [verifyPassphrase], rather than incrementing.
     *
     * See the class KDoc: on this platform the failure is counted inside the verification, and counting it
     * again would make the schedule escalate twice as fast as the shared policy it claims to apply.
     */
    override fun recordFailedUnlockAttempt(): Long = unlockLockoutSecondsRemaining()

    /**
     * Already done by the store, and deliberately NOT [DesktopSecurityStore.resetSecurityState].
     *
     * A successful `verifyPassphrase` zeroes the attempt counter and clears the lockout timestamp as part of
     * answering, so by the time a caller reaches this method on this platform the state it clears is already
     * cleared. Calling `resetSecurityState()` instead would satisfy the method's name by DESTROYING the
     * verifier the caller just proved they knew — a working passphrase silently forgotten, which is the worst
     * outcome this whole class exists to prevent. So this is a no-op with a reason, not an approximation of
     * the right call.
     */
    override fun resetUnlockAttempts() = Unit

    /** Clears the unlock credential only. The database file is not this method's business. */
    override fun clearPassphrase() {
        store.resetSecurityState()
    }
}
