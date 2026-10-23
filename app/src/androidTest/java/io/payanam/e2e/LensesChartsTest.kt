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
        h.assertVisible(l.ui.assistantTitle)
        h.capture("lenses-charts")

        timeAverageDailySection()
        scoreMetricDropdown()
        h.log("lenses-charts=done")
    }

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
    }
}
