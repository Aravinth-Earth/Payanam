//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("UndocumentedPublicProperty")

package io.payanam.feature.settings

import io.payanam.BuildConfig
import io.payanam.database.DatabaseHealthChecker

/**
 * SettingsUiState.
 */
data class SettingsUiState(
    /** Task count. */
    val taskCount: Int = 0,
    /** Time entry count. */
    val timeEntryCount: Int = 0,
    /** Note count. */
    val noteCount: Int = 0,
    /** Database size kb. */
    val databaseSizeKb: Long = 0,
    /** Database artifacts. */
    val databaseArtifacts: List<DatabaseArtifactUiModel> = emptyList(),
    /** Current database schema version. */
    val currentDatabaseSchemaVersion: Int = DatabaseHealthChecker.CURRENT_VERSION,
    /** Minimum supported schema version. */
    val minimumSupportedSchemaVersion: Int = DatabaseHealthChecker.MIN_MIGRATABLE_VERSION,
    /** App version. */
    val appVersion: String = BuildConfig.VERSION_NAME,
    /** Build number. */
    val buildNumber: Int = BuildConfig.VERSION_CODE,
    /** Imported uhabits habit count. */
    val importedUhabitsHabitCount: Int = 0,
    /** Unlock session timeout minutes. */
    val unlockSessionTimeoutMinutes: Int = 10,
    /** Biometric unlock enabled. */
    val biometricUnlockEnabled: Boolean = false,
    /** Is exporting. */
    val isExporting: Boolean = false,
    /** Is importing. */
    val isImporting: Boolean = false,
    /** Is uhabits importing. */
    val isUhabitsImporting: Boolean = false,
    /** Is bulk mapping imported habits. */
    val isBulkMappingImportedHabits: Boolean = false,
    /** Export result. */
    val exportResult: ExportResult? = null,
    /** Import result. */
    val importResult: ImportResult? = null,
    /** Uhabits import result. */
    val uhabitsImportResult: UhabitsImportResult? = null,
    /** Bulk habit mapping result. */
    val bulkHabitMappingResult: BulkHabitMappingResult? = null,
    /** Show delete export prompt. */
    val showDeleteExportPrompt: Boolean = false,
    /** Awaiting import passphrase. */
    val awaitingImportPassphrase: Boolean = false,
    /** Import passphrase error. */
    val importPassphraseError: String? = null,
    /** Is checking for update. */
    val isCheckingForUpdate: Boolean = false,
    /** Update check result. */
    val updateCheckResult: UpdateCheckResult? = null,
    /** Update channel. */
    val updateChannel: UpdateChannel = UpdateChannel.DEV,
    /** Auto download enabled. */
    val autoDownloadEnabled: Boolean = false,
    /** Prompt install enabled. */
    val promptInstallEnabled: Boolean = false,
    /** Wifi only enabled. */
    val wifiOnlyEnabled: Boolean = false,
    /** Auto check enabled. */
    val autoCheckEnabled: Boolean = false,
    /** Download state. */
    val downloadState: DownloadUiState = DownloadUiState.Idle,
    /** Pending install file path — non-null when the install popup should show. */
    val pendingInstallPath: String? = null,
) {
    /** A check result older than 15 minutes is stale — UI should re-check first. */
    fun isUpdateResultStale(): Boolean {
        /** Checked at. */
        val checkedAt = updateCheckResult?.checkedAtMs ?: return true
        return System.currentTimeMillis() - checkedAt > UPDATE_RESULT_STALE_MS
    }

    companion object {
        private const val UPDATE_RESULT_STALE_MS = 15 * 60 * 1000L
    }
}
/**
 * ExportResult.
 */
sealed class ExportResult {
    /**
     * Success.
     */
    data class Success(val fileName: String) : ExportResult()
    /**
     * Error.
     */
    data class Error(val message: String) : ExportResult()
}
/**
 * ImportResult.
 */
sealed class ImportResult {
    /**
     * Success.
     */
    data class Success(
        /** Tasks imported. */
        val tasksImported: Int,
        /** Time entries imported. */
        val timeEntriesImported: Int,
        /** Notes imported. */
        val notesImported: Int,
        /** Requires app restart. */
        val requiresAppRestart: Boolean = false,
    ) : ImportResult()
    /**
     * Error.
     */
    data class Error(val message: String) : ImportResult()
}
/**
 * UhabitsImportResult.
 */
sealed class UhabitsImportResult {
    /**
     * Success.
     */
    data class Success(val habitsUpserted: Int, val repetitionsUpserted: Int) : UhabitsImportResult()
    /**
     * Error.
     */
    data class Error(val message: String) : UhabitsImportResult()
}
/**
 * BulkHabitMappingResult.
 */
sealed class BulkHabitMappingResult {
    /**
     * Success.
     */
    data class Success(val mappedCount: Int, val dimensionId: String) : BulkHabitMappingResult()
    /**
     * Error.
     */
    data class Error(val message: String) : BulkHabitMappingResult()
}
