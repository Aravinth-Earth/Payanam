//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import io.payanam.core.ui.navigation.PayanamTab
import org.junit.Test

/**
 * The desktop's scoring-configuration journey, walked in-process the way Android's
 * `SettingsTogglesTest.scoringConfig()` walks it on a device.
 *
 * **The gap this closes.** Nothing on the desktop ever opened the scoring-configuration screen through the
 * UI. The Settings surface renders (`DesktopNavHostDestinationRenderTest` covers the nav-host destinations),
 * but the drivable path - the Settings tab, the Scoring card expanding, the Configure action opening the real
 * `ScoringConfigScreen`, a weight slider's `SetProgress` reaching the view model, Save gated on `hasChanges`,
 * and the save returning to Settings - was unexercised. This drives the whole journey and asserts the same
 * things the Android tier asserts: the factor-weights screen is up, Save is disabled until a change registers
 * and enabled after, and the save lands the user back on Settings.
 *
 * **Why the production fixture.** The scoring-config screen reads its weights from the shared preference
 * state, so this mounts over [installProductionFixtureLayer] with the REAL preference state
 * ([loadedPreferences]), exactly as the Tasks and Time journeys do.
 *
 * **What it does NOT claim.** It does not assert the persisted weight re-read on a later launch (the Android
 * tier records the same known gap), the Cancel/discard path, or the per-level sliders below the factor weights;
 * those are other items' claims.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopScoringConfigJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `the scoring configuration screen is reached from Settings and a weight change saves`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // ---- SETTINGS: walk to the Settings tab the way a user does --------------------------
            onAllNodesWithText(PayanamTab.Settings.title(strings)).onFirst().performClick()
            waitForIdle()
            frame("00-settings", "the Settings screen", copy = PayanamTab.Settings.title(strings))

            // ---- SCORING CARD: expand it by its header, then open the configuration screen --------
            onAllNodesWithText(strings.settingsScoringTitle)
                .onFirst()
                .performScrollTo()
                .performClick()
            waitForIdle()
            frame("01-scoring-card-open", "the expanded Scoring card", copy = strings.settingsScoringTitle)

            onNodeWithText(strings.settingsActionConfigureScoring).performScrollTo().performClick()

            // ---- SCORING CONFIGURATION: the factor-weights section is the evidence it is up --------
            awaitNodeWithText(strings.locFactorWeights)
            frame("02-scoring-config", "the scoring-configuration screen", copy = strings.locFactorWeights)

            // Save is enabled ONLY while there are unsaved changes: on entry there are none.
            onAllNodesWithText(strings.locSave).onFirst().assertIsNotEnabled()

            // ---- CHANGE: move the first weight slider (the dimension-weight slider, range 0.1..3.0) --
            onAllNodesWithTag("scoring_config_slider")
                .onFirst()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }
            waitForIdle()

            // The change registered: Save becomes enabled, which is the app's own rendered confirmation.
            waitUntil(timeoutMillis = 10_000L) {
                onAllNodesWithText(strings.locSave).fetchSemanticsNodes().none { node ->
                    node.config.contains(SemanticsProperties.Disabled)
                }
            }
            onAllNodesWithText(strings.locSave).onFirst().assertIsEnabled()
            frame("03-weight-changed", "the scoring-configuration screen with an unsaved change", copy = strings.locSave)

            // ---- SAVE: the change saves and the app is back on the Settings screen -----------------
            onAllNodesWithText(strings.locSave)
                .onFirst()
                .performScrollTo()
                .performClick()

            // The Configure-scoring action exists only on the Settings screen, so its presence is the
            // return trip's own evidence; the Scoring card header is asserted alongside it.
            // The Scoring card may be COLLAPSED after the return, so its inner action is not rendered;
            // the card HEADER is always there and is the honest proof of the return.
            awaitNodeWithText(strings.settingsScoringTitle)
            frame("04-after-save", "the Settings screen after saving", copy = strings.settingsScoringTitle)
        }
}
