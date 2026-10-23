//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the mount's own decision: which startup modes put the SHARED graph's gate in front of the user,
 * and which keep the desktop's own surface.
 *
 * Why this earns a test rather than an inline `when` in `desktopApp`: the two answers it must never confuse
 * are "this mode has a graph gate" and "this mode has no gate at all", and BOTH report no gate through
 * `DesktopNavGateFlags.showsNoGate`. A predicate written as `!showsNoGate` would therefore send a READY app
 * back to the desktop's own surface - and a mode with no graph destination would get no gate - which is
 * exactly the defect this change exists to remove. The mount is a composition decision no render test can
 * reach, so this predicate is the only place the rule can be measured.
 */
class DesktopStartupGateShownByGraphTest {
    @Test
    fun `the shell is mounted for exactly the five modes that have a graph gate or are ready`() {
        val shellModes = DesktopStartupMode.entries.filter(::desktopStartupGateShownByGraph).toSet()

        assertThat(shellModes)
            .containsExactly(
                DesktopStartupMode.SetupPassphrase,
                DesktopStartupMode.UnlockPassphrase,
                DesktopStartupMode.InitializeDatabase,
                DesktopStartupMode.FocusModeSelection,
                DesktopStartupMode.Ready,
            )
    }

    @Test
    fun `the four modes with no graph destination keep the desktop's own surface`() {
        val twinModes = DesktopStartupMode.entries.filterNot(::desktopStartupGateShownByGraph).toSet()

        assertThat(twinModes)
            .containsExactly(
                DesktopStartupMode.OnboardingIntro,
                DesktopStartupMode.OnboardingDatabaseChoice,
                DesktopStartupMode.OnboardingDimensionSetup,
                DesktopStartupMode.SecurityStateUnavailable,
            )
    }

    @Test
    fun `every mode is decided, so a new mode cannot fall through to no gate`() {
        // The two cases above list the mode sets in full; this pins WHY that is safe - `entries` is every
        // mode and the predicate answers for each, so adding a mode without deciding fails the first case
        // rather than silently mounting nothing.
        assertThat(DesktopStartupMode.entries.size).isEqualTo(9)
    }
}
