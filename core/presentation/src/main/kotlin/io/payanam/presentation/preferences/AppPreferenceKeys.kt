//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.preferences

/**
 * The `app_settings` keys the shared state layer reads and writes.
 *
 * The values are the stored keys verbatim: renaming one or changing its stored value is a data migration,
 * not a refactor. They live here rather than in `:app` because a shared ViewModel cannot see that module.
 */
object AppPreferenceKeys {
    const val KEY_APP_LANGUAGE = "app_language"

    const val KEY_TASK_SORT_OPTION = "task_sort_option"

    const val KEY_TASK_FILTER_OPTION = "task_filter_option"

    const val KEY_HABIT_SORT_OPTION = "habit_sort_option"

    const val KEY_SHOW_ARCHIVED_HABITS = "show_archived_habits"

    const val KEY_SHOW_COMPLETED_HABITS = "show_completed_habits"

    const val KEY_HIDE_ALL_MARKED_TODAY = "hide_all_marked_today"

    const val KEY_DUE_TODAY_ONLY = "due_today_only"

    /** The hour the app's day rolls over. */
    const val KEY_DAY_BOUNDARY_HOUR = "day_boundary_hour"

    /** Global flag; a per-dimension row overrides it. */
    const val KEY_AUTO_TRACK_HABIT_TIME = "auto_track_habit_time_global"

    const val KEY_AUTO_TRACK_DIMENSION_PREFIX = "auto_track_dimension_"

    const val KEY_THEME_MODE = "theme_mode"

    const val KEY_FONT_FAMILY = "font_family"

    const val KEY_TIME_FORMAT = "time_format"

    const val KEY_TIME_HOUR_HEIGHT_DP = "time_hour_height_dp"

    /** Pre-rename key; read only to migrate a value an older build wrote. */
    const val KEY_TIME_SCALE_LEGACY_KEY = "time_scale"

    /** Pending-intent row: written before a stop is attempted, cleared once it lands. */
    const val KEY_TRACKING_SERVICE_STOP_PENDING = "tracking_service_stop_pending"

    const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"

    const val KEY_AUTO_BACKUP_INTERVAL = "auto_backup_interval"

    const val KEY_AUTO_BACKUP_LAST_RUN = "auto_backup_last_run"

    const val KEY_BACKUP_ROTATION_ENABLED = "backup_rotation_enabled"

    const val KEY_BACKUP_ROTATION_COUNT = "backup_rotation_count"

    const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"

    const val KEY_DATABASE_INIT_COMPLETED = "database_init_completed"

    /** First-run intent row: written on confirmation, cleared once the catalog write lands. */
    const val KEY_FIRST_RUN_DIMENSIONS_PENDING = "first_run_dimensions_pending"

    /** Stored as `focus_mode_active_preset`, not the constant's name. */
    const val KEY_ACTIVE_PRESET = "focus_mode_active_preset"

    /** Prefix; the tab's id completes the key. */
    const val KEY_TAB_VISIBLE_PREFIX = "tab_visible_"

    const val KEY_FOCUS_MODE_ONBOARDING_COMPLETED = "focus_mode_onboarding_completed"

    /** Absent value derives as twice [KEY_AUTO_BACKUP_INTERVAL], never a constant. */
    const val KEY_SESSION_TIMEOUT_MINUTES = "session_timeout_minutes"

    const val KEY_LAUNCH_DESTINATION_ROUTE = "launch_destination_route"

    const val KEY_LAUNCH_DESTINATION_TASK_FILTER = "launch_destination_task_filter"

    const val KEY_CHART_TIME_MODULE = "chart_time_module_enabled"

    const val KEY_CHART_TIME_OVERALL_SNAPSHOT = "chart_time_overall_snapshot_enabled"

    const val KEY_CHART_TIME_EXECUTION_DETAILS = "chart_time_execution_details_enabled"

    const val KEY_CHART_TIME_SCORE_CARDS = "chart_time_score_cards_enabled"

    const val KEY_CHART_TIME_OVERALL_SCORE_CARD = "chart_time_overall_score_card_enabled"

    const val KEY_CHART_TIME_DIM_SCORE_CARDS = "chart_time_dimension_score_cards_enabled"

    const val KEY_CHART_TIME_LINE_GRAPHS = "chart_time_line_graphs_enabled"

    const val KEY_CHART_TIME_DAILY_SCORE_TREND = "chart_time_daily_score_trend_enabled"

    const val KEY_CHART_TIME_PROGRESS_TREND = "chart_time_progress_trend_enabled"

    const val KEY_CHART_TIME_HISTORICAL_RANKING = "chart_time_historical_ranking_enabled"

    const val KEY_CHART_TIME_MOMENTUM_STREAK = "chart_time_momentum_streak_enabled"

    const val KEY_CHART_TASK_MODULE = "chart_task_module_enabled"

    const val KEY_CHART_HABIT_MODULE = "chart_habit_module_enabled"

    const val KEY_CHART_JOURNAL_MODULE = "chart_journal_module_enabled"

    const val KEY_CHART_NOTE_MODULE = "chart_note_module_enabled"

    const val KEY_CHART_AVERAGE_DAILY_TIME = "chart_average_daily_time_enabled"

    const val KEY_CHART_DIM_SPLIT = "chart_dim_split_enabled"

    const val KEY_CHART_DIM_TREND = "chart_dim_trend_enabled"

    const val KEY_CHART_DAILY_TIMELINE = "chart_daily_timeline_enabled"

    const val KEY_CHART_WEEKLY_PATTERN = "chart_weekly_pattern_enabled"

    const val KEY_CHART_DAILY_RHYTHM = "chart_daily_rhythm_enabled"

    const val KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY = "chart_weekly_pattern_excl_empty"

    const val KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY = "chart_daily_rhythm_excl_empty"

    /**
     * Every chart-visibility row, so a caller holding a key can validate it.
     *
     * Spelled out rather than derived by name prefix, so renaming a key cannot silently add or drop a row.
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
