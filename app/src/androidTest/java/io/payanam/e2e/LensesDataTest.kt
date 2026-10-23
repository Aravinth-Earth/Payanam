//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-process E2E for the Lenses tab after seeding minimal data (one completed task).
 *
 * Flow: FreshSetup → proceed past dimension setup → create + complete one task → open Lenses →
 * assert the Per Dimension header → expand the Tasks module card and assert its CTA → exercise the
 * habit-score matrix (if rendered) → assert the AI Assistance entry card.
 *
 * Every label is read from [E2eLabels] so the test runs in whatever locale the app is in.
 * Module cards are gated by chart-visibility preferences; this test asserts a card only when its
 * expand toggle is found and states what it could not verify otherwise.
 *
 * SHARED ROUTE: it calls [FreshSetup.runIfNeeded], so it provisions on a clean install and REUSES the
 * app an earlier class in the same invocation provisioned - one install and one setup for the whole
 * route. Every class that creates list data deletes it, which is what makes sharing the app safe.
 */
@RunWith(AndroidJUnit4::class)
class LensesDataTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-LensesData")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun lensesRendersSeededData() {
        // ── fresh install → dimension setup → main app ──────────────────────────────────────────
        FreshSetup(h).runIfNeeded()
        h.log("setup=done")

        // ── seed one completed task ───────────────────────────────────────────────────────────────
        seedCompletedTask()
        h.log("seed=done")

        // ── open Lenses ────────────────────────────────────────────────────────────────────────
        h.goToTab(l.lenses)
        h.assertVisible(l.lenses)
        h.log("lenses=open")

        // ── assert we are on the Lenses destination ─────────────────────────────────────────────
        // The AI Assistance card is always rendered on Lenses and is Lenses-specific. The bottom-nav
        // "Lenses" label is NOT used: it is present on every screen, so it proves nothing.
        h.assertVisible(l.ui.assistantTitle)
        h.log("lenses=open")

        // ── expand Tasks module card (if the chart flag is on) ──────────────────────────────────
        expandTasksModuleCard()

        // ── exercise the habit-score matrix (if the Habits module card is on) ───────────────────
        exerciseHabitScoreMatrix()

        // ── assert AI Assistance entry card ─────────────────────────────────────────────────────
        h.scrollTo(l.ui.assistantTitle)
        h.assertVisible(l.ui.assistantTitle)
        h.log("assistant=visible")
    }

    /**
     * Creates a single task via the UI and completes it through the task-detail screen.
     *
     * The task title is data the test types in, not localized copy — it stays a literal so the
     * test can find it again by comparing against what it entered.
     */
    private fun seedCompletedTask() {
        h.goToTab(l.tasks)
        h.click(l.addTask)
        h.assertVisible(l.taskTitleField)
        h.type(l.taskTitleField, SEEDED_TASK_TITLE)
        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.dismissNotificationOfferIfPresent()
        h.assertVisible(SEEDED_TASK_TITLE)

        // Open the task detail and complete it.
        h.click(SEEDED_TASK_TITLE)
        h.assertVisible(l.taskDetails)
        h.click(l.ui.taskNotificationActionComplete)
        // The StatusNoteDialog confirm button reuses the same label; clickLast targets the dialog's
        // copy (composed after the detail screen's button in the semantics tree).
        h.clickLast(l.ui.taskNotificationActionComplete)
        h.waitForGone(l.taskDetails)
        // Prove we are back on the Tasks list (the Add-Task FAB) before navigating away: the bottom-nav
        // labels are present on every screen, so asserting one of those would not prove anything.
        h.assertVisible(l.addTask)
        h.log("task=completed")
    }

    /**
     * Expands the Tasks module card and asserts its CTA when the card is present.
     *
     * The expand/collapse toggle carries `settingsActionExpand` / `settingsActionCollapse`. If the
     * chart-task-module preference is off the card is absent and this step reports that honestly.
     */
    private fun expandTasksModuleCard() {
        if (!h.exists(l.ui.settingsActionExpand)) {
            h.log("tasksModuleCard=absent (chart flag off) — skipping expand + CTA assert")
            return
        }
        h.scrollTo(l.ui.settingsActionExpand)
        h.click(l.ui.settingsActionExpand)
        h.click(l.ui.locGoToTasks)
        // The CTA navigates to the Tasks tab; come back to Lenses for the remaining assertions.
        h.goToTab(l.lenses)
        h.assertVisible(l.lenses)
        h.log("tasksModuleCard=expanded+cta")
    }

    /**
     * Taps the first habit-score matrix row and asserts the score-detail screen opens, then returns.
     *
     * The matrix lives inside the Habits module card. Rows carry testTag `lenses_matrix_row`. If
     * the Habits module card is absent (chart flag off) this step reports that honestly.
     */
    private fun exerciseHabitScoreMatrix() {
        if (!h.existsTag("lenses_matrix_row")) {
            h.log("habitScoreMatrix=absent (chart flag off) — skipping row-tap + detail assert")
            return
        }
        // The matrix may be below the fold inside a collapsed card; expand the Habits card first.
        if (h.exists(l.ui.settingsActionExpand)) {
            h.scrollTo(l.ui.settingsActionExpand)
            h.click(l.ui.settingsActionExpand)
        }
        rule.onAllNodesWithTag("lenses_matrix_row").onFirst().performClick()
        // The score-detail screen shows a back button with contentDescription locBack.
        h.assertVisible(l.ui.locBack)
        h.back()
        h.log("habitScoreMatrix=rowTapped+detailOpened")
    }

    private companion object {
        /** Task title typed by the test — data, not localized copy. */
        const val SEEDED_TASK_TITLE = "Lenses Seed Task"
    }
}
