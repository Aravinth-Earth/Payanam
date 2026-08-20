//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricManager
import io.payanam.BuildConfig
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.service.DatabaseBackupCoordinator
import io.payanam.ui.viewmodel.BackupInterval
import io.payanam.ui.viewmodel.UhabitsImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
/**
 * SettingsViewModel.
 */
class SettingsViewModel @Inject constructor(
    @ApplicationContext internal val context: Context,
    internal val sessionManager: DatabaseSessionManager,
    internal val databaseEncryptionManager: DatabaseEncryptionManager,
    private val databaseBackupCoordinator: DatabaseBackupCoordinator,
    private val taskRepository: TaskRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val noteRepository: NoteRepository,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {
    internal val logger = UnifiedLogger.getInstance()
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val _uiState = MutableStateFlow(SettingsUiState())
    /** Ui state. */
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _navigateToDatabaseInit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Navigate to database init. */
    val navigateToDatabaseInit: SharedFlow<Unit> = _navigateToDatabaseInit.asSharedFlow()

    // Held across the passphrase-prompt gate for encrypted DB imports from Settings
    // Managed by extension functions in SettingsEncryptedImportSupport.kt
    internal var pendingEncryptedImportDbFile: File? = null
    internal var pendingEncryptedImportBackupMappings: List<Pair<File, File>> = emptyList()

    // Auto-download state
    private var activeDownloadId: Long? = null

    internal fun updateUiState(transform: (SettingsUiState) -> SettingsUiState) {
        /** From. */
        val from = _uiState.value
        /** To. */
        val to = transform(from)
        _uiState.update { to }
        // Trace update-flow state transitions (only when download/channel/check fields change).
        /** If. */
        if (from.downloadState != to.downloadState ||
            from.isCheckingForUpdate != to.isCheckingForUpdate ||
            from.updateCheckResult != to.updateCheckResult ||
            from.updateChannel != to.updateChannel
        ) {
            logger.d(
                "SettingsViewModel.updateState",
                "Update-flow state transition",
                /** Map of. */
                mapOf(
                    "downloadState" to (from.downloadState::class.simpleName + " -> " + to.downloadState::class.simpleName),
                    "checking" to (from.isCheckingForUpdate.toString() + " -> " + to.isCheckingForUpdate.toString()),
                    "updateAvailable" to ((from.updateCheckResult?.isUpdateAvailable ?: false).toString() + " -> " + (to.updateCheckResult?.isUpdateAvailable ?: false).toString()),
                    "channel" to (from.updateChannel.name + " -> " + to.updateChannel.name),
                    "latestBuild" to (to.updateCheckResult?.latestBuildNumber ?: -1),
                ),
            )
        }
    }

    init {
        logger.i("SettingsViewModel.init", "ViewModel initialized")
        /** Load database stats. */
        loadDatabaseStats()
        /** Sync timeout from db. */
        syncTimeoutFromDb()
        /** Load update channel. */
        loadUpdateChannel()
        /** Load prompt install. */
        loadPromptInstall()
        /** Load auto download. */
        loadAutoDownload()
        /** Load wifi only. */
        loadWifiOnly()
        /** Load auto check. */
        loadAutoCheck()
    }

    /** Load the persisted update channel (defaults to DEV). */
    private fun loadUpdateChannel() {
        viewModelScope.launch {
            /** Raw. */
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.UPDATE_CHANNEL)
            /** Channel. */
            val channel = UpdateChannel.fromStorage(raw)
            logger.d("SettingsViewModel.loadUpdateChannel", "Loaded channel", mapOf("channel" to channel.name, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(updateChannel = channel) }
        }
    }

    /** Persist the user's channel selection; resets the check cooldown so a fresh check is allowed. */
    internal fun onUpdateChannelSelected(channel: UpdateChannel) {
        viewModelScope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.UPDATE_CHANNEL, channel.name)
            logger.i("SettingsViewModel.onUpdateChannelSelected", "Channel saved", mapOf("channel" to channel.name))
            lastCheckTimestampMs = 0L
            checkCountInWindow = 0
            _uiState.update { it.copy(updateChannel = channel, updateCheckResult = null) }
        }
    }

    /** Load the persisted auto-download toggle (defaults to OFF). */
    private fun loadAutoDownload() {
        viewModelScope.launch {
            /** Raw. */
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.AUTO_DOWNLOAD)
            /** Enabled. */
            val enabled = raw == "true"
            logger.d("SettingsViewModel.loadAutoDownload", "Loaded toggle", mapOf("enabled" to enabled, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(autoDownloadEnabled = enabled) }
            // If a download was in-flight from a previous session, restore its state.
            /** If. */
            if (enabled) restoreDownloadState()
        }
    }

    /** User tapped Cancel while a download is in flight — cancel + clean persisted state. */
    internal fun onCancelDownload() {
        /** Id. */
        val id = activeDownloadId ?: return
        viewModelScope.launch {
            AutoDownloadManager.cancel(context, id)
            activeDownloadId = null
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, null)
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL, null)
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_FILE, null)
            logger.i("SettingsViewModel.onCancelDownload", "Download cancelled", mapOf("downloadId" to id))
            _uiState.update { it.copy(downloadState = DownloadUiState.Idle) }
        }
    }

    /** Toggle auto-download on/off; toggling off cancels any in-flight download. */
    internal fun onAutoDownloadToggled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.AUTO_DOWNLOAD, enabled.toString())
            logger.i("SettingsViewModel.onAutoDownloadToggled", "Toggle saved", mapOf("enabled" to enabled))
            /** If. */
            if (!enabled && activeDownloadId != null) {
                AutoDownloadManager.cancel(context, activeDownloadId!!)
                activeDownloadId = null
                appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, null)
            }
            _uiState.update { it.copy(autoDownloadEnabled = enabled, downloadState = DownloadUiState.Idle) }
        }
    }

    /** If a download ID was persisted, resume polling its status on app start. */
    private fun restoreDownloadState() {
        viewModelScope.launch {
            /** Stored id. */
            val storedId = appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID)?.toLongOrNull()
            /** If. */
            if (storedId != null) {
                activeDownloadId = storedId
                /** Poll download progress. */
                pollDownloadProgress()
                return@launch
            }
            // In-flight download done (or none): check for a previously COMPLETED
            // download still on disk. If it's fresh (< 15 min) restore the
            // "Downloaded — install now" state so the user never re-downloads
            // what's already there. If stale, surface Retry/Check instead.
            /** Restore completed download. */
            restoreCompletedDownload()
        }
    }

    /** Offer a previously completed download from disk instead of re-downloading. */
    private fun restoreCompletedDownload() {
        viewModelScope.launch {
            /** Build. */
            val build = appSettingsRepository.getSetting(UpdatePrefKeys.LAST_DOWNLOADED_BUILD)
            /** File name. */
            val fileName = appSettingsRepository.getSetting(UpdatePrefKeys.LAST_DOWNLOADED_FILE)
            /** At ms. */
            val atMs = appSettingsRepository.getSetting(UpdatePrefKeys.LAST_DOWNLOADED_AT)?.toLongOrNull()
            /** If. */
            if (build.isNullOrEmpty() || fileName.isNullOrEmpty()) return@launch

            /** Local path. */
            val localPath = AutoDownloadManager.findDownloadedApk(context, fileName)
            /** If. */
            if (localPath == null) {
                // File was cleaned/removed — drop the stale markers.
                logger.d("SettingsViewModel.restoreCompletedDownload", "Completed download file missing; clearing markers", mapOf("fileName" to fileName))
                appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_BUILD, null)
                appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_FILE, null)
                appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_AT, null)
                return@launch
            }

            /** Fresh. */
            val fresh = atMs != null && (System.currentTimeMillis() - atMs) < COMPLETED_DOWNLOAD_FRESH_MS
            /** If. */
            if (fresh) {
                logger.d("SettingsViewModel.restoreCompletedDownload", "Fresh completed download restored", mapOf("fileName" to fileName, "build" to build))
                _uiState.update { it.copy(downloadState = DownloadUiState.Downloaded(fileName, localPath)) }
            } else {
                // Stale (>15 min): drop to Idle so the button shows "Check for update".
                // A fresh check will re-discover the APK if it's still the latest.
                logger.d("SettingsViewModel.restoreCompletedDownload", "Completed download is stale; reverting to check", mapOf("fileName" to fileName, "build" to build))
                appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL, null)
            }
        }
    }

    /** Load the persisted prompt-install toggle (defaults to OFF). */
    private fun loadPromptInstall() {
        viewModelScope.launch {
            /** Raw. */
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.PROMPT_INSTALL)
            /** Enabled. */
            val enabled = raw == "true"
            logger.d("SettingsViewModel.loadPromptInstall", "Loaded toggle", mapOf("enabled" to enabled, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(promptInstallEnabled = enabled) }
        }
    }

    /** Toggle prompt-install on/off (only meaningful when auto-download is ON). */
    internal fun onPromptInstallToggled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.PROMPT_INSTALL, enabled.toString())
            logger.i("SettingsViewModel.onPromptInstallToggled", "Toggle saved", mapOf("enabled" to enabled))
            _uiState.update { it.copy(promptInstallEnabled = enabled) }
        }
    }

    /** Load the persisted WiFi-only toggle (defaults to OFF). */
    private fun loadWifiOnly() {
        viewModelScope.launch {
            /** Raw. */
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.WIFI_ONLY)
            /** Enabled. */
            val enabled = raw == "true"
            logger.d("SettingsViewModel.loadWifiOnly", "Loaded toggle", mapOf("enabled" to enabled, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(wifiOnlyEnabled = enabled) }
        }
    }

    /** Toggle WiFi-only downloads on/off. */
    internal fun onWifiOnlyToggled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.WIFI_ONLY, enabled.toString())
            logger.i("SettingsViewModel.onWifiOnlyToggled", "Toggle saved", mapOf("enabled" to enabled))
            _uiState.update { it.copy(wifiOnlyEnabled = enabled) }
        }
    }

    /** Load the persisted auto-check-on-start setting (defaults OFF — opt-in). */
    private fun loadAutoCheck() {
        viewModelScope.launch {
            /** Raw. */
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.AUTO_CHECK)
            /** Enabled. */
            val enabled = raw == "true"
            logger.d("SettingsViewModel.loadAutoCheck", "Loaded toggle", mapOf("enabled" to enabled, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(autoCheckEnabled = enabled) }
        }
    }

    /** Toggle the post-unlock auto update check on/off. */
    internal fun onAutoCheckToggled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.AUTO_CHECK, enabled.toString())
            logger.i("SettingsViewModel.onAutoCheckToggled", "Toggle saved", mapOf("enabled" to enabled))
            _uiState.update { it.copy(autoCheckEnabled = enabled) }
        }
    }

    /** User tapped "Update now" in the popup → launch the system installer. */
    internal fun onInstallNow() {
        // Button path (Downloaded state) may not have a pending popup — derive
        // the file from the download state when that's the case.
        /** Path. */
        val path = _uiState.value.pendingInstallPath
            ?: (_uiState.value.downloadState as? DownloadUiState.Downloaded)?.localPath
            ?: return
        /** File. */
        val file = File(path)
        /** If. */
        if (!file.exists()) {
            _uiState.update { it.copy(pendingInstallPath = null, downloadState = DownloadUiState.Failed("file_missing")) }
            /** Return. */
            return
        }
        try {
            /** Uri. */
            val uri = androidx.core.content.FileProvider.getUriForFile(
                /** Context. */
                context,
                "${context.packageName}.fileprovider",
                /** File. */
                file,
            )
            /** Intent. */
            val intent = Intent(Intent.ACTION_VIEW).apply {
                /** Set data and type. */
                setDataAndType(uri, "application/vnd.android.package-archive")
                /** Add flags. */
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                /** Add flags. */
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            // Install flow handed off to the system; clear pending state.
            _uiState.update { it.copy(pendingInstallPath = null) }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("SettingsViewModel.onInstallNow", "Install launch failed", e)
            _uiState.update { it.copy(pendingInstallPath = null, downloadState = DownloadUiState.Failed("install_launch_failed")) }
        }
    }

    /** User dismissed the update popup ("Later") — keep the downloaded file. */
    internal fun onInstallLater() {
        _uiState.update { it.copy(pendingInstallPath = null) }
    }
    private fun loadDatabaseStats() {
        logger.d("SettingsViewModel.loadDatabaseStats", "Loading database stats")
        viewModelScope.launch {
            try {
                // Count entities
                /** Tasks. */
                val tasks = taskRepository.getAllTasks().first()
                /** Time entries. */
                val timeEntries = timeEntryRepository.getAllTimeEntries().first()
                /** Notes. */
                val notes = noteRepository.getAllNotes().first()
                /** Imported uhabits habits. */
                val importedUhabitsHabits = withContext(Dispatchers.IO) {
                    sessionManager.requireDatabase().taskDao().countByImportSource(IMPORT_SOURCE_UHABITS)
                }
                logger.i(
                    "SettingsViewModel.loadDatabaseStats",
                    "Entity counts loaded",
                    /** Map of. */
                    mapOf(
                        "tasks" to tasks.size,
                        "timeEntries" to timeEntries.size,
                        "notes" to notes.size,
                    ),
                )
                // Get database file size and artifacts in one pass; using artifact scan
                // so WAL-only state (primary .db absent, -wal present) reports correct non-zero size.
                /** Db file. */
                val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
                /** Val. */
                val (sizeKb, databaseArtifacts) = withContext(Dispatchers.IO) {
                    /** Files. */
                    val files = listDatabaseArtifactFiles(context).filter { it.exists() }
                    /** Size. */
                    val size = files.sumOf { it.length() } / 1024
                    /** Artifacts. */
                    val artifacts = files
                        .sortedByDescending { it.lastModified() }
                        .map { it.toDatabaseArtifactUiModel() }
                    size to artifacts
                }
                logger.i(
                    "SettingsViewModel.loadDatabaseStats",
                    "Database file info",
                    /** Map of. */
                    mapOf(
                        "path" to dbFile.absolutePath,
                        "exists" to dbFile.exists(),
                        "sizeKB" to sizeKb,
                        "artifactCount" to databaseArtifacts.size,
                    ),
                )
                _uiState.update {
                    it.copy(
                        taskCount = tasks.size,
                        timeEntryCount = timeEntries.size,
                        noteCount = notes.size,
                        databaseSizeKb = sizeKb,
                        databaseArtifacts = databaseArtifacts,
                        currentDatabaseSchemaVersion = DatabaseHealthChecker.CURRENT_VERSION,
                        minimumSupportedSchemaVersion = DatabaseHealthChecker.MIN_MIGRATABLE_VERSION,
                        importedUhabitsHabitCount = importedUhabitsHabits,
                        unlockSessionTimeoutMinutes = databaseEncryptionManager.getSessionTimeoutMinutes(),
                        biometricUnlockEnabled = databaseEncryptionManager.isBiometricUnlockEnabled(),
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("SettingsViewModel.loadDatabaseStats", "Failed to load stats", e)
                Timber.e(e, "Error loading database stats")
            }
        }
    }
    /**
     * Export data.
     */
    fun exportData(
        /** Destination uri. */
        destinationUri: Uri,
    ) {
        /** Export database. */
        exportDatabase(destinationUri)
    }
    /**
     * Import data.
     */
    fun importData(
        /** Source uri. */
        sourceUri: Uri,
    ) {
        /** Import database. */
        importDatabase(sourceUri)
    }
    /**
     * Export database.
     */
    fun exportDatabase(
        /** Destination uri. */
        destinationUri: Uri,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportResult = null) }
            try {
                /** Bytes copied. */
                val bytesCopied = databaseBackupCoordinator.exportSnapshotToUri(destinationUri)
                logger.i(
                    "SettingsViewModel.exportDatabase",
                    "Database exported",
                    /** Map of. */
                    mapOf(
                        "mode" to "encrypted_full_db",
                        "bytesCopiedKB" to (bytesCopied / 1024),
                    ),
                )
                /** File name. */
                val fileName = getFileNameFromUri(destinationUri) ?: "payanam_backup.db"
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = ExportResult.Success(fileName),
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("SettingsViewModel.exportDatabase", "Export failed", e)
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = ExportResult.Error(e.message ?: "Export failed"),
                    )
                }
            }
            // Reload stats after export
            /** Load database stats. */
            loadDatabaseStats()
        }
    }
    /**
     * Import uhabits data.
     */
    fun importUhabitsData(sourceUri: Uri) {
        logger.i("SettingsViewModel.importUhabitsData", "uHabits import started", mapOf("sourceUri" to sourceUri.toString()))
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isUhabitsImporting = true,
                    uhabitsImportResult = null,
                    bulkHabitMappingResult = null,
                )
            }
            try {
                /** Summary. */
                val summary = withContext(Dispatchers.IO) {
                    /** Db. */
                    val db = sessionManager.requireDatabase()
                    /** Uhabits importer. */
                    UhabitsImporter(context, db.taskDao(), db.taskOccurrenceDao(), db.importBatchDao(), db.dailyInsightDao()).import(sourceUri)
                }
                _uiState.update {
                    it.copy(
                        isUhabitsImporting = false,
                        uhabitsImportResult = UhabitsImportResult.Success(
                            habitsUpserted = summary.habitsUpserted,
                            repetitionsUpserted = summary.repetitionsUpserted,
                        ),
                    )
                }
                /** Load database stats. */
                loadDatabaseStats()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("SettingsViewModel.importUhabitsData", "uHabits import failed", e)
                _uiState.update {
                    it.copy(
                        isUhabitsImporting = false,
                        uhabitsImportResult = UhabitsImportResult.Error(e.message ?: "uHabits import failed"),
                    )
                }
            }
        }
    }
    /**
     * Bulk map imported habits to dimension.
     */
    fun bulkMapImportedHabitsToDimension(targetDimensionId: String, targetDimensionLabel: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBulkMappingImportedHabits = true,
                    bulkHabitMappingResult = null,
                )
            }
            try {
                /** Mapped count. */
                val mappedCount = withContext(Dispatchers.IO) {
                    sessionManager.requireDatabase().taskDao().bulkMapImportSourceDimension(
                        source = IMPORT_SOURCE_UHABITS,
                        dimensionId = targetDimensionId,
                        lifeIntentionCategory = targetDimensionLabel,
                        updatedAt = LocalDateTime.now().format(dateTimeFormatter),
                    )
                }
                logger.i(
                    "SettingsViewModel.bulkMapImportedHabitsToDimension",
                    "Bulk mapping completed",
                    /** Map of. */
                    mapOf(
                        "mappedCount" to mappedCount,
                        "dimensionId" to targetDimensionId,
                    ),
                )
                _uiState.update {
                    it.copy(
                        isBulkMappingImportedHabits = false,
                        bulkHabitMappingResult = BulkHabitMappingResult.Success(
                            mappedCount = mappedCount,
                            dimensionId = targetDimensionId,
                        ),
                    )
                }
                /** Load database stats. */
                loadDatabaseStats()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("SettingsViewModel.bulkMapImportedHabitsToDimension", "Bulk mapping failed", e)
                _uiState.update {
                    it.copy(
                        isBulkMappingImportedHabits = false,
                        bulkHabitMappingResult = BulkHabitMappingResult.Error(e.message ?: "Bulk mapping failed"),
                    )
                }
            }
        }
    }
    /**
     * Generate export file name.
     */
    fun generateExportFileName(
    ): String {
        /** Timestamp. */
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "payanam_backup_encrypted_$timestamp.db"
    }
    /**
     * Request delete database.
     */
    fun requestDeleteDatabase() {
        logger.i("SettingsViewModel.requestDeleteDatabase", "Delete database flow initiated")
        _uiState.update { it.copy(showDeleteExportPrompt = true) }
    }
    /**
     * Dismiss delete export prompt.
     */
    fun dismissDeleteExportPrompt() {
        _uiState.update { it.copy(showDeleteExportPrompt = false) }
    }
    /**
     * Delete database.
     */
    fun deleteDatabase() {
        logger.i("SettingsViewModel.deleteDatabase", "Delete database confirmed — wiping all artifacts")
        _uiState.update { it.copy(showDeleteExportPrompt = false) }
        viewModelScope.launch {
            try {
                /** Deleted count. */
                val deletedCount = withContext(Dispatchers.IO) {
                    /** Delete all database artifact files. */
                    deleteAllDatabaseArtifactFiles(context)
                }
                logger.i(
                    "SettingsViewModel.deleteDatabase",
                    "Database deleted successfully",
                    /** Map of. */
                    mapOf(
                        "filesDeleted" to deletedCount,
                    ),
                )
                // Room FDs on deleted files are released by the imminent process kill (restartProcess); no explicit close needed.
                logger.i("SettingsViewModel.deleteDatabase", "Emitting restart; Room teardown via process kill")
                _navigateToDatabaseInit.tryEmit(Unit)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "SettingsViewModel.deleteDatabase",
                    "Failed to delete database",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                Timber.e(e, "Delete database failed")
            }
        }
    }
    /**
     * Delete database artifact.
     */
    fun deleteDatabaseArtifact(fileName: String) {
        logger.i(
            "SettingsViewModel.deleteDatabaseArtifact",
            "Delete database artifact requested",
            /** Map of. */
            mapOf("fileName" to fileName),
        )
        viewModelScope.launch {
            try {
                /** With context. */
                withContext(Dispatchers.IO) {
                    /** Delete database artifact file. */
                    deleteDatabaseArtifactFile(context, fileName)
                }
                /** Load database stats. */
                loadDatabaseStats()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "SettingsViewModel.deleteDatabaseArtifact",
                    "Failed to delete database artifact",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf("fileName" to fileName),
                )
            }
        }
    }
    /**
     * Clean stale artifacts.
     */
    fun cleanStaleArtifacts() {
        logger.i("SettingsViewModel.cleanStaleArtifacts", "Stale artifact cleanup requested")
        viewModelScope.launch {
            try {
                /** Deleted. */
                val deleted = withContext(Dispatchers.IO) {
                    /** Delete stale artifact files. */
                    deleteStaleArtifactFiles(context)
                }
                logger.i("SettingsViewModel.cleanStaleArtifacts", "Stale cleanup done", mapOf("deleted" to deleted))
                /** Load database stats. */
                loadDatabaseStats()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("SettingsViewModel.cleanStaleArtifacts", "Stale cleanup failed", e)
            }
        }
    }
    /**
     * Clear export result.
     */
    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }
    /**
     * Clear import result.
     */
    fun clearImportResult() {
        _uiState.update { it.copy(importResult = null) }
    }
    /**
     * Clear uhabits import result.
     */
    fun clearUhabitsImportResult() {
        _uiState.update { it.copy(uhabitsImportResult = null) }
    }
    /**
     * Clear bulk habit mapping result.
     */
    fun clearBulkHabitMappingResult() {
        _uiState.update { it.copy(bulkHabitMappingResult = null) }
    }
    private fun syncTimeoutFromDb() {
        viewModelScope.launch {
            try {
                /** Db value. */
                val dbValue = appSettingsRepository.getSetting("session_timeout_minutes")
                /** If. */
                if (dbValue != null) {
                    /** Minutes. */
                    val minutes = dbValue.toIntOrNull()
                    /** If. */
                    if (minutes != null && minutes > 0) {
                        /** Current shared pref. */
                        val currentSharedPref = databaseEncryptionManager.getSessionTimeoutMinutes()
                        /** If. */
                        if (minutes != currentSharedPref) {
                            databaseEncryptionManager.setSessionTimeoutMinutes(minutes)
                            _uiState.update { it.copy(unlockSessionTimeoutMinutes = minutes) }
                            logger.i("SettingsViewModel.syncTimeoutFromDb", "Restored timeout from DB: $minutes min")
                        }
                    }
                } else if (databaseEncryptionManager.isEncryptionEnabled()) {
                    // No explicit timeout set — default to 2× auto-backup interval
                    /** Interval key. */
                    val intervalKey = appSettingsRepository.getSetting("auto_backup_interval")
                    /** Interval minutes. */
                    val intervalMinutes = BackupInterval.fromKey(intervalKey)?.minutes
                        ?: BackupInterval.SIXTY_MIN.minutes
                    /** Default timeout. */
                    val defaultTimeout = (intervalMinutes * 2).toInt()
                    databaseEncryptionManager.setSessionTimeoutMinutes(defaultTimeout)
                    appSettingsRepository.setSetting("session_timeout_minutes", defaultTimeout.toString())
                    _uiState.update { it.copy(unlockSessionTimeoutMinutes = defaultTimeout) }
                    logger.i("SettingsViewModel.syncTimeoutFromDb", "Set default timeout to 2x backup interval: $defaultTimeout min")
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("SettingsViewModel.syncTimeoutFromDb", "Failed to sync timeout from DB", e)
            }
        }
    }

    /**
     * Update unlock session timeout minutes.
     */
    fun updateUnlockSessionTimeoutMinutes(minutes: Int) {
        databaseEncryptionManager.setSessionTimeoutMinutes(minutes)
        /** Effective minutes. */
        val effectiveMinutes = databaseEncryptionManager.getSessionTimeoutMinutes()
        _uiState.update { it.copy(unlockSessionTimeoutMinutes = effectiveMinutes) }
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting("session_timeout_minutes", effectiveMinutes.toString())
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("SettingsViewModel.updateUnlockSessionTimeoutMinutes", "Failed to persist timeout to DB", e)
            }
        }
    }
    /**
     * Disable biometric unlock.
     */
    fun disableBiometricUnlock() {
        /** Disabled. */
        val disabled = databaseEncryptionManager.disableBiometricUnlock()
        /** If. */
        if (disabled) {
            _uiState.update { it.copy(biometricUnlockEnabled = false) }
            logger.i(
                "SettingsViewModel.disableBiometricUnlock",
                "Biometric unlock disabled and biometric material removed",
            )
        } else {
            logger.w(
                "SettingsViewModel.disableBiometricUnlock",
                "Biometric disable requested but cleanup did not complete cleanly",
            )
            _uiState.update { it.copy(biometricUnlockEnabled = false) }
        }
    }

    /**
     * Enable biometric unlock with verification.
     */
    fun enableBiometricUnlockWithVerification(
        /** Activity. */
        activity: FragmentActivity,
        /** Passphrase. */
        passphrase: String,
        onComplete: (Boolean) -> Unit,
    ) {
        logger.i(
            "SettingsViewModel.enableBiometricUnlockWithVerification",
            "Biometric enable verification requested",
            /** Map of. */
            mapOf(
                "activityClass" to activity.javaClass.name,
                "passphraseProvided" to passphrase.isNotBlank(),
            ),
        )
        /** If. */
        if (passphrase.isBlank()) {
            logger.w(
                "SettingsViewModel.enableBiometricUnlockWithVerification",
                "Biometric enable verification blocked: blank passphrase",
            )
            /** On complete. */
            onComplete(false)
            /** Return. */
            return
        }
        viewModelScope.launch {
            /** Passphrase valid. */
            val passphraseValid = withContext(Dispatchers.IO) {
                databaseEncryptionManager.verifyPassphrase(passphrase)
            }
            /** If. */
            if (!passphraseValid) {
                logger.w(
                    "SettingsViewModel.enableBiometricUnlockWithVerification",
                    "Biometric enable verification blocked: passphrase verification failed",
                )
                /** On complete. */
                onComplete(false)
                return@launch
            }

            /** Can auth. */
            val canAuth = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)
            logger.i(
                "SettingsViewModel.enableBiometricUnlockWithVerification",
                "Biometric capability checked",
                /** Map of. */
                mapOf("canAuthenticateResult" to canAuth),
            )
            /** If. */
            if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                logger.w(
                    "SettingsViewModel.enableBiometricUnlockWithVerification",
                    "Biometric enable verification blocked: biometric unavailable",
                    /** Map of. */
                    mapOf("canAuthenticateResult" to canAuth),
                )
                /** On complete. */
                onComplete(false)
                return@launch
            }

            /** Cipher. */
            val cipher = runCatching { databaseEncryptionManager.getCipherForBiometricEnrollment() }.getOrElse { error ->
                logger.e(
                    "SettingsViewModel.enableBiometricUnlockWithVerification",
                    "Failed to initialize cipher for biometric enable verification",
                    /** Error. */
                    error,
                )
                /** On complete. */
                onComplete(false)
                return@launch
            }

            /** Executor. */
            val executor = ContextCompat.getMainExecutor(context)
            /** Callback. */
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    logger.i(
                        "SettingsViewModel.enableBiometricUnlockWithVerification",
                        "Biometric verification succeeded; storing biometric-wrapped passphrase and enabling preference",
                    )
                    /** Authenticated cipher. */
                    val authenticatedCipher = result.cryptoObject?.cipher
                    /** If. */
                    if (authenticatedCipher == null) {
                        logger.e(
                            "SettingsViewModel.enableBiometricUnlockWithVerification",
                            "Biometric verification succeeded but no cipher was returned",
                        )
                        /** On complete. */
                        onComplete(false)
                        /** Return. */
                        return
                    }
                    viewModelScope.launch {
                        /** Stored. */
                        val stored = withContext(Dispatchers.IO) {
                            databaseEncryptionManager.storeBiometricWrappedPassphraseWithCipher(
                                cipher = authenticatedCipher,
                                passphrase = passphrase,
                            )
                        }
                        /** If. */
                        if (!stored) {
                            logger.e(
                                "SettingsViewModel.enableBiometricUnlockWithVerification",
                                "Failed to store biometric-wrapped passphrase after biometric verification",
                            )
                            /** On complete. */
                            onComplete(false)
                            return@launch
                        }
                        databaseEncryptionManager.setBiometricUnlockEnabled(true)
                        _uiState.update { it.copy(biometricUnlockEnabled = true) }
                        logger.i(
                            "SettingsViewModel.enableBiometricUnlockWithVerification",
                            "Biometric unlock preference enabled after successful verification",
                        )
                        /** On complete. */
                        onComplete(true)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    logger.w(
                        "SettingsViewModel.enableBiometricUnlockWithVerification",
                        "Biometric enable verification error",
                        /** Map of. */
                        mapOf("errorCode" to errorCode, "errString" to errString.toString()),
                    )
                    /** On complete. */
                    onComplete(false)
                }

                override fun onAuthenticationFailed() {
                    logger.w(
                        "SettingsViewModel.enableBiometricUnlockWithVerification",
                        "Biometric enable verification failed (not recognized)",
                    )
                }
            }

            /** Prompt info. */
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(io.payanam.R.string.db_passphrase_unlock_biometric_title))
                .setSubtitle(context.getString(io.payanam.R.string.db_passphrase_unlock_biometric_subtitle))
                .setNegativeButtonText(context.getString(io.payanam.R.string.db_passphrase_unlock_biometric_negative))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .build()

            logger.i(
                "SettingsViewModel.enableBiometricUnlockWithVerification",
                "Launching biometric prompt for settings enable flow",
            )
            /** Biometric prompt. */
            BiometricPrompt(activity, executor, callback).authenticate(
                /** Prompt info. */
                promptInfo,
                BiometricPrompt.CryptoObject(cipher),
            )
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? = uri.lastPathSegment?.substringAfterLast("/")

    // ── Update checker ────────────────────────────────────────────────────────
    private var lastCheckTimestampMs = 0L
    private var checkCountInWindow = 0
    private var windowStartMs = 0L

    /**
     * Check for update.
     */
    fun checkForUpdate() {
        /** Now. */
        val now = System.currentTimeMillis()

        // Cooldown guard: 1 minute between checks
        /** If. */
        if (now - lastCheckTimestampMs < CHECK_COOLDOWN_MS) return

        // Rate limit guard: max 5 checks per 5-minute window
        /** If. */
        if (now - windowStartMs > RATE_WINDOW_MS) {
            windowStartMs = now
            checkCountInWindow = 0
        }
        /** If. */
        if (checkCountInWindow >= MAX_CHECKS_PER_WINDOW) return

        lastCheckTimestampMs = now
        checkCountInWindow++

        logger.i("SettingsViewModel.checkForUpdate", "Checking for update", mapOf("buildNumber" to _uiState.value.buildNumber, "channel" to _uiState.value.updateChannel.name))
        _uiState.update { it.copy(isCheckingForUpdate = true, updateCheckResult = null) }
        viewModelScope.launch {
            /** Result. */
            val result = UpdateChecker.check(_uiState.value.buildNumber, _uiState.value.updateChannel)
            logger.i("SettingsViewModel.checkForUpdate", "Update check complete", mapOf(
                "updateAvailable" to result.isUpdateAvailable,
                "latestBuild" to result.latestBuildNumber,
                "error" to result.error?.name,
            ))
            _uiState.update {
                it.copy(isCheckingForUpdate = false, updateCheckResult = result)
            }
            // Auto-download when update available + toggle ON + no active download.
            /** If. */
            if (result.isUpdateAvailable && _uiState.value.autoDownloadEnabled && activeDownloadId == null) {
                /** Latest. */
                val latest = result.latestBuildNumber ?: return@launch
                /** Start auto download. */
                startAutoDownload(latest)
            }
        }
    }

    /** Enqueue the APK download for the given build and start progress polling. */
    private fun startAutoDownload(buildNumber: Int) {
        // Already on disk? Offer Install instead of re-downloading (avoids the
        // duplicate-download case: check → downloaded → killed → re-check).
        /** Existing path. */
        val existingPath = AutoDownloadManager.findApkForBuild(context, buildNumber.toString())
        /** If. */
        if (existingPath != null) {
            /** File name. */
            val fileName = File(existingPath).name
            logger.d("SettingsViewModel.startAutoDownload", "APK already downloaded; offering install", mapOf("build" to buildNumber, "file" to fileName))
            viewModelScope.launch {
                appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_BUILD, buildNumber.toString())
                appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_FILE, fileName)
                appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_AT, System.currentTimeMillis().toString())
            }
            _uiState.update { it.copy(downloadState = DownloadUiState.Downloaded(fileName, existingPath)) }
            /** Return. */
            return
        }
        /** Channel. */
        val channel = _uiState.value.updateChannel
        // Real asset URL + filename come from the release's assets list.
        /** Selected. */
        val selected = _uiState.value.updateCheckResult?.channelStatuses
            ?.firstOrNull { it.channel == channel }
        /** Download url. */
        val downloadUrl = selected?.apkDownloadUrl
        /** If. */
        if (downloadUrl.isNullOrEmpty()) {
            // E1: no silent failure — surface a state so the user knows why
            // the download did not start.
            logger.w("SettingsViewModel.startAutoDownload", "No APK asset URL in check result", mapOf("channel" to channel.name))
            _uiState.update { it.copy(downloadState = DownloadUiState.Failed("no_download_url")) }
            /** Return. */
            return
        }
        /** File name. */
        val fileName = downloadUrl.substringAfterLast('/')

        // Keep only the last 2 APKs (rollback safety), never during this enqueue.
        AutoDownloadManager.cleanupOldApks(context, keepCount = 2)

        /** Id. */
        val id = AutoDownloadManager.enqueue(context, downloadUrl, fileName, wifiOnly = _uiState.value.wifiOnlyEnabled)
        /** If. */
        if (id == null) {
            _uiState.update { it.copy(downloadState = DownloadUiState.Failed("enqueue_failed")) }
            /** Return. */
            return
        }
        activeDownloadId = id
        viewModelScope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, id.toString())
            // Persist the URL so a Retry survives app restarts.
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL, downloadUrl)
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_FILE, fileName)
        }
        logger.i("SettingsViewModel.startAutoDownload", "Auto-download started", mapOf("build" to buildNumber, "file" to fileName, "downloadId" to id))
        /** Poll download progress. */
        pollDownloadProgress()
    }

    /** Manual "Download update" / Retry entry point (single-button state machine). */
    internal fun downloadOrRetry() {
        /** State. */
        val state = _uiState.value.downloadState
        // Retry: reuse the last known URL if the check result is gone.
        /** If. */
        if (state is DownloadUiState.Failed) {
            viewModelScope.launch {
                /** Stored url. */
                val storedUrl = appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL)
                /** If. */
                if (storedUrl.isNullOrEmpty()) return@launch
                /** File name. */
                val fileName = storedUrl.substringAfterLast('/')
                _uiState.update { it.copy(downloadState = DownloadUiState.Idle) }
                // Rebuild a check-result-like state so startAutoDownload can proceed.
                /** Selected. */
                val selected = _uiState.value.updateCheckResult?.channelStatuses
                    ?.firstOrNull { it.channel == _uiState.value.updateChannel }
                /** If. */
                if (selected != null) {
                    /** Patched. */
                    val patched = _uiState.value.updateCheckResult?.copy(
                        channelStatuses = listOf(selected.copy(apkDownloadUrl = storedUrl)),
                    )
                    _uiState.update { it.copy(updateCheckResult = patched) }
                }
                // Direct enqueue — works even when the check result is gone (app restart).
                /** Id. */
                val id = AutoDownloadManager.enqueue(
                    /** Context. */
                    context,
                    /** Stored url. */
                    storedUrl,
                    /** File name. */
                    fileName,
                    wifiOnly = _uiState.value.wifiOnlyEnabled,
                )
                /** If. */
                if (id != null) {
                    activeDownloadId = id
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, id.toString())
                    logger.i("SettingsViewModel.downloadOrRetry", "Retry download enqueued", mapOf("file" to fileName, "downloadId" to id))
                    /** Poll download progress. */
                    pollDownloadProgress()
                } else {
                    _uiState.update { it.copy(downloadState = DownloadUiState.Failed("enqueue_failed")) }
                }
            }
            /** Return. */
            return
        }
        // Plain manual download: only valid when an update is available.
        /** Result. */
        val result = _uiState.value.updateCheckResult ?: return
        /** Build. */
        val build = result.latestBuildNumber ?: return
        /** If. */
        if (result.isUpdateAvailable && activeDownloadId == null) {
            // STALE-URL GUARD: the cached check result can be minutes/hours old
            // (rolling channel moves on). Re-fetch the channel's current
            // release so we always download what is latest NOW, never a
            // superseded build. The retry path above uses the persisted URL
            // because a restart may have no cached result at all.
            viewModelScope.launch {
                /** Channel. */
                val channel = _uiState.value.updateChannel
                /** Fresh. */
                val fresh = UpdateChecker.check(BuildConfig.VERSION_CODE, channel)
                /** If. */
                if (fresh.error != null) {
                    _uiState.update { it.copy(downloadState = DownloadUiState.Failed("refresh_failed")) }
                    return@launch
                }
                /** Selected. */
                val selected = fresh.channelStatuses.firstOrNull { it.channel == channel }
                /** Url. */
                val url = selected?.apkDownloadUrl
                /** If. */
                if (url.isNullOrEmpty()) {
                    _uiState.update { it.copy(downloadState = DownloadUiState.Failed("no_download_url")) }
                    return@launch
                }
                logger.i(
                    "SettingsViewModel.downloadOrRetry",
                    "Manual download re-fetched channel",
                    /** Map of. */
                    mapOf(
                        "cachedBuild" to build,
                        "freshBuild" to (fresh.latestBuildNumber ?: -1),
                        "file" to url.substringAfterLast('/'),
                    ),
                )
                /** If. */
                if (fresh.isUpdateAvailable) {
                    /** Start auto download. */
                    startAutoDownload(fresh.latestBuildNumber ?: build)
                } else {
                    // Channel moved past us: no update to download anymore.
                    _uiState.update { it.copy(updateCheckResult = fresh) }
                }
            }
        }
    }

    /** Poll DownloadManager progress and surface state; stops on terminal state. */
    private fun pollDownloadProgress() {
        /** Id. */
        val id = activeDownloadId ?: return
        viewModelScope.launch {
            /** Terminal. */
            var terminal = false
            /** Polls. */
            var polls = 0
            // E4: bounded polling — stop after 10 minutes even if the system
            // never reports a terminal state (avoids infinite battery drain).
            /** While. */
            while (!terminal && activeDownloadId == id && polls < MAX_POLLS) {
                polls++
                /** State. */
                val state = AutoDownloadManager.queryProgress(context, id)
                // Enrich Downloading with channel + full build name for the UI.
                /** Enriched. */
                val enriched = if (state is DownloadUiState.Downloading) {
                    state.copy(
                        channelName = _uiState.value.updateChannel.name.lowercase(),
                        buildName = appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_FILE) ?: state.fileName,
                    )
                } else {
                    /** State. */
                    state
                }
                _uiState.update { it.copy(downloadState = enriched) }
                /** When. */
                when (state) {
                    is DownloadUiState.Downloading, is DownloadUiState.Paused -> {
                        // keep polling (Paused is transient — system resumes)
                    }
                    is DownloadUiState.Downloaded -> {
                        appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, null)
                        // Remember the completed download so a later restart can
                        // offer "Install" without re-downloading (see restoreDownloadState).
                        appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_BUILD, buildNumberFromFileName(state.fileName))
                        appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_FILE, state.fileName)
                        appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_AT, System.currentTimeMillis().toString())
                        // Prompt to install only when the opt-in toggle is ON;
                        // otherwise just show the "ready" state (tap to install).
                        /** If. */
                        if (_uiState.value.promptInstallEnabled) {
                            _uiState.update { it.copy(pendingInstallPath = state.localPath) }
                        }
                        terminal = true
                    }
                    is DownloadUiState.Failed -> {
                        appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, null)
                        terminal = true
                    }
                    DownloadUiState.Idle -> terminal = true
                }
                /** If. */
                if (!terminal) delay(POLL_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val IMPORT_SOURCE_UHABITS = "uhabits"
        private const val CHECK_COOLDOWN_MS = 60_000L        // 1 minute between checks
        private const val MAX_CHECKS_PER_WINDOW = 5          // max 5 checks
        private const val RATE_WINDOW_MS = 5 * 60_000L       // 5-minute window
        private const val POLL_INTERVAL_MS = 1_000L          // progress poll cadence
        private const val MAX_POLLS = 600                    // 10 min cap (E4)
        /** A completed download is "fresh" for 15 min — after that, re-check first. */
        private const val COMPLETED_DOWNLOAD_FRESH_MS = 15 * 60 * 1_000L
    }
}
