//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

package io.payanam.core.ui.preferences

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.payanam.core.ui.model.DimensionIconCatalog
import io.payanam.core.ui.text.uiStringsFor
import io.payanam.core.ui.theme.LifeDimensionColors
import io.payanam.core.ui.time.MAX_TIME_HOUR_HEIGHT_DP
import io.payanam.core.ui.time.MIN_TIME_HOUR_HEIGHT_DP
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.port.BackupStatusSnapshot
import io.payanam.presentation.dimensions.DimensionTextCatalog
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.presentation.tasks.TaskFilter
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.settings.FocusModePreset

/**
 * Maps the `app_settings` rows onto [AppPreferencesState] - ONE function for every platform.
 *
 * This is where the cross-platform parity is enforced: Android and the desktop read the same rows, so they
 * must produce the same state from them, and the only way to keep that true is for there to be exactly one
 * place that decides what each row means. A platform that grew its own reader would be the divergence this
 * shared state exists to remove.
 *
 * Pure by construction: no `Context`, no resource table, no repository. Everything a platform supplies that
 * is not a row arrives as a parameter, so the function is testable over a map literal.
 *
 * @param settings every `app_settings` row, keyed by [AppPreferenceKeys]; a missing key means the default
 *   below, which is Android's own absent-row fallback.
 * @param catalogRows the configured life-dimension rows. The desktop passes an EMPTY list (it renders no
 *   dimension surface yet), which is why [AppPreferencesState.dimensionPreferences] and
 *   [AppPreferencesState.autoTrackDimensionPreferences] come out empty there - a declared divergence, not an
 *   accident. A named argument, deliberately with no default: a default would let a platform pass nothing by
 *   omission and quietly lose the parity this parameter exists to carry.
 * @param systemLanguageTag the platform's current system language, used only when the stored choice is
 *   [AppLanguageOption.SYSTEM].
 * @param backupStatus the live backup status, or null where the platform keeps no such store (the desktop
 *   today): null means the `autoBackup*` fields fall back to the stored rows alone.
 * @param defaultDebugLoggingEnabled the value used when `debug_logging_enabled` has no row. It is a
 *   parameter because the honest default differs per platform - Android's is `BuildConfig.DEBUG`, a released
 *   desktop build's is false - and neither belongs inside a shared function.
 */
