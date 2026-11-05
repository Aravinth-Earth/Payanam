//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.chartVisibility
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.AppLogger

/**
 * The marker key carried by a caption node.
 *
 * A caption has no preference behind it, so it has no key; this stands in for one so the declaration
 * stays a single tree rather than two, and the completeness check skips it by its name rather than by
 * position. It is not a valid preference key and the writer rejects anything that is not.
 */
internal const val CAPTION_OVERVIEW = "caption.time_insights.overview"

/**
 * Time insights settings section: which chart-visibility rows the dashboard may draw.
 *
 * WHY ONE KEYED CALLBACK RATHER THAN TWENTY-THREE NAMED ONES: every toggle here is the same operation -
 * one boolean into one column of the `app_settings` table under that row's own key - and the writer
 * already collapses them all to `setSetting(key, "true"|"false")`. Twenty-three named callbacks would
 * have stated twenty-three times that there is a single write, and would have pushed a `when` over the
 * row identity into the section, which is exactly where the key already is. This is deliberately not the
 * auto-backup shape: that section's six callbacks exist because those six are six DIFFERENT operations,
 * and a `key, enabled` pair would have hidden the very distinction worth naming there.
 *
 * The key is the whole contract, so a toggle's state is read through `chartVisibility` with the same
 * constant its callback reports. Reading the state by field name while writing by key would be the bug
 * this shape removes: the row would draw honestly and write someone else's preference.
 *
 * There is no coarse module-wide row left to fall back on - every toggle here is its own per-chart key,
 * and there are twenty-three of them in the shared catalogue, which is what this section renders.
 */
