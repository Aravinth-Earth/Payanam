//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("UndocumentedPublicProperty")

package io.payanam.feature.settings

import io.payanam.BuildConfig
import io.payanam.database.DatabaseHealthChecker
/**
 * Holds the settings ui state.
 */
data class SettingsUiState(
    val taskCount: Int = 0,
    val timeEntryCount: Int = 0,
    val noteCount: Int = 0,
    val databaseSizeKb: Long = 0,
    val databaseArtifacts: List<DatabaseArtifactUiModel> = emptyList(),
    val currentDatabaseSchemaVersion: Int = DatabaseHealthChecker.CURRENT_VERSION,
    val minimumSupportedSchemaVersion: Int = DatabaseHealthChecker.MIN_MIGRATABLE_VERSION,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val buildNumber: Int = BuildConfig.VERSION_CODE,
    val importedUhabitsHabitCount: Int = 0,
    val unlockSessionTimeoutMinutes: Int = 10,
    val biometricUnlockEnabled: Boolean = false,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val isUhabitsImporting: Boolean = false,
    val isBulkMappingImportedHabits: Boolean = false,
    val exportResult: ExportResult? = null,
    val importResult: ImportResult? = null,
    val uhabitsImportResult: UhabitsImportResult? = null,
    val bulkHabitMappingResult: BulkHabitMappingResult? = null,
    val showDeleteExportPrompt: Boolean = false,
    val awaitingImportPassphrase: Boolean = false,
    val importPassphraseError: String? = null,
    val isCheckingForUpdate: Boolean = false,
    val updateCheckResult: UpdateCheckResult? = null,
    val updateChannel: UpdateChannel = UpdateChannel.DEV,
    val autoDownloadEnabled: Boolean = false,
    val promptInstallEnabled: Boolean = false,
    val wifiOnlyEnabled: Boolean = false,
    val autoCheckEnabled: Boolean = false,
    val downloadState: DownloadUiState = DownloadUiState.Idle,
    /** Pending install file path — non-null when the install popup should show. */
    val pendingInstallPath: String? = null,
) {
    /** A check result older than 15 minutes is stale — UI should re-check first. */
    fun isUpdateResultStale(): Boolean {
        val checkedAt = updateCheckResult?.checkedAtMs ?: return true
        return System.currentTimeMillis() - checkedAt > UPDATE_RESULT_STALE_MS
    }

    companion object {
        private const val UPDATE_RESULT_STALE_MS = 15 * 60 * 1000L
    }
}
/**
 * Provides the export result.
 */
sealed class ExportResult {
    /**
     * Holds the success.
     */
    data class Success(val fileName: String) : ExportResult()
    /**
     * Holds the error.
     */
    data class Error(val message: String) : ExportResult()
}
/**
 * Provides the import result.
 */
sealed class ImportResult {
    /**
     * Holds the success.
     */
    data class Success(
        val tasksImported: Int,
        val timeEntriesImported: Int,
        val notesImported: Int,
        val requiresAppRestart: Boolean = false,
    ) : ImportResult()
    /**
     * Holds the error.
     */
    data class Error(val message: String) : ImportResult()
}
/**
 * Provides the uhabits import result.
 */
sealed class UhabitsImportResult {
    /**
     * Holds the success.
     */
    data class Success(val habitsUpserted: Int, val repetitionsUpserted: Int) : UhabitsImportResult()
    /**
     * Holds the error.
     */
    data class Error(val message: String) : UhabitsImportResult()
}
/**
 * Provides the bulk habit mapping result.
 */
sealed class BulkHabitMappingResult {
    /**
     * Holds the success.
     */
    data class Success(val mappedCount: Int, val dimensionId: String) : BulkHabitMappingResult()
    /**
     * Holds the error.
     */
    data class Error(val message: String) : BulkHabitMappingResult()
}
