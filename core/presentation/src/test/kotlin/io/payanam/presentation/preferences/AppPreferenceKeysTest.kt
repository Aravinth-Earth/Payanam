//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.preferences

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.repository.AppSettingsRepository
import org.junit.Test

/**
 * Pins every `app_settings` key, so a rename fails the build instead of silently reverting a setting. The same
 * list catches two constants resolving to one value, which would make two settings share a row.
 */
class AppPreferenceKeysTest {
    private val stored: Map<String, String> =
        mapOf(
            "KEY_APP_LANGUAGE" to AppPreferenceKeys.KEY_APP_LANGUAGE,
            "KEY_TASK_SORT_OPTION" to AppPreferenceKeys.KEY_TASK_SORT_OPTION,
            "KEY_TASK_FILTER_OPTION" to AppPreferenceKeys.KEY_TASK_FILTER_OPTION,
            "KEY_HABIT_SORT_OPTION" to AppPreferenceKeys.KEY_HABIT_SORT_OPTION,
            "KEY_SHOW_ARCHIVED_HABITS" to AppPreferenceKeys.KEY_SHOW_ARCHIVED_HABITS,
            "KEY_SHOW_COMPLETED_HABITS" to AppPreferenceKeys.KEY_SHOW_COMPLETED_HABITS,
            "KEY_HIDE_ALL_MARKED_TODAY" to AppPreferenceKeys.KEY_HIDE_ALL_MARKED_TODAY,
            "KEY_DUE_TODAY_ONLY" to AppPreferenceKeys.KEY_DUE_TODAY_ONLY,
            "KEY_DAY_BOUNDARY_HOUR" to AppPreferenceKeys.KEY_DAY_BOUNDARY_HOUR,
            "KEY_AUTO_TRACK_HABIT_TIME" to AppPreferenceKeys.KEY_AUTO_TRACK_HABIT_TIME,
            "KEY_THEME_MODE" to AppPreferenceKeys.KEY_THEME_MODE,
            "KEY_FONT_FAMILY" to AppPreferenceKeys.KEY_FONT_FAMILY,
            "KEY_TIME_FORMAT" to AppPreferenceKeys.KEY_TIME_FORMAT,
            "KEY_TIME_HOUR_HEIGHT_DP" to AppPreferenceKeys.KEY_TIME_HOUR_HEIGHT_DP,
            "KEY_TIME_SCALE_LEGACY_KEY" to AppPreferenceKeys.KEY_TIME_SCALE_LEGACY_KEY,
            "KEY_TRACKING_SERVICE_STOP_PENDING" to AppPreferenceKeys.KEY_TRACKING_SERVICE_STOP_PENDING,
            "KEY_AUTO_BACKUP_ENABLED" to AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED,
            "KEY_AUTO_BACKUP_INTERVAL" to AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL,
            "KEY_AUTO_BACKUP_LAST_RUN" to AppPreferenceKeys.KEY_AUTO_BACKUP_LAST_RUN,
            "KEY_BACKUP_ROTATION_ENABLED" to AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED,
            "KEY_BACKUP_ROTATION_COUNT" to AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT,
            "KEY_DEBUG_LOGGING_ENABLED" to AppPreferenceKeys.KEY_DEBUG_LOGGING_ENABLED,
            "KEY_DATABASE_INIT_COMPLETED" to AppPreferenceKeys.KEY_DATABASE_INIT_COMPLETED,
            "KEY_FIRST_RUN_DIMENSIONS_PENDING" to AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING,
            "KEY_ACTIVE_PRESET" to AppPreferenceKeys.KEY_ACTIVE_PRESET,
            "KEY_FOCUS_MODE_ONBOARDING_COMPLETED" to AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED,
            "KEY_LAUNCH_DESTINATION_ROUTE" to AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE,
            "KEY_LAUNCH_DESTINATION_TASK_FILTER" to AppPreferenceKeys.KEY_LAUNCH_DESTINATION_TASK_FILTER,
            "KEY_SESSION_TIMEOUT_MINUTES" to AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES,
            "KEY_CHART_TIME_MODULE" to AppPreferenceKeys.KEY_CHART_TIME_MODULE,
            "KEY_CHART_TIME_OVERALL_SNAPSHOT" to AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SNAPSHOT,
            "KEY_CHART_TIME_EXECUTION_DETAILS" to AppPreferenceKeys.KEY_CHART_TIME_EXECUTION_DETAILS,
            "KEY_CHART_TIME_SCORE_CARDS" to AppPreferenceKeys.KEY_CHART_TIME_SCORE_CARDS,
            "KEY_CHART_TIME_OVERALL_SCORE_CARD" to AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SCORE_CARD,
            "KEY_CHART_TIME_DIM_SCORE_CARDS" to AppPreferenceKeys.KEY_CHART_TIME_DIM_SCORE_CARDS,
            "KEY_CHART_TIME_LINE_GRAPHS" to AppPreferenceKeys.KEY_CHART_TIME_LINE_GRAPHS,
            "KEY_CHART_TIME_DAILY_SCORE_TREND" to AppPreferenceKeys.KEY_CHART_TIME_DAILY_SCORE_TREND,
            "KEY_CHART_TIME_PROGRESS_TREND" to AppPreferenceKeys.KEY_CHART_TIME_PROGRESS_TREND,
            "KEY_CHART_TIME_HISTORICAL_RANKING" to AppPreferenceKeys.KEY_CHART_TIME_HISTORICAL_RANKING,
            "KEY_CHART_TIME_MOMENTUM_STREAK" to AppPreferenceKeys.KEY_CHART_TIME_MOMENTUM_STREAK,
            "KEY_CHART_TASK_MODULE" to AppPreferenceKeys.KEY_CHART_TASK_MODULE,
            "KEY_CHART_HABIT_MODULE" to AppPreferenceKeys.KEY_CHART_HABIT_MODULE,
            "KEY_CHART_JOURNAL_MODULE" to AppPreferenceKeys.KEY_CHART_JOURNAL_MODULE,
            "KEY_CHART_NOTE_MODULE" to AppPreferenceKeys.KEY_CHART_NOTE_MODULE,
            "KEY_CHART_AVERAGE_DAILY_TIME" to AppPreferenceKeys.KEY_CHART_AVERAGE_DAILY_TIME,
            "KEY_CHART_DIM_SPLIT" to AppPreferenceKeys.KEY_CHART_DIM_SPLIT,
            "KEY_CHART_DIM_TREND" to AppPreferenceKeys.KEY_CHART_DIM_TREND,
            "KEY_CHART_DAILY_TIMELINE" to AppPreferenceKeys.KEY_CHART_DAILY_TIMELINE,
            "KEY_CHART_WEEKLY_PATTERN" to AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN,
            "KEY_CHART_DAILY_RHYTHM" to AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM,
            "KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY" to AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY,
            "KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY" to AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY,
        )

