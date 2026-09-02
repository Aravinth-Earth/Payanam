//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

package io.payanam.ui.viewmodel
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.BuildConfig
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.backfill.ScoreRollupCascadeService
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.service.AutoBackupWorker
import io.payanam.service.BackupStatusSnapshot
import io.payanam.service.BackupStatusStore
import io.payanam.service.BackupTrigger
import io.payanam.service.DatabaseBackupCoordinator
import io.payanam.shared.settings.FocusModePreset
import io.payanam.ui.model.DimensionIconCatalog
import io.payanam.ui.model.DimensionIconOption
import io.payanam.ui.model.DimensionTextCatalog
import io.payanam.ui.theme.LifeDimensionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

private data class BackupSettingsBundle(
    val settings: Map<String, String?>,
    val dimensions: List<ConfiguredLifeDimension>,
    val systemLanguageTag: String?,
    val backupStatus: BackupStatusSnapshot,
)

private val appPreferencesLogger
    get() = if (UnifiedLogger.isInitialized()) UnifiedLogger.getInstance() else null
private val unresolvedDimensionResolutionKeys = mutableSetOf<String>()

private const val DEFAULT_TIME_HOUR_HEIGHT_DP = 60f
private const val MIN_TIME_HOUR_HEIGHT_DP = 24f
private const val MAX_TIME_HOUR_HEIGHT_DP = 2880f
/**
 * User-selectable UI theme mode (system / light / dark), persisted by [key].
 */
enum class ThemeModeOption(val key: String) {
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
enum class FontFamilyOption(val key: String) {
    SANS_SERIF("sans-serif"),
    MONOSPACE("monospace"),
    SERIF("serif"),
    CURSIVE("cursive"),
    ;

