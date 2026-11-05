//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.security.BiometricAuthentication
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricRefusalReason
import io.payanam.shared.security.BiometricUnavailableReason
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pins the desktop's biometric refusal — the one that matters most in this whole seam, because the failure it
 * guards against is silent.
 *
 * The defect under test is a no-op that reports success: a shared screen would render "unlocked" over a
 * database nobody opened, and the UI's appearance would be the entire evidence until the user noticed data
 * missing. So both halves are asserted — that [DesktopBiometricUnlockPrompt.availability] refuses, and that
 * [DesktopBiometricUnlockPrompt.requestAuthentication] refuses even when it is called anyway, which is the
 * path a caller reaches if it forgets to ask first.
 */
class DesktopBiometricUnlockPromptTest {
    @Test
    fun `the desktop reports itself unavailable rather than available`() {
        val availability = DesktopBiometricUnlockPrompt.availability()

        // NOT `Available`, and specifically not a bare `false`: the shared layer renders this, so the answer
        // has to say why in a vocabulary it can put to the user.
        assertThat(availability).isInstanceOf(BiometricAvailability.Unavailable::class.java)
        assertThat((availability as BiometricAvailability.Unavailable).reason)
            .isEqualTo(BiometricUnavailableReason.UNKNOWN)
    }

    @Test
    fun `requesting authentication on the desktop refuses instead of returning a passphrase`(): Unit =
        runBlocking {
            val result = DesktopBiometricUnlockPrompt.requestAuthentication()

            assertThat(result).isEqualTo(BiometricAuthentication.Refused(BiometricRefusalReason.FAILED))
            // The specific thing that must never happen: a `Refused` carrying a passphrase, or an
            // `Authenticated` with an empty one, either of which a careless caller could treat as an unlock.
            assertThat(result).isNotInstanceOf(BiometricAuthentication.Authenticated::class.java)
        }

    @Test
    fun `the desktop's refusal is not a cancellation, so a screen can tell them apart`() {
        // A cancellation is a user's choice and a failure is a fault; a desktop that cannot do this at all is
        // neither, and folding it into CANCELLED would make the UI apologise for a capability it never had.
        val availability = DesktopBiometricUnlockPrompt.availability()

        assertThat(availability)
            .isNotEqualTo(BiometricAvailability.Unavailable(BiometricUnavailableReason.DISABLED_BY_PREFERENCE))
        assertThat(availability)
            .isNotEqualTo(BiometricAvailability.Unavailable(BiometricUnavailableReason.NO_HARDWARE))
        assertThat(availability)
            .isNotEqualTo(BiometricAvailability.Unavailable(BiometricUnavailableReason.NOT_ENROLLED))
    }
}
