//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import io.payanam.BuildConfig
import io.payanam.database.DatabaseHealthChecker

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
)
sealed class ExportResult {
    data class Success(val fileName: String) : ExportResult()
    data class Error(val message: String) : ExportResult()
}
sealed class ImportResult {
    data class Success(
        val tasksImported: Int,
        val timeEntriesImported: Int,
        val notesImported: Int,
        val requiresAppRestart: Boolean = false,
    ) : ImportResult()
    data class Error(val message: String) : ImportResult()
}
sealed class UhabitsImportResult {
    data class Success(val habitsUpserted: Int, val repetitionsUpserted: Int) : UhabitsImportResult()
    data class Error(val message: String) : UhabitsImportResult()
}
sealed class BulkHabitMappingResult {
    data class Success(val mappedCount: Int, val dimensionId: String) : BulkHabitMappingResult()
    data class Error(val message: String) : BulkHabitMappingResult()
}
