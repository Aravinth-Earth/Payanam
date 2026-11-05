//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * The unlock STATE a settings screen reads and writes, as distinct from the unlock CREDENTIAL.
 *
 * ## What this port is for, and why it is not [io.payanam.shared.security.PassphraseVault]
 *
 * A settings screen asks four things about unlocking that are not "is this passphrase correct" or
 * "set the passphrase": how long before the session auto-locks, whether biometric unlock is on,
 * turning it on, and tearing it off. On Android those four answers live on
 * `DatabaseEncryptionManager`, which is a `SharedPreferences` and `KeyStore` owner with no shared
 * counterpart — so the settings screen could only be promoted by naming it.
 *
 * The two members it would be most tempting to fold in here are deliberately NOT here:
 * [io.payanam.shared.security.PassphraseVault.verifyPassphrase] and its
 * [io.payanam.shared.security.PassphraseVault.isEncryptionEnabled]
 * already answer for both platforms, and Android's vault implementation is already a thin delegation
 * onto the same manager. Declaring them a second time here would create a second answer to the same
 * question, and two answers to "is this passphrase right" can disagree — the one defect a credential
 * port cannot have. This port is only what the vault does not already answer.
 *
 * WHY ONE PORT FOR TIMEOUT AND BIOMETRICS: they are not obviously related, but they are the same
 * thing to a caller — "how does this device let me back in, and how fast does it forget". Splitting
 * them would give a settings screen two dependencies for one screen and two injection sites to keep
 * in step. They are not a single mechanism: the timeout is plain stored state, while biometric state
 * is a preference AND platform key material that must agree, and the two are documented separately
 * because they fail differently.
 *
 * WHAT A CALLER MAY ASSUME:
 * - [setSessionTimeoutMinutes] normalizes, and [sessionTimeoutMinutes] reports the EFFECTIVE value.
 *   A caller that writes 0 and reads back 5 has not lost a write; it has been clamped, on both
 *   platforms, so the number a screen renders is the number the platform will honour.
 * - [isBiometricUnlockEnabled] is the honest answer on its own: a preference that survived while the
 *   material it refers to did not reports FALSE, never true-with-nothing-behind-it.
 * - A desktop that has no biometric hardware answers [isBiometricUnlockEnabled] `false` and treats
 *   [enableBiometricUnlock] as a no-op, rather than inventing a wrapping scheme to report true.
 */
interface DatabaseUnlockSettings {
    /**
     * The effective auto-lock timeout in minutes — the value the platform will actually apply.
     *
     * Reads the NORMALIZED value, not the raw stored one, so a screen cannot render a number the
     * platform has already decided against.
     */
    fun sessionTimeoutMinutes(): Int

    /**
     * Stores the auto-lock timeout as [minutes] and returns the value that was actually stored.
     *
     * WHY IT RETURNS THE VALUE: clamping is invisible to the caller otherwise. The current settings
     * flow has to read the timeout back immediately after writing it, purely to learn what the clamp
     * did, and then shows that second number to the user — the write is not complete until it returns.
     * Returning the effective value makes the read-after-write unnecessary to express.
     */
    fun setSessionTimeoutMinutes(minutes: Int): Int

    /**
     * True only when biometric unlock is switched on AND the platform still holds the material it
     * needs to honour that.
     *
     * The conjunction is the point. A preference flag alone is a claim, and a claim whose material is
     * gone produces a screen offering a button that cannot work.
     */
    fun isBiometricUnlockEnabled(): Boolean

    /**
     * Switches biometric unlock ON, after the platform has stored what it needs to unlock later.
     *
     * Takes no argument on purpose: the one call site invokes this immediately after a successful
     * enrollment, so "switch it on" and "the material is in place" are the same moment. Turning it OFF
     * is [disableBiometricUnlock], which is a separate member because it destroys material and can
     * fail — a caller must be able to see that failure instead of routing it through a boolean.
     *
     * An implementation that cannot store the material MUST NOT report success. It leaves the
     * preference off, so [isBiometricUnlockEnabled] continues to answer honestly.
     */
    fun enableBiometricUnlock()

    /**
     * Removes biometric unlock entirely — the stored material and the preference that refers to it.
     *
     * @return `true` when the teardown completed, `false` when the platform could not confirm it.
     *
     * WHY A BOOLEAN AND NOT "nothing": `false` means the platform cannot promise the material is gone,
     * which is not the same as "biometric unlock is still on" and is not a reason to re-enable
     * anything. A caller is expected to record the difference and leave the preference off either
     * way, so a false never resurrects a control the user just turned off.
     */
    fun disableBiometricUnlock(): Boolean
}
