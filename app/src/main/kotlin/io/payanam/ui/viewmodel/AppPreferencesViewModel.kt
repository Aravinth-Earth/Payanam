//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

package io.payanam.ui.viewmodel

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.BuildConfig
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.preferences.TimeFormatOption
import io.payanam.core.ui.preferences.colorToStoredHex
import io.payanam.core.ui.preferences.knownAppOwnedCatalogLabels
import io.payanam.core.ui.preferences.labelKey
import io.payanam.core.ui.preferences.localizedCatalogLabel
import io.payanam.core.ui.preferences.mapAppPreferences
import io.payanam.core.ui.preferences.normalizeSupportedLanguageTag
import io.payanam.core.ui.time.MAX_TIME_HOUR_HEIGHT_DP
import io.payanam.core.ui.time.MIN_TIME_HOUR_HEIGHT_DP
import io.payanam.database.backfill.ScoreRollupCascadeService
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.port.BackupStatusSnapshot
import io.payanam.domain.port.BackupTrigger
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.presentation.tasks.TaskFilter
import io.payanam.service.AutoBackupWorker
import io.payanam.service.BackupStatusStore
import io.payanam.service.DatabaseBackupCoordinator
import io.payanam.shared.settings.FocusModePreset
import io.payanam.ui.logging.DebugLoggingPrefs
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

/**
 * Android's half: the platform's own resource table.
 *
 * A `when` rather than a runtime lookup by name, because `getIdentifier` is not compile-checked and is
 * unreliable once resources are shrunk. A platform mapping is legitimate - a per-platform COPY of the state
 * would not be.
 */
@get:StringRes
val ThemeModeOption.labelResId: Int
    get() = settingsLabelResId(labelKey)

@get:StringRes
val FontFamilyOption.labelResId: Int
    get() = settingsLabelResId(labelKey)

@get:StringRes
val TimeFormatOption.labelResId: Int
    get() = settingsLabelResId(labelKey)

@get:StringRes
val BackupInterval.labelResId: Int
    get() = settingsLabelResId(labelKey)

@get:StringRes
val AppLanguageOption.labelResId: Int
    get() = settingsLabelResId(labelKey)

/**
 * Resolves a label key to its string resource.
 *
 * Every key the four enums can produce is listed here, and [SettingsLabelKeyTest] asserts that: a missing
 * entry would otherwise render as an empty label at runtime.
 */
