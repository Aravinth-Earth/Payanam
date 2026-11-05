//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.payanam.core.ui.navigation.PayanamTab
import org.junit.Test

/**
 * The desktop's day-plan journey, walked in-process the way Android's `TimeGapDayPlanTest` walks it on a
 * device.
 *
 * **The gap this closes.** The Time screen renders and the time-entry write path is driven
 * (`DesktopTimeEntryJourneyTest`), but the day-plan control - the CTA on the Time screen, the
 * `DayPlanDialog` it opens, its mode chips and its Save button - was never driven on the desktop: it
 * rendered and nothing more. This drives the whole journey: open the dialog from its CTA, move it to
 * the Custom mode, Save, and require the dialog to close.
 *
 * **Why the production fixture.** The dialog reads its dimension options from `LocalAppPreferences`, so
 * this mounts over [installProductionFixtureLayer] with the REAL preference state ([loadedPreferences]),
 * exactly as the Time journey does.
 *
 * **Why the mode chip and Save are tapped through [tapOrActivate].** Measured on Android (2026-10-16):
 * inside a dialog the mode FilterChips can report ZERO bounds, so a positional tap cannot reach them and
 * the click ACTION is invoked instead. The desktop mounts the same shared dialog, so the same trap is
 * possible; the helper checks the bounds and drives the control's own wiring either way.
 *
 * **What it does NOT claim.** It does not assert the plan's persisted rows or which mode the dialog
 * reopens in - the claim is that the surface is reachable and drivable through the real UI, the same
 * functional parity Android's `dayPlanDialogSaves()` makes.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopDayPlanJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `the day plan opens, moves to the Custom mode, and saves`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // ---- the Time tab, by its tab control, the way a user walks to it --------------------
            onAllNodesWithText(PayanamTab.Time.title(strings)).onFirst().performClick()
            waitForIdle()

            // The day-plan CTA is the screen's own planning control; its presence is the first claim.
            awaitNodeWithText(strings.locDayPlanShort)
            frame("00-time-day-plan-cta", "the Time screen with its day-plan control", copy = strings.locDayPlanShort)

            // ---- OPEN: the CTA opens the day-plan dialog -------------------------------------------
            onNodeWithText(strings.locDayPlanShort).performClick()
            awaitNodeWithText(strings.locDayPlan)
            frame("01-day-plan-dialog", "the day-plan dialog in its default mode", copy = strings.locDayPlan)

            // ---- MODE: move it to the Custom mode ---------------------------------------------------
            tapOrActivate(strings.locDayPlanModeCustom)
            waitForIdle()
            frame("02-day-plan-custom-mode", "the day-plan dialog moved to the Custom mode", copy = strings.locDayPlanModeCustom)

            // ---- SAVE: the dialog must close --------------------------------------------------------
            tapOrActivate(strings.locSave)
            awaitNodeGone(strings.locDayPlan)
            frame("03-day-plan-saved", "the Time screen after the day plan was saved", copy = strings.locDayPlanShort)
        }

    /**
     * Taps [label] by position when the node has bounds, and invokes its click ACTION when it has none.
     *
     * A positional tap on a zero-bounds node does not throw - it lands somewhere harmless and the
     * control's wiring is silently skipped - so the bounds are checked rather than a failure caught.
     * The ACTION path still exercises the chip's own `onClick` and the save, which is the claim.
     */
    private fun DesktopComposeUiTest.tapOrActivate(label: String) {
        val node = onAllNodesWithText(label).onFirst()
        val hasBounds = node.fetchSemanticsNode().boundsInRoot.let { it.width > 0f && it.height > 0f }
        if (hasBounds) {
            node.performClick()
        } else {
            node.performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
    }
}