fun mapAppPreferences(
    settings: Map<String, String?>,
    catalogRows: List<ConfiguredLifeDimension>,
    systemLanguageTag: String?,
    backupStatus: BackupStatusSnapshot?,
    defaultDebugLoggingEnabled: Boolean,
): AppPreferencesState {
    val themeMode = ThemeModeOption.fromKey(settings[AppPreferenceKeys.KEY_THEME_MODE]) ?: ThemeModeOption.SYSTEM
    val appLanguage =
        AppLanguageOption.fromKey(settings[AppPreferenceKeys.KEY_APP_LANGUAGE]) ?: AppLanguageOption.SYSTEM
    val effectiveLanguageTag = resolveEffectiveLanguageTag(appLanguage, systemLanguageTag)
    val fontFamily =
        FontFamilyOption.fromKey(settings[AppPreferenceKeys.KEY_FONT_FAMILY]) ?: FontFamilyOption.SANS_SERIF
    val timeFormat =
        TimeFormatOption.fromKey(settings[AppPreferenceKeys.KEY_TIME_FORMAT]) ?: TimeFormatOption.TWENTY_FOUR
    val timeHourHeightDp = resolveTimeHourHeightDp(settings)
    val (dimensionPrefs, dynamicDimensionOptions) =
        buildDimensionCatalogUiState(
            dimensions = catalogRows,
            effectiveLanguageTag = effectiveLanguageTag,
        )
    val autoBackupEnabled = settings[AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED]?.toBoolean() ?: false
    val autoBackupInterval =
        BackupInterval.fromKey(settings[AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL]) ?: BackupInterval.SIXTY_MIN
    val autoBackupLastRun = backupStatus?.lastSuccessDisplay ?: settings[AppPreferenceKeys.KEY_AUTO_BACKUP_LAST_RUN]
    val backupFailureStatus = backupStatus?.latestFailure
    val backupRotationEnabled = settings[AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED]?.toBoolean() ?: false
    val backupRotationCount = settings[AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT]?.toIntOrNull()?.coerceIn(1, 999) ?: 50
    val dayBoundaryHour = settings[AppPreferenceKeys.KEY_DAY_BOUNDARY_HOUR]?.toIntOrNull()?.coerceIn(0, 5) ?: 0
    val debugLoggingEnabled =
        settings[AppPreferenceKeys.KEY_DEBUG_LOGGING_ENABLED]?.toBoolean() ?: defaultDebugLoggingEnabled
    val databaseInitCompleted = settings[AppPreferenceKeys.KEY_DATABASE_INIT_COMPLETED]?.toBoolean() ?: false
    // Auto-tracking habit completion time preferences
    val autoTrackHabitTimeGlobal = settings[AppPreferenceKeys.KEY_AUTO_TRACK_HABIT_TIME]?.toBoolean() ?: false
    val autoTrackDimensionIds = (dimensionPrefs.map { it.id } + dynamicDimensionOptions.map { it.id }).distinct()
    val autoTrackDimensionPrefs =
        autoTrackDimensionIds.associateWith { dimensionId ->
            settings["${AppPreferenceKeys.KEY_AUTO_TRACK_DIMENSION_PREFIX}$dimensionId"]?.toBoolean()
                ?: autoTrackHabitTimeGlobal
        }
    // Focus Mode preferences
    val activePreset = FocusModePreset.fromPresetId(settings[AppPreferenceKeys.KEY_ACTIVE_PRESET])
    val tabVisibility =
        ALL_TAB_ROUTES.associateWith { tabRoute ->
            if (tabRoute == SETTINGS_TAB_ROUTE) {
                true // Settings tab is always visible
            } else {
                settings["${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}$tabRoute"]?.toBoolean()
                    ?: activePreset.visibleTabs.contains(tabRoute)
            }
        }
    val focusModeOnboardingCompleted =
        settings[AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED]?.toBoolean() ?: false
    val currentTaskFilter = TaskFilter.fromKey(settings[AppPreferenceKeys.KEY_TASK_FILTER_OPTION])
    val launchDestinationRoute = settings[AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE] ?: DEFAULT_LAUNCH_ROUTE
    val launchDestinationTaskFilter = TaskFilter.fromKey(settings[AppPreferenceKeys.KEY_LAUNCH_DESTINATION_TASK_FILTER])
    val launchDestination =
        LaunchDestination(
            route = launchDestinationRoute,
            taskFilter = launchDestinationTaskFilter,
        )
    // Insights charts visibility prefs
    val chartTimeModuleEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_MODULE]?.toBoolean() ?: true
    val chartTimeOverallSnapshotEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SNAPSHOT]?.toBoolean() ?: false
    val chartTimeExecutionDetailsEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_EXECUTION_DETAILS]?.toBoolean() ?: false
    val chartTimeScoreCardsEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_SCORE_CARDS]?.toBoolean() ?: false
    val chartTimeOverallScoreCardEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SCORE_CARD]?.toBoolean() ?: false
    val chartTimeDimensionScoreCardsEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_DIM_SCORE_CARDS]?.toBoolean() ?: false
    val chartTimeLineGraphsEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_LINE_GRAPHS]?.toBoolean() ?: false
    val chartTimeDailyScoreTrendEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_DAILY_SCORE_TREND]?.toBoolean() ?: false
    val chartTimeProgressTrendEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_PROGRESS_TREND]?.toBoolean() ?: false
    val chartTimeHistoricalRankingEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_HISTORICAL_RANKING]?.toBoolean() ?: false
    val chartTimeMomentumStreakEnabled = settings[AppPreferenceKeys.KEY_CHART_TIME_MOMENTUM_STREAK]?.toBoolean() ?: false
    val chartTaskModuleEnabled = settings[AppPreferenceKeys.KEY_CHART_TASK_MODULE]?.toBoolean() ?: false
    val chartHabitModuleEnabled = settings[AppPreferenceKeys.KEY_CHART_HABIT_MODULE]?.toBoolean() ?: false
    val chartJournalModuleEnabled = settings[AppPreferenceKeys.KEY_CHART_JOURNAL_MODULE]?.toBoolean() ?: false
    val chartNoteModuleEnabled = settings[AppPreferenceKeys.KEY_CHART_NOTE_MODULE]?.toBoolean() ?: false
    val chartAverageDailyTimeEnabled = settings[AppPreferenceKeys.KEY_CHART_AVERAGE_DAILY_TIME]?.toBoolean() ?: true
    val chartDimSplitEnabled = settings[AppPreferenceKeys.KEY_CHART_DIM_SPLIT]?.toBoolean() ?: false
    val chartDimTrendEnabled = settings[AppPreferenceKeys.KEY_CHART_DIM_TREND]?.toBoolean() ?: false
    val chartDailyTimelineEnabled = settings[AppPreferenceKeys.KEY_CHART_DAILY_TIMELINE]?.toBoolean() ?: false
    val chartWeeklyPatternEnabled = settings[AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN]?.toBoolean() ?: false
    val chartDailyRhythmEnabled = settings[AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM]?.toBoolean() ?: false
    val chartWeeklyPatternExclEmpty = settings[AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY]?.toBoolean() ?: false
    val chartDailyRhythmExclEmpty = settings[AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY]?.toBoolean() ?: false
    return AppPreferencesState(
        themeMode = themeMode,
        appLanguage = appLanguage,
        effectiveLanguageTag = effectiveLanguageTag,
        fontFamily = fontFamily,
        timeFormat = timeFormat,
        timeHourHeightDp = timeHourHeightDp,
        dimensionPreferences = dimensionPrefs,
        dynamicDimensionOptions = dynamicDimensionOptions,
        autoBackupEnabled = autoBackupEnabled,
        autoBackupInterval = autoBackupInterval,
        autoBackupLastRun = autoBackupLastRun,
        autoBackupLastErrorMessage = backupFailureStatus?.message,
        autoBackupLastErrorAt = backupFailureStatus?.recordedAtDisplay,
        backupRotationEnabled = backupRotationEnabled,
        backupRotationCount = backupRotationCount,
        dayBoundaryHour = dayBoundaryHour,
        debugLoggingEnabled = debugLoggingEnabled,
        databaseInitCompleted = databaseInitCompleted,
        autoTrackHabitTimeGlobal = autoTrackHabitTimeGlobal,
        autoTrackDimensionPreferences = autoTrackDimensionPrefs,
        activePreset = activePreset,
        tabVisibility = tabVisibility,
        focusModeOnboardingCompleted = focusModeOnboardingCompleted,
        currentTaskFilter = currentTaskFilter,
        launchDestination = launchDestination,
        chartTimeModuleEnabled = chartTimeModuleEnabled,
        chartTimeOverallSnapshotEnabled = chartTimeOverallSnapshotEnabled,
        chartTimeExecutionDetailsEnabled = chartTimeExecutionDetailsEnabled,
        chartTimeScoreCardsEnabled = chartTimeScoreCardsEnabled,
        chartTimeOverallScoreCardEnabled = chartTimeOverallScoreCardEnabled,
        chartTimeDimensionScoreCardsEnabled = chartTimeDimensionScoreCardsEnabled,
        chartTimeLineGraphsEnabled = chartTimeLineGraphsEnabled,
        chartTimeDailyScoreTrendEnabled = chartTimeDailyScoreTrendEnabled,
        chartTimeProgressTrendEnabled = chartTimeProgressTrendEnabled,
        chartTimeHistoricalRankingEnabled = chartTimeHistoricalRankingEnabled,
        chartTimeMomentumStreakEnabled = chartTimeMomentumStreakEnabled,
        chartTaskModuleEnabled = chartTaskModuleEnabled,
        chartHabitModuleEnabled = chartHabitModuleEnabled,
        chartJournalModuleEnabled = chartJournalModuleEnabled,
        chartNoteModuleEnabled = chartNoteModuleEnabled,
        chartAverageDailyTimeEnabled = chartAverageDailyTimeEnabled,
        chartDimSplitEnabled = chartDimSplitEnabled,
        chartDimTrendEnabled = chartDimTrendEnabled,
        chartDailyTimelineEnabled = chartDailyTimelineEnabled,
        chartWeeklyPatternEnabled = chartWeeklyPatternEnabled,
        chartDailyRhythmEnabled = chartDailyRhythmEnabled,
        chartWeeklyPatternExclEmpty = chartWeeklyPatternExclEmpty,
        chartDailyRhythmExclEmpty = chartDailyRhythmExclEmpty,
        isLoading = false,
    )
}

