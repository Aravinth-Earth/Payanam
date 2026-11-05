//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

/**
 * The user's OS-level identity check, as the shared state layer needs it: a question, an answer, and no
 * platform types.
 *
 * WHY THIS BOUNDARY EXISTS: the unlock gate has to ask "prove it is you" before it may unwrap the stored
 * passphrase, and on Android that proof is a `BiometricPrompt` over a Keystore `Cipher` driven from a
 * `FragmentActivity`. All four of those types are unavailable to a shared module — worse, they are
 * unavailable in the place that eventually drives them. So the shared ViewModel can either name them (and
 * never move) or ask for them here. This interface is the ask.
 *
 * WHY THE ANSWER IS A PASSPHRASE AND NOT A CIPHER: the material that unwraps the passphrase is platform
 * private — it lives in the platform's Keystore, on the platform's own key, and it never becomes a value
 * the shared layer can hold. The passphrase is already the shared layer's currency: it is what
 * [PassphraseVault.verifyPassphrase] takes and what a database session is opened with, so handing it back
 * crosses no boundary that is not already crossed, and it keeps the contract to two members. A design that
 * returned an opaque grant instead would have to widen the vault contract by a member whose only purpose
 * is to be unwrapped by the adapter that minted it — a wider contract for a narrower problem.
 *
 * WHAT A CALLER MAY ASSUME:
 *  - [availability] is a question about THIS platform, not about the user's preference, and it is answered
 *    without a prompt ever appearing on screen. It is safe to call from composition.
 *  - [requestAuthentication] resolves EXACTLY ONCE, and only on a terminal event. A caller must not add a
 *    timeout of its own, because a prompt that is still on screen has not answered.
 *  - Neither method ever reports success without a real OS authentication having happened. A platform that
 *    cannot do this must return [BiometricAuthentication.Refused], never a passphrase.
 */
interface BiometricUnlockPrompt {
    /**
     * Whether this platform could show a working prompt right now, and if not, why.
     *
     * The reason is part of the answer because the user-facing copy differs: "your phone has no sensor" and
     * "you have not enrolled a fingerprint" are not the same sentence, and a platform that flattens them
     * forces one of the two to be a lie.
     */
    fun availability(): BiometricAvailability

    /**
     * Shows the platform's prompt and resolves with the unwrapped passphrase, or with the reason the
     * platform refused.
     *
     * Cancellable: if the caller's scope is cancelled the platform dialog is dismissed and the call throws
     * the cancellation, so a dismissed screen leaves no prompt behind it.
     */
    suspend fun requestAuthentication(): BiometricAuthentication
}

/** What this platform can do about biometric unlock. */
sealed interface BiometricAvailability {
    /** A prompt can be shown and is expected to succeed. */
    data object Available : BiometricAvailability

    /**
     * No prompt can be shown, for [reason].
     *
     * A sealed hierarchy rather than an enum so a platform that discovers a case this contract does not
     * name must add it here, where the shared copy that reads it has to be reviewed — the defect this
     * prevents is a platform quietly folding "key invalidated" into "no sensor" and the user being told to
     * buy hardware they already have.
     */
    data class Unavailable(
        val reason: BiometricUnavailableReason,
    ) : BiometricAvailability
}

/** Why this platform cannot show a biometric prompt. */
enum class BiometricUnavailableReason {
    /** The user has not turned biometric unlock on. Not a capability question — a preference. */
    DISABLED_BY_PREFERENCE,

    /** The device has no biometric hardware this app can use. */
    NO_HARDWARE,

    /** Hardware exists but no biometric is enrolled on it. */
    NOT_ENROLLED,

    /** The platform cannot say: the capability check returned something this contract does not name. */
    UNKNOWN,

    /**
     * The platform's key for the stored passphrase is gone — the platform's own keystore invalidated it.
     *
     * Distinct from [NO_HARDWARE] and [NOT_ENROLLED] because nothing is wrong with the sensor and nothing
     * is wrong with the user: the credential must be re-established, and telling them otherwise sends them
     * to a settings screen that cannot help.
     */
    KEY_UNAVAILABLE,
}

/** The result of asking for an OS-level identity check. */
sealed interface BiometricAuthentication {
    /**
     * The OS authenticated the user AND the platform's key unwrapped the stored passphrase.
     *
     * The two halves are one result on purpose: a platform that can authenticate but cannot unwrap has not
     * unlocked anything, and reporting the success without the passphrase would be a success the caller
     * cannot act on.
     */
    data class Authenticated(
        val passphrase: String,
    ) : BiometricAuthentication

    /** The platform refused. The unlock did not happen. */
    data class Refused(
        val reason: BiometricRefusalReason,
    ) : BiometricAuthentication
}

/** Why the platform refused to authenticate. */
enum class BiometricRefusalReason {
    /**
     * The platform has locked biometric attempts out — temporarily, or permanently until the user unlocks
     * the device by their normal credential.
     *
     * The two cases are NOT separate members on purpose, and this is the one branch of the Android callback
     * set that the shared contract deliberately cannot express: the platform reports a permanent lockout
     * with no remaining time at all, and a temporary one whose duration the platform does not publish. A
     * shared countdown would therefore show a number the platform never promised. What the shared layer can
     * honour is the single fact "retrying will not work now", and it routes the wait through the vault's
     * own passphrase lockout, which does publish a remaining time.
     */
    LOCKED_OUT,

    /** The user dismissed the prompt, or the platform cancelled it. Not a failure to report as one. */
    CANCELLED,

    /**
     * The platform's prompt could not be shown or errored — no hardware to read, a corrupted
     * authentication object, a platform-specific error code.
     */
    FAILED,
}
