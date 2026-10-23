//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.preferences

import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.settings.FocusModePreset
import java.time.LocalDate
import java.util.Locale

/**
 * Decides whether a label a user typed is really the app's own label for that dimension, so a rename
 * that matches the shipped translation is stored as "use the default" rather than as a custom label.
 *
 * A contract rather than a function because the two catalogs it compares against
 * (`localizedCatalogLabel`, `knownAppOwnedCatalogLabels`) live in `:core:ui`, and `:core:ui` depends on
 * THIS module - the edge cannot be reversed, so the decision has to be handed in rather than called. The
 * platform that renders the labels is the one that owns them, which is also the one that can answer.
 */
fun interface DimensionLabelNormalizer {
    /**
     * Whether [candidateLabel] is the app's own label for [canonicalId] in [effectiveLanguageTag], and so
     * means "revert to the default" rather than "this is a custom label".
     */
    fun isAppOwnedLabel(
        canonicalId: String,
        candidateLabel: String,
        effectiveLanguageTag: String,
    ): Boolean
}

/**
 * The shared write path for app settings: one place that decides what each settings control stores.
 *
 * WHY A WRITER AND NOT A SHARED VIEWMODEL: the reads are already shared - `mapAppPreferences` in
 * `:core:ui` turns the stored rows plus the dimension catalog into `AppPreferencesState`, and a settings
 * screen on either platform renders that state. What was left behind in `:app` was the other half: the
 * 47 `set*` methods of `AppPreferencesViewModel`, each of which re-derives a key, clamps a value and
 * writes a row. A shared screen cannot own those, because the settings UI files take their writes as
 * PARAMETERS rather than holding an Android ViewModel - the arrangement already proven by the Time screen
 * (`onTimeHourHeightDpChange`) and by `DayPlanTemplateScreen.onNameChange`. This class is what those
 * parameters are bound to.
 *
 * So the seam is the WRITE PATH, not the state: a plain class over the two repositories the writes
 * actually touch ([AppSettingsRepository] for the `app_settings` rows, [LifeDimensionCatalogRepository]
 * for the dimension rows that live in their own table), reached through [io.payanam.presentation.data.
 * PayanamDataLayer] rather than injected. Both platforms already hold that layer, so neither needs a DI
 * container for this, and the desktop gets the identical rows without a second implementation.
 *
 * WHY THE PARAMETERS ARE PRIMITIVES: this module sits BELOW `:core:ui`, which is where `ThemeModeOption`,
 * `FontFamilyOption`, `BackupInterval` and Compose's `Color` live. Passing those types would make this
 * class uncompilable here, and passing them by `.key` string from the caller is what lets the shared
 * screen stay the single owner of what each control MEANS: this class owns what gets STORED, the caller
 * owns what the choice was. A `Boolean` toggle and a `Color` both arrive here as the string the row
 * holds.
 *
 * Every write goes through [AppSettingsRepository.setSetting], and every write is logged through
 * [AppLogger] - the same logger contract the rest of this layer uses, so a settings write is visible from
 * either platform without a second logging convention.
 */
