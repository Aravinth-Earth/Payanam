//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("UndocumentedPublicProperty")

package io.payanam.presentation.settings

import io.payanam.presentation.download.DownloadUiState
import io.payanam.shared.updates.UpdateChannel
import io.payanam.shared.updates.UpdateCheckResult

/**
 * UI state for the Settings screen.
 *
 * Platform facts arrive as values the caller supplies, not as defaults: the schema versions come from
 * the row the platform actually measured, the version from the platform's own build, and the update
 * channel from the stored preference. A default here would render a settings pane that reports
 * something the app never checked.
 */
data class SettingsUiState(
    val currentDatabaseSchemaVersion: Int,
    val minimumSupportedSchemaVersion: Int,
    val appVersion: String,
    val buildNumber: Int,
    val updateChannel: UpdateChannel,
    val taskCount: Int = 0,
    val timeEntryCount: Int = 0,
    val noteCount: Int = 0,
    val databaseSizeKb: Long = 0,
    val databaseArtifacts: List<DatabaseArtifactUiModel> = emptyList(),
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
    val autoDownloadEnabled: Boolean = false,
    val promptInstallEnabled: Boolean = false,
    val wifiOnlyEnabled: Boolean = false,
    val autoCheckEnabled: Boolean = false,
    val downloadState: DownloadUiState = DownloadUiState.Idle,
    /** Pending install file path — non-null when the install popup should show. */
    val pendingInstallPath: String? = null,
    /** Whether the app was installed from F-Droid (disables in-app update UI). */
    val isFDroidBuild: Boolean = false,
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
