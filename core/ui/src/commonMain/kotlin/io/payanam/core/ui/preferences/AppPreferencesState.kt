//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

package io.payanam.core.ui.preferences

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import io.payanam.core.ui.model.DimensionIconCatalog
import io.payanam.core.ui.model.DimensionIconOption
import io.payanam.core.ui.theme.LifeDimensionColors
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.LifeDimension
import io.payanam.presentation.tasks.TaskFilter
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.settings.FocusModePreset

/**
 * The auto-backup cadence, named here so every existing caller keeps compiling unchanged.
 *
 * This is an ALIAS, not a second type: it resolves to [io.payanam.shared.settings.BackupInterval], so
 * a value built here and a value passed to `:core:presentation` are the same type, and the
 * `when (this)` branches below it still exhaust over the real enum. It exists because that enum had to
 * move out of this package to sit below `:core:ui` in the graph - see its own KDoc for why - and
 * `:core:presentation` cannot import this package to reach it.
 *
 * The extension properties and `labelKey` table below stay HERE, not in the new home, on purpose: they
 * resolve a label resource NAME, and the mapping is proven against the real resource table by
 * `SettingsLabelKeyTest`. Moving them would drag a `:core:ui`-side contract into the leaf for no gain.
 */
typealias BackupInterval = io.payanam.shared.settings.BackupInterval

/**
 * The timeline hour-height an unconfigured install starts from.
 *
 * Shared rather than private to each platform's preference reader: it is the default of
 * [AppPreferencesState.timeHourHeightDp], and a platform that fell back to its own copy would silently
 * disagree with the state it is supposed to produce.
 */
const val DEFAULT_TIME_HOUR_HEIGHT_DP = 60f

/**
 * Dimensions whose resolution failed once. The warning fires on the first miss per dimension rather than
 * per frame - [findDimensionOption] is reached from draw paths.
 */
private val unresolvedDimensionResolutionKeys = mutableSetOf<String>()

/**
 * User-selectable UI theme mode (system / light / dark), persisted by [key].
 */
enum class ThemeModeOption(
    val key: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /**
         * Resolves the option whose [key] matches, or null when unknown.
         */
        fun fromKey(key: String?): ThemeModeOption? = entries.find { it.key == key }
    }
}

/**
 * User-selectable font family, persisted by [key] (falls back to nearest known family).
 */
enum class FontFamilyOption(
    val key: String,
) {
    SANS_SERIF("sans-serif"),
    MONOSPACE("monospace"),
    SERIF("serif"),
    CURSIVE("cursive"),
    ;

    companion object {
        /**
         * Resolves the option whose [key] matches, or null when unknown.
         */
        fun fromKey(key: String?): FontFamilyOption? =
            when (key) {
                "roboto", "nunito", "orbitron", "dosis", "sans-serif" -> SANS_SERIF
                "ubuntu-mono", "monospace" -> MONOSPACE
                "libre-baskerville", "serif" -> SERIF
                "kalam", "cursive" -> CURSIVE
                else -> entries.find { it.key == key }
            }
    }
}

/**
 * User-selectable clock format (24h / 12h), persisted by [key].
 */
enum class TimeFormatOption(
    val key: String,
    val use24Hour: Boolean,
) {
    TWENTY_FOUR("24h", true),
    TWELVE("12h", false),
    ;

    companion object {
        /**
         * Resolves the option whose [key] matches, or null when unknown.
         */
        fun fromKey(key: String?): TimeFormatOption? = entries.find { it.key == key }
    }
}

/**
 * User-selectable app language (system / English / Tamil), persisted by [key].
 */
enum class AppLanguageOption(
    val key: String,
) {
    SYSTEM("system"),
    ENGLISH("en"),
    TAMIL("ta"),
    ;

    companion object {
        /**
         * Resolves the option whose [key] matches, or null when unknown.
         */
        fun fromKey(key: String?): AppLanguageOption? = entries.find { it.key == key }
    }
}

/**
 * One configured life-dimension row: label/color/visibility/weight plus its canonical id.
 */
data class DimensionPreference(
    val key: String,
    val label: String,
    val color: Color,
    val isVisible: Boolean,
    val id: String = key,
    val iconKey: String = DimensionIconCatalog.defaultIconKeyForDimensionId(key),
    val hasCustomLabelOverride: Boolean = false,
    val canonicalId: String = key,
    val description: String? = null,
    val weight: Double = 1.0,
)

