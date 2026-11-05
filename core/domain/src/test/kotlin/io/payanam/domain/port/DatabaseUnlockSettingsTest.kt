//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The shape of the unlock settings a screen renders, asserted apart from any implementation.
 *
 * Two behaviours carry this port and are asserted here:
 * - the timeout a caller READS BACK after writing is the one the platform will honour, so a screen
 *   cannot render a number that was clamped away from under it;
 * - biometric unlock never reports enabled without material behind it, and disabling it is the only
 *   member allowed to report that it could not finish.
 *
 * The fake models a platform that clamps to a range and that keeps material and preference in step —
 * the two behaviours the KDoc states a caller may assume.
 */
class DatabaseUnlockSettingsTest {
    /** A platform that clamps its timeout into [min]..[max] and gates biometrics on material. */
    private class ClampingPlatform(
        private val min: Int = 1,
        private val max: Int = 240,
        initialTimeout: Int = 10,
        private val hasBiometricHardware: Boolean = true,
    ) : DatabaseUnlockSettings {
        var storedTimeout: Int = initialTimeout.coerceIn(min, max)
            private set
        var biometricPreference: Boolean = false
            private set
        var biometricMaterialPresent: Boolean = false
        var teardownShouldFail: Boolean = false

        override fun sessionTimeoutMinutes(): Int = storedTimeout.coerceIn(min, max)

        override fun setSessionTimeoutMinutes(minutes: Int): Int {
            storedTimeout = minutes.coerceIn(min, max)
            return storedTimeout
        }

        override fun isBiometricUnlockEnabled(): Boolean = biometricPreference && biometricMaterialPresent

        override fun enableBiometricUnlock() {
            if (!hasBiometricHardware) return
            biometricMaterialPresent = true
            biometricPreference = true
        }

        override fun disableBiometricUnlock(): Boolean {
            if (teardownShouldFail) return false
            biometricMaterialPresent = false
            biometricPreference = false
            return true
        }
    }

    @Test
    fun `a timeout below the floor is clamped and the effective value is returned`() {
        val platform = ClampingPlatform(min = 1, max = 240)

        val effective = platform.setSessionTimeoutMinutes(0)

        assertThat(effective).isEqualTo(1)
        assertThat(platform.sessionTimeoutMinutes()).isEqualTo(1)
    }

    @Test
    fun `a timeout above the ceiling is clamped and the effective value is returned`() {
        val platform = ClampingPlatform(min = 1, max = 240)

        val effective = platform.setSessionTimeoutMinutes(9_999)

        assertThat(effective).isEqualTo(240)
        assertThat(platform.sessionTimeoutMinutes()).isEqualTo(240)
    }

    @Test
    fun `a timeout inside the range is stored unchanged`() {
        val platform = ClampingPlatform(min = 1, max = 240)

        val effective = platform.setSessionTimeoutMinutes(45)

        assertThat(effective).isEqualTo(45)
        assertThat(platform.sessionTimeoutMinutes()).isEqualTo(45)
    }

    @Test
    fun `the value read back is always one the platform will honour`() {
        val platform = ClampingPlatform(min = 5, max = 60, initialTimeout = 10)

        platform.setSessionTimeoutMinutes(-1)
        val afterTooSmall = platform.sessionTimeoutMinutes()

        platform.setSessionTimeoutMinutes(61)
        val afterTooLarge = platform.sessionTimeoutMinutes()

        assertThat(afterTooSmall).isEqualTo(5)
        assertThat(afterTooLarge).isEqualTo(60)
    }

    @Test
    fun `biometric unlock is not enabled while the preference is off`() {
        val platform = ClampingPlatform()
        platform.biometricMaterialPresent = true

        assertThat(platform.isBiometricUnlockEnabled()).isFalse()
    }

    @Test
    fun `biometric unlock is not enabled when the preference outlived its material`() {
        val platform = ClampingPlatform()
        platform.enableBiometricUnlock()
        platform.biometricMaterialPresent = false

        assertThat(platform.biometricPreference).isTrue()
        assertThat(platform.isBiometricUnlockEnabled()).isFalse()
    }

    @Test
    fun `enabling requires material and never reports enabled without it`() {
        val platform = ClampingPlatform(hasBiometricHardware = true)

        platform.enableBiometricUnlock()

        assertThat(platform.isBiometricUnlockEnabled()).isTrue()
        assertThat(platform.biometricMaterialPresent).isTrue()
    }

    @Test
    fun `a platform with no biometric hardware leaves the preference off`() {
        val platform = ClampingPlatform(hasBiometricHardware = false)

        platform.enableBiometricUnlock()

        assertThat(platform.isBiometricUnlockEnabled()).isFalse()
        assertThat(platform.biometricPreference).isFalse()
    }

    @Test
    fun `a completed teardown clears both the material and the preference`() {
        val platform = ClampingPlatform()
        platform.enableBiometricUnlock()

        val tornDown = platform.disableBiometricUnlock()

        assertThat(tornDown).isTrue()
        assertThat(platform.biometricMaterialPresent).isFalse()
        assertThat(platform.isBiometricUnlockEnabled()).isFalse()
    }

    @Test
    fun `a teardown the platform could not confirm reports false`() {
        val platform = ClampingPlatform()
        platform.enableBiometricUnlock()
        platform.teardownShouldFail = true

        val tornDown = platform.disableBiometricUnlock()

        assertThat(tornDown).isFalse()
    }
}