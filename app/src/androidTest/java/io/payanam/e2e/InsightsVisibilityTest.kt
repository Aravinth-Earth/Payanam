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
 * The "Insights Charts Visibility" switches: every one of them flips, the nesting behaves, and a
 * flipped switch actually changes what the Lenses screen shows.
 *
 * Three deliberate choices, each learned the hard way:
 *
 *  - **State is asserted, not taps.** [E2eHarness.setChecked] reads the switch's own on/off state
 *    back, so a row that ignores the tap fails instead of passing quietly.
 *  - **The six rows under "Score cards" and "Line graphs" are not present until those two are on.**
 *    Both default to off, so their children are not composed at all — a test that lists them up
 *    front fails looking for rows the app never rendered. Opening the parents is therefore part of
 *    the test, and the empty-by-default state is asserted as a behaviour rather than worked around.
 *  - **Only the visibility effect the app actually renders is asserted.** Sections whose charts have
 *    no data are not rendered, so asserting them here would be vacuous; the data-hungry sections
 *    stay for a run that seeds history first.
 *
 * SHARED ROUTE: it calls [FreshSetup.runIfNeeded], so it provisions on a clean install and REUSES the
 * app an earlier class in the same invocation provisioned - one install and one setup for the whole
 * route. Every class that creates list data deletes it, which is what makes sharing the app safe.
 */
