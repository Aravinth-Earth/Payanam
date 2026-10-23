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
 * The onboarding stages were the case that proved it: they had no graph parameters of their own, so
 * they projected to "no graph gate" while the desktop's own surface rendered them. That surface is gone
 * now - the tour and the refusal are graph destinations like every other gate - and `Ready` remains the
 * one value that yields no gate at all, which is what it means.
 */
internal data class DesktopNavGateFlags(
    val shouldShowPassphraseSetup: Boolean,
    val shouldShowPassphraseUnlock: Boolean,
    val shouldShowDatabaseInit: Boolean,
    val shouldShowFocusModeOnboarding: Boolean,
    val shouldShowSecurityStateUnavailable: Boolean,
    val shouldShowOnboardingIntro: Boolean,
) {
    /** True when the shared graph may be mounted: no gate is outstanding. */
    val showsNoGate: Boolean
        get() =
            !shouldShowPassphraseSetup &&
                !shouldShowPassphraseUnlock &&
                !shouldShowDatabaseInit &&
                !shouldShowFocusModeOnboarding &&
                !shouldShowSecurityStateUnavailable &&
                !shouldShowOnboardingIntro
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
                shouldShowSecurityStateUnavailable = false,
                shouldShowOnboardingIntro = false,
            )
        }

        DesktopStartupMode.UnlockPassphrase -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = true,
                shouldShowDatabaseInit = false,
                shouldShowFocusModeOnboarding = false,
                shouldShowSecurityStateUnavailable = false,
                shouldShowOnboardingIntro = false,
            )
        }

        DesktopStartupMode.InitializeDatabase -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = false,
                shouldShowDatabaseInit = true,
                shouldShowFocusModeOnboarding = false,
                shouldShowSecurityStateUnavailable = false,
                shouldShowOnboardingIntro = false,
            )
        }

        DesktopStartupMode.FocusModeSelection -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = false,
                shouldShowDatabaseInit = false,
                shouldShowFocusModeOnboarding = true,
                shouldShowSecurityStateUnavailable = false,
                shouldShowOnboardingIntro = false,
            )
        }

        // The refusal is a gate the graph owns now: it renders the shared refusal screen, so the desktop
        // mounts the shell and the graph navigates to it.
        DesktopStartupMode.SecurityStateUnavailable,
        -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = false,
                shouldShowDatabaseInit = false,
                shouldShowFocusModeOnboarding = false,
                shouldShowSecurityStateUnavailable = true,
                shouldShowOnboardingIntro = false,
            )
        }

        // The first-run tour is a graph destination now.
        DesktopStartupMode.OnboardingIntro,
        -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = false,
                shouldShowDatabaseInit = false,
                shouldShowFocusModeOnboarding = false,
                shouldShowSecurityStateUnavailable = false,
                shouldShowOnboardingIntro = true,
            )
        }

        // Ready: the graph is mounted and no gate is outstanding.
        DesktopStartupMode.Ready,
        -> {
            DesktopNavGateFlags(
                shouldShowPassphraseSetup = false,
                shouldShowPassphraseUnlock = false,
                shouldShowDatabaseInit = false,
                shouldShowFocusModeOnboarding = false,
                shouldShowSecurityStateUnavailable = false,
                shouldShowOnboardingIntro = false,
            )
        }
    }

/**
 * Whether the shared graph renders [startupMode]'s gate, rather than the desktop's own surface.
 *
 * Total by construction, like the projection it sits beside: every value names a case, so adding a mode
 * without deciding where its gate comes from is a compile error rather than a silent "shows no gate".
 *
 * Every mode answers `true` now: the last two that did not - the tour and the refusal - are graph
 * destinations themselves. The function stays total and explicit because the next mode added must still
 * decide, and because a silent `true` for a mode whose gate nobody renders would open the app with
 * nothing in front of it.
 *
 * This is deliberately NOT expressed as `!showsNoGate`: `Ready` also reports no gate, and reading the
 * projection would therefore send a ready app back to the desktop's surface.
 */
internal fun desktopStartupGateShownByGraph(startupMode: DesktopStartupMode): Boolean =
    when (startupMode) {
        DesktopStartupMode.SetupPassphrase,
        DesktopStartupMode.UnlockPassphrase,
        DesktopStartupMode.InitializeDatabase,
        DesktopStartupMode.FocusModeSelection,
        DesktopStartupMode.SecurityStateUnavailable,
        DesktopStartupMode.OnboardingIntro,
        DesktopStartupMode.Ready,
        -> true
    }