@StringRes
internal fun settingsLabelResId(key: String): Int =
    when (key) {
        "settings_option_theme_system" -> R.string.settings_option_theme_system
        "settings_option_theme_light" -> R.string.settings_option_theme_light
        "settings_option_theme_dark" -> R.string.settings_option_theme_dark
        "settings_option_font_sans_serif" -> R.string.settings_option_font_sans_serif
        "settings_option_font_monospace" -> R.string.settings_option_font_monospace
        "settings_option_font_serif" -> R.string.settings_option_font_serif
        "settings_option_font_cursive" -> R.string.settings_option_font_cursive
        "settings_option_time_format_24h" -> R.string.settings_option_time_format_24h
        "settings_option_time_format_12h" -> R.string.settings_option_time_format_12h
        "settings_option_backup_15_min" -> R.string.settings_option_backup_15_min
        "settings_option_backup_30_min" -> R.string.settings_option_backup_30_min
        "settings_option_backup_1_hour" -> R.string.settings_option_backup_1_hour
        "settings_option_backup_2_hours" -> R.string.settings_option_backup_2_hours
        "settings_option_backup_6_hours" -> R.string.settings_option_backup_6_hours
        "settings_option_backup_12_hours" -> R.string.settings_option_backup_12_hours
        "settings_option_backup_daily" -> R.string.settings_option_backup_daily
        "settings_language_option_system" -> R.string.settings_language_option_system
        "settings_language_option_english" -> R.string.settings_language_option_english
        "settings_language_option_tamil" -> R.string.settings_language_option_tamil
        // Fails loudly rather than silently rendering nothing: an unmapped key is a programming error, and a
        // blank label is the hardest kind of bug to notice.
        else -> error("No string resource mapped for label key: $key")
    }

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
        UnifiedLogger.setDebugLoggingEnabled(DebugLoggingPrefs.resolve(context))
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
        // The rows -> state mapping lives in :core:ui so both platforms share ONE decision about what each
        // key means; this function keeps the Android-only parts: the flow, the logging, the logger gate and
        // the plain-prefs mirror the process-start path reads.
        val mapped = mapAppPreferences(
            settings = settings,
            catalogRows = configuredDimensions,
            systemLanguageTag = systemLanguageTag,
            backupStatus = backupStatus,
            defaultDebugLoggingEnabled = BuildConfig.DEBUG,
        )
        val dimensionPrefs = mapped.dimensionPreferences
        val dynamicDimensionOptions = mapped.dynamicDimensionOptions
        val dimensionSettingsLogSignature = buildString {
            append("appLanguage=")
            append(mapped.appLanguage.key)
            append("|effectiveLanguageTag=")
            append(mapped.effectiveLanguageTag)
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
                    "appLanguage" to mapped.appLanguage.key,
                    "effectiveLanguageTag" to mapped.effectiveLanguageTag,
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
        // Update UnifiedLogger debug logging + re-sync the plain-prefs mirror so the
        // process-start path honors the stored choice (the DB is the source of truth).
        io.payanam.common.logging.UnifiedLogger.setDebugLoggingEnabled(mapped.debugLoggingEnabled)
        DebugLoggingPrefs.write(context, mapped.debugLoggingEnabled)
        _uiState.update { mapped }
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
        saveSetting(AppPreferenceKeys.KEY_APP_LANGUAGE, language.key)
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
                lifeDimensionCatalogRepository.updateDimensionColor(dimensionId, colorToStoredHex(color))
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
        saveSetting(AppPreferenceKeys.KEY_DAY_BOUNDARY_HOUR, clampedHour.toString())
    }
    /**
     * Persists the debug-logging toggle and applies it live.
     */
    fun setDebugLoggingEnabled(enabled: Boolean) {
        saveSetting(KEY_DEBUG_LOGGING_ENABLED, enabled.toString())
        UnifiedLogger.setDebugLoggingEnabled(enabled)
        // Mirror for the process-start path: the DB preference is unreadable before unlock.
        DebugLoggingPrefs.write(context, enabled)
    }
    /**
     * Persists the global auto-track-habit-time toggle.
     */
    fun setAutoTrackHabitTimeGlobal(enabled: Boolean) {
        saveSetting(AppPreferenceKeys.KEY_AUTO_TRACK_HABIT_TIME, enabled.toString())
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
        saveSetting("$AppPreferenceKeys.KEY_AUTO_TRACK_DIMENSION_PREFIX$dimensionId", enabled.toString())
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
            saveSetting(AppPreferenceKeys.KEY_TASK_FILTER_OPTION, taskFilter.key)
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

        private const val KEY_THEME_MODE = AppPreferenceKeys.KEY_THEME_MODE
        private const val KEY_FONT_FAMILY = AppPreferenceKeys.KEY_FONT_FAMILY
        private const val KEY_TIME_FORMAT = AppPreferenceKeys.KEY_TIME_FORMAT
        private const val KEY_TIME_HOUR_HEIGHT_DP = AppPreferenceKeys.KEY_TIME_HOUR_HEIGHT_DP
        private const val KEY_TIME_SCALE_LEGACY_KEY = AppPreferenceKeys.KEY_TIME_SCALE_LEGACY_KEY
        private const val KEY_AUTO_BACKUP_ENABLED = AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED
        private const val KEY_AUTO_BACKUP_INTERVAL = AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL
        private const val KEY_AUTO_BACKUP_LAST_RUN = AppPreferenceKeys.KEY_AUTO_BACKUP_LAST_RUN
        private const val KEY_BACKUP_ROTATION_ENABLED = AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED
        private const val KEY_BACKUP_ROTATION_COUNT = AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT
        private const val KEY_DEBUG_LOGGING_ENABLED = AppPreferenceKeys.KEY_DEBUG_LOGGING_ENABLED




        private const val KEY_DATABASE_INIT_COMPLETED = AppPreferenceKeys.KEY_DATABASE_INIT_COMPLETED
        private const val KEY_ACTIVE_PRESET = AppPreferenceKeys.KEY_ACTIVE_PRESET
        private const val KEY_TAB_VISIBLE_PREFIX = AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX
        private const val KEY_FOCUS_MODE_ONBOARDING_COMPLETED = AppPreferenceKeys.KEY_FOCUS_MODE_ONBOARDING_COMPLETED
        private const val KEY_LAUNCH_DESTINATION_ROUTE = AppPreferenceKeys.KEY_LAUNCH_DESTINATION_ROUTE
        private const val KEY_LAUNCH_DESTINATION_TASK_FILTER = AppPreferenceKeys.KEY_LAUNCH_DESTINATION_TASK_FILTER

        // Insights charts visibility keys
        private const val KEY_CHART_TIME_MODULE = AppPreferenceKeys.KEY_CHART_TIME_MODULE
        private const val KEY_CHART_TIME_OVERALL_SNAPSHOT = AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SNAPSHOT
        private const val KEY_CHART_TIME_EXECUTION_DETAILS = AppPreferenceKeys.KEY_CHART_TIME_EXECUTION_DETAILS
        private const val KEY_CHART_TIME_SCORE_CARDS = AppPreferenceKeys.KEY_CHART_TIME_SCORE_CARDS
        private const val KEY_CHART_TIME_OVERALL_SCORE_CARD = AppPreferenceKeys.KEY_CHART_TIME_OVERALL_SCORE_CARD
        private const val KEY_CHART_TIME_DIM_SCORE_CARDS = AppPreferenceKeys.KEY_CHART_TIME_DIM_SCORE_CARDS
        private const val KEY_CHART_TIME_LINE_GRAPHS = AppPreferenceKeys.KEY_CHART_TIME_LINE_GRAPHS
        private const val KEY_CHART_TIME_DAILY_SCORE_TREND = AppPreferenceKeys.KEY_CHART_TIME_DAILY_SCORE_TREND
        private const val KEY_CHART_TIME_PROGRESS_TREND = AppPreferenceKeys.KEY_CHART_TIME_PROGRESS_TREND
        private const val KEY_CHART_TIME_HISTORICAL_RANKING = AppPreferenceKeys.KEY_CHART_TIME_HISTORICAL_RANKING
        private const val KEY_CHART_TIME_MOMENTUM_STREAK = AppPreferenceKeys.KEY_CHART_TIME_MOMENTUM_STREAK
        private const val KEY_CHART_TASK_MODULE = AppPreferenceKeys.KEY_CHART_TASK_MODULE
        private const val KEY_CHART_HABIT_MODULE = AppPreferenceKeys.KEY_CHART_HABIT_MODULE
        private const val KEY_CHART_JOURNAL_MODULE = AppPreferenceKeys.KEY_CHART_JOURNAL_MODULE
        private const val KEY_CHART_NOTE_MODULE = AppPreferenceKeys.KEY_CHART_NOTE_MODULE
        private const val KEY_CHART_AVERAGE_DAILY_TIME = AppPreferenceKeys.KEY_CHART_AVERAGE_DAILY_TIME
        private const val KEY_CHART_DIM_SPLIT = AppPreferenceKeys.KEY_CHART_DIM_SPLIT
        private const val KEY_CHART_DIM_TREND = AppPreferenceKeys.KEY_CHART_DIM_TREND
        private const val KEY_CHART_DAILY_TIMELINE = AppPreferenceKeys.KEY_CHART_DAILY_TIMELINE
        private const val KEY_CHART_WEEKLY_PATTERN = AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN
        private const val KEY_CHART_DAILY_RHYTHM = AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM
        private const val KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY = AppPreferenceKeys.KEY_CHART_WEEKLY_PATTERN_EXCL_EMPTY
        private const val KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY = AppPreferenceKeys.KEY_CHART_DAILY_RHYTHM_EXCL_EMPTY
    }
}