//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.payanam.BuildConfig
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.text.uiStringsFor
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.platform.AndroidDatabaseEncryptionProbe
import io.payanam.platform.AndroidDocumentSource
import io.payanam.platform.appDatabaseInitWalConsolidator
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.download.DownloadUiState
import io.payanam.presentation.settings.BulkHabitMappingResult
import io.payanam.presentation.settings.ExportResult
import io.payanam.presentation.settings.ImportResult
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.presentation.settings.UhabitsImportResult
import io.payanam.presentation.settings.listDatabaseArtifactFiles
import io.payanam.presentation.settings.toDatabaseArtifactUiModel
import io.payanam.presentation.updates.UpdateChecker
import io.payanam.presentation.updates.UpdatePrefKeys
import io.payanam.presentation.updates.artifactNameMatchesBuildType
import io.payanam.service.DatabaseBackupCoordinator
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.dbExportName
import io.payanam.shared.settings.BackupInterval
import io.payanam.shared.updates.UpdateCheckError
import io.payanam.shared.updates.UpdateOutcome
import io.payanam.ui.viewmodel.UhabitsImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Settings screen ViewModel: owns database stats/artifact management, the
 * import/export and uHabits-import flows, the encryption passphrase, biometric
 * unlock lifecycle, and the self-update check/auto-download state machine.
 */