/**
 * A selectable dimension option (defaults + dynamic), with resolved icon + visibility.
 */
data class DimensionOption(
    val id: String,
    val label: String,
    val color: Color,
    val isVisible: Boolean,
    val iconKey: String = DimensionIconCatalog.defaultIconKeyForDimensionId(id),
    val hasCustomLabelOverride: Boolean = false,
    val canonicalId: String = id,
    val description: String? = null,
    val weight: Double = 1.0,
)

/**
 * Remembers which screen (route) opens on launch, with an optional task filter.
 */
data class LaunchDestination(
    val route: String = "",
    val taskFilter: TaskFilter? = null,
)

val ThemeModeOption.displayName: String
    get() = key
val FontFamilyOption.displayName: String
    get() = key
val TimeFormatOption.displayName: String
    get() = key
val BackupInterval.displayName: String
    get() = key

/**
 * The label's RESOURCE NAME, which is the value that moves with the enum.
 *
 * The enums themselves are already platform-neutral (`key: String` + a `fromKey` resolver); what kept them
 * in the app module was this label indirection. Naming the resource rather than its id makes the value
 * portable, so the enum and its labels can move to the shared module as a pure relocation - the shared side
 * needs no resource table.
 *
 * The names are asserted against the real string resources in `SettingsLabelKeyTest`, so a rename that is not
 * reflected here fails a test rather than rendering an empty label.
 */
val ThemeModeOption.labelKey: String
    get() =
        when (this) {
            ThemeModeOption.SYSTEM -> "settings_option_theme_system"
            ThemeModeOption.LIGHT -> "settings_option_theme_light"
            ThemeModeOption.DARK -> "settings_option_theme_dark"
        }

val FontFamilyOption.labelKey: String
    get() =
        when (this) {
            FontFamilyOption.SANS_SERIF -> "settings_option_font_sans_serif"
            FontFamilyOption.MONOSPACE -> "settings_option_font_monospace"
            FontFamilyOption.SERIF -> "settings_option_font_serif"
            FontFamilyOption.CURSIVE -> "settings_option_font_cursive"
        }

val TimeFormatOption.labelKey: String
    get() =
        when (this) {
            TimeFormatOption.TWENTY_FOUR -> "settings_option_time_format_24h"
            TimeFormatOption.TWELVE -> "settings_option_time_format_12h"
        }

val BackupInterval.labelKey: String
    get() =
        when (this) {
            BackupInterval.FIFTEEN_MIN -> "settings_option_backup_15_min"
            BackupInterval.THIRTY_MIN -> "settings_option_backup_30_min"
            BackupInterval.SIXTY_MIN -> "settings_option_backup_1_hour"
            BackupInterval.TWO_HOURS -> "settings_option_backup_2_hours"
            BackupInterval.SIX_HOURS -> "settings_option_backup_6_hours"
            BackupInterval.TWELVE_HOURS -> "settings_option_backup_12_hours"
            BackupInterval.DAILY -> "settings_option_backup_daily"
        }

/**
 * The fifth label mapping, which lived inline in the appearance section rather than as a property — so a
 * move that looked only for `labelResId` properties would have left this one behind in the app module.
 */
val AppLanguageOption.labelKey: String
    get() =
        when (this) {
            AppLanguageOption.SYSTEM -> "settings_language_option_system"
            AppLanguageOption.ENGLISH -> "settings_language_option_english"
            AppLanguageOption.TAMIL -> "settings_language_option_tamil"
        }

/**
 * Snapshot of every user-facing preference, surfaced to the Settings UI.
 */
