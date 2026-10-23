//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

/**
 * Where a platform keeps the user's passphrase state, and how it decides whether a typed one is right.
 *
 * The two platforms keep this in genuinely different places — Android in Keystore-backed preferences, the
 * desktop in its own security store — and that difference is legitimate: it is exactly the kind of thing
 * the platform-difference register lists as one contract with two implementations. What must NOT differ is
 * what the rest of the app may ask, which is what this interface fixes.
 *
 * **A vault that cannot be read is NOT a vault with no passphrase.** The desktop learned this the hard way
 * (`DesktopSecurityAvailability`): a corrupt or unreadable store that reports "not configured" sends the
 * user to a setup screen that then overwrites the state it could not read. That is why
 * [hasPassphraseConfigured] is paired with [availability] rather than returning a bare boolean — a caller
 * must be able to tell "there is none" from "I could not find out".
 */
interface PassphraseVault {
    /**
     * Whether a passphrase has been configured.
     *
     * Meaningful only when [availability] is [VaultAvailability.AVAILABLE]. A caller that reads this
     * without checking availability is the defect this contract exists to prevent.
     */
    fun hasPassphraseConfigured(): Boolean

    /** Whether the vault could be read at all. */
    fun availability(): VaultAvailability

    /**
     * True only if [passphrase] is the configured one.
     *
     * Returning `false` for an unreadable vault is correct here and only here: a caller asking this is
     * authenticating, and refusing an unlock is the safe answer to "I could not check".
     */
    fun verifyPassphrase(passphrase: CharArray): Boolean

    /**
     * Sets the passphrase for the first time, or replaces it.
     *
     * Returns a reason rather than a boolean because the failure modes call for different copy: a
     * passphrase rejected by policy is the user's to fix, a store that could not be written is not.
     */
    fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome

    /**
     * Forgets the passphrase state.
     *
     * This clears the UNLOCK credential only. It must never touch the database file: a reset that also
     * destroyed data would be an unrecoverable operation hiding behind a routine-sounding name, and both
     * platforms keep their data-deleting reset on a separate, explicitly-named path —
     * [ForgottenPassphraseReset], named for the data it destroys.
     */
    fun clearPassphrase()

    /**
     * Whether the database this vault guards is actually encrypted at rest.
     *
     * WHY IT IS HERE RATHER THAN ELSEWHERE: encryption is configured as a user-facing MODE, not a
     * consequence of having a passphrase — a user can hold a configured passphrase while the database is
     * still plaintext, and a screen that renders the wrong one lies about the user's data. The unlock gate
     * needs it for that label, so it is asked of the object that already owns the vault's state instead of
     * growing a second port that answers one boolean.
     *
     * A caller may assume nothing about WHEN this flips relative to [hasPassphraseConfigured] — only that
     * the answer describes the database as it is now, not as it was configured.
     */
    fun isEncryptionEnabled(): Boolean

    /**
     * Replaces the passphrase, proving possession of [current] first.
     *
     * WHY THE TWO-ARGUMENT SHAPE: a change screen that asked the vault to store a new passphrase without
     * proving the old one would let anyone who reaches an unlocked device lock the owner out of their own
     * data. The proof is therefore part of the operation, not a separate call a caller may forget.
     *
     * [new] is validated against [SharedPassphrasePolicy] exactly as [configurePassphrase] validates, and
     * the same policy runs on both platforms, so a passphrase accepted here is accepted on the other one.
     *
     * The returned reason distinguishes a wrong [current] from an unwritable vault, because only the first is
     * the user's to fix. A failed change must leave the ORIGINAL passphrase working — an implementation that
     * clears the credential and then fails to write the replacement has locked the owner out.
     */
    fun updatePassphrase(
        current: CharArray,
        new: CharArray,
    ): PassphraseUpdateOutcome

    /**
     * Seconds left on the passphrase-failure lockout, or `0` when no lockout is active.
     *
     * WHY THE VAULT OWNS THIS: the escalation is a property of the CREDENTIAL, not of a screen. The
     * thresholds live in [SharedPassphraseLockoutPolicy] — attempt 3 costs 30 seconds, attempt 6 costs 300
     * — and the only honest way to enforce them across two platforms is for the thing that stores the
     * attempt count to apply them. A caller may therefore treat a non-zero answer as "this passphrase
     * cannot be tried yet", and may display it as a countdown without knowing or restating the schedule.
     *
     * The counter is durable on both platforms, so an app restart does not clear a lockout: a lockout that
     * resets on relaunch is a lockout that an attacker can clear by killing the process.
     */
    fun unlockLockoutSecondsRemaining(): Long

    /**
     * Records one failed unlock attempt and returns the lockout now in force, in seconds (`0` when the
     * failure is not yet punished).
     *
     * Called only after a failed [verifyPassphrase]. A caller MUST record a failure it observed: a refusal
     * to record one is a lockout bypass, because the next attempt is then counted as the first.
     */
    fun recordFailedUnlockAttempt(): Long

    /**
     * Clears the failed-attempt counter and any active lockout.
     *
     * Called after a successful unlock so the next failure is counted from one. It must not affect the
     * stored passphrase.
     */
    fun resetUnlockAttempts()
}

/** Why changing a passphrase did or did not take effect. */
enum class PassphraseUpdateOutcome {
    /** The new passphrase is now the configured one. */
    UPDATED,

    /** [PassphraseVault.updatePassphrase]'s `current` did not verify. The old passphrase still works. */
    CURRENT_PASSPHRASE_WRONG,

    /** The new passphrase was rejected by [SharedPassphrasePolicy]. Nothing changed. */
    REJECTED_BY_POLICY,

    /**
     * The vault could not be written, or the change could not be completed.
     *
     * The original passphrase MUST still verify after this outcome. Anything else has locked the owner out
     * of their own data over a failed save.
     */
    STORE_UNAVAILABLE,
}

/** Whether a vault could be read. */
enum class VaultAvailability {
    /** Read, and its contents are trustworthy. */
    AVAILABLE,

    /**
     * Present but unreadable, blank, or unparseable.
     *
     * Distinct from an absent vault, which is a legitimate fresh install and reports
     * [VaultAvailability.AVAILABLE] with nothing configured.
     */
    UNAVAILABLE,
}

/** Why configuring a passphrase did or did not take effect. */
enum class PassphraseConfigureOutcome {
    /** Stored. */
    CONFIGURED,

    /** Rejected by [SharedPassphrasePolicy] before anything was written. */
    REJECTED_BY_POLICY,

    /** The vault could not be written; nothing changed. */
    STORE_UNAVAILABLE,
}
