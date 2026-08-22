//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("UndocumentedPublicProperty")

package io.payanam.feature.settings

import io.payanam.BuildConfig
import io.payanam.database.DatabaseHealthChecker
/**
 * UI state for the Settings screen: database stats/artifacts, import/export
 * and uHabits-import progress + results, encryption unlock settings, self-
 * update channel preferences and download/install state, plus the delete-
 * database prompt flag.
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
 * Outcome of a database export: [Success] with the written file name, or
 * [Error] with a user-facing message.
 */
sealed class ExportResult {
    /**
     * Export finished; [fileName] identifies the written backup file.
     */
    data class Success(val fileName: String) : ExportResult()
    /**
     * Export failed; [message] is user-displayable.
     */
    data class Error(val message: String) : ExportResult()
}
/**
 * Outcome of a full-database JSON import: per-type row counts on [Success]
 * (optionally requiring an app restart), or [Error].
 */
sealed class ImportResult {
    /**
     * Import finished with the number of tasks/time entries/notes imported.
     */
    data class Success(
        val tasksImported: Int,
        val timeEntriesImported: Int,
        val notesImported: Int,
        val requiresAppRestart: Boolean = false,
    ) : ImportResult()
    /**
     * Import failed; [message] is user-displayable.
     */
    data class Error(val message: String) : ImportResult()
}
/**
 * Outcome of a uHabits database import: upsert counts on success, or [Error].
 */
sealed class UhabitsImportResult {
    /**
     * Import finished: habits + repetitions upserted counts.
     */
    data class Success(val habitsUpserted: Int, val repetitionsUpserted: Int) : UhabitsImportResult()
    /**
     * Import failed; [message] is user-displayable.
     */
    data class Error(val message: String) : UhabitsImportResult()
}
/**
 * Outcome of bulk-tagging imported habits into a life dimension: mapped
 * count + target dimension on success, or [Error].
 */
sealed class BulkHabitMappingResult {
    /**
     * Mapping finished: how many habits were tagged, into which dimension.
     */
    data class Success(val mappedCount: Int, val dimensionId: String) : BulkHabitMappingResult()
    /**
     * Mapping failed; [message] is user-displayable.
     */
    data class Error(val message: String) : BulkHabitMappingResult()
}
