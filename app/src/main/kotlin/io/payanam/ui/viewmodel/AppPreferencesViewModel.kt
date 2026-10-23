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
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.port.BackupStatusSnapshot
import io.payanam.domain.port.BackupTrigger
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.presentation.preferences.AppPreferencesWriter
import io.payanam.presentation.preferences.DimensionLabelNormalizer
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
import javax.inject.Inject

/**
 * Android's remaining half: the backup-interval label as a resource id.
 *
 * The other four mappings left with their sections - their copy now comes from the shared `UiStrings`
 * through `UiStrings.backupIntervalLabel` and friends, so this table no longer needs an entry for them.
 * This one remains only because the auto-backup section is still an Android file; it goes when that
 * section is promoted, and `SettingsLabelKeyTest` proves in the meantime that this key is still covered.
 */
@get:StringRes
val BackupInterval.labelResId: Int
    get() = when (labelKey) {
        "settings_option_backup_15_min" -> R.string.settings_option_backup_15_min
        "settings_option_backup_30_min" -> R.string.settings_option_backup_30_min
        "settings_option_backup_1_hour" -> R.string.settings_option_backup_1_hour
        "settings_option_backup_2_hours" -> R.string.settings_option_backup_2_hours
        "settings_option_backup_6_hours" -> R.string.settings_option_backup_6_hours
        "settings_option_backup_12_hours" -> R.string.settings_option_backup_12_hours
        "settings_option_backup_daily" -> R.string.settings_option_backup_daily
        else -> error("No string resource mapped for label key: $labelKey")
    }

