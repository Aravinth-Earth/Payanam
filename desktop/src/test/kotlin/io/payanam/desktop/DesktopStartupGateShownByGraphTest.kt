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
    fun `the shell is mounted for every mode, gates included, now that the graph owns them all`() {
        val shellModes = DesktopStartupMode.entries.filter(::desktopStartupGateShownByGraph).toSet()

        assertThat(shellModes)
            .containsExactly(
                DesktopStartupMode.SetupPassphrase,
                DesktopStartupMode.UnlockPassphrase,
                DesktopStartupMode.InitializeDatabase,
                DesktopStartupMode.FocusModeSelection,
                DesktopStartupMode.Ready,
                DesktopStartupMode.OnboardingIntro,
                DesktopStartupMode.SecurityStateUnavailable,
            )
    }

    @Test
    fun `every mode is graph-owned now, so the desktop's own surface has no mode to render`() {
        val twinModes = DesktopStartupMode.entries.filterNot(::desktopStartupGateShownByGraph).toSet()

        // `OnboardingDimensionSetup` was the fourth, and the mode itself is gone: the desktop's own
        // dimension card was its only renderer, and nothing ever resolved to that mode - the resolver
        // sends `DatabaseChoice` and `DimensionSetup` to the SHARED `InitializeDatabase` destination, so
        // the first-run dimension step is the shared screen. The database-choice MODE is gone the same way:
        // the resolver sends the DatabaseChoice STAGE to the shared InitializeDatabase destination, so
        // nothing ever resolved to that mode either. What is left here is the intro (its own screen) and
        // the fail-closed security refusal.
        // The tour and the refusal were the last two, and both are graph destinations now: the graph
        // renders the shared tour screen and the shared refusal screen, so the desktop's own surface is
        // gone and this set is empty. The assertion is kept as an emptiness check rather than deleted:
        // it is the thing that fails the day a new mode is added without a destination.
        assertThat(twinModes).isEmpty()
    }

    @Test
    fun `every mode is decided, so a new mode cannot fall through to no gate`() {
        // The two cases above list the mode sets in full; this pins WHY that is safe - `entries` is every
        // mode and the predicate answers for each, so adding a mode without deciding fails the first case
        // rather than silently mounting nothing.
        assertThat(DesktopStartupMode.entries.size).isEqualTo(7)
    }
}