data class AppPreferencesState(
    val themeMode: ThemeModeOption = ThemeModeOption.SYSTEM,
    val appLanguage: AppLanguageOption = AppLanguageOption.SYSTEM,
    val effectiveLanguageTag: String = resolveEffectiveLanguageTag(AppLanguageOption.SYSTEM, null),
    val fontFamily: FontFamilyOption = FontFamilyOption.SANS_SERIF,
    val timeFormat: TimeFormatOption = TimeFormatOption.TWENTY_FOUR,
    val timeHourHeightDp: Float = DEFAULT_TIME_HOUR_HEIGHT_DP,
    val dimensionPreferences: List<DimensionPreference> = emptyList(),
    val dynamicDimensionOptions: List<DimensionOption> = emptyList(),
    // Auto-backup settings
    val autoBackupEnabled: Boolean = false,
    val autoBackupInterval: BackupInterval = BackupInterval.SIXTY_MIN,
    val autoBackupLastRun: String? = null,
    val autoBackupLastErrorMessage: String? = null,
    val autoBackupLastErrorAt: String? = null,
    val backupRotationEnabled: Boolean = false,
    val backupRotationCount: Int = 50,
    // Day boundary for recurrence (0-5, hour when day "ends" for recurring tasks)
    val dayBoundaryHour: Int = 0,
    // Debug logging
    val debugLoggingEnabled: Boolean = false,
    // Database init completed flag
    val databaseInitCompleted: Boolean = false,
    // Auto-tracking habit completion time
    val autoTrackHabitTimeGlobal: Boolean = false,
    val autoTrackDimensionPreferences: Map<String, Boolean> = emptyMap(),
    // Focus Mode
    val activePreset: FocusModePreset = FocusModePreset.FULL_SUITE,
    val tabVisibility: Map<String, Boolean> = emptyMap(),
    val focusModeOnboardingCompleted: Boolean = false,
    val currentTaskFilter: TaskFilter = TaskFilter.TODAY,
    // Default launch destination
    val launchDestination: LaunchDestination = LaunchDestination(),
    // Insights charts visibility settings
    val chartTimeModuleEnabled: Boolean = true,
    val chartTimeOverallSnapshotEnabled: Boolean = false,
    val chartTimeExecutionDetailsEnabled: Boolean = false,
    val chartTimeScoreCardsEnabled: Boolean = false,
    val chartTimeOverallScoreCardEnabled: Boolean = false,
    val chartTimeDimensionScoreCardsEnabled: Boolean = false,
    val chartTimeLineGraphsEnabled: Boolean = false,
    val chartTimeDailyScoreTrendEnabled: Boolean = false,
    val chartTimeProgressTrendEnabled: Boolean = false,
    val chartTimeHistoricalRankingEnabled: Boolean = false,
    val chartTimeMomentumStreakEnabled: Boolean = false,
    val chartTaskModuleEnabled: Boolean = false,
    val chartHabitModuleEnabled: Boolean = false,
    val chartJournalModuleEnabled: Boolean = false,
    val chartNoteModuleEnabled: Boolean = false,
    val chartAverageDailyTimeEnabled: Boolean = true,
    val chartDimSplitEnabled: Boolean = false,
    val chartDimTrendEnabled: Boolean = false,
    val chartDailyTimelineEnabled: Boolean = false,
    val chartWeeklyPatternEnabled: Boolean = false,
    val chartDailyRhythmEnabled: Boolean = false,
    val chartWeeklyPatternExclEmpty: Boolean = false,
    val chartDailyRhythmExclEmpty: Boolean = false,
    val isLoading: Boolean = true,
)

val LocalAppPreferences = compositionLocalOf { AppPreferencesState() }

/**
 * Resolves the display label for [dimensionName], checking dimension prefs then dynamic options, falling back to the raw name.
 */
fun AppPreferencesState.labelFor(dimensionName: String): String =
    dimensionPreferences.firstOrNull { it.id == dimensionName || it.canonicalId == dimensionName }?.label
        ?: dynamicDimensionOptions.firstOrNull { it.label == dimensionName }?.label
        ?: dimensionName

/**
 * Resolves the display label for a dimension by its [dimensionId], or null.
 */
fun AppPreferencesState.labelForDimensionId(dimensionId: String?): String? = findDimensionOption(dimensionId)?.label

/**
 * Resolves a dimension label: tries the [dimensionId] first, then falls back to looking up [dimensionName] via labelFor.
 */
fun AppPreferencesState.labelForDimension(
    dimensionId: String?,
    dimensionName: String?,
): String? {
    val directLabel = labelForDimensionId(dimensionId)
    if (!directLabel.isNullOrBlank()) {
        return directLabel
    }
    val fallbackName = dimensionName?.trim().orEmpty()
    if (fallbackName.isBlank()) {
        return null
    }
    return labelFor(fallbackName)
}

/**
 * True when [option] matches the given dimension (by id or resolved label).
 */
fun AppPreferencesState.matchesDimensionOption(
    option: DimensionOption,
    dimensionId: String?,
    dimensionName: String?,
): Boolean {
    if (!dimensionId.isNullOrBlank() && option.id == dimensionId) {
        return true
    }
    val normalizedName = dimensionName?.trim().orEmpty()
    if (normalizedName.isBlank()) {
        return false
    }
    if (normalizedName == option.label) {
        return true
    }
    return labelForDimension(dimensionId, normalizedName) == option.label
}