private data class BackupSettingsBundle(
    val settings: Map<String, String?>,
    val dimensions: List<ConfiguredLifeDimension>,
    val systemLanguageTag: String?,
    val backupStatus: BackupStatusSnapshot,
)

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

    /**
     * The shared write path, reached instead of re-deriving keys and clamps here.
     *
     * The rows, keys and clamp ranges are the same on both platforms, so they are written once, in
     * `:core:presentation`, and this class keeps only what is genuinely Android: the flow, the plain-prefs
     * mirror, and the platform-only capabilities below. The `ScoreRollupTrigger` it needs is
     * [ScoreRollupCascadeService] itself, which already implements the port, so moving the weight write
     * added no dependency.
     */
    private val writer = AppPreferencesWriter(
        appSettings = appSettingsRepository,
        lifeDimensions = lifeDimensionCatalogRepository,
        scoreRollupTrigger = scoreRollupCascadeService,
        labelNormalizer =
            DimensionLabelNormalizer { canonicalId, candidateLabel, effectiveLanguageTag ->
                localizedCatalogLabel(canonicalId, effectiveLanguageTag) == candidateLabel ||
                    candidateLabel in knownAppOwnedCatalogLabels(canonicalId)
            },
    )
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
        launchWrite { writer.setThemeMode(mode.key) }
    }
    /**
     * Persists the chosen app language.
     */
    fun setAppLanguage(language: AppLanguageOption) {
        launchWrite { writer.setAppLanguage(language.key) }
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
        launchWrite { writer.setFontFamily(fontFamily.key) }
    }
    /**
     * Persists the chosen clock format.
     */
    fun setTimeFormat(timeFormat: TimeFormatOption) {
        launchWrite { writer.setTimeFormat(timeFormat.key) }
    }
    /**
     * Persists the timeline hour-height (clamped to the supported DP range).
     */
    fun setTimeHourHeightDp(hourHeightDp: Float) {
        val clamped = hourHeightDp.coerceIn(MIN_TIME_HOUR_HEIGHT_DP, MAX_TIME_HOUR_HEIGHT_DP)
        launchWrite { writer.setTimeHourHeightDp(clamped) }
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
    fun setDimensionLabel(dimensionId: String, label: String) {
        launchWrite {
            writer.setDimensionLabel(
                dimensionId = dimensionId,
                candidateLabel = label,
                effectiveLanguageTag = _uiState.value.effectiveLanguageTag,
            )
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
    fun setDimensionWeight(dimensionId: String, weight: Double) {
        launchWrite { writer.setDimensionWeight(dimensionId, weight) }
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
    fun setDimensionColor(dimensionId: String, color: Color) {
        launchWrite { writer.setDimensionColor(dimensionId, colorToStoredHex(color)) }
    }
    /**
     * Clears the user-overridden label for a dimension, reverting to the canonical label.
     */
    fun resetDimensionLabel(dimensionId: String) {
        launchWrite { writer.resetDimensionLabel(dimensionId) }
    }
    /**
     * Persists the chosen icon for a dimension and refreshes UI.
     */
    fun setDimensionIcon(dimensionId: String, iconKey: String) {
        launchWrite { writer.setDimensionIcon(dimensionId, iconKey) }
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
    fun setDimensionVisibility(dimensionId: String, isVisible: Boolean) {
        launchWrite { writer.setDimensionVisibility(dimensionId, isVisible) }
    }
    /**
     * Persists the auto-backup on/off toggle.
     */
    fun setAutoBackupEnabled(enabled: Boolean) {
        launchWrite { writer.setAutoBackupEnabled(enabled) }
    }
    /**
     * Persists the auto-backup cadence.
     */
    fun setAutoBackupInterval(interval: BackupInterval) {
        launchWrite { writer.setAutoBackupInterval(interval.key) }
    }
    /**
     * Records the last successful auto-backup timestamp.
     */
    fun setAutoBackupLastRun(timestamp: String) {
        launchWrite { writer.setAutoBackupLastRun(timestamp) }
    }
    /**
     * Persists backup rotation on/off and syncs to shared prefs.
     */
    fun setBackupRotationEnabled(enabled: Boolean) {
        launchWrite { writer.setBackupRotationEnabled(enabled) }
        syncBackupRotationToSharedPrefs(enabled, _uiState.value.backupRotationCount)
    }
    /**
     * Persists the retained-backup count (1-999) and syncs to shared prefs.
     */
    fun setBackupRotationCount(count: Int) {
        launchWrite { writer.setBackupRotationCount(count) }
        syncBackupRotationToSharedPrefs(
            _uiState.value.backupRotationEnabled,
            count.coerceIn(AppPreferencesWriter.MIN_BACKUP_ROTATION_COUNT, AppPreferencesWriter.MAX_BACKUP_ROTATION_COUNT),
        )
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
    /**
     * Runs one shared write on this ViewModel's scope.
     *
     * The writes are `suspend` because the shared writer is a plain class with no lifecycle, and the
     * screen's handlers stay non-suspending by launching here - so a settings screen can bind a click
     * straight to a writer method on either platform.
     */
    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /**
     * Runs a manual backup on this ViewModel's scope and publishes the outcome on the manual-backup
     * result flow, so a tap on the backup button always ends in a state the screen can render.
     *
     * The catch below it is deliberately broad: the backup + restore pipeline this drives fails in
     * several unrelated ways, and every one of them has to become a message rather than a crash out of a
     * button tap.
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
        launchWrite { writer.setDayBoundaryHour(hour) }
    }
    /**
     * Persists the debug-logging toggle and applies it live.
     */
    fun setDebugLoggingEnabled(enabled: Boolean) {
        launchWrite { writer.setDebugLoggingEnabled(enabled) }
        UnifiedLogger.setDebugLoggingEnabled(enabled)
        // Mirror for the process-start path: the DB preference is unreadable before unlock.
        DebugLoggingPrefs.write(context, enabled)
    }
    /**
     * Persists the global auto-track-habit-time toggle.
     */
    fun setAutoTrackHabitTimeGlobal(enabled: Boolean) {
        launchWrite { writer.setAutoTrackHabitTimeGlobal(enabled) }
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
        launchWrite { writer.setAutoTrackDimensionPreference(dimensionId, enabled) }
    }

    /**
     * Set the active focus mode preset and update tab visibility accordingly.
     * Settings tab is always kept visible regardless of preset.
     */
    fun setActivePreset(preset: FocusModePreset) {
        launchWrite { writer.setActivePreset(preset) }
    }

    /**
     * Toggle visibility of individual tab. settings tab cannot be hidden.
     */
    fun setTabVisibility(tabRoute: String, visible: Boolean) {
        launchWrite { writer.setTabVisibility(tabRoute, visible) }
    }

    /**
     * Mark focus mode onboarding as completed. this is a one-time flag.
     */
    fun markFocusModeOnboardingCompleted() {
        launchWrite { writer.markFocusModeOnboardingCompleted() }
    }
    /**
     * Makes the Time screen the default launch destination.
     */
    fun setLaunchDestinationTime() {
        launchWrite { writer.setLaunchDestinationTime() }
    }
    /**
     * Makes the Tasks screen the default launch destination, with an optional [taskFilter].
     */
    fun setLaunchDestinationTasks(taskFilter: TaskFilter?) {
        launchWrite { writer.setLaunchDestinationTasks(taskFilter?.key) }
    }
    /**
     * Persists the default launch-destination route.
     */
    fun setLaunchDestination(route: String) {
        launchWrite { writer.setLaunchDestination(route) }
    }
    /**
     * Toggles the Time insights module on the dashboard.
     */
    fun setChartTimeModuleEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeModuleEnabled(enabled) }
    }
    /**
     * Toggles the Time overall-snapshot card.
     */
    fun setChartTimeOverallSnapshotEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeOverallSnapshotEnabled(enabled) }
    }
    /**
     * Toggles the Time execution-details card.
     */
    fun setChartTimeExecutionDetailsEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeExecutionDetailsEnabled(enabled) }
    }
    /**
     * Toggles the Time score-cards section.
     */
    fun setChartTimeScoreCardsEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeScoreCardsEnabled(enabled) }
    }
    /**
     * Toggles the Time overall score card.
     */
    fun setChartTimeOverallScoreCardEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeOverallScoreCardEnabled(enabled) }
    }
    /**
     * Toggles the per-dimension Time score cards.
     */
    fun setChartTimeDimensionScoreCardsEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeDimensionScoreCardsEnabled(enabled) }
    }
    /**
     * Toggles the Time line-graphs section.
     */
    fun setChartTimeLineGraphsEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeLineGraphsEnabled(enabled) }
    }
    /**
     * Toggles the daily score-trend chart.
     */
    fun setChartTimeDailyScoreTrendEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeDailyScoreTrendEnabled(enabled) }
    }
    /**
     * Toggles the progress-trend chart.
     */
    fun setChartTimeProgressTrendEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeProgressTrendEnabled(enabled) }
    }
    /**
     * Toggles the historical-ranking chart.
     */
    fun setChartTimeHistoricalRankingEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeHistoricalRankingEnabled(enabled) }
    }
    /**
     * Toggles the momentum-streak chart.
     */
    fun setChartTimeMomentumStreakEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTimeMomentumStreakEnabled(enabled) }
    }
    /**
     * Toggles the Task insights module.
     */
    fun setChartTaskModuleEnabled(enabled: Boolean) {
        launchWrite { writer.setChartTaskModuleEnabled(enabled) }
    }
    /**
     * Toggles the Habit insights module.
     */
    fun setChartHabitModuleEnabled(enabled: Boolean) {
        launchWrite { writer.setChartHabitModuleEnabled(enabled) }
    }
    /**
     * Toggles the Journal insights module.
     */
    fun setChartJournalModuleEnabled(enabled: Boolean) {
        launchWrite { writer.setChartJournalModuleEnabled(enabled) }
    }
    /**
     * Toggles the Note insights module.
     */
    fun setChartNoteModuleEnabled(enabled: Boolean) {
        launchWrite { writer.setChartNoteModuleEnabled(enabled) }
    }
    /**
     * Toggles the average-daily-time chart.
     */
    fun setChartAverageDailyTimeEnabled(enabled: Boolean) {
        launchWrite { writer.setChartAverageDailyTimeEnabled(enabled) }
    }
    /**
     * Toggles the dimension-split chart.
     */
    fun setChartDimSplitEnabled(enabled: Boolean) {
        launchWrite { writer.setChartDimSplitEnabled(enabled) }
    }
    /**
     * Toggles the dimension-trend chart.
     */
    fun setChartDimTrendEnabled(enabled: Boolean) {
        launchWrite { writer.setChartDimTrendEnabled(enabled) }
    }
    /**
     * Toggles the daily-timeline chart.
     */
    fun setChartDailyTimelineEnabled(enabled: Boolean) {
        launchWrite { writer.setChartDailyTimelineEnabled(enabled) }
    }
    /**
     * Toggles the weekly-pattern chart.
     */
    fun setChartWeeklyPatternEnabled(enabled: Boolean) {
        launchWrite { writer.setChartWeeklyPatternEnabled(enabled) }
    }
    /**
     * Toggles the daily-rhythm chart.
     */
    fun setChartDailyRhythmEnabled(enabled: Boolean) {
        launchWrite { writer.setChartDailyRhythmEnabled(enabled) }
    }
    /**
     * Toggles excluding empty days from the weekly-pattern chart.
     */
    fun setChartWeeklyPatternExclEmpty(enabled: Boolean) {
        launchWrite { writer.setChartWeeklyPatternExclEmpty(enabled) }
    }
    /**
     * Toggles excluding empty days from the daily-rhythm chart.
     */
    fun setChartDailyRhythmExclEmpty(enabled: Boolean) {
        launchWrite { writer.setChartDailyRhythmExclEmpty(enabled) }
    }

    /**
     * Toggles the chart-visibility row named by [key].
     *
     * The one keyed entry point the shared insights section binds to: it holds a row's key rather than
     * naming a setter per row, so this is the seam that lets a shared section write a per-chart
     * preference without an Android type in the shared file.
     */
    fun setChartVisibility(key: String, enabled: Boolean) {
        launchWrite { writer.setChartVisibility(key, enabled) }
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
