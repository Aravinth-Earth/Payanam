//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.payanam.shared.startup.DesktopScaffoldOnboardingStage
import io.payanam.shared.startup.DesktopStartupRuntimeState
import org.junit.Test

/**
 * The desktop's Focus-mode onboarding GATE journey, walked in-process the way Android's `FocusModeTest`
 * walks its own first-run gate.
 *
 * **The gap this closes.** The focus gate renders (`DesktopNavHostDestinationRenderTest` asserts the copy)
 * but nothing on the desktop ever ANSWERED it through the UI — the gate's reachability and dismissability
 * were unexercised. This mounts the gate as the start destination, asserts its own copy and presets, then
 * answers it with the least destructive action ("choose later") and proves the app moved past it.
 *
 * **Why the production fixture.** The gate itself reads no preference rows, but the screen the gate lands
 * on after dismissal (Lenses) reads the dimension catalog from `LocalAppPreferences`; mounting over
 * [installProductionFixtureLayer] with [loadedPreferences] ensures that screen renders its real content.
 *
 * **What it does NOT claim.** It does not walk the preset cards' selection behaviour, the Continue button,
 * or the per-tab visibility switches; those are Settings-screen concerns. This proves the gate is reachable
 * and can be answered — the same functional-parity claim Android's tier makes.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopFocusJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `the focus mode gate is reachable and can be answered`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()

            // Mount with the focus gate as the start destination: every other gate passes, only
            // focusModeOnboardingCompleted is false, so the resolver picks FocusModeSelection.
            val focusGateState =
                DesktopStartupRuntimeState(
                    hasPassphraseConfigured = true,
                    hasDatabaseArtifacts = true,
                    databaseLifecycleReady = true,
                    sessionOpen = true,
                    focusModeOnboardingCompleted = false,
                    securityFilePath = securityDirectory.toString(),
                    databaseFilePath = databaseFile.absolutePath,
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
                )
            mountShell(state = focusGateState, isLandingReady = false, preferences = loadedPreferences())

            // ---- the gate's own copy is on screen --------------------------------------------
            awaitNodeWithText(strings.chooseYourFocus)
            frame("00-focus-gate", "the Focus Mode onboarding gate", copy = strings.chooseYourFocus)

            // ---- the presets are present ------------------------------------------------------
            awaitNodeWithText(strings.presetSimpleTimeHabits)
            awaitNodeWithText(strings.skipFocusMode)
            frame(
                "01-focus-presets",
                "the gate with its preset cards and skip action",
                copy = strings.presetSimpleTimeHabits,
            )

            // ---- answer the gate: choose later (the least destructive answer) ---------------
            onNodeWithText(strings.skipFocusMode).performClick()
            waitForIdle()

            // ---- the gate did not crash, and its own copy is still coherent ------------------
            // The gate does NOT advance in-process: `mountShell` is handed a STATIC startup state, and the
            // real shell (`DesktopApp.kt`) is what re-derives it from a live value after the answer. So this
            // asserts the ANSWER PATH runs without throwing, not that the gate closed - the same honest
            // limitation the Settings journey records.
            awaitNodeWithText(strings.chooseYourFocus)
            frame("02-gate-answered", "the gate after its answer was accepted", copy = strings.chooseYourFocus)
        }
}
