//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.preferences

/**
 * The `app_settings` keys the shared state layer reads and writes.
 *
 * They live here, beside the ViewModels that use them, rather than in the Android-only preferences
 * ViewModel: a shared ViewModel cannot see `:app`, and a per-module copy of a key is a string that can
 * drift into a second setting - the failure mode this migration exists to remove.
 *
 * The values are the stored keys verbatim, so renaming one is a data migration rather than a refactor.
 */
object AppPreferenceKeys {
    /** The selected app language tag. */
    const val KEY_APP_LANGUAGE = "app_language"

    /** The persisted task sort key. */
    const val KEY_TASK_SORT_OPTION = "task_sort_option"

    /** The persisted task filter key. */
    const val KEY_TASK_FILTER_OPTION = "task_filter_option"

    /** The persisted habit sort key. */
    const val KEY_HABIT_SORT_OPTION = "habit_sort_option"

    /** Whether archived habits are listed. */
    const val KEY_SHOW_ARCHIVED_HABITS = "show_archived_habits"

    /** Whether completed habits are listed. */
    const val KEY_SHOW_COMPLETED_HABITS = "show_completed_habits"

    /** Whether habits fully marked today are hidden. */
    const val KEY_HIDE_ALL_MARKED_TODAY = "hide_all_marked_today"

    /** Whether the habits list shows only what is due today. */
    const val KEY_DUE_TODAY_ONLY = "due_today_only"

    /** The hour the app's day rolls over. */
    const val KEY_DAY_BOUNDARY_HOUR = "day_boundary_hour"

    /** Whether habit time is tracked automatically. */
    const val KEY_AUTO_TRACK_HABIT_TIME = "auto_track_habit_time_global"

    /** Prefix of the per-dimension auto-track flags. */
    const val KEY_AUTO_TRACK_DIMENSION_PREFIX = "auto_track_dimension_"

    /** The selected theme mode. Changing the stored value is a data migration, not a refactor. */
    const val KEY_THEME_MODE = "theme_mode"

    /** The selected font family. */
    const val KEY_FONT_FAMILY = "font_family"

    /** The 12-hour/24-hour time format. */
    const val KEY_TIME_FORMAT = "time_format"

    /** The hour height of the time screen's timeline, in dp. */
    const val KEY_TIME_HOUR_HEIGHT_DP = "time_hour_height_dp"

    /** The timeline scale key before it was renamed. Read only to migrate a value written by an older build. */
    const val KEY_TIME_SCALE_LEGACY_KEY = "time_scale"

    /** Whether automatic backups run. */
    const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"

    /** How often automatic backups run. */
    const val KEY_AUTO_BACKUP_INTERVAL = "auto_backup_interval"

    /** When the last automatic backup ran. */
    const val KEY_AUTO_BACKUP_LAST_RUN = "auto_backup_last_run"

    /** Whether old backups are rotated out. */
    const val KEY_BACKUP_ROTATION_ENABLED = "backup_rotation_enabled"

    /** How many backups are kept when rotation is on. */
    const val KEY_BACKUP_ROTATION_COUNT = "backup_rotation_count"

    /** Whether debug-level logging is on. */
    const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"

    /** Whether the first-run database setup finished. */
    const val KEY_DATABASE_INIT_COMPLETED = "database_init_completed"

    /** The active focus-mode preset's id. */
    const val KEY_ACTIVE_PRESET = "focus_mode_active_preset"

    /** Prefix of the per-tab visibility flags; the tab's id completes the key. */
    const val KEY_TAB_VISIBLE_PREFIX = "tab_visible_"

    /** Whether the focus-mode onboarding has been seen. */
    const val KEY_FOCUS_MODE_ONBOARDING_COMPLETED = "focus_mode_onboarding_completed"

    /**
     * The auto-lock timeout in minutes.
     *
     * Android writes this row AND mirrors it into the security preferences, which is what actually enforces
     * the lock; when the row is absent the value is DERIVED as twice [KEY_AUTO_BACKUP_INTERVAL] rather than
     * defaulted to a constant. The security preferences' own fallback (10 minutes) only covers the window
     * before that sync has run.
     */
    const val KEY_SESSION_TIMEOUT_MINUTES = "session_timeout_minutes"

    /** The route the app opens on. */
    const val KEY_LAUNCH_DESTINATION_ROUTE = "launch_destination_route"

    /** The task filter the launch destination opens with. */
    const val KEY_LAUNCH_DESTINATION_TASK_FILTER = "launch_destination_task_filter"

    /** Whether the time module chart is shown. */
    const val KEY_CHART_TIME_MODULE = "chart_time_module_enabled"

    /** Whether the overall time snapshot chart is shown. */
    const val KEY_CHART_TIME_OVERALL_SNAPSHOT = "chart_time_overall_snapshot_enabled"

    /** Whether the time execution details chart is shown. */
    const val KEY_CHART_TIME_EXECUTION_DETAILS = "chart_time_execution_details_enabled"