@RunWith(AndroidJUnit4::class)
class InsightsVisibilityTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-INSIGHTS")

    /** Every label below comes from [E2eLabels], so a ta-IN device resolves it too. */
    private val l: E2eLabels.Labels get() = h.labels

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun everyInsightsToggleBehaves() {
        FreshSetup(h).runIfNeeded()

        openChartsSection()

        // ── 1. the switches that are always on screen ──
        BASE_SWITCHES.forEach { label ->
            val initial = h.isChecked(label)
            check(initial != null) {
                "'$label' is not a switch — the row is not exposed as one\n${h.diagnose(label)}"
            }
            h.setChecked(label, !initial!!)
            h.setChecked(label, initial)
            h.log("switch-ok=$label")
        }

        // ── 2. both sub-modules start off, so their rows simply do not exist yet ──
        check(!h.find(l.insightsDailyScoreTrend)) {
            "a line-graph row is on screen while its sub-module is off"
        }
        check(!h.find(l.insightsOverallScoreCard)) {
            "a score-card row is on screen while its sub-module is off"
        }
        h.log("submodules-default-off=ok")

        // ── 3. opening one sub-module brings exactly its own rows ──
        h.setChecked(l.insightsScoreCards, true)
        SCORE_CARD_ROWS.forEach { label ->
            check(h.find(label)) { "'$label' did not appear with its sub-module on" }
            val initial = h.isChecked(label)
            check(initial != null) { "'$label' is not a switch\n${h.diagnose(label)}" }
            h.setChecked(label, !initial!!)
            h.setChecked(label, initial)
            h.log("switch-ok=$label")
        }
        h.log("score-cards-open=ok")

        h.setChecked(l.insightsLineGraphs, true)
        LINE_GRAPH_ROWS.forEach { label ->
            check(h.find(label)) { "'$label' did not appear with its sub-module on" }
            val initial = h.isChecked(label)
            check(initial != null) { "'$label' is not a switch\n${h.diagnose(label)}" }
            h.setChecked(label, !initial!!)
            h.setChecked(label, initial)
            h.log("switch-ok=$label")
        }
        h.log("line-graphs-open=ok")

        // ── 4. closing one sub-module takes only its own rows ──
        h.setChecked(l.insightsScoreCards, false)
        check(!h.find(l.insightsOverallScoreCard)) { "a score-card row survived its sub-module being off" }
        LINE_GRAPH_ROWS.forEach { label ->
            check(h.find(label)) { "'$label' disappeared when the OTHER sub-module was switched off" }
        }
        h.log("submodule-isolation=ok")
        h.setChecked(l.insightsScoreCards, true)
        check(h.find(l.insightsOverallScoreCard)) { "score-card rows did not return" }

        // ── 5. conditional rows exist once per enabled parent chart ──
        h.setChecked(l.lensWeekGrid, false)
        h.setChecked(l.lensMinutePattern, false)
        check(h.switchCount(l.chartExcludeEmptyDays) == 0) { "exclude-days row rendered with both parents off" }
        h.setChecked(l.lensWeekGrid, true)
        check(h.switchCount(l.chartExcludeEmptyDays) == 1) { "expected exactly one exclude-days row with one parent on" }
        h.setChecked(l.lensMinutePattern, true)
        check(h.switchCount(l.chartExcludeEmptyDays) == 2) { "expected two exclude-days rows with both parents on" }
        h.log("conditional-rows=ok")

        // ── 6. a switch changes what Lenses shows ──
        // "Average daily time" is the section this app renders even before any history exists, so it
        // is the one whose visibility can be asserted here.
        h.clickNav(l.lenses)
        rule.waitUntil(TEXT_WAIT_MS) { h.dumpTexts().size > 10 }
        check(h.seesText(l.lensTimeAverageDaily)) { "the average-daily-time section is missing while its switch is on" }
        h.log("lenses-visible=1")

        openChartsSection()
        h.setChecked(l.lensTimeAverageDaily, false)
        h.clickNav(l.lenses)
        rule.waitUntil(TEXT_WAIT_MS) { h.dumpTexts().size > 5 }
        check(!h.seesText(l.lensTimeAverageDaily)) { "the section is still on screen after its switch was switched off" }
        h.log("lenses-visible=0")

        openChartsSection()
        h.setChecked(l.lensTimeAverageDaily, true)
        h.clickNav(l.lenses)
        rule.waitUntil(TEXT_WAIT_MS) { h.dumpTexts().size > 10 }
        check(h.seesText(l.lensTimeAverageDaily)) { "the section did not return when its switch was switched back on" }
        h.log("lenses-visible=1-again")

        h.log("insights=done")
    }

    /**
     * Navigates to the switches, opening their card only if it is closed.
     *
     * Tapping the card header toggles it, so an unconditional tap closes a card that was already
     * open and every switch below then looks missing.
     */
    private fun openChartsSection() {
        h.clickNav(l.settings)
        h.assertVisible(l.settings)
        h.scrollTo(l.insightsChartsVisibility)
        if (!h.find(l.lensTimeAverageDaily)) {
            h.click(l.insightsChartsVisibility)
        }
        check(h.find(l.lensTimeAverageDaily)) { "the charts-visibility card did not open" }
    }

    private companion object {
        const val TEXT_WAIT_MS = 30_000L
    }

    /** Row labels read from the app's own copy, so a ta-IN device resolves them too. */
    private val BASE_SWITCHES: List<String>
        get() = listOf(
            l.locLensTimeAverageDailyTitle, l.locLensDimSplitTitle, l.locLensDimTrendTitle,
            l.locLensHeatmapTitle, l.locLensWeekGridTitle, l.locLensMinutePatternTitle,
            l.settingsInsightsTimeOverallSnapshotTitle, l.settingsInsightsTimeExecutionDetailsTitle, l.locTime,
            l.settingsDatabaseTasks, l.locHabits, l.locJournalNotes,
            l.settingsDatabaseNotes,
        )

    /** Row labels read from the app's own copy, so a ta-IN device resolves them too. */
    private val SCORE_CARD_ROWS: List<String>
        get() = listOf(
            l.settingsInsightsTimeOverallScoreCardTitle,
            l.settingsInsightsTimeDimensionScoreCardsTitle,
        )

    /** Row labels read from the app's own copy, so a ta-IN device resolves them too. */
    private val LINE_GRAPH_ROWS: List<String>
        get() = listOf(
            l.settingsInsightsTimeDailyScoreTrendTitle,
            l.settingsInsightsTimeProgressTrendTitle,
            l.settingsInsightsTimeHistoricalRankingTitle,
            l.settingsInsightsTimeMomentumStreakTitle,
        )
}