class SettingsViewModel(
    internal val context: Context,
    internal val sessionManager: DatabaseSessionManager,
    internal val databaseEncryptionManager: DatabaseEncryptionManager,
    private val databaseBackupCoordinator: DatabaseBackupCoordinator,
    // Internal, not private: Kotlin's `private` is FILE-scoped, and this ViewModel's import machinery
    // lives in SettingsEncryptedImportSupport.kt as extensions, which could not reach a private member.
    // The repo's TasksViewModel keeps `private val scope` safely only because its own extensions sit in
    // the same file.
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * The shared install gate.
     *
     * Defaulted because this ViewModel is built at its one call site rather than injected, but a
     * constructor parameter all the same so a test can hand in a scripted verdict and prove the four
     * gate branches. Production's default is the same [ApkChecksumVerifier] Hilt binds.
     */
    private val checksumVerifier: ApkChecksumVerifier = DefaultApkChecksumVerifier(),
) {
    private val taskRepository = LocalDataLayer.current.tasks
    private val timeEntryRepository = LocalDataLayer.current.timeEntries
    private val noteRepository = LocalDataLayer.current.notes
    private val appSettingsRepository = LocalDataLayer.current.appSettings
    internal val logger = UnifiedLogger.getInstance()
    internal val localDatabaseFile: File
        get() = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    // The four values that are facts about THIS build and THIS database are supplied here rather
    // than defaulted in the state: a default would render a settings pane reporting a version and a
    // schema the app never read.
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            currentDatabaseSchemaVersion = DatabaseHealthChecker.CURRENT_VERSION,
            minimumSupportedSchemaVersion = DatabaseHealthChecker.MIN_MIGRATABLE_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            buildNumber = BuildConfig.VERSION_CODE,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Whether this is the F-Droid channel build (disables in-app update UI). */
    val isFDroidBuild: Boolean = FdroidBuildCheck.isFDroidBuild()

    private val _navigateToDatabaseInit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToDatabaseInit: SharedFlow<Unit> = _navigateToDatabaseInit.asSharedFlow()

    // Held across the passphrase-prompt gate for encrypted DB imports from Settings
    // Managed by extension functions in SettingsEncryptedImportSupport.kt
    internal var pendingEncryptedImportDbFile: File? = null
    internal var pendingEncryptedImportBackupMappings: List<Pair<File, File>> = emptyList()

    // Auto-download state
    private var activeDownloadId: Long? = null

    internal fun updateUiState(transform: (SettingsUiState) -> SettingsUiState) {
        val from = _uiState.value
        val to = transform(from)
        _uiState.update { to }
        // Trace update-flow state transitions (only when download/check fields change).
        if (from.downloadState != to.downloadState ||
            from.isCheckingForUpdate != to.isCheckingForUpdate ||
            from.updateCheckResult != to.updateCheckResult
        ) {
            logger.d(
                "SettingsViewModel.updateState",
                "Update-flow state transition",
                mapOf(
                    "downloadState" to (from.downloadState::class.simpleName + " -> " + to.downloadState::class.simpleName),
                    "checking" to (from.isCheckingForUpdate.toString() + " -> " + to.isCheckingForUpdate.toString()),
                    "updateOutcome" to ((from.updateCheckResult?.outcome?.name ?: "none") + " -> " + (to.updateCheckResult?.outcome?.name ?: "none")),
                    "latestVersion" to (to.updateCheckResult?.latestVersionName ?: "none"),
                ),
            )
        }
    }

    init {
        logger.i("SettingsViewModel.init", "ViewModel initialized")
        loadDatabaseStats()
        syncTimeoutFromDb()
        loadPromptInstall()
        loadAutoDownload()
        loadWifiOnly()
        loadAutoCheck()
    }

    /** Load the persisted auto-download toggle (defaults to OFF). */
    private fun loadAutoDownload() {
        scope.launch {
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.AUTO_DOWNLOAD)
            val enabled = raw == "true"
            logger.d("SettingsViewModel.loadAutoDownload", "Loaded toggle", mapOf("enabled" to enabled, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(autoDownloadEnabled = enabled) }
            // If a download was in-flight from a previous session, restore its state.
            if (enabled) restoreDownloadState()
        }
    }

    /** User tapped Cancel while a download is in flight — cancel + clean persisted state. */
    internal fun onCancelDownload() {
        val id = activeDownloadId ?: return
        scope.launch {
            AutoDownloadManager.cancel(context, id)
            activeDownloadId = null
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, null)
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL, null)
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_FILE, null)
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_SHA256_URL, null)
            logger.i("SettingsViewModel.onCancelDownload", "Download cancelled", mapOf("downloadId" to id))
            _uiState.update { it.copy(downloadState = DownloadUiState.Idle) }
        }
    }

    /** Toggle auto-download on/off; toggling off cancels any in-flight download. */
    internal fun onAutoDownloadToggled(enabled: Boolean) {
        scope.launch {
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
        scope.launch {
            val storedId = appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID)?.toLongOrNull()
            if (storedId != null) {
                activeDownloadId = storedId
                pollDownloadProgress()
                return@launch
            }
            // In-flight download done (or none): check for a previously COMPLETED
            // download still on disk. If it's fresh (< 15 min) restore the
            // "Downloaded — install now" state so the user never re-downloads
            // what's already there. If stale, surface Retry/Check instead.
            restoreCompletedDownload()
        }
    }

    /** Offer a previously completed download from disk instead of re-downloading. */
    private fun restoreCompletedDownload() {
        scope.launch {
            val version = appSettingsRepository.getSetting(UpdatePrefKeys.LAST_DOWNLOADED_VERSION)
            val fileName = appSettingsRepository.getSetting(UpdatePrefKeys.LAST_DOWNLOADED_FILE)
            val atMs = appSettingsRepository.getSetting(UpdatePrefKeys.LAST_DOWNLOADED_AT)?.toLongOrNull()
            if (version.isNullOrEmpty() || fileName.isNullOrEmpty()) return@launch
            // The marker holds a version, so the file it recorded must declare that same version.
            // A marker from the old build-number scheme matches no version and is cleared, never offered.
            if (!isApkOfVersion(fileName, version)) {
                logger.d("SettingsViewModel.restoreCompletedDownload", "Completed download marker is not this file's version; clearing markers", mapOf("fileName" to fileName, "version" to version))
                clearCompletedDownloadMarkers()
                return@launch
            }
            val localPath = AutoDownloadManager.findDownloadedApk(context, fileName)
            if (localPath == null) {
                // File was cleaned/removed — drop the stale markers.
                logger.d("SettingsViewModel.restoreCompletedDownload", "Completed download file missing; clearing markers", mapOf("fileName" to fileName))
                clearCompletedDownloadMarkers()
                return@launch
            }
            val fresh = atMs != null && (System.currentTimeMillis() - atMs) < COMPLETED_DOWNLOAD_FRESH_MS
            if (fresh) {
                logger.d("SettingsViewModel.restoreCompletedDownload", "Fresh completed download restored", mapOf("fileName" to fileName, "version" to version))
                _uiState.update { it.copy(downloadState = DownloadUiState.Downloaded(fileName, localPath)) }
            } else {
                // Stale (>15 min): drop to Idle so the button shows "Check for update".
                // A fresh check will re-discover the APK if it's still the latest.
                logger.d("SettingsViewModel.restoreCompletedDownload", "Completed download is stale; reverting to check", mapOf("fileName" to fileName, "version" to version))
                appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL, null)
                appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_SHA256_URL, null)
            }
        }
    }

    /** Drop the completed-download markers; the next check re-discovers whatever is still current. */
    private suspend fun clearCompletedDownloadMarkers() {
        appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_VERSION, null)
        appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_FILE, null)
        appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_AT, null)
    }

    /** Load the persisted prompt-install toggle (defaults to OFF). */
    private fun loadPromptInstall() {
        scope.launch {
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.PROMPT_INSTALL)
            val enabled = raw == "true"
            logger.d("SettingsViewModel.loadPromptInstall", "Loaded toggle", mapOf("enabled" to enabled, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(promptInstallEnabled = enabled) }
        }
    }

    /** Toggle prompt-install on/off (only meaningful when auto-download is ON). */
    internal fun onPromptInstallToggled(enabled: Boolean) {
        scope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.PROMPT_INSTALL, enabled.toString())
            logger.i("SettingsViewModel.onPromptInstallToggled", "Toggle saved", mapOf("enabled" to enabled))
            _uiState.update { it.copy(promptInstallEnabled = enabled) }
        }
    }

    /** Load the persisted WiFi-only toggle (defaults to OFF). */
    private fun loadWifiOnly() {
        scope.launch {
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.WIFI_ONLY)
            val enabled = raw == "true"
            logger.d("SettingsViewModel.loadWifiOnly", "Loaded toggle", mapOf("enabled" to enabled, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(wifiOnlyEnabled = enabled) }
        }
    }

    /** Toggle WiFi-only downloads on/off. */
    internal fun onWifiOnlyToggled(enabled: Boolean) {
        scope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.WIFI_ONLY, enabled.toString())
            logger.i("SettingsViewModel.onWifiOnlyToggled", "Toggle saved", mapOf("enabled" to enabled))
            _uiState.update { it.copy(wifiOnlyEnabled = enabled) }
        }
    }

    /** Load the persisted auto-check-on-start setting (defaults OFF — opt-in). */
    private fun loadAutoCheck() {
        scope.launch {
            val raw = appSettingsRepository.getSetting(UpdatePrefKeys.AUTO_CHECK)
            val enabled = raw == "true"
            logger.d("SettingsViewModel.loadAutoCheck", "Loaded toggle", mapOf("enabled" to enabled, "raw" to (raw ?: "null")))
            _uiState.update { it.copy(autoCheckEnabled = enabled) }
        }
    }

    /** Toggle the post-unlock auto update check on/off. */
    internal fun onAutoCheckToggled(enabled: Boolean) {
        scope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.AUTO_CHECK, enabled.toString())
            logger.i("SettingsViewModel.onAutoCheckToggled", "Toggle saved", mapOf("enabled" to enabled))
            _uiState.update { it.copy(autoCheckEnabled = enabled) }
        }
    }

    /**
     * User tapped "Update now" in the popup → verify the download, then launch the system installer.
     *
     * Fail-closed: only [ChecksumVerdict.Verified] may reach the installer. Every other verdict keeps
     * the downloaded file in place and surfaces its own user-visible message (see [installBlockKey]).
     */
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
        scope.launch {
            val sha256Url = appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_SHA256_URL)
            val verdict = checksumVerifier.verify(file, sha256Url)
            logger.i("SettingsViewModel.onInstallNow", "Install gate verdict", mapOf("verdict" to verdict.logName()))
            val blockKey = verdict.installBlockKey()
            if (blockKey != null) {
                // Dismiss the popup and show the reason; the APK itself is left untouched.
                _uiState.update {
                    it.copy(pendingInstallPath = null, downloadState = DownloadUiState.Failed(blockKey))
                }
                return@launch
            }
            launchInstaller(file)
        }
    }

    /** Hand [file] to the system installer once it has passed the checksum gate. */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    private fun launchInstaller(file: File) {
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
            logger.e("SettingsViewModel.launchInstaller", "Install launch failed", e)
            _uiState.update { it.copy(pendingInstallPath = null, downloadState = DownloadUiState.Failed("install_launch_failed")) }
        }
    }

    /** User dismissed the update popup ("Later") — keep the downloaded file. */
    internal fun onInstallLater() {
        _uiState.update { it.copy(pendingInstallPath = null) }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    private fun loadDatabaseStats() {
        logger.d("SettingsViewModel.loadDatabaseStats", "Loading database stats")
        scope.launch {
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
                val dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
                val (sizeKb, databaseArtifacts) = withContext(Dispatchers.IO) {
                    val files = listDatabaseArtifactFiles(localDatabaseFile).filter { it.exists() }
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
            }
        }
    }

    /**
     * Public wrapper for exporting the database to [destinationUri] (delegates
     * to [exportDatabase]) so the UI can bind a one-tap export action.
     */
    fun exportData(
        destinationUri: Uri,
    ) {
        exportDatabase(destinationUri)
    }

    /**
     * Public wrapper for importing a database from [sourceUri] (delegates to the
     * extension-based import pipeline) so the UI can bind a one-tap import.
     */
    fun importData(
        sourceUri: Uri,
    ) {
        importDatabase(
            sourceUri.toString(),
            context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME),
            AndroidDatabaseEncryptionProbe(context),
            uiStringsFor(context),
            AndroidDocumentSource(context),
            appDatabaseInitWalConsolidator(context, logger),
        )
    }

    /**
     * Exports the live encrypted database to [destinationUri] via the backup
     * coordinator, reporting success/failure into [_uiState], then refreshes
     * stats.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun exportDatabase(
        destinationUri: Uri,
    ) {
        scope.launch {
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

    /**
     * Imports a uHabits database export from [sourceUri], upserting habits and
     * their repetitions, then refreshes stats. Reports progress/errors into
     * [_uiState].
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun importUhabitsData(sourceUri: Uri) {
        logger.i("SettingsViewModel.importUhabitsData", "uHabits import started", mapOf("sourceUri" to sourceUri.toString()))
        scope.launch {
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

    /**
     * Bulk-tags every uHabits-imported task with [targetDimensionId] /
     * [targetDimensionLabel], so imported habits appear under the chosen life
     * dimension. Reports the mapped count into [_uiState].
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun bulkMapImportedHabitsToDimension(targetDimensionId: String, targetDimensionLabel: String) {
        scope.launch {
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

    /**
     * Builds the timestamped filename used when exporting the encrypted database
     * (e.g. `payanam_backup_encrypted_android_1.0.0_abc1234_debug_YYYYMMDD_HHMMSS.db`).
     */
    fun generateExportFileName(): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return dbExportName(
            "android",
            BuildConfig.VERSION_NAME,
            BuildConfig.BUILD_SHORT_SHA,
            BuildConfig.DEBUG,
            timestamp,
        )
    }

    /**
     * Opens the "delete database" confirmation prompt (sets
     * [SettingsUiState.showDeleteExportPrompt]).
     */
    fun requestDeleteDatabase() {
        logger.i("SettingsViewModel.requestDeleteDatabase", "Delete database flow initiated")
        _uiState.update { it.copy(showDeleteExportPrompt = true) }
    }

    /**
     * Dismisses the "delete database" confirmation prompt without deleting.
     */
    fun dismissDeleteExportPrompt() {
        _uiState.update { it.copy(showDeleteExportPrompt = false) }
    }

    /**
     * Confirms deletion: wipes every database artifact file, then signals the app
     * to restart into the DB-init flow.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun deleteDatabase() {
        logger.i("SettingsViewModel.deleteDatabase", "Delete database confirmed — wiping all artifacts")
        _uiState.update { it.copy(showDeleteExportPrompt = false) }
        scope.launch {
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
            }
        }
    }

    /**
     * Deletes a single database artifact by [fileName] (e.g. a stray WAL/SHM
     * file) and refreshes stats.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun deleteDatabaseArtifact(fileName: String) {
        logger.i(
            "SettingsViewModel.deleteDatabaseArtifact",
            "Delete database artifact requested",
            mapOf("fileName" to fileName),
        )
        scope.launch {
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

    /**
     * Deletes stale/crashed database artifacts left by interrupted import or
     * encrypt flows, then refreshes stats.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun cleanStaleArtifacts() {
        logger.i("SettingsViewModel.cleanStaleArtifacts", "Stale artifact cleanup requested")
        scope.launch {
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

    /**
     * Clears the last export result so the success/failure snackbar dismisses.
     */
    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }

    /**
     * Clears the last import result so the success/failure snackbar dismisses.
     */
    fun clearImportResult() {
        _uiState.update { it.copy(importResult = null) }
    }

    /**
     * Clears the uHabits import result so its result UI dismisses.
     */
    fun clearUhabitsImportResult() {
        _uiState.update { it.copy(uhabitsImportResult = null) }
    }

    /**
     * Clears the bulk habit-mapping result so its result UI dismisses.
     */
    fun clearBulkHabitMappingResult() {
        _uiState.update { it.copy(bulkHabitMappingResult = null) }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    private fun syncTimeoutFromDb() {
        scope.launch {
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

    /**
     * Persists the auto-lock timeout as [minutes] (clamping via the manager) and
     * mirrors it into settings storage and [_uiState].
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun updateUnlockSessionTimeoutMinutes(minutes: Int) {
        databaseEncryptionManager.setSessionTimeoutMinutes(minutes)
        val effectiveMinutes = databaseEncryptionManager.getSessionTimeoutMinutes()
        _uiState.update { it.copy(unlockSessionTimeoutMinutes = effectiveMinutes) }
        scope.launch {
            try {
                appSettingsRepository.setSetting("session_timeout_minutes", effectiveMinutes.toString())
            } catch (e: Exception) {
                logger.e("SettingsViewModel.updateUnlockSessionTimeoutMinutes", "Failed to persist timeout to DB", e)
            }
        }
    }

    /**
     * Turns biometric unlock off: tears down the Keystore-wrapped passphrase and
     * flips the preference (mirrored into [_uiState]).
     */
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

    /**
     * Enables biometric unlock: verifies [passphrase], confirms the device can
     * authenticate, then runs the system biometric prompt and — on success —
     * stores the biometric-wrapped passphrase.
     */
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
        scope.launch {
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
                /**
                 * Fired when the biometric prompt authenticates successfully:
                 * stores the wrapped passphrase and enables the preference.
                 */
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
                    scope.launch {
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

                /**
                 * Fired when the biometric prompt is dismissed with a hard error
                 * (e.g. timeout, negative button): reports failure to the caller.
                 */
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    logger.w(
                        "SettingsViewModel.enableBiometricUnlockWithVerification",
                        "Biometric enable verification error",
                        mapOf("errorCode" to errorCode, "errString" to errString.toString()),
                    )
                    onComplete(false)
                }

                /**
                 * Fired on a non-fatal unrecognized attempt (a wrong finger); the
                 * prompt stays open for retry.
                 */
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

    /**
     * Manually checks the releases list for a newer version, honouring the 1-minute
     * cooldown and 5-checks-per-5-minute rate limit, then surfaces the result and
     * kicks off auto-download when applicable.
     */
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
        scope.launch {
            val result = UpdateChecker.check(_uiState.value.buildNumber, ApkBuildType.running())
            logger.i(
                "SettingsViewModel.checkForUpdate",
                "Update check complete",
                mapOf(
                    "outcome" to result.outcome.name,
                    "latestVersion" to (result.latestVersionName ?: "none"),
                    "error" to result.error?.name,
                ),
            )
            _uiState.update {
                it.copy(isCheckingForUpdate = false, updateCheckResult = result)
            }
            // Auto-download when update available + toggle ON + no active download.
            if (result.outcome == UpdateOutcome.UPDATE_AVAILABLE && _uiState.value.autoDownloadEnabled && activeDownloadId == null) {
                val latest = result.latestVersionName ?: return@launch
                startAutoDownload(latest)
            }
        }
    }

    /** Enqueue the APK download for the given release version and start progress polling. */
    private fun startAutoDownload(versionName: String) {
        // Already on disk? Offer Install instead of re-downloading (avoids the
        // duplicate-download case: check → downloaded → killed → re-check).
        val existingPath = AutoDownloadManager.findApkForBuild(context, versionName)
        if (existingPath != null) {
            val fileName = File(existingPath).name
            logger.d("SettingsViewModel.startAutoDownload", "APK already downloaded; offering install", mapOf("version" to versionName, "file" to fileName))
            scope.launch {
                appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_VERSION, versionName)
                appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_FILE, fileName)
                appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_AT, System.currentTimeMillis().toString())
                persistSha256UrlIfKnown()
            }
            _uiState.update { it.copy(downloadState = DownloadUiState.Downloaded(fileName, existingPath)) }
            return
        }
        // Real asset URL + filename come from the check result's selected release asset.
        val downloadUrl = _uiState.value.updateCheckResult?.apkDownloadUrl
        if (downloadUrl.isNullOrEmpty()) {
            // E1: no silent failure — surface a state so the user knows why
            // the download did not start.
            logger.w("SettingsViewModel.startAutoDownload", "No APK asset URL in check result", mapOf("version" to versionName))
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
        scope.launch {
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, id.toString())
            // Persist the URL so a Retry survives app restarts.
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL, downloadUrl)
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_FILE, fileName)
            // ...and the checksum sidecar URL, so the install gate can verify this download
            // even after a restart wipes the in-memory check result.
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_SHA256_URL, _uiState.value.updateCheckResult?.apkSha256Url)
        }
        logger.i("SettingsViewModel.startAutoDownload", "Auto-download started", mapOf("version" to versionName, "file" to fileName, "downloadId" to id))
        pollDownloadProgress()
    }

    /**
     * Remember the published checksum URL when the in-memory check result still carries one. Never
     * clears: a stored value already describes the file on disk, and clearing it would turn a
     * verifiable download into an unconfirmable one after a restart.
     */
    private suspend fun persistSha256UrlIfKnown() {
        val sha256Url = _uiState.value.updateCheckResult?.apkSha256Url
        if (!sha256Url.isNullOrBlank()) {
            appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_SHA256_URL, sha256Url)
        }
    }

    /** Manual "Download update" / Retry entry point (single-button state machine). */
    internal fun downloadOrRetry() {
        val state = _uiState.value.downloadState
        // Retry: reuse the last known URL if the check result is gone.
        if (state is DownloadUiState.Failed) {
            scope.launch {
                val storedUrl = appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL)
                if (storedUrl.isNullOrEmpty()) return@launch
                val fileName = storedUrl.substringAfterLast('/')
                // Fail-closed: the persisted URL may predate an artifact/type
                // change. A stored name that does not carry this install's
                // `_<type>_` can never install over it — clear it, log, and
                // surface a user-visible failure (no silent no-op).
                if (!artifactNameMatchesBuildType(fileName, ApkBuildType.running())) {
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL, null)
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_SHA256_URL, null)
                    logger.i(
                        "SettingsViewModel.downloadOrRetry",
                        "Persisted download URL failed build-type validation; cleared",
                        mapOf("file" to fileName, "runningType" to ApkBuildType.running()),
                    )
                    _uiState.update { it.copy(downloadState = DownloadUiState.Failed("type_mismatch")) }
                    return@launch
                }
                _uiState.update { it.copy(downloadState = DownloadUiState.Idle) }
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
                    persistSha256UrlIfKnown()
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
        val version = result.latestVersionName ?: return
        if (result.outcome == UpdateOutcome.UPDATE_AVAILABLE && activeDownloadId == null) {
            // STALE-URL GUARD: the cached check result can be minutes/hours old
            // (releases keep being published). Re-fetch the current release so we
            // always download what is latest NOW, never a superseded version. The
            // retry path above uses the persisted URL because a restart may have no
            // cached result at all.
            scope.launch {
                val fresh = UpdateChecker.check(BuildConfig.VERSION_CODE, ApkBuildType.running())
                if (fresh.outcome == UpdateOutcome.FAILED) {
                    _uiState.update { it.copy(downloadState = DownloadUiState.Failed("refresh_failed")) }
                    return@launch
                }
                // Classify the outcome before reading the URL: only UPDATE_AVAILABLE carries an
                // APK asset for this build type. Every other outcome has no URL by definition,
                // so it publishes as itself and never renders as a download failure.
                if (fresh.outcome != UpdateOutcome.UPDATE_AVAILABLE) {
                    // The release list moved past us: no update to download anymore, so the URL
                    // and checksum of the previous artifact no longer describe a downloadable file.
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL, null)
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_SHA256_URL, null)
                    _uiState.update { it.copy(updateCheckResult = fresh) }
                    return@launch
                }
                val url = fresh.apkDownloadUrl
                if (url.isNullOrEmpty()) {
                    // Fail closed: an offered update whose release ships no installable APK
                    // cannot download, while the outcome itself stays UPDATE_AVAILABLE.
                    _uiState.update { it.copy(updateCheckResult = fresh, downloadState = DownloadUiState.Failed("no_download_url")) }
                    return@launch
                }
                logger.i(
                    "SettingsViewModel.downloadOrRetry",
                    "Manual download re-fetched releases",
                    mapOf(
                        "cachedVersion" to version,
                        "freshVersion" to (fresh.latestVersionName ?: "none"),
                        "file" to url.substringAfterLast('/'),
                    ),
                )
                // Publish the fresh read before enqueueing: the download must use the
                // asset URL of the release this re-check just found, never the cached one.
                _uiState.update { it.copy(updateCheckResult = fresh) }
                startAutoDownload(fresh.latestVersionName ?: version)
            }
        }
    }

    /** Poll DownloadManager progress and surface state; stops on terminal state. */
    private fun pollDownloadProgress() {
        val id = activeDownloadId ?: return
        scope.launch {
            var terminal = false
            var polls = 0
            // E4: bounded polling — stop after 10 minutes even if the system
            // never reports a terminal state (avoids infinite battery drain).
            while (!terminal && activeDownloadId == id && polls < MAX_POLLS) {
                polls++
                val state = AutoDownloadManager.queryProgress(context, id)
                // Enrich Downloading with the full build name for the UI.
                val enriched = if (state is DownloadUiState.Downloading) {
                    state.copy(
                        buildName = appSettingsRepository.getSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_FILE) ?: state.fileName,
                    )
                } else {
                    state
                }
                _uiState.update { it.copy(downloadState = enriched) }
                when (state) {
                    is DownloadUiState.Downloading, is DownloadUiState.Paused -> {
                        // keep polling (Paused is transient — system resumes)
                    }

                    is DownloadUiState.Downloaded -> {
                        appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, null)
                        // Remember the completed download so a later restart can
                        // offer "Install" without re-downloading (see restoreDownloadState).
                        appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_VERSION, versionFromFileName(state.fileName))
                        appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_FILE, state.fileName)
                        appSettingsRepository.setSetting(UpdatePrefKeys.LAST_DOWNLOADED_AT, System.currentTimeMillis().toString())
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
        private const val CHECK_COOLDOWN_MS = 60_000L // 1 minute between checks
        private const val MAX_CHECKS_PER_WINDOW = 5 // max 5 checks
        private const val RATE_WINDOW_MS = 5 * 60_000L // 5-minute window
        private const val POLL_INTERVAL_MS = 1_000L // progress poll cadence
        private const val MAX_POLLS = 600 // 10 min cap (E4)

        /** A completed download is "fresh" for 15 min — after that, re-check first. */
        private const val COMPLETED_DOWNLOAD_FRESH_MS = 15 * 60 * 1_000L
    }
}
