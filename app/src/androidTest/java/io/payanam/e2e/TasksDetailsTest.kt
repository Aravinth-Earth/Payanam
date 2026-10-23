//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Add Task screen's deep controls: reminders, the scoring rows, the explicit scoring fields, tags,
 * and the recurrence picker.
 *
 * **What was uncovered.** [TasksCrudTest] proved a title-only task round-trips. Everything else the Add
 * Task screen offers — the reminder mode and its lead time, the four scoring rows, the four explicit
 * property fields, the tag editor and the recurrence picker — was rendered but never driven. A control
 * that is present and a control that WRITES are different claims, and only the second one is a user
 * action.
 *
 * **The two phases, in the order a user lives them.**
 *  1. ONE-TIME with detail: reminder → Custom (which reveals the lead-minutes field), an Impact selection,
 *     the explicit urgency / focus / blocked / dependency fields, and a tag; then Save, asserted by the
 *     title appearing in the list.
 *  2. RECURRING: a second task, switched to Recurring (which opens the recurrence picker), the Preset
 *     mode selected, "Daily" chosen and confirmed; then Save, asserted by the title appearing in the
 *     HABITS list — a recurring task IS a habit, so that is where the recurrence provably landed.
 *
 * **Device facts this encodes.** The recurrence picker can open on its Custom tab, where the presets are
 * not rendered, so the Preset mode is selected before "Daily" is asked for (measured 2026-10-16). The
 * reminder's lead-minutes field exists only while the mode is Custom. Every label is the app's own copy
 * via [E2eLabels]; the titles and tag text are data the test types in.
 */
@RunWith(AndroidJUnit4::class)
class TasksDetailsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-TASKS-DETAILS")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun remindersScoringTagsAndRecurrenceAreAllDriven() {
        FreshSetup(h).runIfNeeded()

        oneTimeTaskWithDetail()
        recurringTask()
        h.log("tasks-details=done")
    }

    /**
     * The one-time task: reminder Custom reveals the lead-minutes field, one scoring row is moved, the four
     * explicit property fields take values, and a tag is typed — then Save, asserted in the Tasks list.
     */
    private fun oneTimeTaskWithDetail() {
        h.goToTab(l.tasks)
        // The precondition is "this journey's task is ABSENT", not "nothing is due today": this class shares
        // one instrumentation invocation with the rest of the feature journeys, so an earlier class can
        // legitimately have left a task due today - and the emptiness assertion then failed for a reason that
        // has nothing to do with this journey. See [FreshSetup.runIfNeeded].
        h.assertNotVisible(DETAILED_TASK)
        h.click(l.addTask)
        h.type(l.taskTitleField, DETAILED_TASK)

        // ── reminder: the three modes are offered, and Custom reveals its lead-time field ──
        h.scrollTo(l.ui.locReminderNotifications)
        h.assertVisible(l.ui.locAuto)
        h.assertVisible(l.ui.locOff)
        h.click(l.ui.locCustom)
        h.assertVisible(l.ui.locCustomLeadMinutes)
        h.type(l.ui.locCustomLeadMinutes, "20")
        h.log("reminder=custom")

        // ── scoring: the four rows are present, and one is moved off its default ──
        h.scrollTo(l.ui.locImpact)
        h.assertVisible(l.ui.locGoalAlignment)
        h.assertVisible(l.ui.locEnergyRequired)
        h.assertVisible(l.ui.locControlLevel)
        h.click(l.ui.locMajorImpact.split(" ").first())
        h.log("scoring=major-impact")

        // ── explicit properties: all four fields take a value ──
        h.scrollTo(l.ui.locExplicitUrgency01)
        h.type(l.ui.locExplicitUrgency01, "0.8")
        h.type(l.ui.locFocusRequired01, "0.7")
        h.type(l.ui.locBlockedReason, BLOCKED_REASON)
        h.type(l.ui.locExternalDependency, DEPENDENCY)
        h.log("properties=filled")

        // ── tags ──
        h.scrollTo(l.ui.locTagsOptional)
        h.type(l.ui.locTagsOptional, TAG)
        h.log("tags=typed")

        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.assertVisible(DETAILED_TASK)
        h.log("one-time=saved")
    }

    /**
     * The recurring task: switching to Recurring opens the picker; the Preset mode is selected (the picker
     * can open on Custom, where the presets are absent), "Daily" is chosen and confirmed, and the task is
     * saved. A recurring task is a habit, so it is asserted in the Habits list.
     */
    private fun recurringTask() {
        h.goToTab(l.tasks)
        h.click(l.addTask)
        h.type(l.taskTitleField, RECURRING_TASK)

        h.scrollTo(l.ui.locRecurring)
        h.click(l.ui.locRecurring)
        // The picker's own mode selector, then the Daily preset, then its confirm.
        h.assertVisible(l.ui.locPreset)
        h.click(l.ui.locPreset)
        h.click(l.ui.locDaily)
        h.click(l.ui.locOk)
        // With recurrence on, the screen shows the pattern it will save.
        h.assertVisible(l.ui.locRecurrencePattern)
        h.log("recurrence=daily")

        h.scrollTo(l.saveTask)
        h.click(l.saveTask)

        // A recurring task lives in the Habits tab; the title there is the proof recurrence persisted.
        h.goToTab(l.habits)
        h.assertVisible(RECURRING_TASK)
        h.log("recurring=saved-as-habit")
    }

    private companion object {
        /** Suite-authored data, not localized copy. */
        const val DETAILED_TASK = "Detailed Task"
        const val RECURRING_TASK = "Daily Standup"
        const val BLOCKED_REASON = "Waiting on review"
        const val DEPENDENCY = "Team lead"
        const val TAG = "errand"
    }
}
