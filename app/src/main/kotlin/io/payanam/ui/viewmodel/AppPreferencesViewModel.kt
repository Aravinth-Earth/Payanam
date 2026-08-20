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
    /** Settings. */
    val settings: Map<String, String?>,
    /** Dimensions. */
    val dimensions: List<ConfiguredLifeDimension>,
    /** System language tag. */
    val systemLanguageTag: String?,
    /** Backup status. */
    val backupStatus: BackupStatusSnapshot,
)

private val appPreferencesLogger
    /** Get. */
    get() = if (UnifiedLogger.isInitialized()) UnifiedLogger.getInstance() else null
private val unresolvedDimensionResolutionKeys = mutableSetOf<String>()

private const val DEFAULT_TIME_HOUR_HEIGHT_DP = 60f
private const val MIN_TIME_HOUR_HEIGHT_DP = 24f
private const val MAX_TIME_HOUR_HEIGHT_DP = 2880f
/**
 * ThemeModeOption.
 */
enum class ThemeModeOption(val key: String) {
    /** System. */
    SYSTEM("system"),
    /** Light. */
    LIGHT("light"),
    /** Dark. */
    DARK("dark"),
    ;

    companion object {
        /**
         * From key.
         */
        fun fromKey(key: String?): ThemeModeOption? = entries.find { it.key == key }
    }
}
/**
 * FontFamilyOption.
 */
enum class FontFamilyOption(val key: String) {
    /** Sans serif. */
    SANS_SERIF("sans-serif"),
    /** Monospace. */
    MONOSPACE("monospace"),
    /** Serif. */
    SERIF("serif"),
    /** Cursive. */
    CURSIVE("cursive"),
    ;

