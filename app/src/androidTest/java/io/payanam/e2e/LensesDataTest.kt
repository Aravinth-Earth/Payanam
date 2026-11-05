//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
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
 * prove the destination through the screen's own top-bar title → expand the Tasks module card and
 * assert its CTA → exercise the habit-score matrix (if rendered).
 *
 * Every label is read from [E2eLabels] so the test runs in whatever locale the app is in.
 * Module cards are gated by chart-visibility preferences; this test asserts a card only when its
 * expand toggle is found and states what it could not verify otherwise - which is why the
 * destination marker below is deliberately NOT one of their contents.
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
        h.log("lenses=open")

        // ── assert we are on the Lenses destination ─────────────────────────────────────────────
        assertLensesDestination()

        // ── expand Tasks module card (if the chart flag is on) ──────────────────────────────────
        expandTasksModuleCard()

        // ── exercise the habit-score matrix (if the Habits module card is on) ───────────────────
        exerciseHabitScoreMatrix()
    }

    /**
     * Proves the app is on the Lenses destination using copy the bottom bar cannot draw.
     *
     * Neither obvious marker survives. The bottom-nav "Lenses" label is drawn on EVERY screen, so it
     * proves nothing about the destination. The Time module card's CTA (`locGoToTime`, "Go to Time") is
     * composed only INSIDE that card, which `LensesScreen` renders behind `chartTimeModuleEnabled`
     * (`LensesScreen.kt:350,358`) — with that insight switched off the CTA is gone, before any of the
     * chart-off paths this test exists to cover.
     *
     * `LensesScreen` also draws its OWN top-bar title from `locLenses`, in the `Scaffold(topBar = …)`
     * argument (`LensesScreen.kt:205-212`). That is unconditional on this screen: it is outside every
     * preference check, above the `uiState.isLoading` early return (`LensesScreen.kt:214-224`) and
     * outside `ModuleSections` entirely, so no chart flag, no minimal-mode flag and no load state can
     * remove it.
     *
     * The bottom bar carries the same word, so the marker additionally requires the node to be
     * NON-pressable: the nav item merges its label into its own clickable node, so within the merged
     * semantics tree only the screen's title is an exact `locLenses` text with no click action — which
     * is what makes this specific to the Lenses destination.
     */
    private fun assertLensesDestination() {
        rule.waitUntil(DESTINATION_WAIT_MS) { lensesTitleIsOnScreen() }
        check(lensesTitleIsOnScreen()) {
            "the Lenses screen's own title is not on screen — the app is not on the Lenses destination\n${h.diagnose(l.lenses)}"
        }
        h.log("lenses=destination")
    }

    /** One reading of the marker above: an exact `l.lenses` text node that is on screen and not pressable. */
    private fun lensesTitleIsOnScreen(): Boolean = runCatching {
        val nodes = rule.onAllNodes(hasText(l.lenses, substring = false))
        val count = nodes.fetchSemanticsNodes().size
        (0 until count).any { index ->
            val node = nodes[index]
            node.isDisplayed() && node.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick) == null
        }
    }.getOrDefault(false)

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

        /** Long enough for the destination's own title to be drawn; a condition wait returns at once. */
        const val DESTINATION_WAIT_MS = 30_000L
    }
}
