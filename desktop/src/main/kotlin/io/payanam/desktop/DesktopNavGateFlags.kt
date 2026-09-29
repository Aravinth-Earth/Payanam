//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

/**
 * How the desktop's one startup decision maps onto the shared navigation graph's gate parameters.
 *
 * WHY THIS IS A PROJECTION AND NOT A SECOND DECISION:
 *
 * The desktop resolves *which* startup surface to show in exactly one place -
 * `resolveDesktopStartupMode(runtimeState)` - and the shared graph takes that answer as four booleans.
 * The tempting implementation is to re-derive the four booleans from the runtime state here, and that
 * would be a second implementation of the same rule: two places to change when a gate is added, and a
 * window where the desktop's own gate and the graph's disagree about what the user is looking at.
 *
 * So this file only *reads* [startupMode] and projects it. It contains no `if` on security, session,
 * database or onboarding state, and it never consults [DesktopStartupRuntimeState]. The rule lives once.
 *
 * The three onboarding stages are the case that proves it. The graph has no parameters for them - they
 * are desktop-only scaffold - so they project to "no graph gate", and [desktopStartupGateSurface]
 * continues to render them until V5 replaces the scaffold. `Ready` is the only value that yields no
 * gate at all, and the graph is mounted only then.
 */
internal data class DesktopNavGateFlags(
    val shouldShowPassphraseSetup: Boolean,
    val shouldShowPassphraseUnlock: Boolean,
    val shouldShowDatabaseInit: Boolean,
    val shouldShowFocusModeOnboarding: Boolean,
) {
    /** True when the shared graph may be mounted: no gate is outstanding. */
    val showsNoGate: Boolean
        get() =
            !shouldShowPassphraseSetup &&
                !shouldShowPassphraseUnlock &&
                !shouldShowDatabaseInit &&
                !shouldShowFocusModeOnboarding
}

/**
 * Projects [startupMode] onto the shared graph's four gate flags.
 *
 * Total by construction: every [DesktopStartupMode] value names a case, so adding a mode without
 * deciding how it projects is a compile error rather than a silent "shows no gate".
 */
internal fun desktopNavGateFlags(startupMode: DesktopStartupMode): DesktopNavGateFlags =
    when (startupMode) {
        DesktopStartupMode.SetupPassphrase -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = true,
                shouldShowPassphraseUnlock = false,
                shouldShowDatabaseInit = false,
                shouldShowFocusModeOnboarding = false,
            )
        }

        DesktopStartupMode.UnlockPassphrase -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = true,
                shouldShowDatabaseInit = false,
                shouldShowFocusModeOnboarding = false,
            )
        }

        DesktopStartupMode.InitializeDatabase -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = false,
                shouldShowDatabaseInit = true,
                shouldShowFocusModeOnboarding = false,
            )
        }

        DesktopStartupMode.FocusModeSelection -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = false,
                shouldShowDatabaseInit = false,
                shouldShowFocusModeOnboarding = true,
            )
        }

        // The desktop-only onboarding scaffold and Ready. The scaffold stages have no graph equivalent -
        // DesktopStartupGateSurface renders them - and Ready is the only mode that mounts the graph.
        DesktopStartupMode.OnboardingIntro,
        DesktopStartupMode.OnboardingDatabaseChoice,
        DesktopStartupMode.OnboardingDimensionSetup,
        DesktopStartupMode.SecurityStateUnavailable,
        DesktopStartupMode.Ready,
        -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = false,
                shouldShowDatabaseInit = false,
                shouldShowFocusModeOnboarding = false,
            )
        }
    }