    /**
     * Fails when a constant is added to [AppPreferenceKeys] without a matching list entry, and pins literals
     * only where asserted verbatim below. Reading the constants off the object keeps it independent of the lists.
     */
    @Test
    fun `the inventories together cover every key the object declares`() {
        val declared =
            AppPreferenceKeys::class.java.declaredFields
                .filter { it.name.startsWith("KEY_") }
                .onEach { it.isAccessible = true }
                .associate { it.name to it.get(null) as String }

        // Names AND values together: a name-only check passes an entry pointing at another constant's value.
        assertThat(stored + prefixes).isEqualTo(declared)
        assertThat(declared.values).doesNotContain("")
    }

    @Test
    fun `every stored key is unique`() {
        assertThat(stored.values).containsNoDuplicates()
    }

    @Test
    fun `the keys the platforms read directly are pinned verbatim`() {
        assertThat(AppPreferenceKeys.KEY_THEME_MODE).isEqualTo("theme_mode")
        assertThat(AppPreferenceKeys.KEY_APP_LANGUAGE).isEqualTo("app_language")
        assertThat(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE).isEqualTo("launch_destination_route")
        assertThat(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_TASK_FILTER)
            .isEqualTo("launch_destination_task_filter")
        assertThat(AppPreferenceKeys.KEY_TIME_FORMAT).isEqualTo("time_format")
        assertThat(AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED).isEqualTo("auto_backup_enabled")
        assertThat(AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL).isEqualTo("auto_backup_interval")
        assertThat(AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED).isEqualTo("backup_rotation_enabled")
        assertThat(AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT).isEqualTo("backup_rotation_count")
        assertThat(AppPreferenceKeys.KEY_DATABASE_INIT_COMPLETED).isEqualTo("database_init_completed")
        assertThat(AppPreferenceKeys.KEY_ACTIVE_PRESET).isEqualTo("focus_mode_active_preset")
        assertThat(AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES).isEqualTo("session_timeout_minutes")
    }

    /**
     * The init-completed flag is declared in two files; this keeps them one string. [AppSettingsRepository] may
     * not import this module, so the pair is held together here.
     */
    @Test
    fun `the shared contract's init-completed key and value are the ones this module writes`() {
        assertThat(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY)
            .isEqualTo(AppPreferenceKeys.KEY_DATABASE_INIT_COMPLETED)
        assertThat(AppSettingsRepository.INIT_COMPLETED_VALUE).isEqualTo("true")
    }

    /** Composed keys, kept out of [stored] because a prefix is a shape of key, not a key. */
    private val prefixes: Map<String, String> =
        mapOf(
            "KEY_TAB_VISIBLE_PREFIX" to AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX,
            "KEY_AUTO_TRACK_DIMENSION_PREFIX" to AppPreferenceKeys.KEY_AUTO_TRACK_DIMENSION_PREFIX,
        )

    @Test
    fun `a prefix key is a prefix and not itself a stored key`() {
        // Both composed keys are built as prefix + id. A prefix that was itself a stored key would be read by
        // the per-id lookup as a setting of its own, and answered for every id at once.
        prefixes.values.forEach { prefix ->
            assertThat(prefix).endsWith("_")
            assertThat(stored.values).doesNotContain(prefix)
        }
        assertThat(prefixes.values).containsNoDuplicates()
    }

    @Test
    fun `a composed key does not collide with a stored one`() {
        val composed = AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX + "tasks"

        assertThat(composed).isEqualTo("tab_visible_tasks")
        assertThat(stored.values).doesNotContain(composed)
    }

    @Test
    fun `the legacy timeline key is separate from the renamed one`() {
        // time_scale was renamed to time_hour_height_dp, and both are read. Treating them as one row would
        // migrate a stored value by accident on the first write.
        assertThat(AppPreferenceKeys.KEY_TIME_SCALE_LEGACY_KEY).isEqualTo("time_scale")
        assertThat(AppPreferenceKeys.KEY_TIME_HOUR_HEIGHT_DP).isEqualTo("time_hour_height_dp")
        assertThat(AppPreferenceKeys.KEY_TIME_SCALE_LEGACY_KEY)
            .isNotEqualTo(AppPreferenceKeys.KEY_TIME_HOUR_HEIGHT_DP)
    }
}