    companion object {
        /**
         * From key.
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
 * TimeFormatOption.
 */
enum class TimeFormatOption(val key: String, val use24Hour: Boolean) {
    /** Twenty four. */
    TWENTY_FOUR("24h", true),
    /** Twelve. */
    TWELVE("12h", false),
    ;

    companion object {
        /**
         * From key.
         */
        fun fromKey(key: String?): TimeFormatOption? = entries.find { it.key == key }
    }
}
/**
 * AppLanguageOption.
 */
enum class AppLanguageOption(val key: String) {
    /** System. */
    SYSTEM("system"),
    /** English. */
    ENGLISH("en"),
    /** Tamil. */
    TAMIL("ta"),
    ;

    companion object {
        /**
         * From key.
         */
        fun fromKey(key: String?): AppLanguageOption? = entries.find { it.key == key }
    }
}
/**
 * DimensionPreference.
 */
data class DimensionPreference(
    /** Key. */
    val key: String,
    /** Label. */
    val label: String,
    /** Color. */
    val color: Color,
    /** Is visible. */
    val isVisible: Boolean,
    /** Id. */
    val id: String = key,
    /** Icon key. */
    val iconKey: String = DimensionIconCatalog.defaultIconKeyForDimensionId(key),
    /** Has custom label override. */
    val hasCustomLabelOverride: Boolean = false,
    /** Canonical id. */
    val canonicalId: String = key,
    /** Description. */
    val description: String? = null,
    /** Weight. */
    val weight: Double = 1.0,
)
/**
 * DimensionOption.
 */
data class DimensionOption(
    /** Id. */
    val id: String,
    /** Label. */
    val label: String,
    /** Color. */
    val color: Color,
    /** Is visible. */
    val isVisible: Boolean,
    /** Icon key. */
    val iconKey: String = DimensionIconCatalog.defaultIconKeyForDimensionId(id),
    /** Has custom label override. */
    val hasCustomLabelOverride: Boolean = false,
    /** Canonical id. */
    val canonicalId: String = id,
    /** Description. */
    val description: String? = null,
    /** Weight. */
    val weight: Double = 1.0,
)

/**
 * LaunchDestination.
 */
data class LaunchDestination(
    /** Route. */
    val route: String = "",
    /** Task filter. */
    val taskFilter: TaskFilter? = null,
)
/**
 * BackupInterval.
 */
enum class BackupInterval(val key: String, val minutes: Long) {
    /** Fifteen min. */
    FIFTEEN_MIN("15m", 15),
    /** Thirty min. */
    THIRTY_MIN("30m", 30),
    /** Sixty min. */
    SIXTY_MIN("60m", 60),
    /** Two hours. */
    TWO_HOURS("2h", 120),
    /** Six hours. */
    SIX_HOURS("6h", 360),
    /** Twelve hours. */
    TWELVE_HOURS("12h", 720),
    /** Daily. */
    DAILY("24h", 1440),
    ;

    companion object {
        /**
         * From key.
         */
        fun fromKey(key: String?): BackupInterval? = entries.find { it.key == key }
    }
}
val ThemeModeOption.displayName: String
    /** Get. */
    get() = key
val FontFamilyOption.displayName: String
    /** Get. */
    get() = key
val TimeFormatOption.displayName: String
    /** Get. */
    get() = key
val BackupInterval.displayName: String
    /** Get. */
    get() = key
val ThemeModeOption.labelResId: Int
    /** Get. */
    get() = when (this) {
        ThemeModeOption.SYSTEM -> R.string.settings_option_theme_system
        ThemeModeOption.LIGHT -> R.string.settings_option_theme_light
        ThemeModeOption.DARK -> R.string.settings_option_theme_dark
    }
val FontFamilyOption.labelResId: Int
    /** Get. */
    get() = when (this) {
        FontFamilyOption.SANS_SERIF -> R.string.settings_option_font_sans_serif
        FontFamilyOption.MONOSPACE -> R.string.settings_option_font_monospace
        FontFamilyOption.SERIF -> R.string.settings_option_font_serif
        FontFamilyOption.CURSIVE -> R.string.settings_option_font_cursive
    }
val TimeFormatOption.labelResId: Int
    /** Get. */
    get() = when (this) {
        TimeFormatOption.TWENTY_FOUR -> R.string.settings_option_time_format_24h
        TimeFormatOption.TWELVE -> R.string.settings_option_time_format_12h
    }
val BackupInterval.labelResId: Int
    /** Get. */
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
 * AppPreferencesState.
 */
data class AppPreferencesState(
    /** Theme mode. */
    val themeMode: ThemeModeOption = ThemeModeOption.SYSTEM,
    /** App language. */
    val appLanguage: AppLanguageOption = AppLanguageOption.SYSTEM,
    /** Effective language tag. */
    val effectiveLanguageTag: String = resolveEffectiveLanguageTag(AppLanguageOption.SYSTEM, null),
    /** Font family. */
    val fontFamily: FontFamilyOption = FontFamilyOption.SANS_SERIF,
    /** Time format. */
    val timeFormat: TimeFormatOption = TimeFormatOption.TWENTY_FOUR,
    /** Time hour height dp. */
    val timeHourHeightDp: Float = DEFAULT_TIME_HOUR_HEIGHT_DP,
    /** Dimension preferences. */
    val dimensionPreferences: List<DimensionPreference> = emptyList(),
    /** Dynamic dimension options. */
    val dynamicDimensionOptions: List<DimensionOption> = emptyList(),
    // Auto-backup settings
    /** Auto backup enabled. */
    val autoBackupEnabled: Boolean = false,
    /** Auto backup interval. */
    val autoBackupInterval: BackupInterval = BackupInterval.SIXTY_MIN,
    /** Auto backup last run. */
    val autoBackupLastRun: String? = null,
    /** Auto backup last error message. */
    val autoBackupLastErrorMessage: String? = null,
    /** Auto backup last error at. */
    val autoBackupLastErrorAt: String? = null,
    /** Backup rotation enabled. */
    val backupRotationEnabled: Boolean = false,
    /** Backup rotation count. */
    val backupRotationCount: Int = 50,
    // Day boundary for recurrence (0-5, hour when day "ends" for recurring tasks)
    /** Day boundary hour. */
    val dayBoundaryHour: Int = 0,
    // Debug logging
    /** Debug logging enabled. */
    val debugLoggingEnabled: Boolean = BuildConfig.DEBUG,
    // Database init completed flag
    /** Database init completed. */
    val databaseInitCompleted: Boolean = false,
    // Auto-tracking habit completion time
    /** Auto track habit time global. */
    val autoTrackHabitTimeGlobal: Boolean = false,
    /** Auto track dimension preferences. */
    val autoTrackDimensionPreferences: Map<String, Boolean> = emptyMap(),
    // Focus Mode
    /** Active preset. */
    val activePreset: FocusModePreset = FocusModePreset.FULL_SUITE,
    /** Tab visibility. */
    val tabVisibility: Map<String, Boolean> = emptyMap(),
    /** Focus mode onboarding completed. */
    val focusModeOnboardingCompleted: Boolean = false,
    /** Current task filter. */
    val currentTaskFilter: TaskFilter = TaskFilter.TODAY,
    // Default launch destination
    /** Launch destination. */
    val launchDestination: LaunchDestination = LaunchDestination(),
    // Insights charts visibility settings
    /** Chart time module enabled. */
    val chartTimeModuleEnabled: Boolean = true,
    /** Chart time overall snapshot enabled. */
    val chartTimeOverallSnapshotEnabled: Boolean = false,
    /** Chart time execution details enabled. */
    val chartTimeExecutionDetailsEnabled: Boolean = false,
    /** Chart time score cards enabled. */
    val chartTimeScoreCardsEnabled: Boolean = false,
    /** Chart time overall score card enabled. */
    val chartTimeOverallScoreCardEnabled: Boolean = false,
    /** Chart time dimension score cards enabled. */
    val chartTimeDimensionScoreCardsEnabled: Boolean = false,
    /** Chart time line graphs enabled. */
    val chartTimeLineGraphsEnabled: Boolean = false,
    /** Chart time daily score trend enabled. */
    val chartTimeDailyScoreTrendEnabled: Boolean = false,
    /** Chart time progress trend enabled. */
    val chartTimeProgressTrendEnabled: Boolean = false,
    /** Chart time historical ranking enabled. */
    val chartTimeHistoricalRankingEnabled: Boolean = false,
    /** Chart time momentum streak enabled. */
    val chartTimeMomentumStreakEnabled: Boolean = false,
    /** Chart task module enabled. */
    val chartTaskModuleEnabled: Boolean = false,
    /** Chart habit module enabled. */
    val chartHabitModuleEnabled: Boolean = false,
    /** Chart journal module enabled. */
    val chartJournalModuleEnabled: Boolean = false,
    /** Chart note module enabled. */
    val chartNoteModuleEnabled: Boolean = false,
    /** Chart average daily time enabled. */
    val chartAverageDailyTimeEnabled: Boolean = true,
    /** Chart dim split enabled. */
    val chartDimSplitEnabled: Boolean = false,
    /** Chart dim trend enabled. */
    val chartDimTrendEnabled: Boolean = false,
    /** Chart daily timeline enabled. */
    val chartDailyTimelineEnabled: Boolean = false,
    /** Chart weekly pattern enabled. */
    val chartWeeklyPatternEnabled: Boolean = false,
    /** Chart daily rhythm enabled. */
    val chartDailyRhythmEnabled: Boolean = false,
    /** Chart weekly pattern excl empty. */
    val chartWeeklyPatternExclEmpty: Boolean = false,
    /** Chart daily rhythm excl empty. */
    val chartDailyRhythmExclEmpty: Boolean = false,
    /** Is loading. */
    val isLoading: Boolean = true,
)
/** Local app preferences. */
val LocalAppPreferences = compositionLocalOf { AppPreferencesState() }
/**
 * App preferences state.
 */
fun AppPreferencesState.labelFor(dimensionName: String): String = dimensionPreferences.firstOrNull { it.id == dimensionName || it.canonicalId == dimensionName }?.label
    ?: dynamicDimensionOptions.firstOrNull { it.label == dimensionName }?.label
    ?: dimensionName
/**
 * App preferences state.
 */
fun AppPreferencesState.labelForDimensionId(dimensionId: String?): String? = findDimensionOption(dimensionId)?.label
/**
 * App preferences state.
 */
fun AppPreferencesState.labelForDimension(dimensionId: String?, dimensionName: String?): String? {
    /** Direct label. */
    val directLabel = labelForDimensionId(dimensionId)
    /** If. */
    if (!directLabel.isNullOrBlank()) {
        return directLabel
    }
    /** Fallback name. */
    val fallbackName = dimensionName?.trim().orEmpty()
    /** If. */
    if (fallbackName.isBlank()) {
        return null
    }
    return labelFor(fallbackName)
}
/**
 * App preferences state.
 */
fun AppPreferencesState.matchesDimensionOption(
    /** Option. */
    option: DimensionOption,
    dimensionId: String?,
    dimensionName: String?,
): Boolean {
    /** If. */
    if (!dimensionId.isNullOrBlank() && option.id == dimensionId) {
        return true
    }
    /** Normalized name. */
    val normalizedName = dimensionName?.trim().orEmpty()
    /** If. */
    if (normalizedName.isBlank()) {
        return false
    }
    /** If. */
    if (normalizedName == option.label) {
        return true
    }
    return labelForDimension(dimensionId, normalizedName) == option.label
}
/**
 * App preferences state.
 */
fun AppPreferencesState.colorFor(dimensionName: String): Color = dimensionPreferences.firstOrNull { it.id == dimensionName || it.canonicalId == dimensionName }?.color
    ?: dynamicDimensionOptions.firstOrNull { it.label == dimensionName }?.color
    ?: LifeDimensionColors.forDimension(dimensionName)
/**
 * App preferences state.
 */
fun AppPreferencesState.colorForDimensionId(dimensionId: String?): Color? = findDimensionOption(dimensionId)?.color
/**
 * App preferences state.
 */
fun AppPreferencesState.iconKeyForDimensionId(dimensionId: String?): String? = findDimensionOption(dimensionId)?.iconKey
/**
 * App preferences state.
 */
fun AppPreferencesState.iconOptionForDimensionId(dimensionId: String?): DimensionIconOption? = findDimensionOption(dimensionId)?.let { DimensionIconCatalog.resolve(it.iconKey, it.id) }
/**
 * App preferences state.
 */
fun AppPreferencesState.autoTrackEnabledForDimensionId(dimensionId: String?): Boolean {
    /** Requested id. */
    val requestedId = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    /** Requested canonical id. */
    val requestedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id
    return autoTrackDimensionPreferences.entries.firstOrNull { (storedId, _) ->
        /** Stored canonical id. */
        val storedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(storedId)?.id
        storedId == requestedId ||
            (!requestedCanonicalId.isNullOrBlank() && storedCanonicalId == requestedCanonicalId)
    }?.value ?: false
}
/**
 * App preferences state.
 */
fun AppPreferencesState.colorForDimension(dimensionId: String?, dimensionName: String?): Color? = colorForDimensionId(dimensionId)
    ?: dimensionName?.trim()?.takeIf { it.isNotEmpty() }?.let(::colorFor)
/**
 * App preferences state.
 */
fun AppPreferencesState.isVisibleDimensionId(dimensionId: String?): Boolean {
    /** Requested id. */
    val requestedId = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    /** Requested canonical id. */
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
 * App preferences state.
 */
fun AppPreferencesState.isVisible(dimensionName: String): Boolean = dimensionPreferences.firstOrNull { it.id == dimensionName || it.canonicalId == dimensionName }?.isVisible ?: true
/**
 * App preferences state.
 */
fun AppPreferencesState.effectiveLaunchTaskFilter(): TaskFilter = launchDestination.taskFilter ?: currentTaskFilter
/**
 * App preferences state.
 */
fun AppPreferencesState.visibleDimensions(): List<DimensionPreference> = dimensionPreferences.filter { it.isVisible }
/**
 * App preferences state.
 */
fun AppPreferencesState.optionsForSelection(selected: LifeDimension?): List<DimensionPreference> {
    /** If. */
    if (selected == null) {
        return visibleDimensions()
    }
    return dimensionPreferences.filter { it.isVisible || it.canonicalId == selected.id || it.id == selected.id }
}
/**
 * App preferences state.
 */
fun AppPreferencesState.visibleDimensionOptions(): List<DimensionOption> {
    /** Defaults. */
    val defaults = dimensionPreferences.map {
        /** Dimension option. */
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
    /** Return. */
    return (defaults + dynamicDimensionOptions)
        .distinctBy { it.id }
        .filter { it.isVisible }
}
/**
 * App preferences state.
 */
fun AppPreferencesState.optionsForSelection(selectedDimensionId: String?): List<DimensionOption> {
    /** Defaults. */
    val defaults = dimensionPreferences.map {
        /** Dimension option. */
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
    /** All. */
    val all = (defaults + dynamicDimensionOptions).distinctBy { it.id }
    /** If. */
    if (selectedDimensionId.isNullOrBlank()) {
        return all.filter { it.isVisible }
    }
    /** Selected canonical id. */
    val selectedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(selectedDimensionId)?.id
    return all.filter { option ->
        option.isVisible ||
            option.id == selectedDimensionId ||
            (!selectedCanonicalId.isNullOrBlank() && option.canonicalId == selectedCanonicalId)
    }
}

private fun AppPreferencesState.findVisibleDimensionOption(dimensionId: String?): DimensionOption? {
    /** Requested id. */
    val requestedId = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    /** Requested canonical id. */
    val requestedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id
    return visibleDimensionOptions().firstOrNull { option ->
        option.id == requestedId ||
            (!requestedCanonicalId.isNullOrBlank() && option.canonicalId == requestedCanonicalId)
    }
}

private fun AppPreferencesState.findDimensionOption(dimensionId: String?): DimensionOption? {
    /** Requested id. */
    val requestedId = dimensionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    /** Requested canonical id. */
    val requestedCanonicalId = DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id
    /** Options. */
    val options = dimensionPreferences.map {
        /** Dimension option. */
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
    /** Resolved. */
    val resolved = options
        .distinctBy { it.id }
        .firstOrNull { option ->
            option.id == requestedId ||
                (!requestedCanonicalId.isNullOrBlank() && option.canonicalId == requestedCanonicalId)
        }
    /** If. */
    if (resolved == null) {
        /** Trace key. */
        val traceKey = requestedCanonicalId ?: requestedId
        /** Synchronized. */
        synchronized(unresolvedDimensionResolutionKeys) {
            /** If. */
            if (unresolvedDimensionResolutionKeys.add(traceKey)) {
                appPreferencesLogger?.w(
                    "AppPreferencesState.findDimensionOption",
                    "Could not resolve dimension option from app preferences state",
                    /** Map of. */
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
 * AppPreferencesViewModel.
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
    /** Ui state. */
    val uiState: StateFlow<AppPreferencesState> = _uiState.asStateFlow()
    private val _manualBackupResultMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Manual backup result message. */
    val manualBackupResultMessage: SharedFlow<String> = _manualBackupResultMessage.asSharedFlow()
    private val _manualBackupInProgress = MutableStateFlow(false)
    /** Manual backup in progress. */
    val manualBackupInProgress: StateFlow<Boolean> = _manualBackupInProgress.asStateFlow()
    private val _habitScoreDiagnosticsMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Habit score diagnostics message. */
    val habitScoreDiagnosticsMessage: SharedFlow<String> = _habitScoreDiagnosticsMessage.asSharedFlow()
    private val _habitScoreDiagnosticsInProgress = MutableStateFlow(false)
    /** Habit score diagnostics in progress. */
    val habitScoreDiagnosticsInProgress: StateFlow<Boolean> = _habitScoreDiagnosticsInProgress.asStateFlow()
    init {
        UnifiedLogger.setDebugLoggingEnabled(BuildConfig.DEBUG)
        /** Observe settings. */
        observeSettings()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeSettings() {
        viewModelScope.launch {
            sessionManager.isOpen
                .filter { it }
                .flatMapLatest {
                    /** Combine. */
                    combine(
                        appSettingsRepository.getAllSettings(),
                        lifeDimensionCatalogRepository.observeAllDimensions(),
                        /** Runtime system language tag. */
                        runtimeSystemLanguageTag,
                        backupStatusStore.status,
                    ) { settings, dimensions, systemLanguageTag, backupStatus ->
                        /** Backup settings bundle. */
                        BackupSettingsBundle(
                            settings = settings,
                            dimensions = dimensions,
                            systemLanguageTag = systemLanguageTag,
                            backupStatus = backupStatus,
                        )
                    }
                }
                .collect { bundle ->
                    /** Apply settings bundle. */
                    applySettingsBundle(bundle)
                }
        }
    }

    private fun applySettingsBundle(bundle: BackupSettingsBundle) {
        /** Settings. */
        val settings = bundle.settings
        /** Configured dimensions. */
        val configuredDimensions = bundle.dimensions
        /** System language tag. */
        val systemLanguageTag = bundle.systemLanguageTag
        /** Backup status. */
        val backupStatus = bundle.backupStatus
        /** Theme mode. */
        val themeMode = ThemeModeOption.fromKey(settings[KEY_THEME_MODE]) ?: ThemeModeOption.SYSTEM
        /** App language. */
        val appLanguage = AppLanguageOption.fromKey(settings[KEY_APP_LANGUAGE]) ?: AppLanguageOption.SYSTEM
        /** Effective language tag. */
        val effectiveLanguageTag = resolveEffectiveLanguageTag(appLanguage, systemLanguageTag)
        /** Font family. */
        val fontFamily = FontFamilyOption.fromKey(settings[KEY_FONT_FAMILY]) ?: FontFamilyOption.SANS_SERIF
        /** Time format. */
        val timeFormat = TimeFormatOption.fromKey(settings[KEY_TIME_FORMAT]) ?: TimeFormatOption.TWENTY_FOUR
        /** Time hour height dp. */
        val timeHourHeightDp = resolveTimeHourHeightDp(settings)
        /** Val. */
        val (dimensionPrefs, dynamicDimensionOptions) = buildDimensionCatalogUiState(
            dimensions = configuredDimensions,
            effectiveLanguageTag = effectiveLanguageTag,
        )
        /** Dimension settings log signature. */
        val dimensionSettingsLogSignature = buildString {
            /** Append. */
            append("appLanguage=")
            /** Append. */
            append(appLanguage.key)
            /** Append. */
            append("|effectiveLanguageTag=")
            /** Append. */
            append(effectiveLanguageTag)
            /** Append. */
            append("|systemLanguageTag=")
            /** Append. */
            append(systemLanguageTag)
            /** Append. */
            append("|catalogIds=")
            /** Append. */
            append(configuredDimensions.joinToString(",") { it.id })
            /** Append. */
            append("|defaultIds=")
            /** Append. */
            append(dimensionPrefs.joinToString(",") { it.id })
            /** Append. */
            append("|customIds=")
            /** Append. */
            append(dynamicDimensionOptions.joinToString(",") { it.id })
        }
        /** If. */
        if (lastLoggedDimensionSettingsSignature != dimensionSettingsLogSignature) {
            logger.i(
                "AppPreferencesViewModel.observeSettings",
                "Dimension settings snapshot loaded",
                /** Map of. */
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
        /** Auto backup enabled. */
        val autoBackupEnabled = settings[KEY_AUTO_BACKUP_ENABLED]?.toBoolean() ?: false
        /** Auto backup interval. */
        val autoBackupInterval = BackupInterval.fromKey(settings[KEY_AUTO_BACKUP_INTERVAL]) ?: BackupInterval.SIXTY_MIN
        /** Auto backup last run. */
        val autoBackupLastRun = backupStatus.lastSuccessDisplay ?: settings[KEY_AUTO_BACKUP_LAST_RUN]
        /** Backup failure status. */
        val backupFailureStatus = backupStatus.latestFailure
        /** Backup rotation enabled. */
        val backupRotationEnabled = settings[KEY_BACKUP_ROTATION_ENABLED]?.toBoolean() ?: false
        /** Backup rotation count. */
        val backupRotationCount = settings[KEY_BACKUP_ROTATION_COUNT]?.toIntOrNull()?.coerceIn(1, 999) ?: 50
        /** Day boundary hour. */
        val dayBoundaryHour = settings[KEY_DAY_BOUNDARY_HOUR]?.toIntOrNull()?.coerceIn(0, 5) ?: 0
        /** Debug logging enabled. */
        val debugLoggingEnabled = settings[KEY_DEBUG_LOGGING_ENABLED]?.toBoolean() ?: BuildConfig.DEBUG
        /** Database init completed. */
        val databaseInitCompleted = settings[KEY_DATABASE_INIT_COMPLETED]?.toBoolean() ?: false
        // Auto-tracking habit completion time preferences
        /** Auto track habit time global. */
        val autoTrackHabitTimeGlobal = settings[KEY_AUTO_TRACK_HABIT_TIME]?.toBoolean() ?: false
        /** Auto track dimension ids. */
        val autoTrackDimensionIds = (dimensionPrefs.map { it.id } + dynamicDimensionOptions.map { it.id }).distinct()
        /** Auto track dimension prefs. */
        val autoTrackDimensionPrefs = autoTrackDimensionIds.associateWith { dimensionId ->
            settings["$KEY_AUTO_TRACK_DIMENSION_PREFIX$dimensionId"]?.toBoolean()
                ?: autoTrackHabitTimeGlobal
        }
        // Focus Mode preferences
        /** Active preset. */
        val activePreset = FocusModePreset.fromPresetId(settings[KEY_ACTIVE_PRESET])
        /** All tabs. */
        val allTabs = listOf("tasks", "habits", "time", "journal", "notes", "lenses", "settings")
        /** Tab visibility. */
        val tabVisibility = allTabs.associateWith { tabRoute ->
            /** If. */
            if (tabRoute == "settings") {
                /** True. */
                true // Settings tab is always visible
            } else {
                settings["$KEY_TAB_VISIBLE_PREFIX$tabRoute"]?.toBoolean()
                    ?: activePreset.visibleTabs.contains(tabRoute)
            }
        }
        /** Focus mode onboarding completed. */
        val focusModeOnboardingCompleted = settings[KEY_FOCUS_MODE_ONBOARDING_COMPLETED]?.toBoolean() ?: false
        /** Current task filter. */
        val currentTaskFilter = TaskFilter.fromKey(settings[KEY_TASK_FILTER_OPTION])
        /** Launch destination route. */
        val launchDestinationRoute = settings[KEY_LAUNCH_DESTINATION_ROUTE] ?: "time"
        /** Launch destination task filter. */
        val launchDestinationTaskFilter = TaskFilter.fromKey(settings[KEY_LAUNCH_DESTINATION_TASK_FILTER])
        /** Launch destination. */
        val launchDestination = LaunchDestination(
            route = launchDestinationRoute,
            taskFilter = launchDestinationTaskFilter,
        )
        // Insights charts visibility prefs
        /** Chart time module enabled. */
        val chartTimeModuleEnabled = settings[KEY_CHART_TIME_MODULE]?.toBoolean() ?: true
        /** Chart time overall snapshot enabled. */
        val chartTimeOverallSnapshotEnabled = settings[KEY_CHART_TIME_OVERALL_SNAPSHOT]?.toBoolean() ?: false
        /** Chart time execution details enabled. */
        val chartTimeExecutionDetailsEnabled = settings[KEY_CHART_TIME_EXECUTION_DETAILS]?.toBoolean() ?: false
        /** Chart time score cards enabled. */
        val chartTimeScoreCardsEnabled = settings[KEY_CHART_TIME_SCORE_CARDS]?.toBoolean() ?: false
        /** Chart time overall score card enabled. */
        val chartTimeOverallScoreCardEnabled = settings[KEY_CHART_TIME_OVERALL_SCORE_CARD]?.toBoolean() ?: false
        /** Chart time dimension score cards enabled. */
        val chartTimeDimensionScoreCardsEnabled = settings[KEY_CHART_TIME_DIM_SCORE_CARDS]?.toBoolean() ?: false
        /** Chart time line graphs enabled. */
        val chartTimeLineGraphsEnabled = settings[KEY_CHART_TIME_LINE_GRAPHS]?.toBoolean() ?: false
        /** Chart time daily score trend enabled. */
        val chartTimeDailyScoreTrendEnabled = settings[KEY_CHART_TIME_DAILY_SCORE_TREND]?.toBoolean() ?: false
        /** Chart time progress trend enabled. */
        val chartTimeProgressTrendEnabled = settings[KEY_CHART_TIME_PROGRESS_TREND]?.toBoolean() ?: false
        /** Chart time historical ranking enabled. */
        val chartTimeHistoricalRankingEnabled = settings[KEY_CHART_TIME_HISTORICAL_RANKING]?.toBoolean() ?: false
        /** Chart time momentum streak enabled. */
        val chartTimeMomentumStreakEnabled = settings[KEY_CHART_TIME_MOMENTUM_STREAK]?.toBoolean() ?: false
        /** Chart task module enabled. */
        val chartTaskModuleEnabled = settings[KEY_CHART_TASK_MODULE]?.toBoolean() ?: false
        /** Chart habit module enabled. */
        val chartHabitModuleEnabled = settings[KEY_CHART_HABIT_MODULE]?.toBoolean() ?: false
        /** Chart journal module enabled. */
        val chartJournalModuleEnabled = settings[KEY_CHART_JOURNAL_MODULE]?.toBoolean() ?: false
        /** Chart note module enabled. */
        val chartNoteModuleEnabled = settings[KEY_CHART_NOTE_MODULE]?.toBoolean() ?: false
        /** Chart average daily time enabled. */
        val chartAverageDailyTimeEnabled = settings[KEY_CHART_AVERAGE_DAILY_TIME]?.toBoolean() ?: true
        /** Chart dim split enabled. */
        val chartDimSplitEnabled = settings[KEY_CHART_DIM_SPLIT]?.toBoolean() ?: false
        /** Chart dim trend enabled. */
        val chartDimTrendEnabled = settings[KEY_CHART_DIM_TREND]?.toBoolean() ?: false
        /** Chart daily timeline enabled. */
        val chartDailyTimelineEnabled = settings[KEY_CHART_DAILY_TIMELINE]?.toBoolean() ?: false
        /** Chart weekly pattern enabled. */
        val chartWeeklyPatternEnabled = settings[KEY_CHART_WEEKLY_PATTERN]?.toBoolean() ?: false
        /** Chart daily rhythm enabled. */
        val chartDailyRhythmEnabled = settings[KEY_CHART_DAILY_RHYTHM]?.toBoolean() ?: false
        /** Chart weekly pattern excl empty. */
        val chartWeeklyPatternExclEmpty = settings[KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY]?.toBoolean() ?: false
        /** Chart daily rhythm excl empty. */
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
     * Set theme mode.
     */
    fun setThemeMode(mode: ThemeModeOption) {
        /** Save setting. */
        saveSetting(KEY_THEME_MODE, mode.key)
    }
    /**
     * Set app language.
     */
    fun setAppLanguage(language: AppLanguageOption) {
        /** Save setting. */
        saveSetting(KEY_APP_LANGUAGE, language.key)
    }
    /**
     * Update system language tag.
     */
    fun updateSystemLanguageTag(languageTag: String?) {
        /** Normalized tag. */
        val normalizedTag = normalizeSupportedLanguageTag(languageTag)
        /** If. */
        if (runtimeSystemLanguageTag.value == normalizedTag) {
            /** Return. */
            return
        }
        runtimeSystemLanguageTag.value = normalizedTag
        logger.i(
            "AppPreferencesViewModel.updateSystemLanguageTag",
            "Updated runtime system language tag",
            /** Map of. */
            mapOf("systemLanguageTag" to normalizedTag),
        )
    }
    /**
     * Set font family.
     */
    fun setFontFamily(fontFamily: FontFamilyOption) {
        /** Save setting. */
        saveSetting(KEY_FONT_FAMILY, fontFamily.key)
    }
    /**
     * Set time format.
     */
    fun setTimeFormat(timeFormat: TimeFormatOption) {
        /** Save setting. */
        saveSetting(KEY_TIME_FORMAT, timeFormat.key)
    }
    /**
     * Set time hour height dp.
     */
    fun setTimeHourHeightDp(hourHeightDp: Float) {
        /** Clamped. */
        val clamped = hourHeightDp.coerceIn(MIN_TIME_HOUR_HEIGHT_DP, MAX_TIME_HOUR_HEIGHT_DP)
        /** Normalized. */
        val normalized = String.format(Locale.US, "%.2f", clamped)
        /** Save setting. */
        saveSetting(KEY_TIME_HOUR_HEIGHT_DP, normalized)
    }
    /**
     * Set dimension label.
     */
    fun setDimensionLabel(dimension: LifeDimension, label: String) {
        /** Set dimension label. */
        setDimensionLabel(dimension.id, label)
    }
    /**
     * Set dimension label.
     */
    fun setDimensionLabel(dimensionId: String, label: String) {
        viewModelScope.launch {
            /** Normalized label. */
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
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionLabel",
                    "Failed to update dimension label in DB-backed catalog",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId),
                )
            }
        }
    }
    /**
     * Set dimension weight.
     */
    fun setDimensionWeight(dimension: LifeDimension, weight: Double) {
        /** Set dimension weight. */
        setDimensionWeight(dimension.id, weight)
    }

    /**
     * C2: user-editable dimension weight. Weighted L3 aggregation kicks in on
     * the NEXT day-score recalc; this edit triggers an immediate L3-only
     * recalc (self-gov `dim_weight_change` path — L1/L2 untouched).
     */
    fun setDimensionWeight(dimensionId: String, weight: Double) {
        /** Clamped. */
        val clamped = weight.coerceIn(0.1, 10.0)
        viewModelScope.launch {
            try {
                lifeDimensionCatalogRepository.updateDimensionWeight(dimensionId, clamped)
                logger.i(
                    "AppPreferencesViewModel.setDimensionWeight",
                    "Dimension weight updated",
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId, "weight" to clamped),
                )
                // L3-only recalc: day scores re-aggregate with new weights.
                scoreRollupCascadeService.recalcDayOnly(LocalDate.now())
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionWeight",
                    "Failed to update dimension weight / recalc day layer",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId, "weight" to clamped),
                )
            }
        }
    }

    /**
     * Set dimension color.
     */
    fun setDimensionColor(dimension: LifeDimension, color: Color) {
        /** Set dimension color. */
        setDimensionColor(dimension.id, color)
    }
    /**
     * Set dimension color.
     */
    fun setDimensionColor(dimensionId: String, color: Color) {
        viewModelScope.launch {
            try {
                lifeDimensionCatalogRepository.updateDimensionColor(dimensionId, colorToHex(color))
                logger.i(
                    "AppPreferencesViewModel.setDimensionColor",
                    "Dimension color updated in DB-backed catalog",
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionColor",
                    "Failed to update dimension color in DB-backed catalog",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId),
                )
            }
        }
    }
    /**
     * Reset dimension label.
     */
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
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.resetDimensionLabel",
                    "Failed to reset dimension label in DB-backed catalog",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId),
                )
            }
        }
    }
    /**
     * Set dimension icon.
     */
    fun setDimensionIcon(dimensionId: String, iconKey: String) {
        viewModelScope.launch {
            try {
                lifeDimensionCatalogRepository.updateDimensionIcon(dimensionId, iconKey)
                logger.i(
                    "AppPreferencesViewModel.setDimensionIcon",
                    "Dimension icon updated in DB-backed catalog",
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId, "iconKey" to iconKey),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionIcon",
                    "Failed to update dimension icon in DB-backed catalog",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId, "iconKey" to iconKey),
                )
            }
        }
    }
    /**
     * Set dimension visibility.
     */
    fun setDimensionVisibility(dimension: LifeDimension, isVisible: Boolean) {
        /** Set dimension visibility. */
        setDimensionVisibility(dimension.id, isVisible)
    }
    /**
     * Set dimension visibility.
     */
    fun setDimensionVisibility(dimensionId: String, isVisible: Boolean) {
        viewModelScope.launch {
            try {
                lifeDimensionCatalogRepository.updateDimensionActiveState(dimensionId, isVisible)
                logger.i(
                    "AppPreferencesViewModel.setDimensionVisibility",
                    "Dimension visibility updated in DB-backed catalog",
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId, "isVisible" to isVisible),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.setDimensionVisibility",
                    "Failed to update dimension visibility in DB-backed catalog",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf("dimensionId" to dimensionId, "isVisible" to isVisible),
                )
            }
        }
    }
    /**
     * Set auto backup enabled.
     */
    fun setAutoBackupEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_AUTO_BACKUP_ENABLED, enabled.toString())
    }
    /**
     * Set auto backup interval.
     */
    fun setAutoBackupInterval(interval: BackupInterval) {
        /** Save setting. */
        saveSetting(KEY_AUTO_BACKUP_INTERVAL, interval.key)
    }
    /**
     * Set auto backup last run.
     */
    fun setAutoBackupLastRun(timestamp: String) {
        /** Save setting. */
        saveSetting(KEY_AUTO_BACKUP_LAST_RUN, timestamp)
    }
    /**
     * Set backup rotation enabled.
     */
    fun setBackupRotationEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_BACKUP_ROTATION_ENABLED, enabled.toString())
        /** Sync backup rotation to shared prefs. */
        syncBackupRotationToSharedPrefs(enabled, _uiState.value.backupRotationCount)
    }
    /**
     * Set backup rotation count.
     */
    fun setBackupRotationCount(count: Int) {
        /** Clamped. */
        val clamped = count.coerceIn(1, 999)
        /** Save setting. */
        saveSetting(KEY_BACKUP_ROTATION_COUNT, clamped.toString())
        /** Sync backup rotation to shared prefs. */
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
     * Refresh auto backup status from storage.
     */
    fun refreshAutoBackupStatusFromStorage() {
        backupStatusStore.refresh()
        /** Status. */
        val status = backupStatusStore.status.value
        logger.d(
            "AppPreferencesViewModel.refreshAutoBackupStatusFromStorage",
            "Refreshed backup status from backup artifacts",
            /** Map of. */
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
     * Dismiss auto backup failure message.
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
     * Trigger manual backup now.
     */
    fun triggerManualBackupNow() {
        _manualBackupInProgress.value = true
        viewModelScope.launch {
            try {
                /** Result. */
                val result = databaseBackupCoordinator.backupToAppBackupDirectory(BackupTrigger.MANUAL)
                /** Refresh auto backup status from storage. */
                refreshAutoBackupStatusFromStorage()
                _manualBackupResultMessage.tryEmit(
                    context.getString(R.string.settings_manual_backup_success, result.recordedAtDisplay),
                )
                logger.i(
                    "AppPreferencesViewModel.triggerManualBackupNow",
                    "Manual backup succeeded",
                    /** Map of. */
                    mapOf(
                        "recordedAt" to result.recordedAtDisplay,
                        "attemptsUsed" to result.attemptsUsed,
                        "destinationPath" to result.destinationPath,
                    ),
                )
                AutoBackupWorker.rescheduleFromNow(context, appSettingsRepository)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
                /** Refresh auto backup status from storage. */
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
        /** Rows. */
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
            /** While. */
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
        /** Rows. */
        val rows = mutableListOf<Map<String, Any>>()
        readableDb.query(
            """
            SELECT taskId,
                   /** Count. */
                   COUNT(*) AS total,
                   /** Min. */
                   MIN(dueDate) AS firstDue,
                   /** Max. */
                   MAX(dueDate) AS lastDue,
                   /** Sum. */
                   SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) AS completed,
                   /** Sum. */
                   SUM(CASE WHEN status = 'missed' THEN 1 ELSE 0 END) AS missed,
                   /** Sum. */
                   SUM(CASE WHEN status = 'skipped' THEN 1 ELSE 0 END) AS skipped
            FROM task_occurrences
            GROUP BY taskId
            """.trimIndent(),
        ).use { cursor ->
            /** While. */
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
        /** Rows. */
        val rows = mutableListOf<Map<String, Any>>()
        readableDb.query(
            """
            SELECT id, key, label, sortOrder, isActive, color, icon
            FROM life_dimensions
            ORDER BY sortOrder
            """.trimIndent(),
        ).use { cursor ->
            /** While. */
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
     * Run habit score diagnostics.
     */
    fun runHabitScoreDiagnostics() {
        /** If. */
        if (_habitScoreDiagnosticsInProgress.value) {
            /** Return. */
            return
        }
        _habitScoreDiagnosticsInProgress.value = true
        viewModelScope.launch(Dispatchers.IO) {
            /** Log tag. */
            val logTag = "AppPreferencesViewModel.runHabitScoreDiagnostics"
            try {
                /** Readable db. */
                val readableDb = sessionManager.requireDatabase().openHelper.readableDatabase
                logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_START")

                // ── 1. Habit inventory: raw recurrence rule formats ──────────
                /** Habit rows. */
                val habitRows = queryHabitInventory(readableDb)
                logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_HABIT_COUNT", mapOf("count" to habitRows.size))
                habitRows.forEach { row ->
                    // Classify the recurrence rule format for migration planning
                    /** Rule. */
                    val rule = row["recurrenceRule"]?.toString() ?: ""
                    /** Format. */
                    val format = when {
                        rule.contains("CONFIG:") -> "config"
                        rule.contains("RRULE:") || rule.contains("FREQ=") -> "rrule"
                        rule.matches(Regex("""\d+/\d+(!start=\d{4}-\d{2}-\d{2})?""")) -> "num_den"
                        rule.isBlank() -> "blank"
                        else -> "other"
                    }
                    logger.i(
                        /** Log tag. */
                        logTag,
                        "HABIT_SCORE_DIAGNOSTICS_HABIT",
                        row + mapOf("ruleFormat" to format),
                    )
                }

                // ── 2. Frequency x/y inventory (num_den habits) ─────────────
                /** Num den habits. */
                val numDenHabits = habitRows.filter {
                    it["recurrenceRule"]?.toString()?.matches(Regex("""\d+/\d+""")) == true
                }
                /** Num den rules. */
                val numDenRules: List<String> = numDenHabits
                    .mapNotNull { it["recurrenceRule"]?.toString() }
                    .distinct()
                    .sorted()
                logger.i(
                    /** Log tag. */
                    logTag,
                    "HABIT_SCORE_DIAGNOSTICS_NUM_DEN_INVENTORY",
                    /** Map of. */
                    mapOf(
                        "numDenCount" to numDenHabits.size,
                        "habitTotal" to habitRows.size,
                        "rules" to numDenRules,
                    ),
                )

                // ── 3. Occurrence stats per habit ───────────────────────────
                /** Occ rows. */
                val occRows = queryOccurrenceStats(readableDb)
                logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_OCCURRENCE_COUNT", mapOf("habitsWithOccurrences" to occRows.size))
                occRows.forEach { row ->
                    logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_OCCURRENCE", row)
                }

                // ── 4. Dimension weights (life_dimensions actuals) ──────────
                /** Dim rows. */
                val dimRows = queryDimensionWeights(readableDb)
                dimRows.forEach { row ->
                    logger.i(logTag, "HABIT_SCORE_DIAGNOSTICS_DIMENSION", row)
                }

                // ── 5. Habit → dimension distribution ──────────────────────
                /** Dim dist. */
                val dimDist = habitRows.groupBy { it["dimensionId"]?.toString() ?: "null" }
                    .mapValues { (_, v) -> v.size }
                logger.i(
                    /** Log tag. */
                    logTag,
                    "HABIT_SCORE_DIAGNOSTICS_DIM_DISTRIBUTION",
                    /** Map of. */
                    mapOf("distribution" to dimDist),
                )

                // ── 6. Score roll-up metric tables (actual DB read-back) ───
                // Reads the v18 metric tables directly so the backfill result
                // can be verified from the DB itself, not just write-path logs.
                /** Metric tables. */
                val metricTables = listOf("habit_metrics", "dimension_metrics", "day_metrics")
                /** For. */
                for (table in metricTables) {
                    readableDb.query(
                        """
                        SELECT COUNT(*), MIN(dayKey), MAX(dayKey)
                        FROM $table
                        """.trimIndent(),
                    ).use { cursor ->
                        /** If. */
                        if (cursor.moveToNext()) {
                            logger.i(
                                /** Log tag. */
                                logTag,
                                "HABIT_SCORE_DIAGNOSTICS_METRIC_TABLE",
                                /** Map of. */
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
                    /** If. */
                    if (cursor.moveToNext()) {
                        logger.i(
                            /** Log tag. */
                            logTag,
                            "HABIT_SCORE_DIAGNOSTICS_METRIC_COVERAGE",
                            /** Map of. */
                            mapOf("distinctHabitsInL1" to cursor.getLong(0)),
                        )
                    }
                }
                readableDb.query("SELECT COUNT(DISTINCT dimensionId) FROM dimension_metrics").use { cursor ->
                    /** If. */
                    if (cursor.moveToNext()) {
                        logger.i(
                            /** Log tag. */
                            logTag,
                            "HABIT_SCORE_DIAGNOSTICS_METRIC_COVERAGE",
                            /** Map of. */
                            mapOf("distinctDimensionsInL2" to cursor.getLong(0)),
                        )
                    }
                }

                logger.i(
                    /** Log tag. */
                    logTag,
                    "HABIT_SCORE_DIAGNOSTICS_END",
                    /** Map of. */
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    /** Log tag. */
                    logTag,
                    "HABIT_SCORE_DIAGNOSTICS_FAILED",
                    /** E. */
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
     * Set day boundary hour.
     */
    fun setDayBoundaryHour(hour: Int) {
        /** Clamped hour. */
        val clampedHour = hour.coerceIn(0, 5)
        /** Save setting. */
        saveSetting(KEY_DAY_BOUNDARY_HOUR, clampedHour.toString())
    }
    /**
     * Set debug logging enabled.
     */
    fun setDebugLoggingEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_DEBUG_LOGGING_ENABLED, enabled.toString())
        UnifiedLogger.setDebugLoggingEnabled(enabled)
    }
    /**
     * Set auto track habit time global.
     */
    fun setAutoTrackHabitTimeGlobal(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_AUTO_TRACK_HABIT_TIME, enabled.toString())
        logger.i(
            "AppPreferencesViewModel.setAutoTrackHabitTimeGlobal",
            "Auto-tracking global setting updated",
            /** Map of. */
            mapOf(
                "enabled" to enabled,
            ),
        )
    }
    /**
     * Set auto track dimension preference.
     */
    fun setAutoTrackDimensionPreference(dimension: LifeDimension, enabled: Boolean) {
        /** Set auto track dimension preference. */
        setAutoTrackDimensionPreference(dimension.id, enabled)
    }
    /**
     * Set auto track dimension preference.
     */
    fun setAutoTrackDimensionPreference(dimensionId: String, enabled: Boolean) {
        /** Save setting. */
        saveSetting("$KEY_AUTO_TRACK_DIMENSION_PREFIX$dimensionId", enabled.toString())
        logger.i(
            "AppPreferencesViewModel.setAutoTrackDimensionPreference",
            "Auto-tracking dimension setting updated",
            /** Map of. */
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
        /** Save setting. */
        saveSetting(KEY_ACTIVE_PRESET, preset.presetId)
        // Update individual tab visibility based on preset
        // Settings tab is always visible
        /** All tabs. */
        val allTabs = listOf("tasks", "habits", "time", "journal", "notes", "lenses", "settings")
        allTabs.forEach { tabRoute ->
            /** Is visible. */
            val isVisible = tabRoute == "settings" || preset.visibleTabs.contains(tabRoute)
            /** Save setting. */
            saveSetting("$KEY_TAB_VISIBLE_PREFIX$tabRoute", isVisible.toString())
        }
        logger.i(
            "AppPreferencesViewModel.setActivePreset",
            "Focus mode preset changed",
            /** Map of. */
            mapOf(
                "preset" to preset.presetId,
                "visibleTabs" to preset.visibleTabs.joinToString(", "),
            ),
        )
    }

    /**
     * Toggle visibility of individual tab. Settings tab cannot be hidden.
     */
    fun setTabVisibility(tabRoute: String, visible: Boolean) {
        /** If. */
        if (tabRoute == "settings") {
            logger.w("AppPreferencesViewModel.setTabVisibility", "Attempted to hide Settings tab, ignoring")
            /** Return. */
            return
        }
        /** Save setting. */
        saveSetting("$KEY_TAB_VISIBLE_PREFIX$tabRoute", visible.toString())
        logger.i(
            "AppPreferencesViewModel.setTabVisibility",
            "Tab visibility changed",
            /** Map of. */
            mapOf(
                "tabRoute" to tabRoute,
                "visible" to visible,
            ),
        )
    }

    /**
     * Mark focus mode onboarding as completed. This is a one-time flag.
     */
    fun markFocusModeOnboardingCompleted() {
        /** Save setting. */
        saveSetting(KEY_FOCUS_MODE_ONBOARDING_COMPLETED, "true")
        logger.i("AppPreferencesViewModel.markFocusModeOnboardingCompleted", "Focus mode onboarding marked as completed")
    }
    /**
     * Set launch destination time.
     */
    fun setLaunchDestinationTime() {
        /** Save setting. */
        saveSetting(KEY_LAUNCH_DESTINATION_ROUTE, "time")
        /** Clear setting. */
        clearSetting(KEY_LAUNCH_DESTINATION_TASK_FILTER)
        logger.i(
            "AppPreferencesViewModel.setLaunchDestinationTime",
            "Default launch destination saved",
            /** Map of. */
            mapOf("route" to "time"),
        )
    }
    /**
     * Set launch destination tasks.
     */
    fun setLaunchDestinationTasks(taskFilter: TaskFilter?) {
        /** Save setting. */
        saveSetting(KEY_LAUNCH_DESTINATION_ROUTE, "tasks")
        /** Save setting nullable. */
        saveSettingNullable(KEY_LAUNCH_DESTINATION_TASK_FILTER, taskFilter?.key)
        /** If. */
        if (taskFilter != null) {
            /** Save setting. */
            saveSetting(KEY_TASK_FILTER_OPTION, taskFilter.key)
        }
        logger.i(
            "AppPreferencesViewModel.setLaunchDestinationTasks",
            "Default launch destination updated",
            /** Map of. */
            mapOf("route" to "tasks", "taskFilter" to (taskFilter?.key ?: "none")),
        )
    }
    /**
     * Set launch destination.
     */
    fun setLaunchDestination(route: String) {
        /** Save setting. */
        saveSetting(KEY_LAUNCH_DESTINATION_ROUTE, route)
        /** Clear setting. */
        clearSetting(KEY_LAUNCH_DESTINATION_TASK_FILTER)
        logger.i(
            "AppPreferencesViewModel.setLaunchDestination",
            "Default launch destination saved",
            /** Map of. */
            mapOf("route" to route),
        )
    }

    /**
     * Set chart time module enabled.
     */
    fun setChartTimeModuleEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeModuleEnabled", "Time insights module toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time overall snapshot enabled.
     */
    fun setChartTimeOverallSnapshotEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_OVERALL_SNAPSHOT, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeOverallSnapshotEnabled", "Time overall snapshot card toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time execution details enabled.
     */
    fun setChartTimeExecutionDetailsEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_EXECUTION_DETAILS, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeExecutionDetailsEnabled", "Time execution details toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time score cards enabled.
     */
    fun setChartTimeScoreCardsEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_SCORE_CARDS, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeScoreCardsEnabled", "Time score cards section toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time overall score card enabled.
     */
    fun setChartTimeOverallScoreCardEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_OVERALL_SCORE_CARD, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeOverallScoreCardEnabled", "Time overall score card toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time dimension score cards enabled.
     */
    fun setChartTimeDimensionScoreCardsEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_DIM_SCORE_CARDS, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeDimensionScoreCardsEnabled", "Time dimension score cards toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time line graphs enabled.
     */
    fun setChartTimeLineGraphsEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_LINE_GRAPHS, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeLineGraphsEnabled", "Time line graphs section toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time daily score trend enabled.
     */
    fun setChartTimeDailyScoreTrendEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_DAILY_SCORE_TREND, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeDailyScoreTrendEnabled", "Time daily score trend toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time progress trend enabled.
     */
    fun setChartTimeProgressTrendEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_PROGRESS_TREND, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeProgressTrendEnabled", "Time progress trend toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time historical ranking enabled.
     */
    fun setChartTimeHistoricalRankingEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_HISTORICAL_RANKING, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeHistoricalRankingEnabled", "Time historical ranking toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart time momentum streak enabled.
     */
    fun setChartTimeMomentumStreakEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TIME_MOMENTUM_STREAK, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTimeMomentumStreakEnabled", "Time momentum streak toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart task module enabled.
     */
    fun setChartTaskModuleEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_TASK_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartTaskModuleEnabled", "Task insights module toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart habit module enabled.
     */
    fun setChartHabitModuleEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_HABIT_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartHabitModuleEnabled", "Habit insights module toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart journal module enabled.
     */
    fun setChartJournalModuleEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_JOURNAL_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartJournalModuleEnabled", "Journal insights module toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart note module enabled.
     */
    fun setChartNoteModuleEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_NOTE_MODULE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartNoteModuleEnabled", "Note insights module toggled", mapOf("enabled" to enabled))
    }

    /**
     * Set chart average daily time enabled.
     */
    fun setChartAverageDailyTimeEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_AVERAGE_DAILY_TIME, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartAverageDailyTimeEnabled", "Average daily time chart toggled", mapOf("enabled" to enabled))
    }

    /**
     * Check if focus mode onboarding has been completed.
     */
    suspend fun hasFocusModeOnboardingCompleted(): Boolean = appSettingsRepository.getSetting(KEY_FOCUS_MODE_ONBOARDING_COMPLETED) == "true"
    private fun saveSetting(key: String, value: String) {
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting(key, value)
                logger.d(
                    "AppPreferencesViewModel.saveSetting",
                    "Setting updated",
                    /** Map of. */
                    mapOf(
                        "key" to key,
                        "value" to value,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.saveSetting",
                    "Failed to update setting",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf(
                        "key" to key,
                    ),
                )
            }
        }
    }
    private fun clearSetting(key: String) {
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting(key, null)
                logger.d(
                    "AppPreferencesViewModel.saveSetting",
                    "Setting cleared",
                    /** Map of. */
                    mapOf(
                        "key" to key,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "AppPreferencesViewModel.saveSetting",
                    "Failed to clear setting",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf(
                        "key" to key,
                    ),
                )
            }
        }
    }
    private fun saveSettingNullable(key: String, value: String?) {
        /** If. */
        if (value == null) {
            /** Clear setting. */
            clearSetting(key)
        } else {
            /** Save setting. */
            saveSetting(key, value)
        }
    }
    private fun parseColor(hex: String): Color {
        /** Normalized. */
        val normalized = hex.removePrefix("#")
        return try {
            /** Color long. */
            val colorLong = normalized.toLong(16)
            /** If. */
            if (normalized.length <= 6) {
                /** Color. */
                Color((0xFF000000 or colorLong).toInt())
            } else {
                /** Color. */
                Color(colorLong.toInt())
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            LifeDimensionColors.forDimension("Career & Work")
        }
    }
    private fun colorToHex(color: Color): String = "#%08X".format(color.toArgb())

    private fun buildDimensionCatalogUiState(
        dimensions: List<ConfiguredLifeDimension>,
        /** Effective language tag. */
        effectiveLanguageTag: String,
    ): Pair<List<DimensionPreference>, List<DimensionOption>> {
        /** Preferred rows. */
        val preferredRows = dimensions
            .filterNot { it.id == DimensionTaxonomyCatalog.UNASSIGNED.id }
            .groupBy { DimensionTaxonomyCatalog.fromCanonicalId(it.id)?.id ?: it.id }
            .values
            .map(::selectPreferredCatalogDimensionRow)
            .sortedBy { DimensionTaxonomyCatalog.fromCanonicalId(it.id)?.sortOrder ?: it.sortOrder }

        /** Built in preferences. */
        val builtInPreferences = mutableListOf<DimensionPreference>()

        preferredRows.forEach { dimension ->
            /** Option. */
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
            /** Canonical id. */
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
        /** Dimension. */
        dimension: ConfiguredLifeDimension,
        /** Effective language tag. */
        effectiveLanguageTag: String,
    ): DimensionOption {
        /** Canonical id. */
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimension.id)?.id
        /** Canonical definition. */
        val canonicalDefinition = DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)
        /** Resolved color hex. */
        val resolvedColorHex = dimension.colorHex.ifBlank {
            canonicalDefinition?.defaultColorHex ?: colorToHex(LifeDimensionColors.forDimension("Career & Work"))
        }
        /** Resolved icon key. */
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
                /** Has custom db backed label. */
                hasCustomDbBackedLabel(
                    canonicalId = canonicalId,
                    storedLabel = dimension.label,
                ),
        )
    }

    private fun resolveDbBackedDimensionLabel(
        /** Dimension. */
        dimension: ConfiguredLifeDimension,
        canonicalId: String?,
        /** Effective language tag. */
        effectiveLanguageTag: String,
    ): String {
        /** Trimmed label. */
        val trimmedLabel = dimension.label.trim()
        /** If. */
        if (canonicalId != null && !hasCustomDbBackedLabel(canonicalId, trimmedLabel)) {
            return localizedCatalogLabel(
                canonicalId = canonicalId,
                languageTag = effectiveLanguageTag,
            ) ?: DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)?.fallbackLabel
                ?: trimmedLabel.ifBlank { dimension.id }
        }
        return trimmedLabel.ifBlank {
            /** Localized catalog label. */
            localizedCatalogLabel(
                canonicalId = canonicalId,
                languageTag = effectiveLanguageTag,
            ) ?: dimension.id
        }
    }

    private fun resolveDbBackedDimensionDescription(
        /** Dimension. */
        dimension: ConfiguredLifeDimension,
        canonicalId: String?,
        /** Effective language tag. */
        effectiveLanguageTag: String,
    ): String? {
        /** If. */
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
        /** Trimmed label. */
        val trimmedLabel = storedLabel?.trim().orEmpty()
        /** If. */
        if (trimmedLabel.isBlank()) {
            return false
        }
        return trimmedLabel !in knownAppOwnedCatalogLabels(canonicalId)
    }

    private fun knownAppOwnedCatalogLabels(canonicalId: String): Set<String> {
        /** Canonical definition. */
        val canonicalDefinition = DimensionTaxonomyCatalog.fromCanonicalId(canonicalId)
        return buildSet {
            canonicalDefinition?.fallbackLabel?.let(::add)
            /** Supported dimension locale tags. */
            SUPPORTED_DIMENSION_LOCALE_TAGS
                .mapNotNull { localeTag -> localizedCatalogLabel(canonicalId, localeTag) }
                .forEach(::add)
        }
    }

    private fun localizedCatalogLabel(canonicalId: String?, languageTag: String?): String? {
        /** Res id. */
        val resId = DimensionTextCatalog.labelResIdForCanonicalId(canonicalId) ?: return null
        return localizedStringForLanguageTag(resId, languageTag)
    }

    private fun localizedCatalogDescription(canonicalId: String?, languageTag: String?): String? {
        /** Res id. */
        val resId = DimensionTextCatalog.descriptionResIdForCanonicalId(canonicalId) ?: return null
        return localizedStringForLanguageTag(resId, languageTag)
    }

    private fun localizedStringForLanguageTag(resId: Int, languageTag: String?): String {
        /** If. */
        if (languageTag.isNullOrBlank()) {
            return context.getString(resId)
        }
        /** Config. */
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(languageTag))
        return context.createConfigurationContext(config).getString(resId)
    }

    private fun normalizeDimensionLabelForStorage(
        /** Dimension id. */
        dimensionId: String,
        /** Candidate label. */
        candidateLabel: String,
        /** Effective language tag. */
        effectiveLanguageTag: String,
    ): String {
        /** Trimmed label. */
        val trimmedLabel = candidateLabel.trim()
        /** If. */
        if (trimmedLabel.isBlank()) {
            return defaultStoredLabelForDimension(dimensionId)
        }
        /** Canonical id. */
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.id ?: return trimmedLabel
        /** Localized label. */
        val localizedLabel = localizedCatalogLabel(canonicalId, effectiveLanguageTag)
        return if (
            trimmedLabel == localizedLabel ||
            trimmedLabel in knownAppOwnedCatalogLabels(canonicalId)
        ) {
            /** Default stored label for dimension. */
            defaultStoredLabelForDimension(dimensionId)
        } else {
            /** Trimmed label. */
            trimmedLabel
        }
    }

    private fun defaultStoredLabelForDimension(dimensionId: String): String = DimensionTaxonomyCatalog.fromCanonicalId(dimensionId)?.fallbackLabel ?: dimensionId

    private fun resolveTimeHourHeightDp(settings: Map<String, String?>): Float {
        /** Persisted. */
        val persisted = settings[KEY_TIME_HOUR_HEIGHT_DP]
            ?.toFloatOrNull()
            ?.coerceIn(MIN_TIME_HOUR_HEIGHT_DP, MAX_TIME_HOUR_HEIGHT_DP)
        /** If. */
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
     * Set chart dim split enabled.
     */
    fun setChartDimSplitEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_DIM_SPLIT, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartDimSplitEnabled", "Chart dim split toggled", mapOf("enabled" to enabled))
    }
    /**
     * Set chart dim trend enabled.
     */
    fun setChartDimTrendEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_DIM_TREND, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartDimTrendEnabled", "Chart dim trend toggled", mapOf("enabled" to enabled))
    }
    /**
     * Set chart daily timeline enabled.
     */
    fun setChartDailyTimelineEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_DAILY_TIMELINE, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartDailyTimelineEnabled", "Chart daily timeline toggled", mapOf("enabled" to enabled))
    }
    /**
     * Set chart weekly pattern enabled.
     */
    fun setChartWeeklyPatternEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_WEEKLY_PATTERN, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartWeeklyPatternEnabled", "Chart weekly pattern toggled", mapOf("enabled" to enabled))
    }
    /**
     * Set chart daily rhythm enabled.
     */
    fun setChartDailyRhythmEnabled(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_DAILY_RHYTHM, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartDailyRhythmEnabled", "Chart daily rhythm toggled", mapOf("enabled" to enabled))
    }
    /**
     * Set chart weekly pattern excl empty.
     */
    fun setChartWeeklyPatternExclEmpty(enabled: Boolean) {
        /** Save setting. */
        saveSetting(KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY, enabled.toString())
        logger.i("AppPreferencesViewModel.setChartWeeklyPatternExclEmpty", "Chart weekly pattern excl-empty toggled", mapOf("enabled" to enabled))
    }
    /**
     * Set chart daily rhythm excl empty.
     */
    fun setChartDailyRhythmExclEmpty(enabled: Boolean) {
        /** Save setting. */
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
        /** Key app language. */
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
        /** Key task sort option. */
        const val KEY_TASK_SORT_OPTION = "task_sort_option"
        /** Key task filter option. */
        const val KEY_TASK_FILTER_OPTION = "task_filter_option"
        /** Key habit sort option. */
        const val KEY_HABIT_SORT_OPTION = "habit_sort_option"
        /** Key show archived habits. */
        const val KEY_SHOW_ARCHIVED_HABITS = "show_archived_habits"
        /** Key show completed habits. */
        const val KEY_SHOW_COMPLETED_HABITS = "show_completed_habits"
        /** Key hide all marked today. */
        const val KEY_HIDE_ALL_MARKED_TODAY = "hide_all_marked_today"
        /** Key due today only. */
        const val KEY_DUE_TODAY_ONLY = "due_today_only"
        /** Key day boundary hour. */
        const val KEY_DAY_BOUNDARY_HOUR = "day_boundary_hour"
        /** Key auto track habit time. */
        const val KEY_AUTO_TRACK_HABIT_TIME = "auto_track_habit_time_global"
        /** Key auto track dimension prefix. */
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
    /** Option. */
    option: AppLanguageOption,
    systemLanguageTag: String?,
): String = when (option) {
    AppLanguageOption.ENGLISH -> AppLanguageOption.ENGLISH.key
    AppLanguageOption.TAMIL -> AppLanguageOption.TAMIL.key
    AppLanguageOption.SYSTEM -> normalizeSupportedLanguageTag(systemLanguageTag)
}

internal fun normalizeSupportedLanguageTag(languageTag: String?): String {
    /** Normalized. */
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
