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
 * REQUIRES A FRESH INSTALL: run with `-KeepInstalled:$false`, like the other journey tests.
 */
@RunWith(AndroidJUnit4::class)
class InsightsVisibilityTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-INSIGHTS")

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun everyInsightsToggleBehaves() {
        FreshSetup(h).run()
        h.scrollTo("Verify and proceed")
        h.click("Verify and proceed")
        h.assertVisible("Per Dimension")

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
        check(!h.find("Daily score trend")) {
            "a line-graph row is on screen while its sub-module is off"
        }
        check(!h.find("Overall score card")) {
            "a score-card row is on screen while its sub-module is off"
        }
        h.log("submodules-default-off=ok")

        // ── 3. opening one sub-module brings exactly its own rows ──
        h.setChecked(SCORE_CARDS, true)
        SCORE_CARD_ROWS.forEach { label ->
            check(h.find(label)) { "'$label' did not appear with its sub-module on" }
            val initial = h.isChecked(label)
            check(initial != null) { "'$label' is not a switch\n${h.diagnose(label)}" }
            h.setChecked(label, !initial!!)
            h.setChecked(label, initial)
            h.log("switch-ok=$label")
        }
        h.log("score-cards-open=ok")

        h.setChecked(LINE_GRAPHS, true)
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
        h.setChecked(SCORE_CARDS, false)
        check(!h.find("Overall score card")) { "a score-card row survived its sub-module being off" }
        LINE_GRAPH_ROWS.forEach { label ->
            check(h.find(label)) { "'$label' disappeared when the OTHER sub-module was switched off" }
        }
        h.log("submodule-isolation=ok")
        h.setChecked(SCORE_CARDS, true)
        check(h.find("Overall score card")) { "score-card rows did not return" }

        // ── 5. conditional rows exist once per enabled parent chart ──
        h.setChecked(WEEKLY, false)
        h.setChecked(RHYTHM, false)
        check(h.switchCount(EXCLUDE) == 0) { "exclude-days row rendered with both parents off" }
        h.setChecked(WEEKLY, true)
        check(h.switchCount(EXCLUDE) == 1) { "expected exactly one exclude-days row with one parent on" }
        h.setChecked(RHYTHM, true)
        check(h.switchCount(EXCLUDE) == 2) { "expected two exclude-days rows with both parents on" }
        h.log("conditional-rows=ok")

        // ── 6. a switch changes what Lenses shows ──
        // "Average daily time" is the section this app renders even before any history exists, so it
        // is the one whose visibility can be asserted here.
        h.clickNav("Lenses")
        rule.waitUntil(TEXT_WAIT_MS) { h.dumpTexts().size > 10 }
        check(h.seesText(AVERAGE)) { "the average-daily-time section is missing while its switch is on" }
        h.log("lenses-visible=1")

        openChartsSection()
        h.setChecked(AVERAGE, false)
        h.clickNav("Lenses")
        rule.waitUntil(TEXT_WAIT_MS) { h.dumpTexts().size > 5 }
        check(!h.seesText(AVERAGE)) { "the section is still on screen after its switch was switched off" }
        h.log("lenses-visible=0")

        openChartsSection()
        h.setChecked(AVERAGE, true)
        h.clickNav("Lenses")
        rule.waitUntil(TEXT_WAIT_MS) { h.dumpTexts().size > 10 }
        check(h.seesText(AVERAGE)) { "the section did not return when its switch was switched back on" }
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
        h.clickNav("Settings")
        h.assertVisible("Settings")
        h.scrollTo(SECTION)
        if (!h.find(ANY_SWITCH)) {
            h.click(SECTION)
        }
        check(h.find(ANY_SWITCH)) { "the charts-visibility card did not open" }
    }

    private companion object {
        const val SECTION = "Insights Charts Visibility"
        const val ANY_SWITCH = "Average daily time"
        const val AVERAGE = "Average daily time"
        const val SCORE_CARDS = "Score cards"
        const val LINE_GRAPHS = "Line graphs"
        const val WEEKLY = "Weekly Pattern"
        const val RHYTHM = "Daily Rhythm"
        const val EXCLUDE = "Exclude days with no tracking"
        const val TEXT_WAIT_MS = 30_000L

        /** Switch rows that are on screen whether or not any sub-module is open. */
        val BASE_SWITCHES = listOf(
            "Average daily time", "Time by Dimension", "Dimension Trend", "Daily Timeline",
            "Weekly Pattern", "Daily Rhythm",
            "Overall snapshot card", "Execution details",
            "Time", "Tasks", "Habits", "Journal Notes", "Notes",
        )

        /** Only composed once "Score cards" is on (it defaults to off). */
        val SCORE_CARD_ROWS = listOf("Overall score card", "Per-dimension score cards")

        /** Only composed once "Line graphs" is on (it defaults to off). */
        val LINE_GRAPH_ROWS = listOf(
            "Daily score trend", "Progress trend", "Historical ranking by day", "Momentum streak trend",
        )
    }
}
