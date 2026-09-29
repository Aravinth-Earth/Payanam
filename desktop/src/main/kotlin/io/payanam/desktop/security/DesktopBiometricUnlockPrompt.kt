//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import io.payanam.desktop.DesktopPassphraseActionResult
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.BiometricAuthentication
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricRefusalReason
import io.payanam.shared.security.BiometricUnlockPrompt
import io.payanam.shared.security.BiometricUnavailableReason

/**
 * The desktop's [BiometricUnlockPrompt]: a logged refusal that reports itself unavailable.
 *
 * WHY A REFUSAL AND NOT A NO-OP THAT SUCCEEDS: this is the failure the project forbids outright. A no-op
 * that returned a passphrase would let a shared screen render "unlocked" over a database nobody opened — the
 * UI's appearance of success would be the whole of the evidence, and the user would find out by losing
 * something. So this platform says it cannot do it, in the log and in the returned value, and the shared
 * layer falls back to typing a passphrase.
 *
 * WHY IT LIVES WITH THE OTHER UNSUPPORTED PORTS RATHER THAN IN ITS OWN FILE: [io.payanam.desktop.port.DesktopUnsupportedPorts]
 * is the established home for exactly this shape — a capability a platform does not have, implemented where
 * the platform's dependencies are, visible at one place. A second, parallel home for the same idea is how a
 * reader stops trusting either.
 *
 * WHY [BiometricUnavailableReason.UNKNOWN] AND NOT [BiometricUnavailableReason.NO_HARDWARE]: the desktop has
 * no sensor to enumerate, so it cannot report an inventory result it never obtained. `NO_HARDWARE` claims a
 * survey happened; `UNKNOWN` says the platform cannot answer, which is what is true, and the shared copy that
 * renders it already handles a reason it cannot explain.
 */
internal object DesktopBiometricUnlockPrompt : BiometricUnlockPrompt {
    private val logger = AppLogger.current

    override fun availability(): BiometricAvailability {
        logger.d(
            "DesktopBiometricUnlockPrompt.availability",
            "Biometric unlock is not available on this platform: a desktop session has no OS identity check",
        )
        return BiometricAvailability.Unavailable(BiometricUnavailableReason.UNKNOWN)
    }

    /**
     * Refuses rather than returning a passphrase, and says so in the log.
     *
     * Unreachable in practice — [availability] already refuses, and a shared caller reads it first — but it
     * is implemented rather than left to throw, because a port method that throws is a crash a caller must
     * defend against and this one has a real, honest answer.
     */
    override suspend fun requestAuthentication(): BiometricAuthentication {
        logger.w(
            "DesktopBiometricUnlockPrompt.requestAuthentication",
            "Biometric unlock refused: this platform has no OS identity check, so nothing was authenticated",
        )
        return BiometricAuthentication.Refused(BiometricRefusalReason.FAILED)
    }
}
