//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
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

    internal fun updateUiState(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.update(transform)
    }

    init {
        logger.i("SettingsViewModel.init", "ViewModel initialized")
        loadDatabaseStats()
        syncTimeoutFromDb()
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

        logger.i("SettingsViewModel.checkForUpdate", "Checking for update", mapOf("buildNumber" to _uiState.value.buildNumber))
        _uiState.update { it.copy(isCheckingForUpdate = true, updateCheckResult = null) }
        viewModelScope.launch {
            val result = UpdateChecker.check(_uiState.value.buildNumber)
            logger.i("SettingsViewModel.checkForUpdate", "Update check complete", mapOf(
                "updateAvailable" to result.isUpdateAvailable,
                "latestBuild" to result.latestBuildNumber,
                "error" to result.error?.name,
            ))
            _uiState.update {
                it.copy(isCheckingForUpdate = false, updateCheckResult = result)
            }
        }
    }

    companion object {
        private const val IMPORT_SOURCE_UHABITS = "uhabits"
        private const val CHECK_COOLDOWN_MS = 60_000L        // 1 minute between checks
        private const val MAX_CHECKS_PER_WINDOW = 5          // max 5 checks
        private const val RATE_WINDOW_MS = 5 * 60_000L       // 5-minute window
    }
}
