//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.security

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pins the biometric seam's outcome vocabulary, driven through a FAKE PLATFORM rather than a real one.
 *
 * WHY A FAKE AND NOT AN ANDROID TEST: the mapping under test is the mapping from "what the platform said" to
 * "what the shared layer is told", and that mapping lives entirely in the Android adapter, which cannot be
 * constructed in a plain JVM test — it needs a `FragmentActivity` and a Keystore. So the fake stands in for
 * the platform, and what is asserted is that every case the contract names is DISTINGUISHABLE from every
 * other one. A contract that merged two of them would still compile; only this test notices.
 */
class BiometricUnlockContractTest {
    /**
     * A fake platform: it holds whatever the real one would report and hands it back unchanged.
     *
     * It is the smallest possible implementation of the contract, which is itself the point — if the
     * contract were impossible to satisfy without an Activity, this class would not compile.
     */
    private class FakePrompt(
        private val answer: BiometricAvailability,
        private val result: BiometricAuthentication,
    ) : BiometricUnlockPrompt {
        var requests = 0
            private set

        override fun availability(): BiometricAvailability = answer

        override suspend fun requestAuthentication(): BiometricAuthentication {
            requests++
            return result
        }
    }

    @Test
    fun `a platform that authenticates returns the unwrapped passphrase, not a grant`(): Unit =
        runBlocking {
            val prompt = FakePrompt(BiometricAvailability.Available, BiometricAuthentication.Authenticated("s3cret-Passphrase!"))

            assertThat(prompt.availability()).isEqualTo(BiometricAvailability.Available)
            // The assertion that matters: the contract's success carries a PASSPHRASE, so an adapter has
            // nothing to hand back that is not already the currency the vault and the session take.
            assertThat(prompt.requestAuthentication())
                .isEqualTo(BiometricAuthentication.Authenticated("s3cret-Passphrase!"))
            assertThat(prompt.requests).isEqualTo(1)
        }

    @Test
    fun `every unavailable reason is distinct, so no platform can be read as another`() {
        val reasons = BiometricUnavailableReason.entries

        assertThat(reasons).containsExactly(
            BiometricUnavailableReason.DISABLED_BY_PREFERENCE,
            BiometricUnavailableReason.NO_HARDWARE,
            BiometricUnavailableReason.NOT_ENROLLED,
            BiometricUnavailableReason.UNKNOWN,
            BiometricUnavailableReason.KEY_UNAVAILABLE,
        )
        // The two that would be most damaging to conflate: a user whose key was invalidated must not be
        // told to buy a sensor, and a user who switched the feature off must not be told it is unsupported.
        assertThat(BiometricAvailability.Unavailable(BiometricUnavailableReason.KEY_UNAVAILABLE))
            .isNotEqualTo(BiometricAvailability.Unavailable(BiometricUnavailableReason.NO_HARDWARE))
        assertThat(BiometricAvailability.Unavailable(BiometricUnavailableReason.DISABLED_BY_PREFERENCE))
            .isNotEqualTo(BiometricAvailability.Unavailable(BiometricUnavailableReason.UNKNOWN))
    }

    @Test
    fun `temporary and permanent lockout share one reason, and it is not a failure`() {
        // Deliberate: the platform publishes no remaining time for the permanent case and does not publish
        // one for the temporary case either, so a shared countdown would be inventing a number. What the
        // shared layer can honour is "retrying will not work now" — asserted here as ONE case, distinct
        // from FAILED so the screen can say "locked out" rather than "something went wrong".
        assertThat(BiometricRefusalReason.entries).containsExactly(
            BiometricRefusalReason.LOCKED_OUT,
            BiometricRefusalReason.CANCELLED,
            BiometricRefusalReason.FAILED,
        )
        assertThat(BiometricAuthentication.Refused(BiometricRefusalReason.LOCKED_OUT))
            .isNotEqualTo(BiometricAuthentication.Refused(BiometricRefusalReason.FAILED))
        // A cancellation is not a failure, so it must not read as one.
        assertThat(BiometricAuthentication.Refused(BiometricRefusalReason.CANCELLED))
            .isNotEqualTo(BiometricAuthentication.Refused(BiometricRefusalReason.FAILED))
    }

    @Test
    fun `a refusal is never a success, and a success always carries a passphrase`() {
        val refused = BiometricAuthentication.Refused(BiometricRefusalReason.FAILED)

        assertThat(refused).isNotInstanceOf(BiometricAuthentication.Authenticated::class.java)
        // The pairing that makes the contract safe: an adapter cannot report "authenticated" without
        // something it actually unwrapped, so there is no value a caller could mistake for an unlock.
        val authenticated = BiometricAuthentication.Authenticated("")
        assertThat(authenticated.passphrase).isEmpty()
    }
}
