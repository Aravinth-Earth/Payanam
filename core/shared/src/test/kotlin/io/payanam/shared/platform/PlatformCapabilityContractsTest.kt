//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the capability and debug/haptics contracts.
 *
 * [PlatformCapabilities.hasLimitations] is the one piece of behaviour here, and it is asserted in both
 * directions: a fully capable platform must report no limitations (so the UI does not explain a difference
 * that does not exist), and EACH individual missing capability must turn it on (so a new capability cannot
 * be added without deciding what it means). The all-capable baseline matters more than it looks — a
 * `hasLimitations` that was always true would pass a one-directional test and explain nothing.
 */
class PlatformCapabilityContractsTest {
    private val capable =
        PlatformCapabilities(
            biometrics = SupportState.SUPPORTED,
            supportsDynamicColor = true,
            supportsHaptics = true,
            reminders = SupportState.SUPPORTED,
            backupBackground = SupportState.SUPPORTED,
        )

    private class FakeDebugFlags(
        override var debugLoggingEnabled: Boolean = false,
    ) : DebugFlags {
        override fun setDebugLoggingEnabled(enabled: Boolean): Boolean {
            debugLoggingEnabled = enabled
            return debugLoggingEnabled
        }
    }

    @Test
    fun `a fully capable platform reports no limitations`() {
        assertThat(capable.hasLimitations).isFalse()
        assertThat(capable.offersBiometricControls).isTrue()
    }

    @Test
    fun `each missing capability on its own turns limitations on`() {
        assertThat(capable.copy(biometrics = SupportState.NOT_APPLICABLE).hasLimitations).isTrue()
        assertThat(capable.copy(supportsDynamicColor = false).hasLimitations).isTrue()
        assertThat(capable.copy(supportsHaptics = false).hasLimitations).isTrue()
        assertThat(capable.copy(reminders = SupportState.NOT_SURFACED).hasLimitations).isTrue()
        assertThat(capable.copy(backupBackground = SupportState.IN_PROCESS_ONLY).hasLimitations).isTrue()
    }

    @Test
    fun `the defaults are the least-capable platform, so an unstated capability is not claimed`() {
        val defaults = PlatformCapabilities()

        // A platform configures what it HAS. Defaulting to capable would let a missing capability read as
        // present, which is how a settings screen ends up offering biometrics that cannot work.
        assertThat(defaults.biometrics).isEqualTo(SupportState.NOT_APPLICABLE)
        assertThat(defaults.supportsDynamicColor).isFalse()
        assertThat(defaults.supportsHaptics).isFalse()
        assertThat(defaults.reminders).isEqualTo(SupportState.NOT_SURFACED)
        assertThat(defaults.backupBackground).isEqualTo(SupportState.IN_PROCESS_ONLY)
        assertThat(defaults.offersBiometricControls).isFalse()
        assertThat(defaults.hasLimitations).isTrue()
    }

    @Test
    fun `the support vocabulary is the five states shared code switches over`() {
        // Pinned by name because both platform factories and (soon) shared UI switch over these values; a
        // rename is a cross-module change, not a refactor.
        assertThat(SupportState.entries)
            .containsExactly(
                SupportState.SUPPORTED,
                SupportState.IN_PROCESS_ONLY,
                SupportState.NOT_SURFACED,
                SupportState.DECLINES,
                SupportState.NOT_APPLICABLE,
            )
        assertThat(SupportState.valueOf("DECLINES")).isEqualTo(SupportState.DECLINES)
    }

    @Test
    fun `the no-op haptic sink is honest about being inactive and performs harmlessly`() {
        assertThat(NoOpHapticSink.isActive).isFalse()

        // Never throws: a device without a vibrator is a normal configuration, and a UI that failed because
        // it could not buzz would be worse than one that stays silent.
        NoOpHapticSink.perform(HapticFeedback.TAP)
        HapticFeedback.entries.forEach { NoOpHapticSink.perform(it) }

        assertThat(HapticFeedback.entries)
            .containsExactly(
                HapticFeedback.TAP,
                HapticFeedback.SELECTION,
                HapticFeedback.CONFIRMATION,
                HapticFeedback.REJECTION,
            )
        assertThat(HapticFeedback.valueOf("CONFIRMATION")).isEqualTo(HapticFeedback.CONFIRMATION)
    }

    @Test
    fun `debug logging reports the value in force after a change`() {
        val flags = FakeDebugFlags(debugLoggingEnabled = false)

        assertThat(flags.debugLoggingEnabled).isFalse()
        assertThat(flags.setDebugLoggingEnabled(true)).isTrue()
        assertThat(flags.debugLoggingEnabled).isTrue()
        assertThat(flags.setDebugLoggingEnabled(false)).isFalse()
    }
}
