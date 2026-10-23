//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Habits CRUD journey on a fresh install: empty state → create a recurring habit → mark a day done via
 * the completion dialog → edit a day's status via the checkmark dialog → open the detail → delete.
 *
 * One @Test method, one fresh-install invocation (run with `-KeepInstalled:$false`). Every label is read
 * from [E2eLabels] — the app's own copy, in the app's current language — so the flow runs unchanged on a
 * Tamil-locale device. The only literal is the habit title the test types in; that is data the test
 * compares against what it wrote, not localized copy.
 *
 * Gesture mapping, resolved against `io.payanam.core.ui.components.CheckmarkPanelCanvas`:
 * a TAP on the `habit_checkmark_panel` canvas toggles the day — for a recurring habit whose new status is
 * "completed" that raises the CompletionDialog (title and confirm both `locMarkDone`); a LONG-PRESS on the
 * same canvas opens the CheckmarkDialog (status options `locDone` / `taskNotificationActionSkip` /
 * `locMissed`, confirm `locSave`). The canvas is driven by its testTag because its day cells carry only a
 * contentDescription.
 */
@RunWith(AndroidJUnit4::class)
class HabitsCrudTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-HabitsCrud")

    /** The app's own copy in the app's current language. */
    private val l get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun habitsCrudJourney() {
        // ═══ FRESH INSTALL → MAIN APP ═══════════════════════════════════════════════════════════════
        FreshSetup(h).runIfNeeded()

        // ═══ 1. HABITS TAB — THIS JOURNEY'S HABIT IS NOT ALREADY THERE ════════════════════════════
        // The precondition is "the habit this class is about is ABSENT", not "the list is empty".
        // This class now shares one instrumentation invocation with the rest of the feature journeys
        // ([FreshSetup.runIfNeeded]), so an emptiness assertion would fail whenever an earlier class had
        // left something - for a reason that has nothing to do with this journey. Every class that creates
        // list data deletes it, so the list is normally empty here anyway; the assertion simply no longer
        // DEPENDS on that.
        h.goToTab(l.habits)
        h.assertNotVisible(HABIT_TITLE)
        h.capture("habits_screen")
        h.log("phase1=subjectAbsent")

        // ═══ 2. CREATE A RECURRING HABIT ══════════════════════════════════════════════════════════
        h.click(l.addTask)
        h.type(l.taskTitleField, HABIT_TITLE)
        // Selecting "Recurring" opens the recurrence picker. It can open on its Custom tab, where the
        // Daily preset is not rendered, so select the Preset tab first (a no-op when it is already
        // selected), then the Daily preset, then confirm.
        h.click(l.ui.locRecurring)
        h.click(l.ui.locPreset)
        h.click(l.ui.locDaily)
        h.click(l.ui.locOk)
        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.assertVisible(HABIT_TITLE)
        h.assertLogged("AddTaskViewModel.createTask", "Task created successfully")
        h.log("phase2=created")

        // ═══ 3. TAP THE CANVAS → COMPLETION DIALOG ════════════════════════════════════════════════
        tapCanvas()
        h.assertVisible(l.ui.locMarkDone)
        h.click(l.ui.locMarkDone) // confirm — the dialog's button reuses the title's label
        h.waitForGone(l.ui.locMarkDone)
        h.log("phase3=completionDialog")

        // ═══ 4. LONG-PRESS THE CANVAS → CHECKMARK DIALOG ══════════════════════════════════════════
        rule.onAllNodesWithTag(CHECKMARK_PANEL_TAG, useUnmergedTree = true)
            .onFirst()
            .performTouchInput { longClick() }
        h.assertVisible(l.ui.locHowDidItGo)
        h.click(l.ui.taskNotificationActionSkip)
        h.click(l.ui.locSave)
        h.waitForGone(l.ui.locHowDidItGo)
        h.log("phase4=checkmarkDialog")

        // ═══ 5. OPEN THE DETAIL, THEN LEAVE IT ════════════════════════════════════════════════════
        openHabitDetail()
        h.assertVisible(l.taskDetails)
        h.capture("habit_detail")
        h.back()
        h.assertVisible(HABIT_TITLE)
        h.log("phase5=detail")

        // ═══ 6. DELETE ═════════════════════════════════════════════════════════════════════════════
        openHabitDetail()
        h.assertVisible(l.taskDetails)
        h.click(l.ui.locDelete) // opens the confirm dialog
        h.clickLast(l.ui.locDelete) // confirms — the dialog reuses the "Delete" label
        h.waitForGone(HABIT_TITLE)
        h.assertNotVisible(HABIT_TITLE)
        h.log("phase6=deleted")
    }

    /**
     * Taps the habit completion canvas.
     *
     * The canvas testTag sits on a child of the habit card, whose merged semantics hide it — so it is
     * addressed through the UNMERGED tree.
     */
    private fun tapCanvas() {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag(CHECKMARK_PANEL_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithTag(CHECKMARK_PANEL_TAG, useUnmergedTree = true)
            .onFirst()
            .performClick()
    }

    /**
     * Opens the habit's detail screen by tapping its row.
     *
     * The card is clickable as a whole, but the checkmark canvas fills the right half of the row, so a tap
     * aimed at the merged row node lands on the canvas and toggles a day instead of opening the detail. The
     * title's own (unmerged) node sits left of the canvas, so tapping that node reaches the card's click
     * handler.
     */
    private fun openHabitDetail() {
        rule.onAllNodes(h.labelled(HABIT_TITLE), useUnmergedTree = true)
            .onFirst()
            .performClick()
    }

    private companion object {
        /** The habit title the test types in — data, not localized copy. */
        const val HABIT_TITLE = "Morning Stretch"

        /** The checkmark canvas testTag (see `io.payanam.core.ui.components.CheckmarkPanelCanvas`). */
        const val CHECKMARK_PANEL_TAG = "habit_checkmark_panel"
    }
}