    companion object {
        /**
         * Resolves the option whose [key] matches, or null when unknown.
         */
        fun fromKey(key: String?): FontFamilyOption? = when (key) {
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
enum class TimeFormatOption(val key: String, val use24Hour: Boolean) {
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
enum class AppLanguageOption(val key: String) {
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
/**
 * Auto-backup cadence options, persisted by [key] with [minutes] for scheduling.
 */
enum class BackupInterval(val key: String, val minutes: Long) {
    FIFTEEN_MIN("15m", 15),
    THIRTY_MIN("30m", 30),
    SIXTY_MIN("60m", 60),
    TWO_HOURS("2h", 120),
    SIX_HOURS("6h", 360),
    TWELVE_HOURS("12h", 720),
    DAILY("24h", 1440),
    ;

    companion object {
        /**
         * Resolves the option whose [key] matches, or null when unknown.
         */
        fun fromKey(key: String?): BackupInterval? = entries.find { it.key == key }
    }
}
val ThemeModeOption.displayName: String
    get() = key
val FontFamilyOption.displayName: String
    get() = key
val TimeFormatOption.displayName: String
    get() = key
val BackupInterval.displayName: String
    get() = key
val ThemeModeOption.labelResId: Int
    get() = when (this) {
        ThemeModeOption.SYSTEM -> R.string.settings_option_theme_system
        ThemeModeOption.LIGHT -> R.string.settings_option_theme_light
        ThemeModeOption.DARK -> R.string.settings_option_theme_dark
    }
val FontFamilyOption.labelResId: Int
    get() = when (this) {
        FontFamilyOption.SANS_SERIF -> R.string.settings_option_font_sans_serif
        FontFamilyOption.MONOSPACE -> R.string.settings_option_font_monospace
        FontFamilyOption.SERIF -> R.string.settings_option_font_serif
        FontFamilyOption.CURSIVE -> R.string.settings_option_font_cursive
    }
val TimeFormatOption.labelResId: Int
    get() = when (this) {
        TimeFormatOption.TWENTY_FOUR -> R.string.settings_option_time_format_24h
        TimeFormatOption.TWELVE -> R.string.settings_option_time_format_12h
    }
val BackupInterval.labelResId: Int
    get() = when (this) {
        BackupInterval.FIFTEEN_MIN -> R.string.settings_option_backup_15_min
        BackupInterval.THIRTY_MIN -> R.string.settings_option_backup_30_min
        BackupInterval.SIXTY_MIN -> R.string.settings_option_backup_1_hour
        BackupInterval.TWO_HOURS -> R.string.settings_option_backup_2_hours
        BackupInterval.SIX_HOURS -> R.string.settings_option_backup_6_hours
        BackupInterval.TWELVE_HOURS -> R.string.settings_option_backup_12_hours
        BackupInterval.DAILY -> R.string.settings_option_backup_daily
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
    val debugLoggingEnabled: Boolean = BuildConfig.DEBUG,
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
fun AppPreferencesState.labelFor(dimensionName: String): String = dimensionPreferences.firstOrNull { it.id == dimensionName || it.canonicalId == dimensionName }?.label
    ?: dynamicDimensionOptions.firstOrNull { it.label == dimensionName }?.label
    ?: dimensionName
/**
 * Resolves the display label for a dimension by its [dimensionId], or null.
 */
fun AppPreferencesState.labelForDimensionId(dimensionId: String?): String? = findDimensionOption(dimensionId)?.label
/**
 * Resolves a dimension label: tries the [dimensionId] first, then falls back to looking up [dimensionName] via labelFor.
 */
fun AppPreferencesState.labelForDimension(dimensionId: String?, dimensionName: String?): String? {
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
fun AppPreferencesState.colorFor(dimensionName: String): Color = dimensionPreferences.firstOrNull { it.id == dimensionName || it.canonicalId == dimensionName }?.color
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
fun AppPreferencesState.iconOptionForDimensionId(dimensionId: String?): DimensionIconOption? = findDimensionOption(dimensionId)?.let { DimensionIconCatalog.resolve(it.iconKey, it.id) }
/**
 * True when auto-tracking is enabled for [dimensionId] (matching canonical id too).
 */
fun AppPreferencesState.autoTrackEnabledForDimensionId(dimensionId: String?): Boolean {
    val requestedId = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val requestedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id
    return autoTrackDimensionPreferences.entries.firstOrNull { (storedId, _) ->
        val storedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(storedId)?.id
        storedId == requestedId ||
            (!requestedCanonicalId.isNullOrBlank() && storedCanonicalId == requestedCanonicalId)
    }?.value ?: false
}
/**
 * Resolves a dimension color: by [dimensionId] first, then by resolved [dimensionName].
 */
fun AppPreferencesState.colorForDimension(dimensionId: String?, dimensionName: String?): Color? = colorForDimensionId(dimensionId)
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
fun AppPreferencesState.isVisible(dimensionName: String): Boolean = dimensionPreferences.firstOrNull { it.id == dimensionName || it.canonicalId == dimensionName }?.isVisible ?: true
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
    val defaults = dimensionPreferences.map {
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
    val defaults = dimensionPreferences.map {
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
    val options = dimensionPreferences.map {
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
    val resolved = options
        .distinctBy { it.id }
        .firstOrNull { option ->
            option.id == requestedId ||
                (!requestedCanonicalId.isNullOrBlank() && option.canonicalId == requestedCanonicalId)
        }
    if (resolved == null) {
        val traceKey = requestedCanonicalId ?: requestedId
        synchronized(unresolvedDimensionResolutionKeys) {
            if (unresolvedDimensionResolutionKeys.add(traceKey)) {
                appPreferencesLogger?.w(
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

private fun dimensionSortOrder(dimensionId: String): Int = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.sortOrder ?: Int.MAX_VALUE

@HiltViewModel
/**
 * Exposes app settings (theme, language, dimensions, backup, charts) and persists edits.
 */
class AppPreferencesViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val lifeDimensionCatalogRepository: LifeDimensionCatalogRepository,
    private val sessionManager: DatabaseSessionManager,
    private val backupStatusStore: BackupStatusStore,
    private val databaseBackupCoordinator: DatabaseBackupCoordinator,
    private val scoreRollupCascadeService: ScoreRollupCascadeService,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(AppPreferencesState())
    private val runtimeSystemLanguageTag = MutableStateFlow(resolveSystemLanguageTag())
    val uiState: StateFlow<AppPreferencesState> = _uiState.asStateFlow()
    private val _manualBackupResultMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val manualBackupResultMessage: SharedFlow<String> = _manualBackupResultMessage.asSharedFlow()
    private val _manualBackupInProgress = MutableStateFlow(false)
    val manualBackupInProgress: StateFlow<Boolean> = _manualBackupInProgress.asStateFlow()
    private val _habitScoreDiagnosticsMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val habitScoreDiagnosticsMessage: SharedFlow<String> = _habitScoreDiagnosticsMessage.asSharedFlow()
    private val _habitScoreDiagnosticsInProgress = MutableStateFlow(false)
    val habitScoreDiagnosticsInProgress: StateFlow<Boolean> = _habitScoreDiagnosticsInProgress.asStateFlow()
    init {
        UnifiedLogger.setDebugLoggingEnabled(BuildConfig.DEBUG)
        observeSettings()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeSettings() {
        viewModelScope.launch {
            sessionManager.isOpen
                .filter { it }
                .flatMapLatest {
                    combine(
                        appSettingsRepository.getAllSettings(),
                        lifeDimensionCatalogRepository.observeAllDimensions(),
                        runtimeSystemLanguageTag,
                        backupStatusStore.status,
                    ) { settings, dimensions, systemLanguageTag, backupStatus ->
                        BackupSettingsBundle(
                            settings = settings,
                            dimensions = dimensions,
                            systemLanguageTag = systemLanguageTag,
                            backupStatus = backupStatus,
                        )
                    }
                }
                .collect { bundle ->
                    applySettingsBundle(bundle)
                }
        }
    }

    private fun applySettingsBundle(bundle: BackupSettingsBundle) {
        val settings = bundle.settings
        val configuredDimensions = bundle.dimensions
        val systemLanguageTag = bundle.systemLanguageTag
        val backupStatus = bundle.backupStatus
        val themeMode = ThemeModeOption.fromKey(settings[KEY_THEME_MODE]) ?: ThemeModeOption.SYSTEM
        val appLanguage = AppLanguageOption.fromKey(settings[KEY_APP_LANGUAGE]) ?: AppLanguageOption.SYSTEM
        val effectiveLanguageTag = resolveEffectiveLanguageTag(appLanguage, systemLanguageTag)
        val fontFamily = FontFamilyOption.fromKey(settings[KEY_FONT_FAMILY]) ?: FontFamilyOption.SANS_SERIF
        val timeFormat = TimeFormatOption.fromKey(settings[KEY_TIME_FORMAT]) ?: TimeFormatOption.TWENTY_FOUR
        val timeHourHeightDp = resolveTimeHourHeightDp(settings)
        val (dimensionPrefs, dynamicDimensionOptions) = buildDimensionCatalogUiState(
            dimensions = configuredDimensions,
            effectiveLanguageTag = effectiveLanguageTag,
        )
        val dimensionSettingsLogSignature = buildString {
            append("appLanguage=")
            append(appLanguage.key)
            append("|effectiveLanguageTag=")
            append(effectiveLanguageTag)
            append("|systemLanguageTag=")
            append(systemLanguageTag)
            append("|catalogIds=")
            append(configuredDimensions.joinToString(",") { it.id })
            append("|defaultIds=")
            append(dimensionPrefs.joinToString(",") { it.id })
            append("|customIds=")
            append(dynamicDimensionOptions.joinToString(",") { it.id })
        }
        if (lastLoggedDimensionSettingsSignature != dimensionSettingsLogSignature) {
            logger.i(
                "AppPreferencesViewModel.observeSettings",
                "Dimension settings snapshot loaded",
                mapOf(
                    "defaultDimensionCount" to dimensionPrefs.size,
                    "dynamicDimensionCount" to dynamicDimensionOptions.size,
                    "catalogDimensionCount" to configuredDimensions.size,
                    "appLanguage" to appLanguage.key,
                    "effectiveLanguageTag" to effectiveLanguageTag,
                    "systemLanguageTag" to systemLanguageTag,
                ),
            )
            logger.i(
                "AppPreferencesViewModel.observeSettings",
                "Dimension trace catalogIds=${configuredDimensions.joinToString(",") { it.id }} defaultIds=${dimensionPrefs.joinToString(",") { it.id }} customIds=${dynamicDimensionOptions.joinToString(",") { it.id }}",
            )
            logger.i(
                "AppPreferencesViewModel.observeSettings",
                "Dimension label trace ${
                    (
                        dimensionPrefs.map { "${it.id}:label=${it.label},custom=${it.hasCustomLabelOverride}" } +
                            dynamicDimensionOptions.map { "${it.id}:label=${it.label},custom=${it.hasCustomLabelOverride}" }
                        )
                        .joinToString(" | ")
                }",
            )
            logger.i(
                "AppPreferencesViewModel.observeSettings",
                "Dimension icon trace defaultIcons=${dimensionPrefs.joinToString(",") { "${it.id}:${it.iconKey}" }} customIcons=${dynamicDimensionOptions.joinToString(",") { "${it.id}:${it.iconKey}" }}",
            )
            lastLoggedDimensionSettingsSignature = dimensionSettingsLogSignature
        }
        val autoBackupEnabled = settings[KEY_AUTO_BACKUP_ENABLED]?.toBoolean() ?: false
        val autoBackupInterval = BackupInterval.fromKey(settings[KEY_AUTO_BACKUP_INTERVAL]) ?: BackupInterval.SIXTY_MIN
        val autoBackupLastRun = backupStatus.lastSuccessDisplay ?: settings[KEY_AUTO_BACKUP_LAST_RUN]
        val backupFailureStatus = backupStatus.latestFailure
        val backupRotationEnabled = settings[KEY_BACKUP_ROTATION_ENABLED]?.toBoolean() ?: false
        val backupRotationCount = settings[KEY_BACKUP_ROTATION_COUNT]?.toIntOrNull()?.coerceIn(1, 999) ?: 50
        val dayBoundaryHour = settings[KEY_DAY_BOUNDARY_HOUR]?.toIntOrNull()?.coerceIn(0, 5) ?: 0
        val debugLoggingEnabled = settings[KEY_DEBUG_LOGGING_ENABLED]?.toBoolean() ?: BuildConfig.DEBUG
        val databaseInitCompleted = settings[KEY_DATABASE_INIT_COMPLETED]?.toBoolean() ?: false
        // Auto-tracking habit completion time preferences
        val autoTrackHabitTimeGlobal = settings[KEY_AUTO_TRACK_HABIT_TIME]?.toBoolean() ?: false
        val autoTrackDimensionIds = (dimensionPrefs.map { it.id } + dynamicDimensionOptions.map { it.id }).distinct()
        val autoTrackDimensionPrefs = autoTrackDimensionIds.associateWith { dimensionId ->
            settings["$KEY_AUTO_TRACK_DIMENSION_PREFIX$dimensionId"]?.toBoolean()
                ?: autoTrackHabitTimeGlobal
        }
        // Focus Mode preferences
        val activePreset = FocusModePreset.fromPresetId(settings[KEY_ACTIVE_PRESET])
        val allTabs = listOf("tasks", "habits", "time", "journal", "notes", "lenses", "settings")
        val tabVisibility = allTabs.associateWith { tabRoute ->
            if (tabRoute == "settings") {
                true // Settings tab is always visible
            } else {
                settings["$KEY_TAB_VISIBLE_PREFIX$tabRoute"]?.toBoolean()
                    ?: activePreset.visibleTabs.contains(tabRoute)
            }
        }
        val focusModeOnboardingCompleted = settings[KEY_FOCUS_MODE_ONBOARDING_COMPLETED]?.toBoolean() ?: false
        val currentTaskFilter = TaskFilter.fromKey(settings[KEY_TASK_FILTER_OPTION])
        val launchDestinationRoute = settings[KEY_LAUNCH_DESTINATION_ROUTE] ?: "time"
        val launchDestinationTaskFilter = TaskFilter.fromKey(settings[KEY_LAUNCH_DESTINATION_TASK_FILTER])
        val launchDestination = LaunchDestination(
            route = launchDestinationRoute,
            taskFilter = launchDestinationTaskFilter,
        )
        // Insights charts visibility prefs
        val chartTimeModuleEnabled = settings[KEY_CHART_TIME_MODULE]?.toBoolean() ?: true
        val chartTimeOverallSnapshotEnabled = settings[KEY_CHART_TIME_OVERALL_SNAPSHOT]?.toBoolean() ?: false
        val chartTimeExecutionDetailsEnabled = settings[KEY_CHART_TIME_EXECUTION_DETAILS]?.toBoolean() ?: false
        val chartTimeScoreCardsEnabled = settings[KEY_CHART_TIME_SCORE_CARDS]?.toBoolean() ?: false
        val chartTimeOverallScoreCardEnabled = settings[KEY_CHART_TIME_OVERALL_SCORE_CARD]?.toBoolean() ?: false
        val chartTimeDimensionScoreCardsEnabled = settings[KEY_CHART_TIME_DIM_SCORE_CARDS]?.toBoolean() ?: false
        val chartTimeLineGraphsEnabled = settings[KEY_CHART_TIME_LINE_GRAPHS]?.toBoolean() ?: false
        val chartTimeDailyScoreTrendEnabled = settings[KEY_CHART_TIME_DAILY_SCORE_TREND]?.toBoolean() ?: false
        val chartTimeProgressTrendEnabled = settings[KEY_CHART_TIME_PROGRESS_TREND]?.toBoolean() ?: false
        val chartTimeHistoricalRankingEnabled = settings[KEY_CHART_TIME_HISTORICAL_RANKING]?.toBoolean() ?: false
        val chartTimeMomentumStreakEnabled = settings[KEY_CHART_TIME_MOMENTUM_STREAK]?.toBoolean() ?: false
        val chartTaskModuleEnabled = settings[KEY_CHART_TASK_MODULE]?.toBoolean() ?: false
        val chartHabitModuleEnabled = settings[KEY_CHART_HABIT_MODULE]?.toBoolean() ?: false
        val chartJournalModuleEnabled = settings[KEY_CHART_JOURNAL_MODULE]?.toBoolean() ?: false
        val chartNoteModuleEnabled = settings[KEY_CHART_NOTE_MODULE]?.toBoolean() ?: false
        val chartAverageDailyTimeEnabled = settings[KEY_CHART_AVERAGE_DAILY_TIME]?.toBoolean() ?: true
        val chartDimSplitEnabled = settings[KEY_CHART_DIM_SPLIT]?.toBoolean() ?: false
        val chartDimTrendEnabled = settings[KEY_CHART_DIM_TREND]?.toBoolean() ?: false
        val chartDailyTimelineEnabled = settings[KEY_CHART_DAILY_TIMELINE]?.toBoolean() ?: false
        val chartWeeklyPatternEnabled = settings[KEY_CHART_WEEKLY_PATTERN]?.toBoolean() ?: false
        val chartDailyRhythmEnabled = settings[KEY_CHART_DAILY_RHYTHM]?.toBoolean() ?: false
        val chartWeeklyPatternExclEmpty = settings[KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY]?.toBoolean() ?: false
        val chartDailyRhythmExclEmpty = settings[KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY]?.toBoolean() ?: false
        // Update UnifiedLogger debug logging
        io.payanam.common.logging.UnifiedLogger.setDebugLoggingEnabled(debugLoggingEnabled)
        _uiState.update {
            it.copy(
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
    }
    /**
     * Persists the chosen UI theme mode.
     */
    fun setThemeMode(mode: ThemeModeOption) {
        saveSetting(KEY_THEME_MODE, mode.key)
    }
    /**
     * Persists the chosen app language.
     */
    fun setAppLanguage(language: AppLanguageOption) {
        saveSetting(KEY_APP_LANGUAGE, language.key)
    }
    /**
     * Normalizes [languageTag] to a supported tag and updates the runtime system language.
     */
    fun updateSystemLanguageTag(languageTag: String?) {
        val normalizedTag = normalizeSupportedLanguageTag(languageTag)
        if (runtimeSystemLanguageTag.value == normalizedTag) {
            return
        }
        runtimeSystemLanguageTag.value = normalizedTag
        logger.i(
            "AppPreferencesViewModel.updateSystemLanguageTag",
            "Updated runtime system language tag",
            mapOf("systemLanguageTag" to normalizedTag),
        )
    }
    /**
     * Persists the chosen font family.
     */
    fun setFontFamily(fontFamily: FontFamilyOption) {
        saveSetting(KEY_FONT_FAMILY, fontFamily.key)
    }
    /**
     * Persists the chosen clock format.
     */
    fun setTimeFormat(timeFormat: TimeFormatOption) {
        saveSetting(KEY_TIME_FORMAT, timeFormat.key)
    }
    /**
     * Persists the timeline hour-height (clamped to the supported DP range).
     */
    fun setTimeHourHeightDp(hourHeightDp: Float) {
        val clamped = hourHeightDp.coerceIn(MIN_TIME_HOUR_HEIGHT_DP, MAX_TIME_HOUR_HEIGHT_DP)
        val normalized = String.format(Locale.US, "%.2f", clamped)
        saveSetting(KEY_TIME_HOUR_HEIGHT_DP, normalized)
    }
    /**
     * Persists a user-edited display label for [dimension] (resolves to its id).
     */
    fun setDimensionLabel(dimension: LifeDimension, label: String) {
        setDimensionLabel(dimension.id, label)
    }
    /**
     * Persists a user-edited display label for a dimension by [dimensionId].
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun setDimensionLabel(dimensionId: String, label: String) {
        viewModelScope.launch {
            val normalizedLabel = normalizeDimensionLabelForStorage(
                dimensionId = dimensionId,
                candidateLabel = label,
                effectiveLanguageTag = _uiState.value.effectiveLanguageTag,
            )
            try {
                lifeDimensionCatalogRepository.updateDimensionLabel(dimensionId, normalizedLabel)
                logger.i(
                    "AppPreferencesViewModel.setDimensionLabel",
                    "Dimension label updated in DB-backed catalog",
                    mapOf("dimensionId" to dimensionId),
                )
            } catch (e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionLabel",
                    "Failed to update dimension label in DB-backed catalog",
                    e,
                    mapOf("dimensionId" to dimensionId),
                )
            }
        }
    }
    /**
     * Persists the editable weight for [dimension] (delegates to the id overload).
     */
    fun setDimensionWeight(dimension: LifeDimension, weight: Double) {
        setDimensionWeight(dimension.id, weight)
    }

    /**
     * C2: user-editable dimension weight. Weighted L3 aggregation kicks in on
     * the NEXT day-score recalc; this edit triggers an immediate L3-only
     * recalc (self-gov `dim_weight_change` path — L1/L2 untouched).
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun setDimensionWeight(dimensionId: String, weight: Double) {
        val clamped = weight.coerceIn(0.1, 10.0)
        viewModelScope.launch {
            try {
                lifeDimensionCatalogRepository.updateDimensionWeight(dimensionId, clamped)
                logger.i(
                    "AppPreferencesViewModel.setDimensionWeight",
                    "Dimension weight updated",
                    mapOf("dimensionId" to dimensionId, "weight" to clamped),
                )
                // L3-only recalc: day scores re-aggregate with new weights.
                scoreRollupCascadeService.recalcDayOnly(LocalDate.now())
            } catch (e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionWeight",
                    "Failed to update dimension weight / recalc day layer",
                    e,
                    mapOf("dimensionId" to dimensionId, "weight" to clamped),
                )
            }
        }
    }
    /**
     * Persists a user-edited color for [dimension] (resolves to its id).
     */
    fun setDimensionColor(dimension: LifeDimension, color: Color) {
        setDimensionColor(dimension.id, color)
    }
    /**
     * Persists a user-edited color for a dimension by [dimensionId].
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun setDimensionColor(dimensionId: String, color: Color) {
        viewModelScope.launch {
            try {
                lifeDimensionCatalogRepository.updateDimensionColor(dimensionId, colorToHex(color))
                logger.i(
                    "AppPreferencesViewModel.setDimensionColor",
                    "Dimension color updated in DB-backed catalog",
                    mapOf("dimensionId" to dimensionId),
                )
            } catch (e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionColor",
                    "Failed to update dimension color in DB-backed catalog",
                    e,
                    mapOf("dimensionId" to dimensionId),
                )
            }
        }
    }
    /**
     * Clears the user-overridden label for a dimension, reverting to the canonical label.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun resetDimensionLabel(dimensionId: String) {
        viewModelScope.launch {
            try {
                lifeDimensionCatalogRepository.updateDimensionLabel(
                    dimensionId = dimensionId,
                    label = defaultStoredLabelForDimension(dimensionId),
                )
                logger.i(
                    "AppPreferencesViewModel.resetDimensionLabel",
                    "Dimension label reset to app default in DB-backed catalog",
                    mapOf("dimensionId" to dimensionId),
                )
            } catch (e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.resetDimensionLabel",
                    "Failed to reset dimension label in DB-backed catalog",
                    e,
                    mapOf("dimensionId" to dimensionId),
                )
            }
        }
    }
    /**
     * Persists the chosen icon for a dimension and refreshes UI.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun setDimensionIcon(dimensionId: String, iconKey: String) {
        viewModelScope.launch {
            try {
                lifeDimensionCatalogRepository.updateDimensionIcon(dimensionId, iconKey)
                logger.i(
                    "AppPreferencesViewModel.setDimensionIcon",
                    "Dimension icon updated in DB-backed catalog",
                    mapOf("dimensionId" to dimensionId, "iconKey" to iconKey),
                )
            } catch (e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionIcon",
                    "Failed to update dimension icon in DB-backed catalog",
                    e,
                    mapOf("dimensionId" to dimensionId, "iconKey" to iconKey),
                )
            }
        }
    }
    /**
     * Persists the visible/hidden toggle for [dimension] (resolves to its id).
     */
    fun setDimensionVisibility(dimension: LifeDimension, isVisible: Boolean) {
        setDimensionVisibility(dimension.id, isVisible)
    }
    /**
     * Persists the visible/hidden toggle for a dimension by [dimensionId].
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun setDimensionVisibility(dimensionId: String, isVisible: Boolean) {
        viewModelScope.launch {
            try {
                lifeDimensionCatalogRepository.updateDimensionActiveState(dimensionId, isVisible)
                logger.i(
                    "AppPreferencesViewModel.setDimensionVisibility",
                    "Dimension visibility updated in DB-backed catalog",
                    mapOf("dimensionId" to dimensionId, "isVisible" to isVisible),
                )
            } catch (e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionVisibility",
                    "Failed to update dimension visibility in DB-backed catalog",
                    e,
                    mapOf("dimensionId" to dimensionId, "isVisible" to isVisible),
                )
            }
        }
    }
    /**
     * Persists the auto-backup on/off toggle.
     */
    fun setAutoBackupEnabled(enabled: Boolean) {
        saveSetting(KEY_AUTO_BACKUP_ENABLED, enabled.toString())
    }
    /**
     * Persists the auto-backup cadence.
     */
    fun setAutoBackupInterval(interval: BackupInterval) {
        saveSetting(KEY_AUTO_BACKUP_INTERVAL, interval.key)
    }
    /**
     * Records the last successful auto-backup timestamp.
     */
    fun setAutoBackupLastRun(timestamp: String) {
        saveSetting(KEY_AUTO_BACKUP_LAST_RUN, timestamp)
    }
    /**
     * Persists backup rotation on/off and syncs to shared prefs.
     */
    fun setBackupRotationEnabled(enabled: Boolean) {
        saveSetting(KEY_BACKUP_ROTATION_ENABLED, enabled.toString())
        syncBackupRotationToSharedPrefs(enabled, _uiState.value.backupRotationCount)
    }
    /**
     * Persists the retained-backup count (1-999) and syncs to shared prefs.
     */
    fun setBackupRotationCount(count: Int) {
        val clamped = count.coerceIn(1, 999)
        saveSetting(KEY_BACKUP_ROTATION_COUNT, clamped.toString())
        syncBackupRotationToSharedPrefs(_uiState.value.backupRotationEnabled, clamped)
    }
    private fun syncBackupRotationToSharedPrefs(enabled: Boolean, count: Int) {
        context.getSharedPreferences("payanam_backup_meta", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("backup_rotation_enabled", enabled)
            .putInt("backup_rotation_count", count)
            .apply()
    }
    /**
     * Re-reads backup status from storage and pushes it into UI state.
     */
    fun refreshAutoBackupStatusFromStorage() {
        backupStatusStore.refresh()
        val status = backupStatusStore.status.value
        logger.d(
            "AppPreferencesViewModel.refreshAutoBackupStatusFromStorage",
            "Refreshed backup status from backup artifacts",
            mapOf(
                "latestBackup" to (status.lastSuccessDisplay ?: "none"),
                "latestFailure" to (status.latestFailure?.message ?: "none"),
            ),
        )
        _uiState.update { state ->
            state.copy(
                autoBackupLastRun = status.lastSuccessDisplay ?: state.autoBackupLastRun,
                autoBackupLastErrorMessage = status.latestFailure?.message,
                autoBackupLastErrorAt = status.latestFailure?.recordedAtDisplay,
            )
        }
    }
    /**
     * Clears the current auto-backup failure message from the UI.
     */
    fun dismissAutoBackupFailureMessage() {
        backupStatusStore.dismissLatestFailure()
        logger.i(
            "AppPreferencesViewModel.dismissAutoBackupFailureMessage",
            "Dismissed auto-backup failure message",
        )
        _uiState.update { state ->
            state.copy(
                autoBackupLastErrorMessage = null,
                autoBackupLastErrorAt = null,
            )
        }
    }
    /**
     * Kicks off a manual backup to the app backup directory and refreshes status.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: backup + restore pipeline; multiple failure modes
    fun triggerManualBackupNow() {
        _manualBackupInProgress.value = true
        viewModelScope.launch {
            try {
                val result = databaseBackupCoordinator.backupToAppBackupDirectory(BackupTrigger.MANUAL)
                refreshAutoBackupStatusFromStorage()
                _manualBackupResultMessage.tryEmit(
                    context.getString(R.string.settings_manual_backup_success, result.recordedAtDisplay),
                )
                logger.i(
                    "AppPreferencesViewModel.triggerManualBackupNow",
                    "Manual backup succeeded",
                    mapOf(
                        "recordedAt" to result.recordedAtDisplay,
                        "attemptsUsed" to result.attemptsUsed,
                        "destinationPath" to result.destinationPath,
                    ),
                )
                AutoBackupWorker.rescheduleFromNow(context, appSettingsRepository)
            } catch (error: Exception) {
                refreshAutoBackupStatusFromStorage()
                _manualBackupResultMessage.tryEmit(
                    context.getString(R.string.settings_manual_backup_failed, error.message ?: "Backup failed"),
                )
                logger.e("AppPreferencesViewModel.triggerManualBackupNow", "Manual backup failed", error)
            } finally {
                _manualBackupInProgress.value = false
            }
        }
    }
    private fun queryHabitInventory(readableDb: SupportSQLiteDatabase): List<Map<String, Any>> {
        val rows = mutableListOf<Map<String, Any>>()
        readableDb.query(
            """
            SELECT id, title, recurrenceRule, recurrenceEnabled, dimension_id,
                   createdAt, updatedAt, status, archivedAt
            FROM tasks
            WHERE recurrenceEnabled = 1
            ORDER BY createdAt
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += mapOf(
                    "habitId" to cursor.getString(0),
                    "title" to cursor.getString(1),
                    "recurrenceRule" to (cursor.getString(2) ?: "null"),
                    "recurrenceEnabled" to cursor.getInt(3),
                    "dimensionId" to (cursor.getString(4) ?: "null"),
                    "createdAt" to (cursor.getString(5) ?: "null"),
                    "updatedAt" to (cursor.getString(6) ?: "null"),
                    "status" to (cursor.getString(7) ?: "null"),
                    "archivedAt" to (cursor.getString(8) ?: "null"),
                )
            }
        }
        return rows
    }

    private fun queryOccurrenceStats(readableDb: SupportSQLiteDatabase): List<Map<String, Any>> {
        val rows = mutableListOf<Map<String, Any>>()
        readableDb.query(
            """
            SELECT taskId,
                   COUNT(*) AS total,
                   MIN(dueDate) AS firstDue,
                   MAX(dueDate) AS lastDue,
                   SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) AS completed,
                   SUM(CASE WHEN status = 'missed' THEN 1 ELSE 0 END) AS missed,
                   SUM(CASE WHEN status = 'skipped' THEN 1 ELSE 0 END) AS skipped
            FROM task_occurrences
            GROUP BY taskId
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += mapOf(
                    "habitId" to cursor.getString(0),
                    "occurrenceTotal" to cursor.getLong(1),
                    "firstDue" to (cursor.getString(2) ?: "null"),
                    "lastDue" to (cursor.getString(3) ?: "null"),
                    "completed" to cursor.getLong(4),
                    "missed" to cursor.getLong(5),
                    "skipped" to cursor.getLong(6),
                )
            }
        }
        return rows
    }

    private fun queryDimensionWeights(readableDb: SupportSQLiteDatabase): List<Map<String, Any>> {
        val rows = mutableListOf<Map<String, Any>>()
        readableDb.query(
            """
            SELECT id, key, label, sortOrder, isActive, color, icon
            FROM life_dimensions
            ORDER BY sortOrder
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += mapOf(
                    "dimensionId" to cursor.getString(0),
                    "key" to (cursor.getString(1) ?: "null"),
                    "label" to (cursor.getString(2) ?: "null"),
                    "sortOrder" to cursor.getInt(3),
                    "isActive" to cursor.getInt(4),
                    "color" to (cursor.getString(5) ?: "null"),
                    "icon" to (cursor.getString(6) ?: "null"),
                )
            }
        }
        return rows
    }
    /**
     * Runs the habit-score diagnostics pass and publishes the result to the UI.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun runHabitScoreDiagnostics() {
        if (_habitScoreDiagnosticsInProgress.value) {
            return
        }
        _habitScoreDiagnosticsInProgress.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val logTag = "AppPreferencesViewModel.runHabitScoreDiagnostics"
            try {
                val readableDb = sessionManager.requireDatabase().openHelper.readableDatabase
                logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_START")

                // ── 1. Habit inventory: raw recurrence rule formats ──────────
                val habitRows = queryHabitInventory(readableDb)
                logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_HABIT_COUNT", mapOf("count" to habitRows.size))
                habitRows.forEach { row ->
                    // Classify the recurrence rule format for migration planning
                    val rule = row["recurrenceRule"]?.toString() ?: ""
                    val format = when {
                        rule.contains("CONFIG:") -> "config"
                        rule.contains("RRULE:") || rule.contains("FREQ=") -> "rrule"
                        rule.matches(Regex("""\d+/\d+(!start=\d{4}-\d{2}-\d{2})?""")) -> "num_den"
                        rule.isBlank() -> "blank"
                        else -> "other"
                    }
                    logger.i(
                        logTag,
                        "HABIT_SCORE_DIAGNOSTICS_HABIT",
                        row + mapOf("ruleFormat" to format),
                    )
                }

                // ── 2. Frequency x/y inventory (num_den habits) ─────────────
                val numDenHabits = habitRows.filter {
                    it["recurrenceRule"]?.toString()?.matches(Regex("""\d+/\d+""")) == true
                }
                val numDenRules: List<String> = numDenHabits
                    .mapNotNull { it["recurrenceRule"]?.toString() }
                    .distinct()
                    .sorted()
                logger.i(
                    logTag,
                    "HABIT_SCORE_DIAGNOSTICS_NUM_DEN_INVENTORY",
                    mapOf(
                        "numDenCount" to numDenHabits.size,
                        "habitTotal" to habitRows.size,
                        "rules" to numDenRules,
                    ),
                )

                // ── 3. Occurrence stats per habit ───────────────────────────
                val occRows = queryOccurrenceStats(readableDb)
                logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_OCCURRENCE_COUNT", mapOf("habitsWithOccurrences" to occRows.size))
                occRows.forEach { row ->
                    logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_OCCURRENCE", row)
                }

                // ── 4. Dimension weights (life_dimensions actuals) ──────────
                val dimRows = queryDimensionWeights(readableDb)
                dimRows.forEach { row ->
                    logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_DIMENSION", row)
                }

                // ── 5. Habit → dimension distribution ──────────────────────
                val dimDist = habitRows.groupBy { it["dimensionId"]?.toString() ?: "null" }
                    .mapValues { (_, v) -> v.size }
                logger.i(
                    logTag,
                    "HABIT_SCORE_DIAGNOSTICS_DIM_DISTRIBUTION",
                    mapOf("distribution" to dimDist),
                )

                // ── 6. Score roll-up metric tables (actual DB read-back) ───
                // Reads the v18 metric tables directly so the backfill result
                // can be verified from the DB itself, not just write-path logs.
                val metricTables = listOf("habit_metrics", "dimension_metrics", "day_metrics")
                for (table in metricTables) {
                    readableDb.query(
                        """
                        SELECT COUNT(*), MIN(dayKey), MAX(dayKey)
                        FROM $table
                        """.trimIndent(),
                    ).use { cursor ->
                        if (cursor.moveToNext()) {
                            logger.i(
                                logTag,
                                "HABIT_SCORE_DIAGNOSTICS_METRIC_TABLE",
                                mapOf(
                                    "table" to table,
                                    "rowCount" to cursor.getLong(0),
                                    "minDayKey" to (cursor.getString(1) ?: "null"),
                                    "maxDayKey" to (cursor.getString(2) ?: "null"),
                                ),
                            )
                        }
                    }
                }
                // Distinct habit/dimension coverage inside metric tables
                readableDb.query("SELECT COUNT(DISTINCT habitId) FROM habit_metrics").use { cursor ->
                    if (cursor.moveToNext()) {
                        logger.i(
                            logTag,
                            "HABIT_SCORE_DIAGNOSTICS_METRIC_COVERAGE",
                            mapOf("distinctHabitsInL1" to cursor.getLong(0)),
                        )
                    }
                }
                readableDb.query("SELECT COUNT(DISTINCT dimensionId) FROM dimension_metrics").use { cursor ->
                    if (cursor.moveToNext()) {
                        logger.i(
                            logTag,
                            "HABIT_SCORE_DIAGNOSTICS_METRIC_COVERAGE",
                            mapOf("distinctDimensionsInL2" to cursor.getLong(0)),
                        )
                    }
                }

                logger.i(
                    logTag,
                    "HABIT_SCORE_DIAGNOSTICS_END",
                    mapOf(
                        "habitCount" to habitRows.size,
                        "occurrenceHabitCount" to occRows.size,
                        "numDenCount" to numDenHabits.size,
                        "dimensionCount" to dimRows.size,
                    ),
                )
                _habitScoreDiagnosticsMessage.tryEmit(
                    context.getString(
                        R.string.settings_snackbar_habit_score_diagnostics_complete,
                        habitRows.size,
                        occRows.size,
                    ),
                )
            } catch (e: Exception) {
                logger.e(
                    logTag,
                    "HABIT_SCORE_DIAGNOSTICS_FAILED",
                    e,
                )
                _habitScoreDiagnosticsMessage.tryEmit(
                    context.getString(
                        R.string.settings_snackbar_habit_score_diagnostics_failed,
                        e.message ?: e::class.java.simpleName,
                    ),
                )
            } finally {
                _habitScoreDiagnosticsInProgress.value = false
            }
        }
    }
    /**
     * Persists the day-boundary hour (0-5) used for daily rollups.
     */
    fun setDayBoundaryHour(hour: Int) {
        val clampedHour = hour.coerceIn(0, 5)
        saveSetting(KEY_DAY_BOUNDARY_HOUR, clampedHour.toString())
    }
    /**
     * Persists the debug-logging toggle and applies it live.
     */
    fun setDebugLoggingEnabled(enabled: Boolean) {
        saveSetting(KEY_DEBUG_LOGGING_ENABLED, enabled.toString())
        UnifiedLogger.setDebugLoggingEnabled(enabled)
    }
    /**
     * Persists the global auto-track-habit-time toggle.
     */
    fun setAutoTrackHabitTimeGlobal(enabled: Boolean) {
        saveSetting(KEY_AUTO_TRACK_HABIT_TIME, enabled.toString())
        logger.i(
            "AppPreferencesViewModel.setAutoTrackHabitTimeGlobal",
            "Auto-tracking global setting updated",
            mapOf(
                "enabled" to enabled,
            ),
        )
    }
    /**
     * Persists auto-track toggle for the dimension (id or [dimension]).
     */
    fun setAutoTrackDimensionPreference(dimension: LifeDimension, enabled: Boolean) {
        setAutoTrackDimensionPreference(dimension.id, enabled)
    }
    /**
     * Persists auto-track toggle for the dimension (id or [dimension]).
     */
    fun setAutoTrackDimensionPreference(dimensionId: String, enabled: Boolean) {
        saveSetting("$KEY_AUTO_TRACK_DIMENSION_PREFIX$dimensionId", enabled.toString())
        logger.i(
            "AppPreferencesViewModel.setAutoTrackDimensionPreference",
            "Auto-tracking dimension setting updated",
            mapOf(
                "dimensionId" to dimensionId,
                "enabled" to enabled,
            ),
        )
    }

    /**
     * Set the active focus mode preset and update tab visibility accordingly.
     * Settings tab is always kept visible regardless of preset.
     */
    fun setActivePreset(preset: FocusModePreset) {
        saveSetting(KEY_ACTIVE_PRESET, preset.presetId)
        // Update individual tab visibility based on preset
        // Settings tab is always visible
        val allTabs = listOf("tasks", "habits", "time", "journal", "notes", "lenses", "settings")
        allTabs.forEach { tabRoute ->
            val isVisible = tabRoute == "settings" || preset.visibleTabs.contains(tabRoute)
            saveSetting("$KEY_TAB_VISIBLE_PREFIX$tabRoute", isVisible.toString())
        }
        logger.i(
            "AppPreferencesViewModel.setActivePreset",
            "Focus mode preset changed",
            mapOf(
                "preset" to preset.presetId,
                "visibleTabs" to preset.visibleTabs.joinToString(", "),
            ),
        )
    }

    /**
     * Toggle visibility of individual tab. settings tab cannot be hidden.
     */
    fun setTabVisibility(tabRoute: String, visible: Boolean) {
        if (tabRoute == "settings") {
            logger.w("AppPreferencesViewModel.setTabVisibility", "Attempted to hide Settings tab, ignoring")
            return
        }
        saveSetting("$KEY_TAB_VISIBLE_PREFIX$tabRoute", visible.toString())
        logger.i(
            "AppPreferencesViewModel.setTabVisibility",
            "Tab visibility changed",
            mapOf(
                "tabRoute" to tabRoute,
                "visible" to visible,
            ),
        )
    }

    /**
     * Mark focus mode onboarding as completed. this is a one-time flag.
     */
    fun markFocusModeOnboardingCompleted() {
        saveSetting(KEY_FOCUS_MODE_ONBOARDING_COMPLETED, "true")
        logger.i("AppPreferencesViewModel.markFocusModeOnboardingCompleted", "Focus mode onboarding marked as completed")
    }
    /**
     * Makes the Time screen the default launch destination.
     */
    fun setLaunchDestinationTime() {
        saveSetting(KEY_LAUNCH_DESTINATION_ROUTE, "time")
        clearSetting(KEY_LAUNCH_DESTINATION_TASK_FILTER)
        logger.i(
            "AppPreferencesViewModel.setLaunchDestinationTime",
            "Default launch destination saved",
            mapOf("route" to "time"),
        )
    }
    /**
     * Makes the Tasks screen the default launch destination, with an optional [taskFilter].
     */
    fun setLaunchDestinationTasks(taskFilter: TaskFilter?) {
        saveSetting(KEY_LAUNCH_DESTINATION_ROUTE, "tasks")
        saveSettingNullable(KEY_LAUNCH_DESTINATION_TASK_FILTER, taskFilter?.key)
        if (taskFilter != null) {
            saveSetting(KEY_TASK_FILTER_OPTION, taskFilter.key)
        }
        logger.i(
            "AppPreferencesViewModel.setLaunchDestinationTasks",
            "Default launch destination updated",
            mapOf("route" to "tasks", "taskFilter" to (taskFilter?.key ?: "none")),
        )
    }
    /**
     * Persists the default launch-destination route.
     */
    fun setLaunchDestination(route: String) {
        saveSetting(KEY_LAUNCH_DESTINATION_ROUTE, route)
        clearSetting(KEY_LAUNCH_DESTINATION_TASK_FILTER)
        logger.i(
            "AppPreferencesViewModel.setLaunchDestination",
            "Default launch destination saved",
            mapOf("route" to route),
        )
    }
    /**
     * Toggles the Time insights module on the dashboard.
     */
    fun setChartTimeModuleEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeModuleEnabled", "Time insights module toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the Time overall-snapshot card.
     */
    fun setChartTimeOverallSnapshotEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_OVERALL_SNAPSHOT, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeOverallSnapshotEnabled", "Time overall snapshot card toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the Time execution-details card.
     */
    fun setChartTimeExecutionDetailsEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_EXECUTION_DETAILS, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeExecutionDetailsEnabled", "Time execution details toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the Time score-cards section.
     */
    fun setChartTimeScoreCardsEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_SCORE_CARDS, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeScoreCardsEnabled", "Time score cards section toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the Time overall score card.
     */
    fun setChartTimeOverallScoreCardEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_OVERALL_SCORE_CARD, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeOverallScoreCardEnabled", "Time overall score card toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the per-dimension Time score cards.
     */
    fun setChartTimeDimensionScoreCardsEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_DIM_SCORE_CARDS, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeDimensionScoreCardsEnabled", "Time dimension score cards toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the Time line-graphs section.
     */
    fun setChartTimeLineGraphsEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_LINE_GRAPHS, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeLineGraphsEnabled", "Time line graphs section toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the daily score-trend chart.
     */
    fun setChartTimeDailyScoreTrendEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_DAILY_SCORE_TREND, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeDailyScoreTrendEnabled", "Time daily score trend toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the progress-trend chart.
     */
    fun setChartTimeProgressTrendEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_PROGRESS_TREND, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeProgressTrendEnabled", "Time progress trend toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the historical-ranking chart.
     */
    fun setChartTimeHistoricalRankingEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_HISTORICAL_RANKING, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeHistoricalRankingEnabled", "Time historical ranking toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the momentum-streak chart.
     */
    fun setChartTimeMomentumStreakEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TIME_MOMENTUM_STREAK, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeMomentumStreakEnabled", "Time momentum streak toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the Task insights module.
     */
    fun setChartTaskModuleEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_TASK_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTaskModuleEnabled", "Task insights module toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the Habit insights module.
     */
    fun setChartHabitModuleEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_HABIT_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartHabitModuleEnabled", "Habit insights module toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the Journal insights module.
     */
    fun setChartJournalModuleEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_JOURNAL_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartJournalModuleEnabled", "Journal insights module toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the Note insights module.
     */
    fun setChartNoteModuleEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_NOTE_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartNoteModuleEnabled", "Note insights module toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the average-daily-time chart.
     */
    fun setChartAverageDailyTimeEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_AVERAGE_DAILY_TIME, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartAverageDailyTimeEnabled", "Average daily time chart toggled", mapOf("enabled" to enabled))
    }

    /**
     * Check if focus mode onboarding has been completed.
     */
    suspend fun hasFocusModeOnboardingCompleted(): Boolean = appSettingsRepository.getSetting(KEY_FOCUS_MODE_ONBOARDING_COMPLETED) == "true"
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    private fun saveSetting(key: String, value: String) {
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting(key, value)
                logger.d(
                    "AppPreferencesViewModel.saveSetting",
                    "Setting updated",
                    mapOf(
                        "key" to key,
                        "value" to value,
                    ),
                )
            } catch (e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.saveSetting",
                    "Failed to update setting",
                    e,
                    mapOf(
                        "key" to key,
                    ),
                )
            }
        }
    }
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    private fun clearSetting(key: String) {
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting(key, null)
                logger.d(
                    "AppPreferencesViewModel.saveSetting",
                    "Setting cleared",
                    mapOf(
                        "key" to key,
                    ),
                )
            } catch (e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.saveSetting",
                    "Failed to clear setting",
                    e,
                    mapOf(
                        "key" to key,
                    ),
                )
            }
        }
    }
    private fun saveSettingNullable(key: String, value: String?) {
        if (value == null) {
            clearSetting(key)
        } else {
            saveSetting(key, value)
        }
    }
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    private fun parseColor(hex: String): Color {
        val normalized = hex.removePrefix("#")
        return try {
            val colorLong = normalized.toLong(16)
            if (normalized.length <= 6) {
                Color((0xFF000000 or colorLong).toInt())
            } else {
                Color(colorLong.toInt())
            }
        } catch (e: Exception) {
            logger.w("AppPreferencesViewModel.resolveDimensionColor", "Failed to resolve dimension color, using fallback", mapOf("error" to (e.message ?: "unknown")))
            LifeDimensionColors.forDimension("Career & Work")
        }
    }
    private fun colorToHex(color: Color): String = "#%08X".format(color.toArgb())

    private fun buildDimensionCatalogUiState(
        dimensions: List<ConfiguredLifeDimension>,
        effectiveLanguageTag: String,
    ): Pair<List<DimensionPreference>, List<DimensionOption>> {
        val preferredRows = dimensions
            .filterNot { it.id == DimensionTaxonomyCatalog.UNASSIGNED.id }
            .groupBy { DimensionTaxonomyCatalog.fromCanonicalId(it.id)?.id ?: it.id }
            .values
            .map(::selectPreferredCatalogDimensionRow)
            .sortedBy { DimensionTaxonomyCatalog.fromCanonicalId(it.id)?.sortOrder ?: it.sortOrder }
        val builtInPreferences = mutableListOf<DimensionPreference>()

        preferredRows.forEach { dimension ->
            val option = toDbBackedDimensionOption(
                dimension = dimension,
                effectiveLanguageTag = effectiveLanguageTag,
            )
            builtInPreferences += DimensionPreference(
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

    private fun selectPreferredCatalogDimensionRow(
        candidates: List<ConfiguredLifeDimension>,
    ): ConfiguredLifeDimension = candidates.sortedWith(
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
        val resolvedColorHex = dimension.colorHex.ifBlank {
            canonicalDefinition?.defaultColorHex ?: colorToHex(LifeDimensionColors.forDimension("Career & Work"))
        }
        val resolvedIconKey = dimension.iconKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: canonicalDefinition?.defaultIconKey
            ?: DimensionIconCatalog.defaultIconKeyForDimensionId(dimension.id)
        return DimensionOption(
            id = dimension.id,
            canonicalId = canonicalId ?: dimension.id,
            label = resolveDbBackedDimensionLabel(
                dimension = dimension,
                canonicalId = canonicalId,
                effectiveLanguageTag = effectiveLanguageTag,
            ),
            description = resolveDbBackedDimensionDescription(
                dimension = dimension,
                canonicalId = canonicalId,
                effectiveLanguageTag = effectiveLanguageTag,
            ),
            color = parseColor(resolvedColorHex),
            isVisible = dimension.isActive,
            iconKey = resolvedIconKey,
            weight = dimension.weight,
            hasCustomLabelOverride = canonicalId == null ||
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

    private fun hasCustomDbBackedLabel(canonicalId: String, storedLabel: String?): Boolean {
        val trimmedLabel = storedLabel?.trim().orEmpty()
        if (trimmedLabel.isBlank()) {
            return false
        }
        return trimmedLabel !in knownAppOwnedCatalogLabels(canonicalId)
    }

    private fun knownAppOwnedCatalogLabels(canonicalId: String): Set<String> {
        val canonicalDefinition = DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)
        return buildSet {
            canonicalDefinition?.fallbackLabel?.let(::add)
            SUPPORTED_DIMENSION_LOCALE_TAGS
                .mapNotNull { localeTag -> localizedCatalogLabel(canonicalId, localeTag) }
                .forEach(::add)
        }
    }

    private fun localizedCatalogLabel(canonicalId: String?, languageTag: String?): String? {
        val resId = DimensionTextCatalog.labelResIdForCanonicalId(canonicalId) ?: return null
        return localizedStringForLanguageTag(resId, languageTag)
    }

    private fun localizedCatalogDescription(canonicalId: String?, languageTag: String?): String? {
        val resId = DimensionTextCatalog.descriptionResIdForCanonicalId(canonicalId) ?: return null
        return localizedStringForLanguageTag(resId, languageTag)
    }

    private fun localizedStringForLanguageTag(resId: Int, languageTag: String?): String {
        if (languageTag.isNullOrBlank()) {
            return context.getString(resId)
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(languageTag))
        return context.createConfigurationContext(config).getString(resId)
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
        val localizedLabel = localizedCatalogLabel(canonicalId, effectiveLanguageTag)
        return if (
            trimmedLabel == localizedLabel ||
            trimmedLabel in knownAppOwnedCatalogLabels(canonicalId)
        ) {
            defaultStoredLabelForDimension(dimensionId)
        } else {
            trimmedLabel
        }
    }

    private fun defaultStoredLabelForDimension(dimensionId: String): String = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.fallbackLabel ?: dimensionId

    private fun resolveTimeHourHeightDp(settings: Map<String, String?>): Float {
        val persisted = settings[KEY_TIME_HOUR_HEIGHT_DP]
            ?.toFloatOrNull()
            ?.coerceIn(MIN_TIME_HOUR_HEIGHT_DP, MAX_TIME_HOUR_HEIGHT_DP)
        if (persisted != null) {
            return persisted
        }
        return legacyTimeScaleKeyToHourHeightDp(settings[KEY_TIME_SCALE_LEGACY_KEY])
            ?: DEFAULT_TIME_HOUR_HEIGHT_DP
    }
    private fun legacyTimeScaleKeyToHourHeightDp(legacyKey: String?): Float? = when (legacyKey) {
        "2h" -> 30f
        "1h" -> 60f
        "20m" -> 180f
        "15m" -> 240f
        "10m" -> 360f
        "5m" -> 720f
        else -> null
    }?.coerceIn(MIN_TIME_HOUR_HEIGHT_DP, MAX_TIME_HOUR_HEIGHT_DP)
    /**
     * Toggles the dimension-split chart.
     */
    fun setChartDimSplitEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_DIM_SPLIT, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartDimSplitEnabled", "Chart dim split toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the dimension-trend chart.
     */
    fun setChartDimTrendEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_DIM_TREND, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartDimTrendEnabled", "Chart dim trend toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the daily-timeline chart.
     */
    fun setChartDailyTimelineEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_DAILY_TIMELINE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartDailyTimelineEnabled", "Chart daily timeline toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the weekly-pattern chart.
     */
    fun setChartWeeklyPatternEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_WEEKLY_PATTERN, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartWeeklyPatternEnabled", "Chart weekly pattern toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles the daily-rhythm chart.
     */
    fun setChartDailyRhythmEnabled(enabled: Boolean) {
        saveSetting(KEY_CHART_DAILY_RHYTHM, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartDailyRhythmEnabled", "Chart daily rhythm toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles excluding empty days from the weekly-pattern chart.
     */
    fun setChartWeeklyPatternExclEmpty(enabled: Boolean) {
        saveSetting(KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartWeeklyPatternExclEmpty", "Chart weekly pattern excl-empty toggled", mapOf("enabled" to enabled))
    }
    /**
     * Toggles excluding empty days from the daily-rhythm chart.
     */
    fun setChartDailyRhythmExclEmpty(enabled: Boolean) {
        saveSetting(KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartDailyRhythmExclEmpty", "Chart daily rhythm excl-empty toggled", mapOf("enabled" to enabled))
    }

    private fun resolveSystemLanguageTag(): String = normalizeSupportedLanguageTag(
        Resources.getSystem()
            .configuration
            .locales[0]
            ?.language,
    )
    companion object {
        @Volatile
        private var lastLoggedDimensionSettingsSignature: String? = null

        private const val KEY_THEME_MODE = "theme_mode"
        const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_TIME_FORMAT = "time_format"
        private const val KEY_TIME_HOUR_HEIGHT_DP = "time_hour_height_dp"
        private const val KEY_TIME_SCALE_LEGACY_KEY = "time_scale"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_AUTO_BACKUP_INTERVAL = "auto_backup_interval"
        private const val KEY_AUTO_BACKUP_LAST_RUN = "auto_backup_last_run"
        private const val KEY_BACKUP_ROTATION_ENABLED = "backup_rotation_enabled"
        private const val KEY_BACKUP_ROTATION_COUNT = "backup_rotation_count"
        private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"




        private const val KEY_DATABASE_INIT_COMPLETED = "database_init_completed"
        const val KEY_TASK_SORT_OPTION = "task_sort_option"
        const val KEY_TASK_FILTER_OPTION = "task_filter_option"
        const val KEY_HABIT_SORT_OPTION = "habit_sort_option"
        const val KEY_SHOW_ARCHIVED_HABITS = "show_archived_habits"
        const val KEY_SHOW_COMPLETED_HABITS = "show_completed_habits"
        const val KEY_HIDE_ALL_MARKED_TODAY = "hide_all_marked_today"
        const val KEY_DUE_TODAY_ONLY = "due_today_only"
        const val KEY_DAY_BOUNDARY_HOUR = "day_boundary_hour"
        const val KEY_AUTO_TRACK_HABIT_TIME = "auto_track_habit_time_global"
        const val KEY_AUTO_TRACK_DIMENSION_PREFIX = "auto_track_dimension_"
        private const val KEY_ACTIVE_PRESET = "focus_mode_active_preset"
        private const val KEY_TAB_VISIBLE_PREFIX = "tab_visible_"
        private const val KEY_FOCUS_MODE_ONBOARDING_COMPLETED = "focus_mode_onboarding_completed"
        private const val KEY_LAUNCH_DESTINATION_ROUTE = "launch_destination_route"
        private const val KEY_LAUNCH_DESTINATION_TASK_FILTER = "launch_destination_task_filter"

        // Insights charts visibility keys
        private const val KEY_CHART_TIME_MODULE = "chart_time_module_enabled"
        private const val KEY_CHART_TIME_OVERALL_SNAPSHOT = "chart_time_overall_snapshot_enabled"
        private const val KEY_CHART_TIME_EXECUTION_DETAILS = "chart_time_execution_details_enabled"
        private const val KEY_CHART_TIME_SCORE_CARDS = "chart_time_score_cards_enabled"
        private const val KEY_CHART_TIME_OVERALL_SCORE_CARD = "chart_time_overall_score_card_enabled"
        private const val KEY_CHART_TIME_DIM_SCORE_CARDS = "chart_time_dimension_score_cards_enabled"
        private const val KEY_CHART_TIME_LINE_GRAPHS = "chart_time_line_graphs_enabled"
        private const val KEY_CHART_TIME_DAILY_SCORE_TREND = "chart_time_daily_score_trend_enabled"
        private const val KEY_CHART_TIME_PROGRESS_TREND = "chart_time_progress_trend_enabled"
        private const val KEY_CHART_TIME_HISTORICAL_RANKING = "chart_time_historical_ranking_enabled"
        private const val KEY_CHART_TIME_MOMENTUM_STREAK = "chart_time_momentum_streak_enabled"
        private const val KEY_CHART_TASK_MODULE = "chart_task_module_enabled"
        private const val KEY_CHART_HABIT_MODULE = "chart_habit_module_enabled"
        private const val KEY_CHART_JOURNAL_MODULE = "chart_journal_module_enabled"
        private const val KEY_CHART_NOTE_MODULE = "chart_note_module_enabled"
        private const val KEY_CHART_AVERAGE_DAILY_TIME = "chart_average_daily_time_enabled"
        private const val KEY_CHART_DIM_SPLIT = "chart_dim_split_enabled"
        private const val KEY_CHART_DIM_TREND = "chart_dim_trend_enabled"
        private const val KEY_CHART_DAILY_TIMELINE = "chart_daily_timeline_enabled"
        private const val KEY_CHART_WEEKLY_PATTERN = "chart_weekly_pattern_enabled"
        private const val KEY_CHART_DAILY_RHYTHM = "chart_daily_rhythm_enabled"
        private const val KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY = "chart_weekly_pattern_excl_empty"
        private const val KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY = "chart_daily_rhythm_excl_empty"
        private val SUPPORTED_DIMENSION_LOCALE_TAGS = listOf("en", "ta")
    }
}

internal fun resolveEffectiveLanguageTag(
    option: AppLanguageOption,
    systemLanguageTag: String?,
): String = when (option) {
    AppLanguageOption.ENGLISH -> AppLanguageOption.ENGLISH.key
    AppLanguageOption.TAMIL -> AppLanguageOption.TAMIL.key
    AppLanguageOption.SYSTEM -> normalizeSupportedLanguageTag(systemLanguageTag)
}

internal fun normalizeSupportedLanguageTag(languageTag: String?): String {
    val normalized = languageTag
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.substringBefore('-')
    return if (normalized == AppLanguageOption.TAMIL.key) {
        AppLanguageOption.TAMIL.key
    } else {
        AppLanguageOption.ENGLISH.key
    }
}