/**
 * Resolves the color for [dimensionName] from prefs/dynamic options, falling back to the canonical dimension color.
 */
fun AppPreferencesState.colorFor(dimensionName: String): Color =
    dimensionPreferences.firstOrNull { it.id == dimensionName || it.canonicalId == dimensionName }?.color
        ?: dynamicDimensionOptions.firstOrNull { it.label == dimensionName }?.color
        ?: LifeDimensionColors.forDimension(dimensionName)

/**
 * Resolves the color for a dimension by its [dimensionId], or null.
 */
fun AppPreferencesState.colorForDimensionId(dimensionId: String?): Color? = findDimensionOption(dimensionId)?.color

/**
 * Resolves the icon key for a dimension by its [dimensionId], or null.
 */
fun AppPreferencesState.iconKeyForDimensionId(dimensionId: String?): String? = findDimensionOption(dimensionId)?.iconKey

/**
 * Resolves the icon option for a dimension by its [dimensionId], or null.
 */
fun AppPreferencesState.iconOptionForDimensionId(dimensionId: String?): DimensionIconOption? =
    findDimensionOption(dimensionId)?.let { DimensionIconCatalog.resolve(it.iconKey, it.id) }

/**
 * True when auto-tracking is enabled for [dimensionId] (matching canonical id too).
 */
fun AppPreferencesState.autoTrackEnabledForDimensionId(dimensionId: String?): Boolean {
    val requestedId = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val requestedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id
    return autoTrackDimensionPreferences.entries
        .firstOrNull { (storedId, _) ->
            val storedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(storedId)?.id
            storedId == requestedId ||
                (!requestedCanonicalId.isNullOrBlank() && storedCanonicalId == requestedCanonicalId)
        }?.value ?: false
}

/**
 * Resolves a dimension color: by [dimensionId] first, then by resolved [dimensionName].
 */
fun AppPreferencesState.colorForDimension(
    dimensionId: String?,
    dimensionName: String?,
): Color? =
    colorForDimensionId(dimensionId)
        ?: dimensionName?.trim()?.takeIf { it.isNotEmpty() }?.let(::colorFor)

/**
 * True when the dimension [dimensionId] is in the visible set (defaults to visible).
 */
fun AppPreferencesState.isVisibleDimensionId(dimensionId: String?): Boolean {
    val requestedId = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    val requestedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id
    return visibleDimensionOptions().any { option ->
        option.isVisible &&
            (
                option.id == requestedId ||
                    (!requestedCanonicalId.isNullOrBlank() && option.canonicalId == requestedCanonicalId)
            )
    }
}

/**
 * True when the named dimension [dimensionName] is visible (defaults to visible).
 */
fun AppPreferencesState.isVisible(dimensionName: String): Boolean =
    dimensionPreferences.firstOrNull { it.id == dimensionName || it.canonicalId == dimensionName }?.isVisible ?: true

/**
 * The task filter to use at launch: the launch-destination filter, or the current one.
 */
fun AppPreferencesState.effectiveLaunchTaskFilter(): TaskFilter = launchDestination.taskFilter ?: currentTaskFilter

/**
 * All dimension preferences marked visible.
 */
fun AppPreferencesState.visibleDimensions(): List<DimensionPreference> = dimensionPreferences.filter { it.isVisible }

/**
 * Dimension preferences offered for selection: visible ones, plus [selected] even if hidden.
 */
fun AppPreferencesState.optionsForSelection(selected: LifeDimension?): List<DimensionPreference> {
    if (selected == null) {
        return visibleDimensions()
    }
    return dimensionPreferences.filter { it.isVisible || it.canonicalId == selected.id || it.id == selected.id }
}

/**
 * Merged, de-duplicated, visible dimension options (defaults + dynamic).
 */
fun AppPreferencesState.visibleDimensionOptions(): List<DimensionOption> {
    val defaults =
        dimensionPreferences.map {
            DimensionOption(
                id = it.id,
                canonicalId = it.canonicalId,
                label = it.label,
                description = it.description,
                color = it.color,
                isVisible = it.isVisible,
                iconKey = it.iconKey,
                hasCustomLabelOverride = it.hasCustomLabelOverride,
            )
        }
    return (defaults + dynamicDimensionOptions)
        .distinctBy { it.id }
        .filter { it.isVisible }
}