/**
 * The tab routes the visibility map is built over, in the order Android has always used.
 *
 * Written as data rather than derived from the nav model so a nav-model change cannot silently change what
 * a stored `tab_visible_*` row means; the equality of these ids with `PayanamTab`'s routes is asserted by
 * test instead.
 */
internal val ALL_TAB_ROUTES = listOf("tasks", "habits", "time", "journal", "notes", "lenses", "settings")

/** The tab that is visible whatever the rows say. */
internal const val SETTINGS_TAB_ROUTE = "settings"

/** The route a launch destination falls back to when no row is stored - Android's own default. */
internal const val DEFAULT_LAUNCH_ROUTE = "time"

private val SUPPORTED_DIMENSION_LOCALE_TAGS = listOf("en", "ta")

private fun dimensionSortOrder(dimensionId: String): Int = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.sortOrder ?: Int.MAX_VALUE

/**
 * Parses a stored `#RRGGBB`/`#AARRGGBB` colour, falling back to the default dimension colour and logging
 * once per failure rather than per frame - this is reached from the dimension list build.
 */
internal fun parseDimensionColor(hex: String): Color {
    val normalized = hex.removePrefix("#")
    return try {
        val colorLong = normalized.toLong(16)
        if (normalized.length <= 6) {
            Color((0xFF000000 or colorLong).toInt())
        } else {
            Color(colorLong.toInt())
        }
    } catch (e: NumberFormatException) {
        AppLogger.current.w(
            "AppPreferencesMapper.parseDimensionColor",
            "Failed to resolve dimension color, using fallback",
            mapOf("error" to (e.message ?: "unknown")),
        )
        LifeDimensionColors.forDimension("Career & Work")
    }
}

