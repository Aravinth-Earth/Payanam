//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricManager
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
import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
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
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _navigateToDatabaseInit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToDatabaseInit: SharedFlow<Unit> = _navigateToDatabaseInit.asSharedFlow()

    // Held across the passphrase-prompt gate for encrypted DB imports from Settings
    // Managed by extension functions in SettingsEncryptedImportSupport.kt
    internal var pendingEncryptedImportDbFile: File? = null
    internal var pendingEncryptedImportBackupMappings: List<Pair<File, File>> = emptyList()

    // Auto-download state
    private var activeDownloadId: Long? = null

    internal fun updateUiState(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.update(transform)
    }

    init {
        logger.i("SettingsViewModel.init", "ViewModel initialized")
        loadDatabaseStats()
        syncTimeoutFromDb()
        loadUpdateChannel()
        loadPromptInstall()
        loadAutoDownload()
        loadWifiOnly()
    }

    /** Load the persisted update channel (defaults to DEV). */
    private fun loadUpdateChannel() {
        viewModelScope.launch {
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.UPDATE_CHANNEL)
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
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.AUTO_DOWNLOAD)
            val enabled = raw == "true"
            logger.d("SettingsViewModel.loadAutoDownload", "Loaded toggle", mapOf("enabled" to enabled, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(autoDownloadEnabled = enabled) }
            // If a download was in-flight from a previous session, restore its state.
            if (enabled) restoreDownloadState()
        }
    }

    /** Toggle auto-download on/off; toggling off cancels any in-flight download. */
    internal fun onAutoDownloadToggled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.AUTO_DOWNLOAD, enabled.toString())
            logger.i("SettingsViewModel.onAutoDownloadToggled", "Toggle saved", mapOf("enabled" to enabled))
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
            val storedId = appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID)?.toLongOrNull()
            if (storedId != null) {
                activeDownloadId = storedId
                pollDownloadProgress()
            } else if (!appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL).isNullOrEmpty()) {
                // A previous download ended (failed/cancelled) but the URL is
                // still stored → surface Retry so the user can re-attempt.
                _uiState.update { it.copy(downloadState = DownloadUiState.Failed("retry_available")) }
            }
        }
    }

    /** Load the persisted prompt-install toggle (defaults to OFF). */
    private fun loadPromptInstall() {
        viewModelScope.launch {
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.PROMPT_INSTALL)
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
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.WIFI_ONLY)
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

    /** User tapped "Update now" in the popup → launch the system installer. */
    internal fun onInstallNow() {
        // Button path (Downloaded state) may not have a pending popup — derive
        // the file from the download state when that's the case.
        val path = _uiState.value.pendingInstallPath
            ?: (_uiState.value.downloadState as? DownloadUiState.Downloaded)?.localPath
            ?: return
        val file = File(path)
        if (!file.exists()) {
            _uiState.update { it.copy(pendingInstallPath = null, downloadState = DownloadUiState.Failed("file_missing")) }
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            // Install flow handed off to the system; clear pending state.
            _uiState.update { it.copy(pendingInstallPath = null) }
        } catch (e: Exception) {
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
                val tasks = taskRepository.getAllTasks().first()
                val timeEntries = timeEntryRepository.getAllTimeEntries().first()
                val notes = noteRepository.getAllNotes().first()
                val importedUhabitsHabits = withContext(Dispatchers.IO) {
                    sessionManager.requireDatabase().taskDao().countByImportSource(IMPORT_SOURCE_UHABITS)
                }
                logger.i(
                    "SettingsViewModel.loadDatabaseStats",
                    "Entity counts loaded",
                    mapOf(
                        "tasks" to tasks.size,
                        "timeEntries" to timeEntries.size,
                        "notes" to notes.size,
                    ),
                )
                // Get database file size and artifacts in one pass; using artifact scan
                // so WAL-only state (primary .db absent, -wal present) reports correct non-zero size.
                val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
                val (sizeKb, databaseArtifacts) = withContext(Dispatchers.IO) {
                    val files = listDatabaseArtifactFiles(context).filter { it.exists() }
                    val size = files.sumOf { it.length() } / 1024
                    val artifacts = files
                        .sortedByDescending { it.lastModified() }
                        .map { it.toDatabaseArtifactUiModel() }
                    size to artifacts
                }
                logger.i(
                    "SettingsViewModel.loadDatabaseStats",
                    "Database file info",
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
            } catch (e: Exception) {
                logger.e("SettingsViewModel.loadDatabaseStats", "Failed to load stats", e)
                Timber.e(e, "Error loading database stats")
            }
        }
    }
    fun exportData(
        destinationUri: Uri,
    ) {
        exportDatabase(destinationUri)
    }
    fun importData(
        sourceUri: Uri,
    ) {
        importDatabase(sourceUri)
    }
    fun exportDatabase(
        destinationUri: Uri,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportResult = null) }
            try {
                val bytesCopied = databaseBackupCoordinator.exportSnapshotToUri(destinationUri)
                logger.i(
                    "SettingsViewModel.exportDatabase",
                    "Database exported",
                    mapOf(
                        "mode" to "encrypted_full_db",
                        "bytesCopiedKB" to (bytesCopied / 1024),
                    ),
                )
                val fileName = getFileNameFromUri(destinationUri) ?: "payanam_backup.db"
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = ExportResult.Success(fileName),
                    )
                }
            } catch (e: Exception) {
                logger.e("SettingsViewModel.exportDatabase", "Export failed", e)
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = ExportResult.Error(e.message ?: "Export failed"),
                    )
                }
            }
            // Reload stats after export
            loadDatabaseStats()
        }
    }
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
                val summary = withContext(Dispatchers.IO) {
                    val db = sessionManager.requireDatabase()
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
                loadDatabaseStats()
            } catch (e: Exception) {
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
    fun bulkMapImportedHabitsToDimension(targetDimensionId: String, targetDimensionLabel: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBulkMappingImportedHabits = true,
                    bulkHabitMappingResult = null,
                )
            }
            try {
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
                loadDatabaseStats()
            } catch (e: Exception) {
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
    fun generateExportFileName(
    ): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "payanam_backup_encrypted_$timestamp.db"
    }
    fun requestDeleteDatabase() {
        logger.i("SettingsViewModel.requestDeleteDatabase", "Delete database flow initiated")
        _uiState.update { it.copy(showDeleteExportPrompt = true) }
    }
    fun dismissDeleteExportPrompt() {
        _uiState.update { it.copy(showDeleteExportPrompt = false) }
    }
    fun deleteDatabase() {
        logger.i("SettingsViewModel.deleteDatabase", "Delete database confirmed — wiping all artifacts")
        _uiState.update { it.copy(showDeleteExportPrompt = false) }
        viewModelScope.launch {
            try {
                val deletedCount = withContext(Dispatchers.IO) {
                    deleteAllDatabaseArtifactFiles(context)
                }
                logger.i(
                    "SettingsViewModel.deleteDatabase",
                    "Database deleted successfully",
                    mapOf(
                        "filesDeleted" to deletedCount,
                    ),
                )
                // Room FDs on deleted files are released by the imminent process kill (restartProcess); no explicit close needed.
                logger.i("SettingsViewModel.deleteDatabase", "Emitting restart; Room teardown via process kill")
                _navigateToDatabaseInit.tryEmit(Unit)
            } catch (e: Exception) {
                logger.e(
                    "SettingsViewModel.deleteDatabase",
                    "Failed to delete database",
                    e,
                    mapOf(
                        "error" to (e.message ?: "Unknown error"),
                    ),
                )
                Timber.e(e, "Delete database failed")
            }
        }
    }
    fun deleteDatabaseArtifact(fileName: String) {
        logger.i(
            "SettingsViewModel.deleteDatabaseArtifact",
            "Delete database artifact requested",
            mapOf("fileName" to fileName),
        )
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    deleteDatabaseArtifactFile(context, fileName)
                }
                loadDatabaseStats()
            } catch (e: Exception) {
                logger.e(
                    "SettingsViewModel.deleteDatabaseArtifact",
                    "Failed to delete database artifact",
                    e,
                    mapOf("fileName" to fileName),
                )
            }
        }
    }
    fun cleanStaleArtifacts() {
        logger.i("SettingsViewModel.cleanStaleArtifacts", "Stale artifact cleanup requested")
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    deleteStaleArtifactFiles(context)
                }
                logger.i("SettingsViewModel.cleanStaleArtifacts", "Stale cleanup done", mapOf("deleted" to deleted))
                loadDatabaseStats()
            } catch (e: Exception) {
                logger.e("SettingsViewModel.cleanStaleArtifacts", "Stale cleanup failed", e)
            }
        }
    }
    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }
    fun clearImportResult() {
        _uiState.update { it.copy(importResult = null) }
    }
    fun clearUhabitsImportResult() {
        _uiState.update { it.copy(uhabitsImportResult = null) }
    }
    fun clearBulkHabitMappingResult() {
        _uiState.update { it.copy(bulkHabitMappingResult = null) }
    }
    private fun syncTimeoutFromDb() {
        viewModelScope.launch {
            try {
                val dbValue = appSettingsRepository.getSetting("session_timeout_minutes")
                if (dbValue != null) {
                    val minutes = dbValue.toIntOrNull()
                    if (minutes != null && minutes > 0) {
                        val currentSharedPref = databaseEncryptionManager.getSessionTimeoutMinutes()
                        if (minutes != currentSharedPref) {
                            databaseEncryptionManager.setSessionTimeoutMinutes(minutes)
                            _uiState.update { it.copy(unlockSessionTimeoutMinutes = minutes) }
                            logger.i("SettingsViewModel.syncTimeoutFromDb", "Restored timeout from DB: $minutes min")
                        }
                    }
                } else if (databaseEncryptionManager.isEncryptionEnabled()) {
                    // No explicit timeout set — default to 2× auto-backup interval
                    val intervalKey = appSettingsRepository.getSetting("auto_backup_interval")
                    val intervalMinutes = BackupInterval.fromKey(intervalKey)?.minutes
                        ?: BackupInterval.SIXTY_MIN.minutes
                    val defaultTimeout = (intervalMinutes * 2).toInt()
                    databaseEncryptionManager.setSessionTimeoutMinutes(defaultTimeout)
                    appSettingsRepository.setSetting("session_timeout_minutes", defaultTimeout.toString())
                    _uiState.update { it.copy(unlockSessionTimeoutMinutes = defaultTimeout) }
                    logger.i("SettingsViewModel.syncTimeoutFromDb", "Set default timeout to 2x backup interval: $defaultTimeout min")
                }
            } catch (e: Exception) {
                logger.e("SettingsViewModel.syncTimeoutFromDb", "Failed to sync timeout from DB", e)
            }
        }
    }

    fun updateUnlockSessionTimeoutMinutes(minutes: Int) {
        databaseEncryptionManager.setSessionTimeoutMinutes(minutes)
        val effectiveMinutes = databaseEncryptionManager.getSessionTimeoutMinutes()
        _uiState.update { it.copy(unlockSessionTimeoutMinutes = effectiveMinutes) }
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting("session_timeout_minutes", effectiveMinutes.toString())
            } catch (e: Exception) {
                logger.e("SettingsViewModel.updateUnlockSessionTimeoutMinutes", "Failed to persist timeout to DB", e)
            }
        }
    }
    fun disableBiometricUnlock() {
        val disabled = databaseEncryptionManager.disableBiometricUnlock()
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

    fun enableBiometricUnlockWithVerification(
        activity: FragmentActivity,
        passphrase: String,
        onComplete: (Boolean) -> Unit,
    ) {
        logger.i(
            "SettingsViewModel.enableBiometricUnlockWithVerification",
            "Biometric enable verification requested",
            mapOf(
                "activityClass" to activity.javaClass.name,
                "passphraseProvided" to passphrase.isNotBlank(),
            ),
        )
        if (passphrase.isBlank()) {
            logger.w(
                "SettingsViewModel.enableBiometricUnlockWithVerification",
                "Biometric enable verification blocked: blank passphrase",
            )
            onComplete(false)
            return
        }
        viewModelScope.launch {
            val passphraseValid = withContext(Dispatchers.IO) {
                databaseEncryptionManager.verifyPassphrase(passphrase)
            }
            if (!passphraseValid) {
                logger.w(
                    "SettingsViewModel.enableBiometricUnlockWithVerification",
                    "Biometric enable verification blocked: passphrase verification failed",
                )
                onComplete(false)
                return@launch
            }

            val canAuth = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)
            logger.i(
                "SettingsViewModel.enableBiometricUnlockWithVerification",
                "Biometric capability checked",
                mapOf("canAuthenticateResult" to canAuth),
            )
            if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                logger.w(
                    "SettingsViewModel.enableBiometricUnlockWithVerification",
                    "Biometric enable verification blocked: biometric unavailable",
                    mapOf("canAuthenticateResult" to canAuth),
                )
                onComplete(false)
                return@launch
            }

            val cipher = runCatching { databaseEncryptionManager.getCipherForBiometricEnrollment() }.getOrElse { error ->
                logger.e(
                    "SettingsViewModel.enableBiometricUnlockWithVerification",
                    "Failed to initialize cipher for biometric enable verification",
                    error,
                )
                onComplete(false)
                return@launch
            }

            val executor = ContextCompat.getMainExecutor(context)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    logger.i(
                        "SettingsViewModel.enableBiometricUnlockWithVerification",
                        "Biometric verification succeeded; storing biometric-wrapped passphrase and enabling preference",
                    )
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher == null) {
                        logger.e(
                            "SettingsViewModel.enableBiometricUnlockWithVerification",
                            "Biometric verification succeeded but no cipher was returned",
                        )
                        onComplete(false)
                        return
                    }
                    viewModelScope.launch {
                        val stored = withContext(Dispatchers.IO) {
                            databaseEncryptionManager.storeBiometricWrappedPassphraseWithCipher(
                                cipher = authenticatedCipher,
                                passphrase = passphrase,
                            )
                        }
                        if (!stored) {
                            logger.e(
                                "SettingsViewModel.enableBiometricUnlockWithVerification",
                                "Failed to store biometric-wrapped passphrase after biometric verification",
                            )
                            onComplete(false)
                            return@launch
                        }
                        databaseEncryptionManager.setBiometricUnlockEnabled(true)
                        _uiState.update { it.copy(biometricUnlockEnabled = true) }
                        logger.i(
                            "SettingsViewModel.enableBiometricUnlockWithVerification",
                            "Biometric unlock preference enabled after successful verification",
                        )
                        onComplete(true)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    logger.w(
                        "SettingsViewModel.enableBiometricUnlockWithVerification",
                        "Biometric enable verification error",
                        mapOf("errorCode" to errorCode, "errString" to errString.toString()),
                    )
                    onComplete(false)
                }

                override fun onAuthenticationFailed() {
                    logger.w(
                        "SettingsViewModel.enableBiometricUnlockWithVerification",
                        "Biometric enable verification failed (not recognized)",
                    )
                }
            }

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
            BiometricPrompt(activity, executor, callback).authenticate(
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

    fun checkForUpdate() {
        val now = System.currentTimeMillis()

        // Cooldown guard: 1 minute between checks
        if (now - lastCheckTimestampMs < CHECK_COOLDOWN_MS) return

        // Rate limit guard: max 5 checks per 5-minute window
        if (now - windowStartMs > RATE_WINDOW_MS) {
            windowStartMs = now
            checkCountInWindow = 0
        }
        if (checkCountInWindow >= MAX_CHECKS_PER_WINDOW) return

        lastCheckTimestampMs = now
        checkCountInWindow++

        logger.i("SettingsViewModel.checkForUpdate", "Checking for update", mapOf("buildNumber" to _uiState.value.buildNumber, "channel" to _uiState.value.updateChannel.name))
        _uiState.update { it.copy(isCheckingForUpdate = true, updateCheckResult = null) }
        viewModelScope.launch {
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
            if (result.isUpdateAvailable && _uiState.value.autoDownloadEnabled && activeDownloadId == null) {
                val latest = result.latestBuildNumber ?: return@launch
                startAutoDownload(latest)
            }
        }
    }

    /** Enqueue the APK download for the given build and start progress polling. */
    private fun startAutoDownload(buildNumber: Int) {
        val channel = _uiState.value.updateChannel
        // Real asset URL + filename come from the release's assets list.
        val selected = _uiState.value.updateCheckResult?.channelStatuses
            ?.firstOrNull { it.channel == channel }
        val downloadUrl = selected?.apkDownloadUrl
        if (downloadUrl.isNullOrEmpty()) {
            // E1: no silent failure — surface a state so the user knows why
            // the download did not start.
            logger.w("SettingsViewModel.startAutoDownload", "No APK asset URL in check result", mapOf("channel" to channel.name))
            _uiState.update { it.copy(downloadState = DownloadUiState.Failed("no_download_url")) }
            return
        }
        val fileName = downloadUrl.substringAfterLast('/')

        // Keep only the last 2 APKs (rollback safety), never during this enqueue.
        AutoDownloadManager.cleanupOldApks(context, keepCount = 2)

        val id = AutoDownloadManager.enqueue(context, downloadUrl, fileName, wifiOnly = _uiState.value.wifiOnlyEnabled)
        if (id == null) {
            _uiState.update { it.copy(downloadState = DownloadUiState.Failed("enqueue_failed")) }
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
        pollDownloadProgress()
    }

    /** Manual "Download update" / Retry entry point (single-button state machine). */
    internal fun downloadOrRetry() {
        val state = _uiState.value.downloadState
        // Retry: reuse the last known URL if the check result is gone.
        if (state is DownloadUiState.Failed) {
            viewModelScope.launch {
                val storedUrl = appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL)
                if (storedUrl.isNullOrEmpty()) return@launch
                val fileName = storedUrl.substringAfterLast('/')
                _uiState.update { it.copy(downloadState = DownloadUiState.Idle) }
                // Rebuild a check-result-like state so startAutoDownload can proceed.
                val selected = _uiState.value.updateCheckResult?.channelStatuses
                    ?.firstOrNull { it.channel == _uiState.value.updateChannel }
                if (selected != null) {
                    val patched = _uiState.value.updateCheckResult?.copy(
                        channelStatuses = listOf(selected.copy(apkDownloadUrl = storedUrl)),
                    )
                    _uiState.update { it.copy(updateCheckResult = patched) }
                }
                // Direct enqueue — works even when the check result is gone (app restart).
                val id = AutoDownloadManager.enqueue(
                    context,
                    storedUrl,
                    fileName,
                    wifiOnly = _uiState.value.wifiOnlyEnabled,
                )
                if (id != null) {
                    activeDownloadId = id
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, id.toString())
                    logger.i("SettingsViewModel.downloadOrRetry", "Retry download enqueued", mapOf("file" to fileName, "downloadId" to id))
                    pollDownloadProgress()
                } else {
                    _uiState.update { it.copy(downloadState = DownloadUiState.Failed("enqueue_failed")) }
                }
            }
            return
        }
        // Plain manual download: only valid when an update is available.
        val result = _uiState.value.updateCheckResult ?: return
        val build = result.latestBuildNumber ?: return
        if (result.isUpdateAvailable && activeDownloadId == null) {
            startAutoDownload(build)
        }
    }

    /** Poll DownloadManager progress and surface state; stops on terminal state. */
    private fun pollDownloadProgress() {
        val id = activeDownloadId ?: return
        viewModelScope.launch {
            var terminal = false
            var polls = 0
            // E4: bounded polling — stop after 10 minutes even if the system
            // never reports a terminal state (avoids infinite battery drain).
            while (!terminal && activeDownloadId == id && polls < MAX_POLLS) {
                polls++
                val state = AutoDownloadManager.queryProgress(context, id)
                _uiState.update { it.copy(downloadState = state) }
                when (state) {
                    is DownloadUiState.Downloading, is DownloadUiState.Paused -> {
                        // keep polling (Paused is transient — system resumes)
                    }
                    is DownloadUiState.Downloaded -> {
                        appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, null)
                        // Prompt to install only when the opt-in toggle is ON;
                        // otherwise just show the "ready" state (tap to install).
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
    }
}
