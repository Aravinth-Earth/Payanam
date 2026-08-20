//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.AppPreferencesViewModel

/**
 * Full SettingsCard wrapper for the insights charts visibility section.
 */
@Composable
internal fun InsightsChartsVisibilitySettingsCard(
    /** Expanded. */
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    /** Prefs state. */
    prefsState: AppPreferencesState,
    /** Prefs view model. */
    prefsViewModel: AppPreferencesViewModel,
    /** Logger. */
    logger: UnifiedLogger,
) {
    /** Settings card. */
    SettingsCard(
        title = stringResource(id = R.string.settings_insights_charts_visibility_title),
        icon = Icons.Default.TrendingUp,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        /** Insights charts visibility settings section. */
        InsightsChartsVisibilitySettingsSection(
            prefsState = prefsState,
            prefsViewModel = prefsViewModel,
            logger = logger,
        )
    }
}

@Composable
internal fun InsightsChartsVisibilitySettingsSection(
    /** Prefs state. */
    prefsState: AppPreferencesState,
    /** Prefs view model. */
    prefsViewModel: AppPreferencesViewModel,
    /** Logger. */
    logger: UnifiedLogger,
) {
    /** Column. */
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        /** Text. */
        Text(
            text = stringResource(id = R.string.settings_insights_charts_visibility_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        /** Module toggle section. */
        ModuleToggleSection(
            title = stringResource(id = R.string.loc_time),
            checked = prefsState.chartTimeModuleEnabled,
            onCheckedChange = { enabled ->
                prefsViewModel.setChartTimeModuleEnabled(enabled)
                logger.d(
                    "SettingsTimeInsightsSection.timeModule",
                    "Time insights module toggled",
                    /** Map of. */
                    mapOf("enabled" to enabled),
                )
            },
        ) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.settings_insights_time_overview_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            /** Chart toggle row. */
            ChartToggleRow(
                label = stringResource(id = R.string.settings_insights_time_overall_snapshot_title),
                checked = prefsState.chartTimeOverallSnapshotEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartTimeOverallSnapshotEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.timeOverallSnapshot",
                        "Time overall snapshot card toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            )

            /** Chart toggle row. */
            ChartToggleRow(
                label = stringResource(id = R.string.settings_insights_time_execution_details_title),
                checked = prefsState.chartTimeExecutionDetailsEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartTimeExecutionDetailsEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.timeExecutionDetails",
                        "Time execution details toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            )

            /** Module toggle section. */
            ModuleToggleSection(
                title = stringResource(id = R.string.settings_insights_time_score_cards_title),
                checked = prefsState.chartTimeScoreCardsEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartTimeScoreCardsEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.timeScoreCards",
                        "Time score cards section toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            ) {
                /** Chart toggle row. */
                ChartToggleRow(
                    label = stringResource(id = R.string.settings_insights_time_overall_score_card_title),
                    checked = prefsState.chartTimeOverallScoreCardEnabled,
                    onCheckedChange = { enabled ->
                        prefsViewModel.setChartTimeOverallScoreCardEnabled(enabled)
                        logger.d(
                            "SettingsTimeInsightsSection.timeOverallScoreCard",
                            "Time overall score card toggled",
                            /** Map of. */
                            mapOf("enabled" to enabled),
                        )
                    },
                )

                /** Chart toggle row. */
                ChartToggleRow(
                    label = stringResource(id = R.string.settings_insights_time_dimension_score_cards_title),
                    checked = prefsState.chartTimeDimensionScoreCardsEnabled,
                    onCheckedChange = { enabled ->
                        prefsViewModel.setChartTimeDimensionScoreCardsEnabled(enabled)
                        logger.d(
                            "SettingsTimeInsightsSection.timeDimensionScoreCards",
                            "Time dimension score cards toggled",
                            /** Map of. */
                            mapOf("enabled" to enabled),
                        )
                    },
                )
            }

            /** Module toggle section. */
            ModuleToggleSection(
                title = stringResource(id = R.string.settings_insights_time_line_graphs_title),
                checked = prefsState.chartTimeLineGraphsEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartTimeLineGraphsEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.timeLineGraphs",
                        "Time line graphs section toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            ) {
                /** Chart toggle row. */
                ChartToggleRow(
                    label = stringResource(id = R.string.settings_insights_time_daily_score_trend_title),
                    checked = prefsState.chartTimeDailyScoreTrendEnabled,
                    onCheckedChange = { enabled ->
                        prefsViewModel.setChartTimeDailyScoreTrendEnabled(enabled)
                        logger.d(
                            "SettingsTimeInsightsSection.timeDailyScoreTrend",
                            "Time daily score trend toggled",
                            /** Map of. */
                            mapOf("enabled" to enabled),
                        )
                    },
                )

                /** Chart toggle row. */
                ChartToggleRow(
                    label = stringResource(id = R.string.settings_insights_time_progress_trend_title),
                    checked = prefsState.chartTimeProgressTrendEnabled,
                    onCheckedChange = { enabled ->
                        prefsViewModel.setChartTimeProgressTrendEnabled(enabled)
                        logger.d(
                            "SettingsTimeInsightsSection.timeProgressTrend",
                            "Time progress trend toggled",
                            /** Map of. */
                            mapOf("enabled" to enabled),
                        )
                    },
                )

                /** Chart toggle row. */
                ChartToggleRow(
                    label = stringResource(id = R.string.settings_insights_time_historical_ranking_title),
                    checked = prefsState.chartTimeHistoricalRankingEnabled,
                    onCheckedChange = { enabled ->
                        prefsViewModel.setChartTimeHistoricalRankingEnabled(enabled)
                        logger.d(
                            "SettingsTimeInsightsSection.timeHistoricalRanking",
                            "Time historical ranking toggled",
                            /** Map of. */
                            mapOf("enabled" to enabled),
                        )
                    },
                )

                /** Chart toggle row. */
                ChartToggleRow(
                    label = stringResource(id = R.string.settings_insights_time_momentum_streak_title),
                    checked = prefsState.chartTimeMomentumStreakEnabled,
                    onCheckedChange = { enabled ->
                        prefsViewModel.setChartTimeMomentumStreakEnabled(enabled)
                        logger.d(
                            "SettingsTimeInsightsSection.timeMomentumStreak",
                            "Time momentum streak toggled",
                            /** Map of. */
                            mapOf("enabled" to enabled),
                        )
                    },
                )
            }

            /** Chart toggle row. */
            ChartToggleRow(
                label = stringResource(id = R.string.loc_lens_time_average_daily_title),
                checked = prefsState.chartAverageDailyTimeEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartAverageDailyTimeEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.averageDailyTime",
                        "Average daily time chart toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            )

            /** Chart toggle row. */
            ChartToggleRow(
                label = stringResource(id = R.string.loc_lens_dim_split_title),
                checked = prefsState.chartDimSplitEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartDimSplitEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.dimSplit",
                        "Chart dim split toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            )

            /** Chart toggle row. */
            ChartToggleRow(
                label = stringResource(id = R.string.loc_lens_dim_trend_title),
                checked = prefsState.chartDimTrendEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartDimTrendEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.dimTrend",
                        "Chart dim trend toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            )

            /** Chart toggle row. */
            ChartToggleRow(
                label = stringResource(id = R.string.loc_lens_heatmap_title),
                checked = prefsState.chartDailyTimelineEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartDailyTimelineEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.dailyTimeline",
                        "Chart daily timeline toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            )

            /** Chart toggle row. */
            ChartToggleRow(
                label = stringResource(id = R.string.loc_lens_week_grid_title),
                checked = prefsState.chartWeeklyPatternEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartWeeklyPatternEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.weeklyPattern",
                        "Chart weekly pattern toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            )
            /** If. */
            if (prefsState.chartWeeklyPatternEnabled) {
                /** Chart toggle row. */
                ChartToggleRow(
                    label = stringResource(id = R.string.loc_settings_chart_excl_empty_days),
                    checked = prefsState.chartWeeklyPatternExclEmpty,
                    indented = true,
                    onCheckedChange = { enabled ->
                        prefsViewModel.setChartWeeklyPatternExclEmpty(enabled)
                        logger.d(
                            "SettingsTimeInsightsSection.weeklyPatternExclEmpty",
                            "Chart weekly pattern excl-empty toggled",
                            /** Map of. */
                            mapOf("enabled" to enabled),
                        )
                    },
                )
            }

            /** Chart toggle row. */
            ChartToggleRow(
                label = stringResource(id = R.string.loc_lens_minute_pattern_title),
                checked = prefsState.chartDailyRhythmEnabled,
                onCheckedChange = { enabled ->
                    prefsViewModel.setChartDailyRhythmEnabled(enabled)
                    logger.d(
                        "SettingsTimeInsightsSection.dailyRhythm",
                        "Chart daily rhythm toggled",
                        /** Map of. */
                        mapOf("enabled" to enabled),
                    )
                },
            )
            /** If. */
            if (prefsState.chartDailyRhythmEnabled) {
                /** Chart toggle row. */
                ChartToggleRow(
                    label = stringResource(id = R.string.loc_settings_chart_excl_empty_days),
                    checked = prefsState.chartDailyRhythmExclEmpty,
                    indented = true,
                    onCheckedChange = { enabled ->
                        prefsViewModel.setChartDailyRhythmExclEmpty(enabled)
                        logger.d(
                            "SettingsTimeInsightsSection.dailyRhythmExclEmpty",
                            "Chart daily rhythm excl-empty toggled",
                            /** Map of. */
                            mapOf("enabled" to enabled),
                        )
                    },
                )
            }
        }

        /** Horizontal divider. */
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        /** Module toggle section. */
        ModuleToggleSection(
            title = stringResource(id = R.string.settings_database_tasks),
            checked = prefsState.chartTaskModuleEnabled,
            onCheckedChange = { enabled ->
                prefsViewModel.setChartTaskModuleEnabled(enabled)
                logger.d(
                    "SettingsTimeInsightsSection.taskModule",
                    "Task insights module toggled",
                    /** Map of. */
                    mapOf("enabled" to enabled),
                )
            },
            emptyHint = stringResource(id = R.string.settings_insights_charts_empty_hint),
        )

        /** Module toggle section. */
        ModuleToggleSection(
            title = stringResource(id = R.string.loc_habits),
            checked = prefsState.chartHabitModuleEnabled,
            onCheckedChange = { enabled ->
                prefsViewModel.setChartHabitModuleEnabled(enabled)
                logger.d(
                    "SettingsTimeInsightsSection.habitModule",
                    "Habit insights module toggled",
                    /** Map of. */
                    mapOf("enabled" to enabled),
                )
            },
            emptyHint = stringResource(id = R.string.settings_insights_charts_empty_hint),
        )

        /** Module toggle section. */
        ModuleToggleSection(
            title = stringResource(id = R.string.loc_journal_notes),
            checked = prefsState.chartJournalModuleEnabled,
            onCheckedChange = { enabled ->
                prefsViewModel.setChartJournalModuleEnabled(enabled)
                logger.d(
                    "SettingsTimeInsightsSection.journalModule",
                    "Journal insights module toggled",
                    /** Map of. */
                    mapOf("enabled" to enabled),
                )
            },
            emptyHint = stringResource(id = R.string.settings_insights_charts_empty_hint),
        )

        /** Module toggle section. */
        ModuleToggleSection(
            title = stringResource(id = R.string.settings_database_notes),
            checked = prefsState.chartNoteModuleEnabled,
            onCheckedChange = { enabled ->
                prefsViewModel.setChartNoteModuleEnabled(enabled)
                logger.d(
                    "SettingsTimeInsightsSection.noteModule",
                    "Note insights module toggled",
                    /** Map of. */
                    mapOf("enabled" to enabled),
                )
            },
            emptyHint = stringResource(id = R.string.settings_insights_charts_empty_hint),
        )
    }
}

@Composable
private fun ModuleToggleSection(
    /** Title. */
    title: String,
    /** Checked. */
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    emptyHint: String? = null,
    content: @Composable (() -> Unit)? = null,
) {
    /** Column. */
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        /** Chart toggle row. */
        ChartToggleRow(
            label = title,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        /** If. */
        if (checked) {
            /** If. */
            if (content != null) {
                /** Column. */
                Column(
                    modifier = Modifier.padding(start = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    /** Content. */
                    content()
                }
            } else if (!emptyHint.isNullOrBlank()) {
                /** Text. */
                Text(
                    text = emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun ChartToggleRow(
    /** Label. */
    label: String,
    /** Checked. */
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    indented: Boolean = false,
) {
    /** Row. */
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indented) 24.dp else 0.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /** Text. */
        Text(
            text = label,
            style = if (indented) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = if (indented) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        /** Switch. */
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
