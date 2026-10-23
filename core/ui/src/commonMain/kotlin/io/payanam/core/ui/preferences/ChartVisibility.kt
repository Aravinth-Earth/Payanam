//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.preferences

import io.payanam.presentation.preferences.AppPreferenceKeys

/**
 * One insights-chart visibility toggle: the preference row it owns, and how the shared state reports it.
 *
 * WHY A CATALOG: the chart toggles are a set of identically shaped booleans, and the settings section
 * that edits them lives in shared code while the rows are read out of [AppPreferencesState] by field
 * name. Naming the read next to the key it belongs to is what keeps those two in step - a toggle that
 * reported its state from another row's field, or wrote its own key and read a different one, would
 * render honestly and behave wrongly.
 */
data class ChartVisibilityToggle(
    /** The `app_settings` key this toggle reads and writes. */
    val key: String,
    /** The field on [AppPreferencesState] that carries the row's current value. */
    val read: (AppPreferencesState) -> Boolean,
)

/**
 * Every chart-visibility row, in the order the section renders them.
 *
 * The per-chart keys are the whole contract here: there is no coarse module-wide row left to fall back
 * on, so a toggle absent from this list is one the section cannot offer. Every write the section makes
 * is one of these keys - the writer collapses them all to `setSetting(key, "true"/"false")` - which is
 * why the section hands the CALLER a single key-and-value callback rather than 23 named ones.
 */
internal val CHART_VISIBILITY_TOGGLES: List<ChartVisibilityToggle> =
    listOf(
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_MODULE) { it.chartTimeModuleEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SNAPSHOT) { it.chartTimeOverallSnapshotEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_EXECUTION_DETAILS) { it.chartTimeExecutionDetailsEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_SCORE_CARDS) { it.chartTimeScoreCardsEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SCORE_CARD) { it.chartTimeOverallScoreCardEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_DIM_SCORE_CARDS) { it.chartTimeDimensionScoreCardsEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_LINE_GRAPHS) { it.chartTimeLineGraphsEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_DAILY_SCORE_TREND) { it.chartTimeDailyScoreTrendEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_PROGRESS_TREND) { it.chartTimeProgressTrendEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_HISTORICAL_RANKING) { it.chartTimeHistoricalRankingEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TIME_MOMENTUM_STREAK) { it.chartTimeMomentumStreakEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_AVERAGE_DAILY_TIME) { it.chartAverageDailyTimeEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_DIM_SPLIT) { it.chartDimSplitEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_DIM_TREND) { it.chartDimTrendEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_DAILY_TIMELINE) { it.chartDailyTimelineEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN) { it.chartWeeklyPatternEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY) { it.chartWeeklyPatternExclEmpty },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM) { it.chartDailyRhythmEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY) { it.chartDailyRhythmExclEmpty },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_TASK_MODULE) { it.chartTaskModuleEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_HABIT_MODULE) { it.chartHabitModuleEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_JOURNAL_MODULE) { it.chartJournalModuleEnabled },
        ChartVisibilityToggle(AppPreferenceKeys.KEY_CHART_NOTE_MODULE) { it.chartNoteModuleEnabled },
    )

/** The registered toggle for [key]. */
fun chartVisibilityToggle(key: String): ChartVisibilityToggle =
    CHART_VISIBILITY_TOGGLES.firstOrNull { it.key == key }
        ?: error("No chart visibility toggle registered for preference key: $key")

/**
 * The current value of the chart-visibility row [key].
 *
 * An unregistered key fails loudly rather than reading a default: a toggle that silently reported
 * `false` would look like an untouched chart, and the row the user edited would appear to have undone
 * itself.
 */
fun AppPreferencesState.chartVisibility(key: String): Boolean = chartVisibilityToggle(key).read(this)