/**
 * Formats a colour the way the dimension rows store it (`#AARRGGBB`).
 *
 * Public because the platform write path stores colours too, and a platform that formatted its own would
 * write a value the other side reads back differently.
 */
fun colorToStoredHex(color: Color): String = "#%08X".format(color.toArgb())

private fun buildDimensionCatalogUiState(
    dimensions: List<ConfiguredLifeDimension>,
    effectiveLanguageTag: String,
): Pair<List<DimensionPreference>, List<DimensionOption>> {
    val preferredRows =
        dimensions
            .filterNot { it.id == DimensionTaxonomyCatalog.UNASSIGNED.id }
            .groupBy { DimensionTaxonomyCatalog.fromCanonicalId(it.id)?.id ?: it.id }
            .values
            .map(::selectPreferredCatalogDimensionRow)
            .sortedBy { DimensionTaxonomyCatalog.fromCanonicalId(it.id)?.sortOrder ?: it.sortOrder }
    val builtInPreferences = mutableListOf<DimensionPreference>()

    preferredRows.forEach { dimension ->
        val option =
            toDbBackedDimensionOption(
                dimension = dimension,
                effectiveLanguageTag = effectiveLanguageTag,
            )
        builtInPreferences +=
            DimensionPreference(
                key = option.canonicalId,
                id = option.id,
                canonicalId = option.canonicalId,
                label = option.label,
                description = option.description,
                color = option.color,
                isVisible = option.isVisible,
                iconKey = option.iconKey,
                weight = option.weight,
                hasCustomLabelOverride = option.hasCustomLabelOverride,
            )
    }

    return builtInPreferences.sortedBy { dimensionSortOrder(it.canonicalId) } to emptyList()
}

private fun selectPreferredCatalogDimensionRow(candidates: List<ConfiguredLifeDimension>): ConfiguredLifeDimension =
    candidates
        .sortedWith(
            compareByDescending<ConfiguredLifeDimension> { candidate ->
                val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(candidate.id)?.id
                when {
                    canonicalId != null && candidate.id == canonicalId -> 3

                    canonicalId != null &&
                        candidate.key == DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.slug -> 2

                    candidate.isActive -> 1

                    else -> 0
                }
            }.thenBy { it.sortOrder },
        ).first()

private fun toDbBackedDimensionOption(
    dimension: ConfiguredLifeDimension,
    effectiveLanguageTag: String,
): DimensionOption {
    val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimension.id)?.id
    val canonicalDefinition = DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)
    val resolvedColorHex =
        dimension.colorHex.ifBlank {
            canonicalDefinition?.defaultColorHex
                ?: colorToStoredHex(LifeDimensionColors.forDimension("Career & Work"))
        }
    val resolvedIconKey =
        dimension.iconKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: canonicalDefinition?.defaultIconKey
            ?: DimensionIconCatalog.defaultIconKeyForDimensionId(dimension.id)
    return DimensionOption(
        id = dimension.id,
        canonicalId = canonicalId ?: dimension.id,
        label =
            resolveDbBackedDimensionLabel(
                dimension = dimension,
                canonicalId = canonicalId,
                effectiveLanguageTag = effectiveLanguageTag,
            ),
        description =
            resolveDbBackedDimensionDescription(
                dimension = dimension,
                canonicalId = canonicalId,
                effectiveLanguageTag = effectiveLanguageTag,
            ),
        color = parseDimensionColor(resolvedColorHex),
        isVisible = dimension.isActive,
        iconKey = resolvedIconKey,
        weight = dimension.weight,
        hasCustomLabelOverride =
            canonicalId == null ||
                hasCustomDbBackedLabel(
                    canonicalId = canonicalId,
                    storedLabel = dimension.label,
                ),
    )
}

