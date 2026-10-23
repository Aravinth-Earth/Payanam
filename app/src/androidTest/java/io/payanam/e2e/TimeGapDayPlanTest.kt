//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Time screen's planning controls: the untracked-gap block and the day-plan dialog.
 *
 * **What was uncovered.** [TimeEntryEditTest] drives the Add-Time-Entry FAB and an existing entry's modal.
 * Two controls it never reaches are the GAP block on the timeline (tap an untracked stretch to assign time
 * to it) and the day-plan CTA (open the day's plan, choose a mode, save). Both are rendered but never
 * driven, so "the timeline offers no way to fill an untracked gap" or "the day plan never saves" would look
 * identical to a working screen.
 *
 * **The journey.** On a fresh install the timeline is one long untracked gap, so the gap block is present
 * without seeding anything: tap it and assert the assignment modal comes up (the same modal the FAB opens,
 * reached through the gap path), then dismiss. Then open the day plan from its CTA, assert the dialog, move
 * it to the Custom mode and Save, and assert the dialog closes.
 *
 * Every label is the app's own copy via [E2eLabels]. The gap block is addressed by its `testTag`.
 */
@RunWith(AndroidJUnit4::class)
class TimeGapDayPlanTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-TIME-GAP")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun theGapBlockAndTheDayPlanAreDriven() {
        FreshSetup(h).runIfNeeded()

        h.goToTab(l.time)
        h.assertVisible(l.ui.locDayPlanShort)
        h.capture("time-screen")

        gapBlockOpensTheAssignModal()
        dayPlanDialogSaves()

        h.log("time-gap-dayplan=done")
    }

    /**
     * The gap block: a tap opens the assignment modal (the same one the Add FAB opens), which is dismissed.
     *
     * The block is addressed by its `testTag` because its only text is the computed untracked-duration
     * caption, which changes with the clock. It is scrolled to first: the timeline is taller than the
     * viewport and the block may sit off screen.
     */
    private fun gapBlockOpensTheAssignModal() {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag(GAP_BLOCK_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithTag(GAP_BLOCK_TAG).onFirst().performScrollTo().performClick()

        // The modal's confirm button carries a stable testTag regardless of which action it offers.
        rule.waitUntil(20_000) { h.existsTag(MODAL_CONFIRM_TAG) }
        h.log("gap-block=assign-modal")

        // Dismiss without assigning: this case is about the control being reachable and wired.
        h.click(l.cancel)
        h.waitForGone(l.cancel)
    }

    /**
     * The day-plan CTA: open the dialog, move it to the Custom mode, Save, and require it to close.
     *
     * The mode chips report ZERO bounds inside the dialog window (measured 2026-10-16), so they cannot be
     * tapped by position; the click ACTION is invoked directly instead. That still exercises the wiring
     * (the chip's onClick and the save), which is the claim this case makes. The zero-bounds itself is
     * recorded on the coverage matrix as an open finding.
     */
    private fun dayPlanDialogSaves() {
        h.assertVisible(l.ui.locDayPlanShort)
        h.click(l.ui.locDayPlanShort)
        h.assertVisible(l.ui.locDayPlan)
        h.capture("day-plan-dialog")

        rule.onAllNodesWithText(l.ui.locDayPlanModeCustom).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onAllNodesWithText(l.ui.locSave).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }

        h.waitForGone(l.ui.locDayPlan)
        h.log("day-plan=saved")
    }

    private companion object {
        /** The untracked-gap block's testTag (`TimeScreenTimeline`). */
        const val GAP_BLOCK_TAG = "time_gap_block"

        /** The time-entry modal's confirm button testTag (`TimeBlockModalDialog`). */
        const val MODAL_CONFIRM_TAG = "edit_time_entry_confirm_button"
    }
}
