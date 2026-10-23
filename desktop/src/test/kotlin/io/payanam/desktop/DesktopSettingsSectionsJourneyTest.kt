//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.payanam.core.ui.navigation.PayanamTab
import org.junit.Test

/**
 * The desktop's Settings sections for insights chart visibility and life dimensions, walked in-process
 * the way Android's `InsightsVisibilityTest` and `DimensionSetupTest` walk them on a device.
 *
 * **The gap this closes.** The Settings screen renders (`DesktopNavHostSharedScreenRenderTest`) and its
 * toggles report their writes (`SettingsTogglesBehaviourTest`), but no journey on the desktop has ever
 * opened the Insights Charts Visibility card or the Life Dimensions card through the real UI and driven
 * their controls. This drives both: the chart-visibility switch path and the dimension editor path.
 *
 * **Why the production fixture.** The Settings screen reads its state from `LocalAppPreferences`, so this
 * mounts over [installProductionFixtureLayer] with the REAL preference state ([loadedPreferences]), exactly
 * as the Tasks and Time journeys do.
 *
 * **What it does NOT claim.** The harness passes a STATIC `AppPreferencesState`, so a toggle's EFFECT
 * cannot be observed in the UI. This asserts REACH + the click path only - that the switch is present,
 * that pressing it does not crash or navigate away, and that the card remains coherent. It does NOT
 * assert that a switch changed state. The dimension editor is asserted the same way: it opens, it is
 * dismissable, and the card remains coherent after dismissal.
 *
 * **Chart title choice.** The daily score trend row (`settingsInsightsTimeDailyScoreTrendTitle`) is
 * nested under "Time line graphs", which is OFF by default, so that row is not visible on a fresh
 * install. This test uses the "Time overall snapshot" row (`settingsInsightsTimeOverallSnapshotTitle`),
 * which is visible by default because its parent (the Time module) is ON.
 *
 * **Dimension editor strings.** The Settings dimension editor is an INLINE editor (not a dialog), so it
 * does not use `dbInitDimensionSetupNameLabel` or `locSave` (those belong to the first-run setup screen).
 * Its save button carries `settingsDimensionSave` as its contentDescription, and it is dismissed via
 * the close icon (`settingsDimensionCancel`), not `settingsActionCancel` (which is used by dialogs
 * elsewhere).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopSettingsSectionsJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `the insights charts visibility and life dimensions settings sections are reachable and drivable`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // Go to Settings
            onAllNodesWithText(PayanamTab.Settings.title(strings)).onFirst().performClick()
            waitForIdle()
            frame("00-settings", "the Settings screen", copy = PayanamTab.Settings.title(strings))

            // ---- SECTION 1: Insights Charts Visibility ----
            onAllNodesWithText(strings.settingsInsightsChartsVisibilityTitle).onFirst().performScrollTo().performClick()
            waitForIdle()
            frame(
                "01-insights-charts-visibility",
                "the Insights Charts Visibility card expanded",
                copy = strings.settingsInsightsChartsVisibilityTitle,
            )

            // Assert a chart-visibility switch row is present
            awaitNodeWithContentDescription(strings.settingsInsightsTimeOverallSnapshotTitle)
            frame(
                "02-chart-row-present",
                "a chart-visibility switch row is present",
                copy = strings.settingsInsightsTimeOverallSnapshotTitle,
            )

            // Press the switch once
            onAllNodes(hasContentDescription(strings.settingsInsightsTimeOverallSnapshotTitle) and isToggleable()).onFirst().performClick()
            waitForIdle()

            // Assert the card is still coherent
            awaitNodeWithContentDescription(strings.settingsInsightsTimeOverallSnapshotTitle)
            frame(
                "03-chart-row-toggled",
                "the Insights Charts Visibility card after toggling a switch",
                copy = strings.settingsInsightsTimeOverallSnapshotTitle,
            )

            // ---- SECTION 2: Life Dimensions ----
            onAllNodesWithText(strings.settingsLifeDimensionsTitle).onFirst().performScrollTo().performClick()
            waitForIdle()
            frame("04-life-dimensions", "the Life Dimensions card expanded", copy = strings.settingsLifeDimensionsTitle)

            // Assert the dimension edit control is present
            awaitNodeWithContentDescription(strings.settingsDimensionEdit)
            frame("05-dimension-edit-present", "the dimension edit control is present", copy = strings.settingsDimensionEdit)

            // Tap the edit control
            onAllNodesWithContentDescription(strings.settingsDimensionEdit).onFirst().performScrollTo().performClick()
            waitForIdle()

            // Assert the editor is up
            awaitNodeWithContentDescription(strings.settingsDimensionSave)
            frame("06-dimension-editor-open", "the dimension editor is open", copy = strings.settingsDimensionSave)

            // Dismiss the editor
            onAllNodesWithContentDescription(strings.settingsDimensionCancel).onFirst().performClick()
            waitForIdle()
            frame(
                "07-dimension-editor-dismissed",
                "the Life Dimensions card after dismissing the editor",
                copy = strings.settingsLifeDimensionsTitle,
            )
        }
}