private fun resolveDbBackedDimensionLabel(
    dimension: ConfiguredLifeDimension,
    canonicalId: String?,
    effectiveLanguageTag: String,
): String {
    val trimmedLabel = dimension.label.trim()
    if (canonicalId != null && !hasCustomDbBackedLabel(canonicalId, trimmedLabel)) {
        return localizedCatalogLabel(
            canonicalId = canonicalId,
            languageTag = effectiveLanguageTag,
        ) ?: DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel
            ?: trimmedLabel.ifBlank { dimension.id }
    }
    return trimmedLabel.ifBlank {
        localizedCatalogLabel(
            canonicalId = canonicalId,
            languageTag = effectiveLanguageTag,
        ) ?: dimension.id
    }
}

private fun resolveDbBackedDimensionDescription(
    dimension: ConfiguredLifeDimension,
    canonicalId: String?,
    effectiveLanguageTag: String,
): String? {
    if (canonicalId != null) {
        return localizedCatalogDescription(
            canonicalId = canonicalId,
            languageTag = effectiveLanguageTag,
        ) ?: DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackDescription
            ?: dimension.description?.trim()?.takeIf { it.isNotEmpty() }
    }
    return dimension.description?.trim()?.takeIf { it.isNotEmpty() }
}

private fun hasCustomDbBackedLabel(
    canonicalId: String,
    storedLabel: String?,
): Boolean {
    val trimmedLabel = storedLabel?.trim().orEmpty()
    if (trimmedLabel.isBlank()) {
        return false
    }
    return trimmedLabel !in knownAppOwnedCatalogLabels(canonicalId)
}

/**
 * The set of labels the app itself owns for a canonical dimension: its fallback label plus every supported
 * locale's localized one. Public for the same reason [localizedCatalogLabel] is - the write path decides
 * "user override or app label?" with it, and both platforms must answer that identically.
 */
fun knownAppOwnedCatalogLabels(canonicalId: String): Set<String> {
    val canonicalDefinition = DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)
    return buildSet {
        canonicalDefinition?.fallbackLabel?.let(::add)
        SUPPORTED_DIMENSION_LOCALE_TAGS
            .mapNotNull { localeTag -> localizedCatalogLabel(canonicalId, localeTag) }
            .forEach(::add)
    }
}

/**
 * The app's own localized label for a canonical dimension, or null when the catalog has none.
 *
 * Public because the platform's WRITE path needs the same answer this mapper reads with: a platform that
 * decided "is this stored label the app's own?" differently would persist a label the other platform then
 * reads back as a user override.
 */
fun localizedCatalogLabel(
    canonicalId: String?,
    languageTag: String?,
): String? = DimensionTextCatalog.localizedLabel(canonicalId, uiStringsFor(languageTag))

private fun localizedCatalogDescription(
    canonicalId: String?,
    languageTag: String?,
): String? = DimensionTextCatalog.localizedDescription(canonicalId, uiStringsFor(languageTag))

private fun resolveTimeHourHeightDp(settings: Map<String, String?>): Float {
    val persisted =
        settings[AppPreferenceKeys.KEY_TIME_HOUR_HEIGHT_DP]
            ?.toFloatOrNull()
            ?.coerceIn(MIN_TIME_HOUR_HEIGHT_DP, MAX_TIME_HOUR_HEIGHT_DP)
    if (persisted != null) {
        return persisted
    }
    return legacyTimeScaleKeyToHourHeightDp(settings[AppPreferenceKeys.KEY_TIME_SCALE_LEGACY_KEY])
        ?: DEFAULT_TIME_HOUR_HEIGHT_DP
}

private fun legacyTimeScaleKeyToHourHeightDp(legacyKey: String?): Float? =
    when (legacyKey) {
        "2h" -> 30f
        "1h" -> 60f
        "20m" -> 180f
        "15m" -> 240f
        "10m" -> 360f
        "5m" -> 720f
        else -> null
    }?.coerceIn(MIN_TIME_HOUR_HEIGHT_DP, MAX_TIME_HOUR_HEIGHT_DP)