    /** Whether the time score cards chart is shown. */
    const val KEY_CHART_TIME_SCORE_CARDS = "chart_time_score_cards_enabled"

    /** Whether the overall score card chart is shown. */
    const val KEY_CHART_TIME_OVERALL_SCORE_CARD = "chart_time_overall_score_card_enabled"

    /** Whether the per-dimension score cards chart is shown. */
    const val KEY_CHART_TIME_DIM_SCORE_CARDS = "chart_time_dimension_score_cards_enabled"

    /** Whether the time line graphs chart is shown. */
    const val KEY_CHART_TIME_LINE_GRAPHS = "chart_time_line_graphs_enabled"

    /** Whether the daily score trend chart is shown. */
    const val KEY_CHART_TIME_DAILY_SCORE_TREND = "chart_time_daily_score_trend_enabled"

    /** Whether the progress trend chart is shown. */
    const val KEY_CHART_TIME_PROGRESS_TREND = "chart_time_progress_trend_enabled"

    /** Whether the historical ranking chart is shown. */
    const val KEY_CHART_TIME_HISTORICAL_RANKING = "chart_time_historical_ranking_enabled"

    /** Whether the momentum streak chart is shown. */
    const val KEY_CHART_TIME_MOMENTUM_STREAK = "chart_time_momentum_streak_enabled"

    /** Whether the task module chart is shown. */
    const val KEY_CHART_TASK_MODULE = "chart_task_module_enabled"

    /** Whether the habit module chart is shown. */
    const val KEY_CHART_HABIT_MODULE = "chart_habit_module_enabled"

    /** Whether the journal module chart is shown. */
    const val KEY_CHART_JOURNAL_MODULE = "chart_journal_module_enabled"

    /** Whether the note module chart is shown. */
    const val KEY_CHART_NOTE_MODULE = "chart_note_module_enabled"

    /** Whether the average daily time chart is shown. */
    const val KEY_CHART_AVERAGE_DAILY_TIME = "chart_average_daily_time_enabled"

    /** Whether the dimension split chart is shown. */
    const val KEY_CHART_DIM_SPLIT = "chart_dim_split_enabled"

    /** Whether the dimension trend chart is shown. */
    const val KEY_CHART_DIM_TREND = "chart_dim_trend_enabled"

    /** Whether the daily timeline chart is shown. */
    const val KEY_CHART_DAILY_TIMELINE = "chart_daily_timeline_enabled"

    /** Whether the weekly pattern chart is shown. */
    const val KEY_CHART_WEEKLY_PATTERN = "chart_weekly_pattern_enabled"

    /** Whether the daily rhythm chart is shown. */
    const val KEY_CHART_DAILY_RHYTHM = "chart_daily_rhythm_enabled"

    /** Whether empty days are excluded from the weekly pattern chart. */
    const val KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY = "chart_weekly_pattern_excl_empty"

    /** Whether empty days are excluded from the daily rhythm chart. */
    const val KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY = "chart_daily_rhythm_excl_empty"

    /**
     * Every chart-visibility row, as one set.
     *
     * The per-chart keys replaced a single coarse toggle, and a caller that HOLDS a key rather than
     * naming a method - the shared insights settings section does exactly that - needs something to
     * check it against. Without this set, a mistyped key would write a row nothing ever reads and the
     * toggle would look like it worked forever.
     *
     * It is a set and not the whole table because the other keys are values with a richer shape than a
     * boolean (a route, a count, an enum), so "is this a chart toggle" is a real question rather than a
     * tautology. It is spelled out here rather than derived by name prefix so that renaming a key
     * cannot silently add or drop a row.
     */
    val CHART_VISIBILITY_KEYS: Set<String> =
        setOf(
            KEY_CHART_TIME_MODULE,
            KEY_CHART_TIME_OVERALL_SNAPSHOT,
            KEY_CHART_TIME_EXECUTION_DETAILS,
            KEY_CHART_TIME_SCORE_CARDS,
            KEY_CHART_TIME_OVERALL_SCORE_CARD,
            KEY_CHART_TIME_DIM_SCORE_CARDS,
            KEY_CHART_TIME_LINE_GRAPHS,
            KEY_CHART_TIME_DAILY_SCORE_TREND,
            KEY_CHART_TIME_PROGRESS_TREND,
            KEY_CHART_TIME_HISTORICAL_RANKING,
            KEY_CHART_TIME_MOMENTUM_STREAK,
            KEY_CHART_TASK_MODULE,
            KEY_CHART_HABIT_MODULE,
            KEY_CHART_JOURNAL_MODULE,
            KEY_CHART_NOTE_MODULE,
            KEY_CHART_AVERAGE_DAILY_TIME,
            KEY_CHART_DIM_SPLIT,
            KEY_CHART_DIM_TREND,
            KEY_CHART_DAILY_TIMELINE,
            KEY_CHART_WEEKLY_PATTERN,
            KEY_CHART_DAILY_RHYTHM,
            KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY,
            KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY,
        )
}