/**
 * Dimension options for selection: visible ones, or all when [selectedDimensionId] is blank; [selectedDimensionId] itself is always included.
 */
fun AppPreferencesState.optionsForSelection(selectedDimensionId: String?): List<DimensionOption> {
    val defaults =
        dimensionPreferences.map {
            DimensionOption(
                id = it.id,
                canonicalId = it.canonicalId,
                label = it.label,
                description = it.description,
                color = it.color,
                isVisible = it.isVisible,
                iconKey = it.iconKey,
                hasCustomLabelOverride = it.hasCustomLabelOverride,
            )
        }
    val all = (defaults + dynamicDimensionOptions).distinctBy { it.id }
    if (selectedDimensionId.isNullOrBlank()) {
        return all.filter { it.isVisible }
    }
    val selectedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(selectedDimensionId)?.id
    return all.filter { option ->
        option.isVisible ||
            option.id == selectedDimensionId ||
            (!selectedCanonicalId.isNullOrBlank() && option.canonicalId == selectedCanonicalId)
    }
}

private fun AppPreferencesState.findVisibleDimensionOption(dimensionId: String?): DimensionOption? {
    val requestedId = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val requestedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id
    return visibleDimensionOptions().firstOrNull { option ->
        option.id == requestedId ||
            (!requestedCanonicalId.isNullOrBlank() && option.canonicalId == requestedCanonicalId)
    }
}

private fun AppPreferencesState.findDimensionOption(dimensionId: String?): DimensionOption? {
    val requestedId = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val requestedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id
    val options =
        dimensionPreferences.map {
            DimensionOption(
                id = it.id,
                canonicalId = it.canonicalId,
                label = it.label,
                description = it.description,
                color = it.color,
                isVisible = it.isVisible,
                iconKey = it.iconKey,
                hasCustomLabelOverride = it.hasCustomLabelOverride,
            )
        } + dynamicDimensionOptions
    val resolved =
        options
            .distinctBy { it.id }
            .firstOrNull { option ->
                option.id == requestedId ||
                    (!requestedCanonicalId.isNullOrBlank() && option.canonicalId == requestedCanonicalId)
            }
    if (resolved == null) {
        val traceKey = requestedCanonicalId ?: requestedId
        synchronized(unresolvedDimensionResolutionKeys) {
            if (unresolvedDimensionResolutionKeys.add(traceKey)) {
                AppLogger.current.w(
                    "AppPreferencesState.findDimensionOption",
                    "Could not resolve dimension option from app preferences state",
                    mapOf(
                        "requestedId" to requestedId,
                        "requestedCanonicalId" to (requestedCanonicalId ?: "null"),
                        "dimensionPreferenceIds" to dimensionPreferences.joinToString(",") { it.id },
                        "dynamicOptionIds" to dynamicDimensionOptions.joinToString(",") { it.id },
                    ),
                )
            }
        }
    }
    return resolved
}

/**
 * Resolves the language actually rendered for [option], given the platform's own [systemLanguageTag].
 *
 * Public rather than internal: a platform's preference reader and the tests that pin the mapping call it
 * from outside this module, and the alternative - a second copy per platform - is the divergence this
 * shared state exists to remove.
 */
fun resolveEffectiveLanguageTag(
    option: AppLanguageOption,
    systemLanguageTag: String?,
): String =
    when (option) {
        AppLanguageOption.ENGLISH -> AppLanguageOption.ENGLISH.key
        AppLanguageOption.TAMIL -> AppLanguageOption.TAMIL.key
        AppLanguageOption.SYSTEM -> normalizeSupportedLanguageTag(systemLanguageTag)
    }

/**
 * Normalizes [languageTag] to one of the two shipped languages, falling back to English.
 *
 * `lowercase()` is locale-independent (invariant), which is what the Android original asked for with
 * `Locale.ROOT`; spelling it that way keeps this file free of `java.*` so it compiles for every target.
 */
fun normalizeSupportedLanguageTag(languageTag: String?): String {
    val normalized =
        languageTag
            ?.trim()
            ?.lowercase()
            ?.substringBefore('-')
    return if (normalized == AppLanguageOption.TAMIL.key) {
        AppLanguageOption.TAMIL.key
    } else {
        AppLanguageOption.ENGLISH.key
    }
}
