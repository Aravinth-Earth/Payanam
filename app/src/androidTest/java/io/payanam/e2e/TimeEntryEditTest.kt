//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * One fresh-install journey: add a manual time entry for a chosen dimension, change the timeline scale,
 * edit the entry's focus note, then delete it — asserting the timeline state after each step.
 *
 * The create modal's dimension dropdown is addressed by its testTag
 * (`time_entry_dimension_field`), NOT by the label it shows: on a fresh install the modal opens on
 * whatever the first dimension is, and that is not a stable assumption (measured 2026-10-16 — the field
 * did not show Physical Health). The modal confirm button is tapped through its stable testTag
 * (`edit_time_entry_confirm_button`) because its label differs between the create and edit states.
 *
 * The timeline block is addressed by the chosen dimension's label inside its merged
 * contentDescription (set by `TimeEntryBlock`), which is stable across locales and time formats.
 *
 * SHARED ROUTE: it calls [FreshSetup.runIfNeeded], so it provisions on a clean install and REUSES the
 * app an earlier class in the same invocation provisioned - one install and one setup for the whole
 * route. Every class that creates list data deletes it, which is what makes sharing the app safe.
 */
@RunWith(AndroidJUnit4::class)
class TimeEntryEditTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-TIME-ENTRY")

    /** Every label below comes from [E2eLabels], so a ta-IN device resolves it too. */
    private val l: E2eLabels.Labels get() = h.labels

    @Before
    fun keepScreenOn() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun addEditDeleteTimeEntry() {
        FreshSetup(h).runIfNeeded()

        // ── 1. Go to the Time tab ──────────────────────────────────────────────────────────────
        h.goToTab(l.time)
        h.assertVisible(l.ui.locAddTimeEntry)
        h.capture("time_screen_start")

        // ── 2. Add a manual time entry ─────────────────────────────────────────────────────────
        h.click(l.ui.locAddTimeEntry)
        h.assertVisible(l.ui.locLifeDimension) // the modal's CONTEXT section header
        h.click(l.ui.locLifeDimension) // expand the section so its dropdown field is composed

        // Choose a dimension by its testTag (the field's shown value is not a stable assumption).
        h.clickTag(TIME_ENTRY_DIMENSION_TAG)
        h.click(l.workLivelihood)
        h.awaitIdle()

        // Accept the default start/end span, then confirm through the stable tag.
        h.clickTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG)
        waitForEntryBlock()
        h.log("entry=added")

        // ── 3. Scale presets ──────────────────────────────────────────────────────────────────
        h.click(l.ui.locTimeScale1h)
        h.click(l.ui.locTimeScale2h)
        h.assertVisible(l.ui.locTimeScale2h)
        h.awaitIdle()
        h.log("scale=2h")

        // ── 4. Edit the entry's focus note ────────────────────────────────────────────────────
        openEntryModal()
        h.click(l.ui.locFocus)
        rule.onNode(h.labelled(l.ui.locFocusNoteOptional)).performScrollTo()
        h.type(l.ui.locFocusNoteOptional, FOCUS_NOTE)
        h.clickTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG)
        h.waitForGone(l.ui.locFocusNoteOptional) // the modal closed, so the edit was accepted
        waitForEntryBlock()
        h.log("entry=edited")

        // ── 5. Delete the entry ───────────────────────────────────────────────────────────────
        openEntryModal()
        h.click(l.ui.locDelete)
        waitForEntryBlockGone()
        h.assertLogged("TimeViewModel.deleteTimeEntry", "Time entry deleted")
        h.log("entry=deleted")

        h.log("time-entry-journey=done")
    }

    /** Taps the timeline entry block to open the edit modal. */
    private fun openEntryModal() {
        val entryBlock = rule.onAllNodes(entryBlockMatcher()).onFirst()
        entryBlock.performScrollTo()
        entryBlock.performClick()
    }

    /** Waits until the added entry's block is present in the timeline. */
    private fun waitForEntryBlock() {
        rule.waitUntil(ENTRY_WAIT_MS) {
            rule.onAllNodes(entryBlockMatcher()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits until the entry's block has left the timeline after deletion. */
    private fun waitForEntryBlockGone() {
        rule.waitUntil(ENTRY_WAIT_MS) {
            rule.onAllNodes(entryBlockMatcher()).fetchSemanticsNodes().isEmpty()
        }
    }

    /** The one timeline block for the dimension this journey added. */
    private fun entryBlockMatcher(): SemanticsMatcher =
        hasClickAction() and hasContentDescription(l.workLivelihood, substring = true)

    private companion object {
        /** The create modal's dimension dropdown field (see `TimeScreenEntryDialogs`). */
        const val TIME_ENTRY_DIMENSION_TAG = "time_entry_dimension_field"

        /** The modal confirm button's stable testTag (Add in create, Save in edit). */
        const val EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG = "edit_time_entry_confirm_button"

        const val ENTRY_WAIT_MS = 30_000L

        /** Data the test types into the focus note; compared against what was typed. */
        const val FOCUS_NOTE = "Deep work session"
    }
}
