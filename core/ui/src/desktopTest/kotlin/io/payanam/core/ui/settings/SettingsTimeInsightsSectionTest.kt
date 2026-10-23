//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.chartVisibility
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.preferences.AppPreferenceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The shared time-insights settings section, rendered for real on the JVM target.
 *
 * This section differs from the ones already shared in one way worth pinning: it takes ONE callback
 * carrying a preference KEY rather than one per row, because every toggle is the same write. What that
 * shape can silently get wrong is the PAIRING - a switch that draws one row's state while reporting
 * another row's key would render perfectly and overwrite the wrong preference - so these cases assert
 * the call the section makes against the row it draws, and never the copy.
 *
 * The nesting is the other thing a move can break: a chart is reachable only while its module is on, so
 * a section that "renders" can still draw nothing. The cases pin that rule from both sides, and the last
 * one pins the declaration against the writer's key set, which is the one mismatch that would both
 * render and discard an edit.
 */
class SettingsTimeInsightsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `time module off hides the charts and the caption beneath it`() {
        val writes = render(state(chartTimeModuleEnabled = false))

        composeRule.onAllNodesWithContentDescription(strings.locTime).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(strings.locTime).assertIsOff()
        // The overview caption is inside the module's content, so it goes with it.
        composeRule.onAllNodesWithText(strings.settingsInsightsTimeOverviewTitle).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(strings.settingsInsightsTimeOverallSnapshotTitle).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(strings.locLensWeekGridTitle).assertCountEquals(0)
        assertTrue("no write without a gesture", writes.isEmpty())
    }

    @Test
    fun `time module on shows its charts but not those of a group that is off`() {
        val writes =
            render(
                state(
                    chartTimeModuleEnabled = true,
                    chartTimeScoreCardsEnabled = true,
                    chartTimeLineGraphsEnabled = false,
                ),
            )

        composeRule.onAllNodesWithText(strings.settingsInsightsTimeOverviewTitle).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(strings.settingsInsightsTimeOverallSnapshotTitle).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(strings.settingsInsightsTimeOverallScoreCardTitle).assertCountEquals(1)
        // The line-graphs group shares the parent, and is off, so its four charts stay hidden.
        composeRule.onAllNodesWithContentDescription(strings.settingsInsightsTimeDailyScoreTrendTitle).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(strings.settingsInsightsTimeMomentumStreakTitle).assertCountEquals(0)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a write through the store reveals the rows gated behind it`() {
        val harness = Harness(state(chartTimeModuleEnabled = false))
        harness.render()

        // Nothing is drawn beneath the module until its own row is on.
        composeRule.onAllNodesWithContentDescription(strings.locLensWeekGridTitle).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsInsightsTimeOverviewTitle).assertCountEquals(0)

        // The write goes to the store and back through the state, exactly as a caller's repository does.
        harness.click(strings.locTime)

        composeRule.onAllNodesWithContentDescription(strings.locLensWeekGridTitle).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsInsightsTimeOverviewTitle).assertCountEquals(1)
        assertEquals(listOf(AppPreferenceKeys.KEY_CHART_TIME_MODULE to true), harness.writes)
        composeRule.onNodeWithContentDescription(strings.locTime).assertIsOn()
    }

    @Test
    fun `switch reports the requested value and not the one it is drawing`() {
        val harness = Harness(state(chartWeeklyPatternEnabled = false, chartWeeklyPatternExclEmpty = true))
        harness.render()

        // The excl-empty row is stored ON and drawn on, while the chart above it is stored OFF: clicking
        // the chart must report the value it was ASKED for, not the stale state it just read.
        harness.click(strings.locLensWeekGridTitle)
        assertEquals(listOf(AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN to true), harness.writes)

        harness.click(strings.locLensWeekGridTitle)
        assertEquals(
            listOf(
                AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN to true,
                AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN to false,
            ),
            harness.writes,
        )
    }

    @Test
    fun `a nested row draws its own state, not its parent chart's`() {
        render(
            state(
                chartTimeModuleEnabled = true,
                chartWeeklyPatternEnabled = true,
                chartWeeklyPatternExclEmpty = true,
                chartDailyRhythmEnabled = true,
                chartDailyRhythmExclEmpty = false,
            ),
        )

        // Both nested rows share ONE caption, so there are two nodes with it; the first belongs to the
        // weekly-pattern chart and must read that chart's own row.
        composeRule
            .onAllNodesWithContentDescription(strings.locSettingsChartExclEmptyDays)[0]
            .assertIsOn()
        composeRule
            .onAllNodesWithContentDescription(strings.locSettingsChartExclEmptyDays)[1]
            .assertIsOff()
    }

    @Test
    fun `a switch draws the row its reported key names`() {
        val writes =
            render(
                state(
                    chartTimeModuleEnabled = true,
                    chartDimSplitEnabled = true,
                    chartDimTrendEnabled = false,
                ),
            )

        composeRule.onAllNodesWithContentDescription(strings.locLensDimSplitTitle)[0].assertIsOn()
        composeRule.onAllNodesWithContentDescription(strings.locLensDimTrendTitle)[0].assertIsOff()

        // Clicking the drawn-ON row reports that row's own key, carrying the value it was asked for.
        composeRule.onAllNodesWithContentDescription(strings.locLensDimSplitTitle)[0].performClick()
        assertEquals(listOf(AppPreferenceKeys.KEY_CHART_DIM_SPLIT to false), writes)
    }

    @Test
    fun `leaf modules show the empty hint only while they are on`() {
        val writes =
            render(
                state(
                    chartTimeModuleEnabled = false,
                    chartTaskModuleEnabled = true,
                    chartNoteModuleEnabled = false,
                ),
            )

        // Task is on, so its hint is drawn; Note is off and has nothing beneath it.
        composeRule.onAllNodesWithText(strings.settingsInsightsChartsEmptyHint).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(strings.settingsDatabaseTasks).assertIsOn()
        composeRule.onNodeWithContentDescription(strings.settingsDatabaseNotes).assertIsOff()

        composeRule.onNodeWithContentDescription(strings.settingsDatabaseNotes).performClick()
        assertEquals(listOf(AppPreferenceKeys.KEY_CHART_NOTE_MODULE to true), writes)
    }

    @Test
    fun `every declared row is a key the writer accepts and every accepted key has a row`() {
        val declared = timeInsightsModules(strings).flatMap { it.flattenKeys() }

        // Both directions, because either half alone passes trivially: a section that dropped a row
        // would still be "all rows are writable", and an extra writer key would still be "all rows
        // render". A key with no row is an edit the user makes into nothing.
        assertEquals(AppPreferenceKeys.CHART_VISIBILITY_KEYS.toSortedSet(), declared.toSortedSet())
        assertEquals("no row may appear twice", declared.size, declared.toSet().size)
    }

    @Test
    fun `each row reads the field its own key names`() {
        val state =
            AppPreferencesState(
                chartWeeklyPatternExclEmpty = true,
                chartDailyRhythmExclEmpty = false,
            )

        assertTrue(state.chartVisibility(AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY))
        assertTrue(!state.chartVisibility(AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY))
    }

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    /** The keys a row and everything beneath it edit. */
    /**
     * The keys a row and everything beneath it edit.
     *
     * Caption nodes carry a marker rather than a key, so they are skipped here: the marker is not a
     * preference and the writer would reject it, which is the point.
     */
    private fun InsightsRow.flattenKeys(): List<String> =
        if (captionOnly) {
            emptyList()
        } else {
            listOf(key) + children.flatMap { it.flattenKeys() }
        }

    /**
     * Renders the section over [state] and returns the writes it reported.
     *
     * The list is the CALLBACK's record rather than a captured field, so an assertion about it is a
     * statement about what the section asked the caller to do.
     */
    private fun render(state: AppPreferencesState): List<Pair<String, Boolean>> =
        Harness(state).also { it.render() }.writes

    /**
     * Renders the section over a store the section does not own.
     *
     * The write goes back into [state] and the whole section is rebuilt from it, which is what a real
     * caller does: a settings write lands in `app_settings`, the next read rebuilds the state, and the
     * section redraws. A section handed a frozen state can only ever report a gesture, so a case that
     * expects rows to APPEAR has to drive the same round trip - otherwise it would be asserting that a
     * local mutable flag exists, which is the bug this move removed.
     */
    private inner class Harness(
        initial: AppPreferencesState,
    ) {
        private var state by mutableStateOf(initial)
        val writes = mutableListOf<Pair<String, Boolean>>()

        fun render() {
            composeRule.setContent {
                CompositionLocalProvider(LocalUiStrings provides strings) {
                    SettingsTimeInsightsSection(
                        prefsState = state,
                        onChartVisibilityChange = { key, enabled ->
                            writes += key to enabled
                            state = state.withChart(key, enabled)
                        },
                    )
                }
            }
        }

        /** Clicks the switch whose label is [label], ignoring captions, which draw no switch. */
        fun click(label: String) {
            composeRule.onAllNodesWithContentDescription(label)[0].performClick()
        }
    }

    /** The state after the write the section asked for - the round trip a caller's read performs. */
    private fun AppPreferencesState.withChart(key: String, enabled: Boolean): AppPreferencesState =
        when (key) {
            AppPreferenceKeys.KEY_CHART_TIME_MODULE -> copy(chartTimeModuleEnabled = enabled)
            AppPreferenceKeys.KEY_CHART_TIME_SCORE_CARDS -> copy(chartTimeScoreCardsEnabled = enabled)
            AppPreferenceKeys.KEY_CHART_TIME_LINE_GRAPHS -> copy(chartTimeLineGraphsEnabled = enabled)
            AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN -> copy(chartWeeklyPatternEnabled = enabled)
            AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY -> copy(chartWeeklyPatternExclEmpty = enabled)
            AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM -> copy(chartDailyRhythmEnabled = enabled)
            AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY -> copy(chartDailyRhythmExclEmpty = enabled)
            AppPreferenceKeys.KEY_CHART_DIM_SPLIT -> copy(chartDimSplitEnabled = enabled)
            AppPreferenceKeys.KEY_CHART_DIM_TREND -> copy(chartDimTrendEnabled = enabled)
            AppPreferenceKeys.KEY_CHART_TASK_MODULE -> copy(chartTaskModuleEnabled = enabled)
            AppPreferenceKeys.KEY_CHART_NOTE_MODULE -> copy(chartNoteModuleEnabled = enabled)
            else -> error("The harness does not model the row for $key")
        }

    private fun state(
        chartTimeModuleEnabled: Boolean = true,
        chartTimeScoreCardsEnabled: Boolean = false,
        chartTimeLineGraphsEnabled: Boolean = false,
        chartWeeklyPatternEnabled: Boolean = false,
        chartWeeklyPatternExclEmpty: Boolean = false,
        chartDailyRhythmEnabled: Boolean = false,
        chartDailyRhythmExclEmpty: Boolean = false,
        chartDimSplitEnabled: Boolean = false,
        chartDimTrendEnabled: Boolean = false,
        chartTaskModuleEnabled: Boolean = false,
        chartNoteModuleEnabled: Boolean = false,
    ) = AppPreferencesState(
        chartTimeModuleEnabled = chartTimeModuleEnabled,
        chartTimeScoreCardsEnabled = chartTimeScoreCardsEnabled,
        chartTimeLineGraphsEnabled = chartTimeLineGraphsEnabled,
        chartWeeklyPatternEnabled = chartWeeklyPatternEnabled,
        chartWeeklyPatternExclEmpty = chartWeeklyPatternExclEmpty,
        chartDailyRhythmEnabled = chartDailyRhythmEnabled,
        chartDailyRhythmExclEmpty = chartDailyRhythmExclEmpty,
        chartDimSplitEnabled = chartDimSplitEnabled,
        chartDimTrendEnabled = chartDimTrendEnabled,
        chartTaskModuleEnabled = chartTaskModuleEnabled,
        chartNoteModuleEnabled = chartNoteModuleEnabled,
    )
}