class AppPreferencesWriter(
    private val appSettings: AppSettingsRepository,
    private val lifeDimensions: LifeDimensionCatalogRepository,
    private val scoreRollupTrigger: ScoreRollupTrigger,
    private val labelNormalizer: DimensionLabelNormalizer,
) {
    // ---------------------------------------------------------------- appearance

    /** Persists the chosen UI theme mode, stored as [key]. */
    suspend fun setThemeMode(key: String) {
        setSetting(AppPreferenceKeys.KEY_THEME_MODE, key)
    }

    /** Persists the chosen app language, stored as [key]. */
    suspend fun setAppLanguage(key: String) {
        setSetting(AppPreferenceKeys.KEY_APP_LANGUAGE, key)
    }

    /** Persists the chosen font family, stored as [key]. */
    suspend fun setFontFamily(key: String) {
        setSetting(AppPreferenceKeys.KEY_FONT_FAMILY, key)
    }

    /** Persists the chosen clock format, stored as [key]. */
    suspend fun setTimeFormat(key: String) {
        setSetting(AppPreferenceKeys.KEY_TIME_FORMAT, key)
    }

    /**
     * Persists the timeline hour height, formatted the way the stored row expects it.
     *
     * The clamp bounds themselves live in `:core:ui`, above this module, so they are applied by the
     * caller; this method owns the storage format, because a caller that formatted the float itself
     * would write a row the shared reader parses as a different height - and a locale that renders a
     * decimal comma would write one it fails to parse at all.
     */
    suspend fun setTimeHourHeightDp(clampedHourHeightDp: Float) {
        setSetting(AppPreferenceKeys.KEY_TIME_HOUR_HEIGHT_DP, formatHourHeightDp(clampedHourHeightDp))
    }

    // ---------------------------------------------------------------- dimensions

    /**
     * Persists a user-edited display label for the dimension [dimensionId].
     *
     * A label that merely restates the app's own label for that dimension in the UI's current language is
     * stored as the canonical default instead, so renaming a dimension to the word it already displayed
     * does not freeze that word into a custom label that stops following translations. A blank label resets
     * rather than storing an empty name, and a dimension id the app does not own keeps the trimmed text as
     * given, because there is no canonical label to fall back to.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: the normalization reads state the write does not own
    suspend fun setDimensionLabel(
        dimensionId: String,
        candidateLabel: String,
        effectiveLanguageTag: String,
    ) {
        val normalizedLabel = normalizeDimensionLabelForStorage(dimensionId, candidateLabel, effectiveLanguageTag)
        try {
            lifeDimensions.updateDimensionLabel(dimensionId, normalizedLabel)
            AppLogger.current.i(
                "AppPreferencesWriter.setDimensionLabel",
                "Dimension label updated in DB-backed catalog",
                mapOf("dimensionId" to dimensionId),
            )
        } catch (e: Exception) {
            AppLogger.current.e(
                "AppPreferencesWriter.setDimensionLabel",
                "Failed to update dimension label in DB-backed catalog",
                e,
                mapOf("dimensionId" to dimensionId),
            )
        }
    }

    /**
     * Clears the user-overridden label for [dimensionId], reverting to the canonical label.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: any repository call can throw
    suspend fun resetDimensionLabel(dimensionId: String) {
        try {
            lifeDimensions.updateDimensionLabel(
                dimensionId = dimensionId,
                label = defaultStoredLabelForDimension(dimensionId),
            )
            AppLogger.current.i(
                "AppPreferencesWriter.resetDimensionLabel",
                "Dimension label reset to app default in DB-backed catalog",
                mapOf("dimensionId" to dimensionId),
            )
        } catch (e: Exception) {
            AppLogger.current.e(
                "AppPreferencesWriter.resetDimensionLabel",
                "Failed to reset dimension label in DB-backed catalog",
                e,
                mapOf("dimensionId" to dimensionId),
            )
        }
    }

    /**
     * Persists the editable weight for [dimensionId], clamped to the supported 0.1..10.0 range, then
     * recomputes the L3 day-score layer so the new weight takes effect without waiting for tomorrow.
     *
     * The clamp is here rather than at the caller because an out-of-range weight is not a value the row
     * can hold: a caller that skipped it would write a weight the aggregation silently mis-scales.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: write + recalc; either can throw
    suspend fun setDimensionWeight(dimensionId: String, weight: Double) {
        val clamped = weight.coerceIn(MIN_DIMENSION_WEIGHT, MAX_DIMENSION_WEIGHT)
        try {
            lifeDimensions.updateDimensionWeight(dimensionId, clamped)
            AppLogger.current.i(
                "AppPreferencesWriter.setDimensionWeight",
                "Dimension weight updated",
                mapOf("dimensionId" to dimensionId, "weight" to clamped),
            )
            // L3-only recalc: day scores re-aggregate with the new weights; L1/L2 are untouched.
            scoreRollupTrigger.recalcDayOnly(LocalDate.now())
        } catch (e: Exception) {
            AppLogger.current.e(
                "AppPreferencesWriter.setDimensionWeight",
                "Failed to update dimension weight / recalc day layer",
                e,
                mapOf("dimensionId" to dimensionId, "weight" to clamped),
            )
        }
    }

    /** Persists a user-edited colour for [dimensionId], stored as the `#AARRGGBB` hex the row holds. */
    @Suppress("TooGenericExceptionCaught") // Intentional: any repository call can throw
    suspend fun setDimensionColor(dimensionId: String, storedHex: String) {
        try {
            lifeDimensions.updateDimensionColor(dimensionId, storedHex)
            AppLogger.current.i(
                "AppPreferencesWriter.setDimensionColor",
                "Dimension color updated in DB-backed catalog",
                mapOf("dimensionId" to dimensionId),
            )
        } catch (e: Exception) {
            AppLogger.current.e(
                "AppPreferencesWriter.setDimensionColor",
                "Failed to update dimension color in DB-backed catalog",
                e,
                mapOf("dimensionId" to dimensionId),
            )
        }
    }

    /** Persists the chosen icon for [dimensionId]. */
    @Suppress("TooGenericExceptionCaught") // Intentional: any repository call can throw
    suspend fun setDimensionIcon(dimensionId: String, iconKey: String) {
        try {
            lifeDimensions.updateDimensionIcon(dimensionId, iconKey)
            AppLogger.current.i(
                "AppPreferencesWriter.setDimensionIcon",
                "Dimension icon updated in DB-backed catalog",
                mapOf("dimensionId" to dimensionId, "iconKey" to iconKey),
            )
        } catch (e: Exception) {
            AppLogger.current.e(
                "AppPreferencesWriter.setDimensionIcon",
                "Failed to update dimension icon in DB-backed catalog",
                e,
                mapOf("dimensionId" to dimensionId, "iconKey" to iconKey),
            )
        }
    }

    /** Persists the visible/hidden toggle for [dimensionId]. */
    @Suppress("TooGenericExceptionCaught") // Intentional: any repository call can throw
    suspend fun setDimensionVisibility(dimensionId: String, isVisible: Boolean) {
        try {
            lifeDimensions.updateDimensionActiveState(dimensionId, isVisible)
            AppLogger.current.i(
                "AppPreferencesWriter.setDimensionVisibility",
                "Dimension visibility updated in DB-backed catalog",
                mapOf("dimensionId" to dimensionId, "isVisible" to isVisible),
            )
        } catch (e: Exception) {
            AppLogger.current.e(
                "AppPreferencesWriter.setDimensionVisibility",
                "Failed to update dimension visibility in DB-backed catalog",
                e,
                mapOf("dimensionId" to dimensionId, "isVisible" to isVisible),
            )
        }
    }

    // ---------------------------------------------------------------- backup

    /** Persists the auto-backup on/off toggle. */
    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        setSetting(AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED, enabled.toString())
    }

    /** Persists the auto-backup cadence, stored as [key]. */
    suspend fun setAutoBackupInterval(key: String) {
        setSetting(AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL, key)
    }

    /** Records the last successful auto-backup timestamp. */
    suspend fun setAutoBackupLastRun(timestamp: String) {
        setSetting(AppPreferenceKeys.KEY_AUTO_BACKUP_LAST_RUN, timestamp)
    }

    /**
     * Persists the retained-backup count, clamped to the supported 1..999 range.
     *
     * Clamping here rather than at the caller: zero retained backups is not a state the rotation worker
     * can act on, so an unclamped write would produce a setting that reads back as 999 by the gate below
     * and as something else by the worker that consumes it.
     */
    suspend fun setBackupRotationCount(count: Int) {
        val clamped = count.coerceIn(MIN_BACKUP_ROTATION_COUNT, MAX_BACKUP_ROTATION_COUNT)
        setSetting(AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT, clamped.toString())
    }

    /**
     * Persists the backup-rotation on/off toggle.
     *
     * The row is the whole of the shared decision; mirroring it into a platform preferences store is the
     * platform's own business and stays on the platform.
     */
    suspend fun setBackupRotationEnabled(enabled: Boolean) {
        setSetting(AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED, enabled.toString())
    }

    /**
     * Persists the debug-logging toggle.
     *
     * The row is the whole of the shared decision; applying it to the live logger and mirroring it for
     * the process-start path is the platform's own business and stays on the platform.
     */
    suspend fun setDebugLoggingEnabled(enabled: Boolean) {
        setSetting(AppPreferenceKeys.KEY_DEBUG_LOGGING_ENABLED, enabled.toString())
    }

    // ---------------------------------------------------------------- habits and day boundary

    /** Persists the day-boundary hour (0-5) used for daily rollups, clamped to the supported range. */
    suspend fun setDayBoundaryHour(hour: Int) {
        val clampedHour = hour.coerceIn(MIN_DAY_BOUNDARY_HOUR, MAX_DAY_BOUNDARY_HOUR)
        setSetting(AppPreferenceKeys.KEY_DAY_BOUNDARY_HOUR, clampedHour.toString())
    }

    /** Persists the global auto-track-habit-time toggle. */
    suspend fun setAutoTrackHabitTimeGlobal(enabled: Boolean) {
        setSetting(AppPreferenceKeys.KEY_AUTO_TRACK_HABIT_TIME, enabled.toString())
        AppLogger.current.i(
            "AppPreferencesWriter.setAutoTrackHabitTimeGlobal",
            "Auto-tracking global setting updated",
            mapOf("enabled" to enabled),
        )
    }

    /** Persists the auto-track toggle for the dimension [dimensionId]. */
    suspend fun setAutoTrackDimensionPreference(dimensionId: String, enabled: Boolean) {
        setSetting("${AppPreferenceKeys.KEY_AUTO_TRACK_DIMENSION_PREFIX}$dimensionId", enabled.toString())
        AppLogger.current.i(
            "AppPreferencesWriter.setAutoTrackDimensionPreference",
            "Auto-tracking dimension setting updated",
            mapOf("dimensionId" to dimensionId, "enabled" to enabled),
        )
    }

    // ---------------------------------------------------------------- focus mode and navigation

    /**
     * Sets the active focus mode preset and rewrites every tab's visibility to match it.
     *
     * All tab routes are written, not just the preset's visible ones: a route the previous preset had
     * visible would otherwise stay visible, so switching to a smaller preset would leave tabs on screen
     * that it does not include. The Settings tab is always written visible, because a focus mode that
     * hides the way out of itself is a trap.
     */
    suspend fun setActivePreset(preset: FocusModePreset) {
        setSetting(AppPreferenceKeys.KEY_ACTIVE_PRESET, preset.presetId)
        ALL_TAB_ROUTES.forEach { tabRoute ->
            val isVisible = tabRoute == SETTINGS_TAB_ROUTE || preset.visibleTabs.contains(tabRoute)
            setSetting("${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}$tabRoute", isVisible.toString())
        }
        AppLogger.current.i(
            "AppPreferencesWriter.setActivePreset",
            "Focus mode preset changed",
            mapOf(
                "preset" to preset.presetId,
                "visibleTabs" to preset.visibleTabs.joinToString(", "),
            ),
        )
    }

    /**
     * Toggles visibility of the individual tab [tabRoute].
     *
     * The Settings tab is refused rather than written, for the same reason it is always written visible
     * above: it is the route that leaves a focus mode.
     */
    suspend fun setTabVisibility(tabRoute: String, visible: Boolean) {
        if (tabRoute == SETTINGS_TAB_ROUTE) {
            AppLogger.current.w(
                "AppPreferencesWriter.setTabVisibility",
                "Attempted to hide Settings tab, ignoring",
            )
            return
        }
        setSetting("${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}$tabRoute", visible.toString())
        AppLogger.current.i(
            "AppPreferencesWriter.setTabVisibility",
            "Tab visibility changed",
            mapOf("tabRoute" to tabRoute, "visible" to visible),
        )
    }

    /** Marks focus mode onboarding as completed. This is a one-time flag. */
    suspend fun markFocusModeOnboardingCompleted() {
        setSetting(AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED, "true")
        AppLogger.current.i(
            "AppPreferencesWriter.markFocusModeOnboardingCompleted",
            "Focus mode onboarding marked as completed",
        )
    }

    /** Makes the Time screen the default launch destination. */
    suspend fun setLaunchDestinationTime() {
        setSetting(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE, "time")
        clearSetting(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_TASK_FILTER)
        AppLogger.current.i(
            "AppPreferencesWriter.setLaunchDestinationTime",
            "Default launch destination saved",
            mapOf("route" to "time"),
        )
    }

    /**
     * Makes the Tasks screen the default launch destination, with an optional [taskFilter] key.
     *
     * The stored filter is written to two keys because the row that decides the landing screen and the
     * row the Tasks screen reads its own filter from are separate, and writing only one leaves the two
     * disagreeing. A null [taskFilter] clears the landing row rather than writing an empty value, because
     * an empty filter key is not a filter the Tasks screen can act on.
     */
    suspend fun setLaunchDestinationTasks(taskFilterKey: String?) {
        setSetting(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE, "tasks")
        setSettingNullable(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_TASK_FILTER, taskFilterKey)
        if (taskFilterKey != null) {
            setSetting(AppPreferenceKeys.KEY_TASK_FILTER_OPTION, taskFilterKey)
        }
        AppLogger.current.i(
            "AppPreferencesWriter.setLaunchDestinationTasks",
            "Default launch destination updated",
            mapOf("route" to "tasks", "taskFilter" to (taskFilterKey ?: "none")),
        )
    }

    /** Persists the default launch-destination route [route]. */
    suspend fun setLaunchDestination(route: String) {
        setSetting(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE, route)
        clearSetting(AppPreferenceKeys.KEY_LAUNCH_DESTINATION_TASK_FILTER)
        AppLogger.current.i(
            "AppPreferencesWriter.setLaunchDestination",
            "Default launch destination saved",
            mapOf("route" to route),
        )
    }

    // ---------------------------------------------------------------- insights chart visibility

    /** Toggles the Time insights module on the dashboard. */
    suspend fun setChartTimeModuleEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_TIME_MODULE, enabled)

    /** Toggles the Time overall-snapshot card. */
    suspend fun setChartTimeOverallSnapshotEnabled(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SNAPSHOT, enabled)

    /** Toggles the Time execution-details card. */
    suspend fun setChartTimeExecutionDetailsEnabled(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_TIME_EXECUTION_DETAILS, enabled)

    /** Toggles the Time score-cards section. */
    suspend fun setChartTimeScoreCardsEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_TIME_SCORE_CARDS, enabled)

    /** Toggles the Time overall score card. */
    suspend fun setChartTimeOverallScoreCardEnabled(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SCORE_CARD, enabled)

    /** Toggles the per-dimension Time score cards. */
    suspend fun setChartTimeDimensionScoreCardsEnabled(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_TIME_DIM_SCORE_CARDS, enabled)

    /** Toggles the Time line-graphs section. */
    suspend fun setChartTimeLineGraphsEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_TIME_LINE_GRAPHS, enabled)

    /** Toggles the daily score-trend chart. */
    suspend fun setChartTimeDailyScoreTrendEnabled(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_TIME_DAILY_SCORE_TREND, enabled)

    /** Toggles the progress-trend chart. */
    suspend fun setChartTimeProgressTrendEnabled(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_TIME_PROGRESS_TREND, enabled)

    /** Toggles the historical-ranking chart. */
    suspend fun setChartTimeHistoricalRankingEnabled(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_TIME_HISTORICAL_RANKING, enabled)

    /** Toggles the momentum-streak chart. */
    suspend fun setChartTimeMomentumStreakEnabled(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_TIME_MOMENTUM_STREAK, enabled)

    /** Toggles the Task insights module. */
    suspend fun setChartTaskModuleEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_TASK_MODULE, enabled)

    /** Toggles the Habit insights module. */
    suspend fun setChartHabitModuleEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_HABIT_MODULE, enabled)

    /** Toggles the Journal insights module. */
    suspend fun setChartJournalModuleEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_JOURNAL_MODULE, enabled)

    /** Toggles the Note insights module. */
    suspend fun setChartNoteModuleEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_NOTE_MODULE, enabled)

    /** Toggles the average-daily-time chart. */
    suspend fun setChartAverageDailyTimeEnabled(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_AVERAGE_DAILY_TIME, enabled)

    /** Toggles the dimension-split chart. */
    suspend fun setChartDimSplitEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_DIM_SPLIT, enabled)

    /** Toggles the dimension-trend chart. */
    suspend fun setChartDimTrendEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_DIM_TREND, enabled)

    /** Toggles the daily-timeline chart. */
    suspend fun setChartDailyTimelineEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_DAILY_TIMELINE, enabled)

    /** Toggles the weekly-pattern chart. */
    suspend fun setChartWeeklyPatternEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN, enabled)

    /** Toggles the daily-rhythm chart. */
    suspend fun setChartDailyRhythmEnabled(enabled: Boolean) = setChart(AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM, enabled)

    /** Toggles excluding empty days from the weekly-pattern chart. */
    suspend fun setChartWeeklyPatternExclEmpty(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY, enabled)

    /** Toggles excluding empty days from the daily-rhythm chart. */
    suspend fun setChartDailyRhythmExclEmpty(enabled: Boolean) =
        setChart(AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY, enabled)

    /**
     * Toggles the chart-visibility row named by [key].
     *
     * The named overloads above stay, because they are what the app's own callers read and a typo in a
     * key cannot compile against them. This one exists for a caller that already HOLDS the key - the
     * shared insights section, whose toggles are the keys themselves - so a shared surface needs no
     * Android type to write one. An unknown key is rejected loudly: a mistyped key would otherwise
     * create a row nothing ever reads, and the toggle would appear to work forever.
     */
    suspend fun setChartVisibility(key: String, enabled: Boolean) {
        require(key in AppPreferenceKeys.CHART_VISIBILITY_KEYS) { "Not a chart-visibility preference key: $key" }
        setChart(key, enabled)
    }

    // ---------------------------------------------------------------- writes

    /**
     * Writes one chart-visibility toggle.
     *
     * Every chart toggle is this same row shape under a different key, so they share one writer and one
     * log line rather than 23 copies that can drift apart - the divergence this migration is removing
     * anyway, since each copy was hand-written next to the last.
     */
    private suspend fun setChart(key: String, enabled: Boolean) {
        setSetting(key, enabled.toString())
        AppLogger.current.i(
            "AppPreferencesWriter.setChart",
            "Chart visibility toggled",
            mapOf("key" to key, "enabled" to enabled),
        )
    }

    /**
     * Writes one `app_settings` row, logging a failure instead of propagating it.
     *
     * A settings write is a user gesture whose outcome the UI already reflects, so a failure here is a
     * missing log line rather than a crash in a toggle handler; the row keeps its previous value and the
     * next read shows the truth.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: any repository call can throw
    private suspend fun setSetting(key: String, value: String) {
        try {
            appSettings.setSetting(key, value)
            AppLogger.current.d(
                "AppPreferencesWriter.setSetting",
                "Setting updated",
                mapOf("key" to key, "value" to value),
            )
        } catch (e: Exception) {
            AppLogger.current.e(
                "AppPreferencesWriter.setSetting",
                "Failed to update setting",
                e,
                mapOf("key" to key),
            )
        }
    }

    /**
     * Clears one `app_settings` row.
     *
     * A cleared row and a row holding an empty string are different states: a reader that tests for a
     * blank value accepts both, and one that distinguishes "never chosen" from "chosen as none" does
     * not.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: any repository call can throw
    private suspend fun clearSetting(key: String) {
        try {
            appSettings.setSetting(key, null)
            AppLogger.current.d(
                "AppPreferencesWriter.clearSetting",
                "Setting cleared",
                mapOf("key" to key),
            )
        } catch (e: Exception) {
            AppLogger.current.e(
                "AppPreferencesWriter.clearSetting",
                "Failed to clear setting",
                e,
                mapOf("key" to key),
            )
        }
    }

    /** Writes [value], or clears the row when it is null. */
    private suspend fun setSettingNullable(key: String, value: String?) {
        if (value == null) {
            clearSetting(key)
        } else {
            setSetting(key, value)
        }
    }

    private fun normalizeDimensionLabelForStorage(
        dimensionId: String,
        candidateLabel: String,
        effectiveLanguageTag: String,
    ): String {
        val trimmedLabel = candidateLabel.trim()
        if (trimmedLabel.isBlank()) {
            return defaultStoredLabelForDimension(dimensionId)
        }
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id ?: return trimmedLabel
        val isAppOwned = labelNormalizer.isAppOwnedLabel(canonicalId, trimmedLabel, effectiveLanguageTag)
        return if (isAppOwned) defaultStoredLabelForDimension(dimensionId) else trimmedLabel
    }

    private fun defaultStoredLabelForDimension(dimensionId: String): String =
        DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.fallbackLabel ?: dimensionId

    companion object {
        /** The lowest stored dimension weight the L3 aggregation is defined for. */
        const val MIN_DIMENSION_WEIGHT = 0.1

        /** The highest stored dimension weight the L3 aggregation is defined for. */
        const val MAX_DIMENSION_WEIGHT = 10.0

        /** The fewest backups a rotation may retain. */
        const val MIN_BACKUP_ROTATION_COUNT = 1

        /** The most backups a rotation may retain. */
        const val MAX_BACKUP_ROTATION_COUNT = 999

        /** The earliest hour a day may be rolled up at. */
        const val MIN_DAY_BOUNDARY_HOUR = 0

        /** The latest hour a day may be rolled up at. */
        const val MAX_DAY_BOUNDARY_HOUR = 5

        /**
         * Every top-level tab route, in the order the preset rewrite writes them.
         *
         * A complete list rather than the union of the presets' routes, because the rewrite has to write
         * the routes a previous preset had made visible as well; a list assembled from the presets would
         * shrink as presets did and leave those rows behind.
         */
        internal val ALL_TAB_ROUTES = listOf("tasks", "habits", "time", "journal", "notes", "lenses", "settings")

        /** The tab that is always visible: the route that leaves a focus mode. */
        internal const val SETTINGS_TAB_ROUTE = "settings"

        /**
         * Formats a clamped timeline hour height the way the stored row expects it.
         *
         * Exposed because the clamp bounds themselves live in `:core:ui` (above this module) and the row
         * is written from both platforms: a caller that formatted differently would write a value the
         * shared reader parses as a different height. Locale-pinned so a locale that renders a decimal
         * comma cannot store a row the reader fails to parse.
         */
        fun formatHourHeightDp(hourHeightDp: Float): String = String.format(Locale.US, "%.2f", hourHeightDp)
    }
}