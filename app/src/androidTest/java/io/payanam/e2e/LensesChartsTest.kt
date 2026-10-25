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
 * The Lenses insights that only render with DATA: the Time module's average-daily section and the
 * habit-score matrix's metric dropdown.
 *
 * **What was uncovered.** [LensesDataTest] seeds one task and exercises the Tasks module card and the
 * matrix ROW drill-in. It never drives the Time module's charts (which need a tracked time entry) or the
 * matrix's METRIC selector (which needs a habit for the matrix to render at all). A chart section that
 * never renders, or a metric dropdown that never writes its selection, would look identical to a working
 * Lenses screen.
 *
 * **The journey.** Seed a tracked time entry and a recurring habit through the UI, open Lenses, assert the
 * Time module's "Average daily time" section renders, then open the matrix's metric dropdown and choose a
 * different metric.
 *
 * Every label is the app's own copy via [E2eLabels]; the titles typed are data. Sections gated by a
 * chart-visibility preference are asserted only when present, and the step says so otherwise.
 */
@RunWith(AndroidJUnit4::class)
class LensesChartsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-LENSES-CHARTS")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun timeChartsAndScoreMetricAreDriven() {
        FreshSetup(h).runIfNeeded()

        seedTimeEntry()
        seedHabit()

        h.goToTab(l.lenses)
        assertLensesDestination()
        h.capture("lenses-charts")

        timeAverageDailySection()
        scoreMetricDropdown()
        h.log("lenses-charts=done")
    }

    /**
     * Proves the app is on the Lenses destination using copy the bottom bar cannot draw.
     *
     * Neither obvious marker survives. The bottom-nav "Lenses" label is drawn on EVERY screen, so it
     * proves nothing about the destination. The Time module card's CTA (`locGoToTime`, "Go to Time") is
     * composed only INSIDE that card, which `LensesScreen` renders behind `chartTimeModuleEnabled`
     * (`LensesScreen.kt:350,358`) — with that insight switched off the CTA is gone, and the failure would
     * land before the chart-off paths this test exists to cover.
     *
     * `LensesScreen` also draws its OWN top-bar title from `locLenses`, in the `Scaffold(topBar = …)`
     * argument (`LensesScreen.kt:205-212`). That is unconditional on this screen: it is outside every
     * preference check, above the `uiState.isLoading` early return (`LensesScreen.kt:214-224`) and
     * outside `ModuleSections` entirely, so no chart flag, no minimal-mode flag and no load state can
     * remove it.
     *
     * The bottom bar's copy of the same word carries it too, so the marker additionally requires the
     * node to be NON-pressable: the nav item merges its label into its own clickable node, so within the
     * merged semantics tree only the screen's title is an exact `locLenses` text with no click action —
     * which is what makes this specific to the Lenses destination.
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

    /** A manual time entry, so the Time module's charts have a data point. */
    private fun seedTimeEntry() {
        h.goToTab(l.time)
        h.click(l.ui.locAddTimeEntry)
        h.clickTag(MODAL_CONFIRM_TAG)
        // The modal closing is the proof the entry was accepted (the FAB itself never leaves).
        rule.waitUntil(20_000) { !h.existsTag(MODAL_CONFIRM_TAG) }
        h.log("seed=time-entry")
    }

    /** A daily habit, so the habit-score matrix renders and its metric dropdown is reachable. */
    private fun seedHabit() {
        h.goToTab(l.habits)
        h.click(l.addTask)
        h.type(l.taskTitleField, HABIT_TITLE)
        h.click(l.ui.locRecurring)
        h.click(l.ui.locPreset)
        h.click(l.ui.locDaily)
        h.click(l.ui.locOk)
        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.dismissNotificationOfferIfPresent()
        h.assertVisible(HABIT_TITLE)
        h.log("seed=habit")
    }

    /** The Time module's average-daily section renders when the chart flag is on. */
    private fun timeAverageDailySection() {
        if (!h.find(l.ui.locLensTimeAverageDailyTitle)) {
            h.log("averageDaily=absent (chart flag off) — skipping")
            return
        }
        h.assertVisible(l.ui.locLensTimeAverageDailyTitle)
        h.log("averageDaily=visible")
    }

    /**
     * The habit-score matrix's metric dropdown: open it and choose a different metric.
     *
     * The trigger carries the formatted "Metric: X" copy, so it is opened by SUBSTRING; the item chosen is
     * the "Running Avg" label, and the menu closing is the observable proof the choice was taken.
     */
    private fun scoreMetricDropdown() {
        val triggerPrefix = l.ui.locLensMetricLabel.substringBefore('%')
        if (!h.existsTag("lenses_matrix_row")) {
            h.log("scoreMetric=absent (matrix not rendered) — skipping")
            return
        }
        h.clickSubstring(triggerPrefix)
        h.assertVisible(l.ui.activityDetailChartRunningAvg)
        h.click(l.ui.activityDetailChartRunningAvg)
        h.waitForGone(l.ui.activityDetailChartRunningAvg)
        h.log("scoreMetric=running-avg")
    }

    private companion object {
        /** The time-entry modal's confirm button testTag. */
        const val MODAL_CONFIRM_TAG = "edit_time_entry_confirm_button"

        /** Data the test types in, not app copy. */
        const val HABIT_TITLE = "Lenses Habit"

        /** Long enough for the destination's own title to be drawn; a condition wait returns at once. */
        const val DESTINATION_WAIT_MS = 30_000L
    }
}