@Composable
fun SettingsTimeInsightsSection(
    prefsState: AppPreferencesState,
    onChartVisibilityChange: (key: String, enabled: Boolean) -> Unit,
) {
    val strings = LocalUiStrings.current
    val modules = remember(strings) { timeInsightsModules(strings) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = strings.settingsInsightsChartsVisibilityDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The first module nests its charts; the remaining three are leaf modules with no charts yet,
        // so a single loop over all four renders the section and the divider that separates them.
        modules.forEachIndexed { index, module ->
            if (index == 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            InsightsToggleGroup(
                group = module,
                prefsState = prefsState,
                onChartVisibilityChange = onChartVisibilityChange,
            )
        }
    }
}

/**
 * One rendered row: the preference key it edits, the words that name it, and what it contains.
 *
 * A row with [children] holds its charts while it is on; a row with an [emptyHint] and no children says
 * so instead. Declaring key, label, log line and containment TOGETHER is what keeps them in step - a
 * toggle drawn against the wrong constant would be a different row, silently, and the log line is the
 * only thing that would tell anyone.
 *
 * The log tag names the chart rather than the mechanism, because that is what someone reading a device
 * log after the fact is looking for; the writer's own line carries the key, so the pair still identifies
 * the row exactly.
 */
internal data class InsightsRow(
    val key: String,
    val label: String,
    val logTag: String,
    val logMessage: String,
    val indented: Boolean = false,
    val emptyHint: String? = null,
    val children: List<InsightsRow> = emptyList(),
    /** True for the caption nodes, which draw text and no switch. */
    val captionOnly: Boolean = false,
)

/**
 * The four module rows, each naming the preference key it edits.
 *
 * ORDER IS THE SECTION'S: the Time module first with its nested charts, then Task, Habit and Journal
 * then Note, each separated by the divider the loop above draws.
 */
internal fun timeInsightsModules(strings: UiStrings): List<InsightsRow> =
    listOf(
        InsightsRow(
            key = AppPreferenceKeys.KEY_CHART_TIME_MODULE,
            label = strings.locTime,
            logTag = "SettingsTimeInsightsSection.timeModule",
            logMessage = "Time insights module toggled",
            children =
                listOf(
                    InsightsRow(
                        key = CAPTION_OVERVIEW,
                        label = strings.settingsInsightsTimeOverviewTitle,
                        logTag = "",
                        logMessage = "",
                        captionOnly = true,
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SNAPSHOT,
                        label = strings.settingsInsightsTimeOverallSnapshotTitle,
                        logTag = "SettingsTimeInsightsSection.timeOverallSnapshot",
                        logMessage = "Time overall snapshot card toggled",
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_TIME_EXECUTION_DETAILS,
                        label = strings.settingsInsightsTimeExecutionDetailsTitle,
                        logTag = "SettingsTimeInsightsSection.timeExecutionDetails",
                        logMessage = "Time execution details toggled",
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_TIME_SCORE_CARDS,
                        label = strings.settingsInsightsTimeScoreCardsTitle,
                        logTag = "SettingsTimeInsightsSection.timeScoreCards",
                        logMessage = "Time score cards section toggled",
                        children =
                            listOf(
                                InsightsRow(
                                    key = AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SCORE_CARD,
                                    label = strings.settingsInsightsTimeOverallScoreCardTitle,
                                    logTag = "SettingsTimeInsightsSection.timeOverallScoreCard",
                                    logMessage = "Time overall score card toggled",
                                ),
                                InsightsRow(
                                    key = AppPreferenceKeys.KEY_CHART_TIME_DIM_SCORE_CARDS,
                                    label = strings.settingsInsightsTimeDimensionScoreCardsTitle,
                                    logTag = "SettingsTimeInsightsSection.timeDimensionScoreCards",
                                    logMessage = "Time dimension score cards toggled",
                                ),
                            ),
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_TIME_LINE_GRAPHS,
                        label = strings.settingsInsightsTimeLineGraphsTitle,
                        logTag = "SettingsTimeInsightsSection.timeLineGraphs",
                        logMessage = "Time line graphs section toggled",
                        children =
                            listOf(
                                InsightsRow(
                                    key = AppPreferenceKeys.KEY_CHART_TIME_DAILY_SCORE_TREND,
                                    label = strings.settingsInsightsTimeDailyScoreTrendTitle,
                                    logTag = "SettingsTimeInsightsSection.timeDailyScoreTrend",
                                    logMessage = "Time daily score trend toggled",
                                ),
                                InsightsRow(
                                    key = AppPreferenceKeys.KEY_CHART_TIME_PROGRESS_TREND,
                                    label = strings.settingsInsightsTimeProgressTrendTitle,
                                    logTag = "SettingsTimeInsightsSection.timeProgressTrend",
                                    logMessage = "Time progress trend toggled",
                                ),
                                InsightsRow(
                                    key = AppPreferenceKeys.KEY_CHART_TIME_HISTORICAL_RANKING,
                                    label = strings.settingsInsightsTimeHistoricalRankingTitle,
                                    logTag = "SettingsTimeInsightsSection.timeHistoricalRanking",
                                    logMessage = "Time historical ranking toggled",
                                ),
                                InsightsRow(
                                    key = AppPreferenceKeys.KEY_CHART_TIME_MOMENTUM_STREAK,
                                    label = strings.settingsInsightsTimeMomentumStreakTitle,
                                    logTag = "SettingsTimeInsightsSection.timeMomentumStreak",
                                    logMessage = "Time momentum streak toggled",
                                ),
                            ),
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_AVERAGE_DAILY_TIME,
                        label = strings.locLensTimeAverageDailyTitle,
                        logTag = "SettingsTimeInsightsSection.averageDailyTime",
                        logMessage = "Average daily time chart toggled",
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_DIM_SPLIT,
                        label = strings.locLensDimSplitTitle,
                        logTag = "SettingsTimeInsightsSection.dimSplit",
                        logMessage = "Chart dim split toggled",
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_DIM_TREND,
                        label = strings.locLensDimTrendTitle,
                        logTag = "SettingsTimeInsightsSection.dimTrend",
                        logMessage = "Chart dim trend toggled",
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_DAILY_TIMELINE,
                        label = strings.locLensHeatmapTitle,
                        logTag = "SettingsTimeInsightsSection.dailyTimeline",
                        logMessage = "Chart daily timeline toggled",
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN,
                        label = strings.locLensWeekGridTitle,
                        logTag = "SettingsTimeInsightsSection.weeklyPattern",
                        logMessage = "Chart weekly pattern toggled",
                        children =
                            listOf(
                                InsightsRow(
                                    key = AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY,
                                    label = strings.locSettingsChartExclEmptyDays,
                                    logTag = "SettingsTimeInsightsSection.weeklyPatternExclEmpty",
                                    logMessage = "Chart weekly pattern excl-empty toggled",
                                    indented = true,
                                ),
                            ),
                    ),
                    InsightsRow(
                        key = AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM,
                        label = strings.locLensMinutePatternTitle,
                        logTag = "SettingsTimeInsightsSection.dailyRhythm",
                        logMessage = "Chart daily rhythm toggled",
                        children =
                            listOf(
                                InsightsRow(
                                    key = AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY,
                                    label = strings.locSettingsChartExclEmptyDays,
                                    logTag = "SettingsTimeInsightsSection.dailyRhythmExclEmpty",
                                    logMessage = "Chart daily rhythm excl-empty toggled",
                                    indented = true,
                                ),
                            ),
                    ),
                ),
        ),
        InsightsRow(
            key = AppPreferenceKeys.KEY_CHART_TASK_MODULE,
            label = strings.settingsDatabaseTasks,
            logTag = "SettingsTimeInsightsSection.taskModule",
            logMessage = "Task insights module toggled",
            emptyHint = strings.settingsInsightsChartsEmptyHint,
        ),
        InsightsRow(
            key = AppPreferenceKeys.KEY_CHART_HABIT_MODULE,
            label = strings.locHabits,
            logTag = "SettingsTimeInsightsSection.habitModule",
            logMessage = "Habit insights module toggled",
            emptyHint = strings.settingsInsightsChartsEmptyHint,
        ),
        InsightsRow(
            key = AppPreferenceKeys.KEY_CHART_JOURNAL_MODULE,
            label = strings.locJournalNotes,
            logTag = "SettingsTimeInsightsSection.journalModule",
            logMessage = "Journal insights module toggled",
            emptyHint = strings.settingsInsightsChartsEmptyHint,
        ),
        InsightsRow(
            key = AppPreferenceKeys.KEY_CHART_NOTE_MODULE,
            label = strings.settingsDatabaseNotes,
            logTag = "SettingsTimeInsightsSection.noteModule",
            logMessage = "Note insights module toggled",
            emptyHint = strings.settingsInsightsChartsEmptyHint,
        ),
    )

/**
 * One row, and - while its own row is on - either its charts or the hint that it has none.
 *
 * The `when` on the row's children is the section's whole nesting rule, so it lives in one place rather
 * than being spelled out separately for the module row and the chart rows.
 */
@Composable
private fun InsightsToggleGroup(
    group: InsightsRow,
    prefsState: AppPreferencesState,
    onChartVisibilityChange: (key: String, enabled: Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (group.captionOnly) {
            Text(
                text = group.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        ChartToggleRow(
            row = group,
            prefsState = prefsState,
            onChartVisibilityChange = onChartVisibilityChange,
        )
        if (prefsState.chartVisibility(group.key)) {
            when {
                group.children.isNotEmpty() -> {
                    Column(
                        modifier = Modifier.padding(start = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        group.children.forEach { child ->
                            InsightsToggleGroup(
                                group = child,
                                prefsState = prefsState,
                                onChartVisibilityChange = onChartVisibilityChange,
                            )
                        }
                    }
                }

                !group.emptyHint.isNullOrBlank() -> {
                    Text(
                        text = group.emptyHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartToggleRow(
    row: InsightsRow,
    prefsState: AppPreferencesState,
    onChartVisibilityChange: (key: String, enabled: Boolean) -> Unit,
) {
    // Read from the shared holder rather than threaded in as a parameter: the type it returns is
    // declared by :core:common, which this module does not depend on, so naming that type here would
    // put :core:common back into the shared UI. Reading it once per row is what the other shared
    // sections do.
    val logger = remember { AppLogger.current }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = if (row.indented) 24.dp else 0.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            style = if (row.indented) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = if (row.indented) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = prefsState.chartVisibility(row.key),
            onCheckedChange = { enabled ->
                onChartVisibilityChange(row.key, enabled)
                logger.d(
                    row.logTag,
                    row.logMessage,
                    mapOf(
                        "key" to row.key,
                        "enabled" to enabled,
                    ),
                )
            },
            // The row's label is a separate Text, so without this the switch is announced as an
            // unlabelled control — on a screen of 21 switches that is unusable with a screen
            // reader, and it is also invisible to UI tests, which can only reach a control through
            // its accessibility node. The label lives in the semantics tree only; nothing about
            // the visible layout changes, and the switch stays the sole touch target.
            //
            // mergeDescendants is what makes the label and the control END UP ON THE SAME NODE: the
            // switch's own on/off state comes from the toggleable modifier applied inside Material3,
            // so a plain semantics block would leave a labelled node with no state next to a stateful
            // node with no label — which reads as "not a switch" to anything inspecting it.
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = row.label },
        )
    }
}
