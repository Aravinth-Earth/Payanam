//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import javax.inject.Inject

sealed class RestoreResult {
    object RestoredOk : RestoreResult()
    object RestoreFailed : RestoreResult()
}

data class DatabaseInitUiState(
    val isChecking: Boolean = true,
    val databaseExists: Boolean = false,
    val databaseCorrupted: Boolean = false,
    val bootIssue: DatabaseBootIssue? = null,
    val corruptionMessage: String? = null,
    val databaseSizeKB: Long = 0,
    val lastModified: Long? = null,
    val taskCount: Int = 0,
    val timeEntryCount: Int = 0,
    val journeyEntryCount: Int = 0,
    val noteCount: Int = 0,
    val databaseCreated: Long? = null,
    val databaseSchemaVersion: Int = 0,
    val isCreating: Boolean = false,
    val isImporting: Boolean = false,
    val errorMessage: String? = null,
    val showCreateNewWipeConfirm: Boolean = false,
    val showImportWipeConfirm: Boolean = false,
    val restoreResult: RestoreResult? = null,
    val awaitingImportPassphrase: Boolean = false,
    val importPassphraseError: String? = null,
    val awaitingDimensionSetup: Boolean = false,
)

enum class DatabaseBootIssueType {
    SIDECAR_PRIMARY_MISSING,
    DB_TOO_OLD,
    DB_TOO_NEW,
    SCHEMA_INVALID,
    OPEN_FAILED,
    REPAIRABLE_GENERIC,
    NON_REPAIRABLE_GENERIC,
}

data class DatabaseBootIssue(
    val type: DatabaseBootIssueType,
    val detailMessage: String? = null,
    val detectedVersion: Int = 0,
)

@HiltViewModel
class DatabaseInitViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val databaseEncryptionManager: DatabaseEncryptionManager,
    private val databaseSessionManager: DatabaseSessionManager,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(DatabaseInitUiState())
    val uiState: StateFlow<DatabaseInitUiState> = _uiState.asStateFlow()

    private var pendingImportDbFile: File? = null
    private var pendingImportBackupMappings: List<Pair<File, File>> = emptyList()
    private var pendingImportTempBackupDir: File? = null
    private var pendingImportUri: Uri? = null
    private var pendingImportOnSuccess: (() -> Unit)? = null
    private var pendingCreatePassphrase: String? = null
    private var pendingCreateNeedsWipe: Boolean = false
    internal val settingsRepository: AppSettingsRepository get() = appSettingsRepository
    internal val importDbFile: File? get() = pendingImportDbFile

    internal fun clearPendingImport() {
        pendingImportDbFile = null
        pendingImportBackupMappings = emptyList()
        pendingImportTempBackupDir = null
        pendingImportUri = null
        pendingImportOnSuccess = null
    }

    private fun clearPendingCreate() {
        pendingCreatePassphrase = null
        pendingCreateNeedsWipe = false
    }

    private fun breadcrumb(stage: String, data: Map<String, Any?>? = null) {
        CrashSafeBreadcrumbs.record(
            context = context,
            source = "DatabaseInitViewModel",
            stage = stage,
            data = data,
        )
    }

    init {
        logger.i("DatabaseInitViewModel.init", "Checking database status")
        checkDatabaseStatus()
    }

    private suspend fun detectBootstrapPlaceholder(dbFile: java.io.File): Boolean {
        val isEncrypted = databaseEncryptionManager.isEncryptionEnabled()
        if (isEncrypted) return false
        val databaseInitCompleted = readDatabaseInitCompletedFlag(dbFile)
        val counts = withContext(Dispatchers.IO) {
            val countMap = DatabaseEncryptionMigrationSupport.readTableCounts(
                context = context,
                databaseFile = dbFile,
                passphrase = null,
                tableNames = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes"),
            )
            DatabaseTableCounts(
                taskCount = countMap["tasks"] ?: 0,
                timeEntryCount = countMap["time_entries"] ?: 0,
                journalEntryCount = (countMap["day_journal_entries"] ?: 0) + (countMap["journal_notes"] ?: 0),
                noteCount = countMap["notes"] ?: 0,
            )
        }
        logger.i(
            "DatabaseInitViewModel.detectBootstrapPlaceholder",
            "Database counts",
            mapOf(
                "taskCount" to counts.taskCount,
                "timeEntryCount" to counts.timeEntryCount,
                "journalEntryCount" to counts.journalEntryCount,
                "noteCount" to counts.noteCount,
            ),
        )
        val hasUserData = counts.taskCount > 0 || counts.timeEntryCount > 0 ||
            counts.journalEntryCount > 0 || counts.noteCount > 0
        return !databaseInitCompleted && !hasUserData
    }

    private fun checkDatabaseStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }

            try {
                val healthResult = DatabaseHealthChecker.checkDatabaseHealth(context, null)

                logger.i(
                    "DatabaseInitViewModel.checkDatabaseStatus",
                    "Health check complete",
                    mapOf(
                        "isHealthy" to healthResult.isHealthy,
                        "needsRepair" to healthResult.needsRepair,
                        "errorMessage" to (healthResult.errorMessage ?: "N/A"),
                    ),
                )

                if (!healthResult.isHealthy) {
                    val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
                    val exists = DatabaseHealthChecker.hasDatabaseArtifacts(context)

                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            databaseExists = exists,
                            databaseCorrupted = exists && healthResult.needsRepair,
                            bootIssue = classifyBootIssue(exists, healthResult),
                            corruptionMessage = healthResult.errorMessage,
                            databaseSizeKB = if (exists) dbFile.length() / 1024 else 0,
                            errorMessage = null,
                        )
                    }
                    return@launch
                }

                val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
                val exists = DatabaseHealthChecker.hasDatabaseArtifacts(context)

                if (exists) {
                    val sizeKB = dbFile.length() / 1024
                    val lastModified = dbFile.lastModified()

                    logger.i(
                        "DatabaseInitViewModel.checkDatabaseStatus",
                        "Database file info",
                        mapOf("sizeKB" to sizeKB, "lastModified" to lastModified, "lastModifiedDate" to Date(lastModified).toString()),
                    )

                    val isBootstrapPlaceholder = detectBootstrapPlaceholder(dbFile)
                    if (isBootstrapPlaceholder) {
                        logger.w(
                            "DatabaseInitViewModel.checkDatabaseStatus",
                            "Detected bootstrap placeholder DB created before init choice; treating as no existing user database",
                            mapOf(
                                "sizeKB" to sizeKB,
                                "lastModified" to lastModified,
                            ),
                        )
                    }

                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            databaseExists = !isBootstrapPlaceholder,
                            databaseCorrupted = false,
                            bootIssue = null,
                            corruptionMessage = null,
                            databaseSizeKB = if (isBootstrapPlaceholder) 0 else sizeKB,
                            lastModified = if (isBootstrapPlaceholder) null else lastModified,
                            taskCount = 0,
                            timeEntryCount = 0,
                            journeyEntryCount = 0,
                            noteCount = 0,
                            databaseSchemaVersion = healthResult.currentVersion,
                            errorMessage = null,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            databaseExists = false,
                            databaseCorrupted = false,
                            bootIssue = null,
                            corruptionMessage = null,
                        )
                    }
                }
            } catch (e: Exception) {
                logger.e("DatabaseInitViewModel.checkDatabaseStatus", "Failed to check database", e)
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        databaseExists = false,
                        databaseCorrupted = false,
                        bootIssue = null,
                        errorMessage = "Error checking database: ${e.message}",
                    )
                }
            }
        }
    }

    fun retryDatabaseStatusCheck() {
        logger.i("DatabaseInitViewModel.retryDatabaseStatusCheck", "Retrying database status check")
        checkDatabaseStatus()
    }

    fun createNewDatabase(passphrase: String) {
        logger.i("DatabaseInitViewModel.createNewDatabase", "Create new database requested")
        val existingFiles = getDatabaseArtifactFiles().filter { it.exists() }
        if (existingFiles.isNotEmpty()) {
            logger.i(
                "DatabaseInitViewModel.createNewDatabase",
                "Existing DB artifacts found; showing wipe confirm",
                mapOf("fileCount" to existingFiles.size),
            )
            _uiState.update { it.copy(showCreateNewWipeConfirm = true) }
            return
        }
        beginMandatoryDimensionSetup(passphrase = passphrase, needsWipe = false)
    }

    fun confirmCreateNew(passphrase: String) {
        logger.i("DatabaseInitViewModel.confirmCreateNew", "User confirmed create new with wipe")
        _uiState.update { it.copy(showCreateNewWipeConfirm = false) }
        beginMandatoryDimensionSetup(passphrase = passphrase, needsWipe = true)
    }

    fun cancelCreateNewWipe() {
        logger.i("DatabaseInitViewModel.cancelCreateNewWipe", "User cancelled create new wipe confirm")
        _uiState.update { it.copy(showCreateNewWipeConfirm = false) }
    }

    fun dismissRestoreResult() {
        _uiState.update { it.copy(restoreResult = null) }
        checkDatabaseStatus()
    }

    private fun beginMandatoryDimensionSetup(passphrase: String, needsWipe: Boolean) {
        pendingCreatePassphrase = passphrase
        pendingCreateNeedsWipe = needsWipe
        logger.i(
            "DatabaseInitViewModel.beginMandatoryDimensionSetup",
            "Passphrase accepted; waiting for mandatory dimension setup before DB creation",
            mapOf("needsWipe" to needsWipe),
        )
        _uiState.update {
            it.copy(
                isCreating = false,
                errorMessage = null,
                restoreResult = null,
                awaitingDimensionSetup = true,
            )
        }
    }

    fun completeNewDatabaseDimensionSetup(
        dimensionInputs: List<NewDatabaseDimensionInput>,
        onSuccess: () -> Unit,
    ) {
        logger.i(
            "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
            "Persisting mandatory life-dimension setup",
            mapOf(
                "inputCount" to dimensionInputs.size,
                "hasPendingPassphrase" to (pendingCreatePassphrase != null),
                "pendingNeedsWipe" to pendingCreateNeedsWipe,
            ),
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, errorMessage = null) }
            var tempBackupDir: File? = null
            try {
                withContext(Dispatchers.IO) {
                    val createPassphrase = pendingCreatePassphrase
                        ?: throw IllegalStateException("Missing pending passphrase. Please set passphrase again.")
                    if (pendingCreateNeedsWipe) {
                        val existingFiles = getDatabaseArtifactFiles().filter { it.exists() }
                        if (existingFiles.isNotEmpty()) {
                            tempBackupDir = createSidecarSafeTempBackup()
                            if (tempBackupDir == null) {
                                throw IllegalStateException("Could not create a safe backup of the current database before wiping.")
                            }
                            logger.i(
                                "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                                "Temp backup created before wipe",
                                mapOf("dir" to tempBackupDir!!.absolutePath),
                            )
                            deleteAllDatabaseFiles()
                        }
                    }
                    val configured = databaseEncryptionManager.configurePassphrase(createPassphrase)
                    if (!configured) throw IllegalStateException("Failed to configure passphrase for new database")
                    val openResult = databaseSessionManager.openDatabase(createPassphrase)
                    openResult.getOrElse { throw IllegalStateException("Failed to open new DB session: ${it.message}", it) }
                    persistNewDatabaseDimensionSetup(
                        context = context,
                        databaseSessionManager = databaseSessionManager,
                        appSettingsRepository = appSettingsRepository,
                        dimensionInputs = dimensionInputs,
                    )
                    tempBackupDir?.let { deleteTempBackup(it) }
                    clearPendingCreate()
                }
                logger.i(
                    "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                    "Dimension setup saved and DB init marked complete",
                )
                _uiState.update { it.copy(isCreating = false, awaitingDimensionSetup = false) }
                onSuccess()
            } catch (e: Exception) {
                logger.e(
                    "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                    "Failed to persist mandatory dimension setup",
                    e,
                )
                val restored = withContext(Dispatchers.IO) {
                    databaseEncryptionManager.resetEncryptionState()
                    databaseSessionManager.closeDatabase()
                    val dir = tempBackupDir
                    if (dir != null) restoreFromTempBackup(dir) else false
                }
                // Keep the pending create passphrase so the user can retry the
                // dimension setup without re-entering the passphrase flow.
                logger.i(
                    "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                    "Dimension setup failed; pending passphrase retained for retry",
                    mapOf(
                        "restoredFromBackup" to restored,
                        "pendingPassphraseRetained" to (pendingCreatePassphrase != null),
                    ),
                )
                _uiState.update { it.copy(isCreating = false, errorMessage = e.message) }
                if (tempBackupDir != null) {
                    _uiState.update {
                        it.copy(
                            restoreResult = if (restored) RestoreResult.RestoredOk else RestoreResult.RestoreFailed,
                        )
                    }
                }
            }
        }
    }

    fun importDatabase(sourceUri: Uri, onSuccess: () -> Unit) {
        logger.i(
            "DatabaseInitViewModel.importDatabase",
            "Import database requested",
            mapOf(
                "sourceUri" to sourceUri.toString(),
            ),
        )
        val existingFiles = getDatabaseArtifactFiles().filter { it.exists() }
        if (existingFiles.isNotEmpty()) {
            logger.i(
                "DatabaseInitViewModel.importDatabase",
                "Existing DB artifacts found; showing wipe confirm",
                mapOf("fileCount" to existingFiles.size),
            )
            pendingImportUri = sourceUri
            pendingImportOnSuccess = onSuccess
            _uiState.update { it.copy(showImportWipeConfirm = true) }
            return
        }
        executeImportDatabase(sourceUri, onSuccess)
    }

    fun confirmImportAfterWipe(onSuccess: () -> Unit) {
        logger.i("DatabaseInitViewModel.confirmImportAfterWipe", "User confirmed import with wipe")
        _uiState.update { it.copy(showImportWipeConfirm = false) }
        val uri = pendingImportUri ?: return
        val cb = pendingImportOnSuccess ?: onSuccess
        pendingImportUri = null
        pendingImportOnSuccess = null
        executeImportDatabase(uri, cb)
    }

    fun cancelImportWipe() {
        logger.i("DatabaseInitViewModel.cancelImportWipe", "User cancelled import wipe confirm")
        pendingImportUri = null
        pendingImportOnSuccess = null
        _uiState.update { it.copy(showImportWipeConfirm = false) }
    }

    private sealed class ImportIOResult {
        data class NeedsPassphrase(val dbFile: File, val tempBackupDir: File?) : ImportIOResult()
        data class Completed(val dbFile: File, val passphrase: String?) : ImportIOResult()
        data class Failed(
            val cause: Throwable,
            val restoreAttempted: Boolean,
            val restoreSucceeded: Boolean,
        ) : ImportIOResult()
    }

    /**
     * Performs the IO-intensive portion of database import: backup, copy, validate,
     * optionally re-encrypt, health-check, and session-open.
     * Returns a sealed result so the caller can update UI state without
     * deeply-nested try/catch/finally control flow.
     */
    private suspend fun executeImportIO(
        sourceUri: Uri,
        dbFile: File,
        existingDatabaseFiles: List<File>,
    ): ImportIOResult = withContext(Dispatchers.IO) {
        var tempBackupDir: File? = null
        var result: ImportIOResult = ImportIOResult.Failed(IllegalStateException("unreachable"), false, false)
        try {
            if (existingDatabaseFiles.isNotEmpty()) {
                tempBackupDir = createSidecarSafeTempBackup()
                if (tempBackupDir == null) {
                    throw IllegalStateException("Could not create a safe backup of the current database before importing.")
                }
                breadcrumb(
                    stage = "temp_backup_created",
                    data = mapOf("path" to tempBackupDir!!.absolutePath),
                )
                logger.i(
                    "DatabaseInitViewModel.executeImportIO",
                    "Temp backup created",
                    mapOf("dir" to tempBackupDir!!.absolutePath),
                )
            }

            databaseEncryptionManager.backupEncryptionPrefs()
            breadcrumb(stage = "encryption_prefs_backed_up")
            deleteAllDatabaseFiles()
            breadcrumb(stage = "runtime_artifacts_deleted")

            val copyResult = DatabaseImportSupport.copyDatabaseArtifacts(
                context = context,
                sourceUri = sourceUri,
                targetDatabaseFile = dbFile,
            )
            breadcrumb(
                stage = "import_artifacts_copied",
                data = mapOf(
                    "sourceKind" to copyResult.sourceKind,
                    "primaryFileName" to copyResult.primaryFileName,
                    "companionFilesCopied" to copyResult.companionFilesCopied,
                ),
            )
            logger.i(
                "DatabaseInitViewModel.executeImportIO",
                "Database file copied",
                mapOf(
                    "bytesCopiedKB" to (copyResult.bytesCopied / 1024),
                    "filePath" to dbFile.absolutePath,
                    "sourceKind" to copyResult.sourceKind,
                    "primaryFileName" to copyResult.primaryFileName,
                    "companionFilesCopied" to copyResult.companionFilesCopied,
                ),
            )

            if (!dbFile.exists() || dbFile.length() == 0L) {
                throw Exception(context.getString(io.payanam.R.string.settings_import_error_empty_db))
            }

            DatabaseImportSupport.consolidateWalAfterImport(
                dbFile = dbFile,
                logTag = "DatabaseInitViewModel.executeImportIO",
            )
            breadcrumb(
                stage = "wal_consolidation_done",
                data = mapOf("dbSizeKB" to (dbFile.length() / 1024)),
            )

            val importedDbIsStandardSqlite = DatabaseImportSupport.isStandardSqliteFile(
                databaseFile = dbFile,
                logTag = "DatabaseInitViewModel.executeImportIO",
            )
            if (!importedDbIsStandardSqlite) {
                val isEncrypted = DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(
                    context = context,
                    databaseFile = dbFile,
                    logTag = "DatabaseInitViewModel.executeImportIO",
                )
                if (isEncrypted) {
                    logger.i(
                        "DatabaseInitViewModel.executeImportIO",
                        "Encrypted import detected; pausing and awaiting user passphrase",
                    )
                    breadcrumb(stage = "awaiting_import_passphrase")
                    result = ImportIOResult.NeedsPassphrase(dbFile, tempBackupDir)
                    return@withContext result
                } else {
                    throw IllegalStateException(
                        context.getString(io.payanam.R.string.settings_import_error_unreadable_db),
                    )
                }
            }

            val importedSchemaVersion = DatabaseImportSupport.validateSupportedPlaintextImportSchema(
                context = context,
                databaseFile = dbFile,
                logTag = "DatabaseInitViewModel.executeImportIO",
            )
            breadcrumb(
                stage = "plaintext_schema_gate_done",
                data = mapOf("dbVersion" to importedSchemaVersion),
            )

            val encryptionPassphraseForImport = if (databaseEncryptionManager.isEncryptionEnabled()) {
                runCatching { databaseSessionManager.requireOpenPassphrase() }.getOrElse {
                    throw IllegalStateException("Encrypted mode active but no open passphrase session is available.")
                }
            } else {
                null
            }
            if (encryptionPassphraseForImport != null) {
                DatabaseEncryptionMigrationSupport.ensureEncryptedWithPassphrase(
                    context = context,
                    databaseFile = dbFile,
                    passphrase = encryptionPassphraseForImport,
                    logTag = "DatabaseInitViewModel.executeImportIO",
                )
                breadcrumb(stage = "re_encrypted_with_session_passphrase")
            }

            val postImportHealth = DatabaseHealthChecker.checkDatabaseHealth(
                context = context,
                sqlCipherPassphrase = encryptionPassphraseForImport,
            )
            if (!postImportHealth.isHealthy) {
                throw IllegalStateException(
                    postImportHealth.errorMessage
                        ?: context.getString(io.payanam.R.string.loc_database_needs_repair),
                )
            }

            markDatabaseInitCompletedDirect(dbFile, encryptionPassphraseForImport)
            breadcrumb(stage = "database_init_completed_marked")

            val openPassphrase = encryptionPassphraseForImport ?: ""
            val openResult = databaseSessionManager.openDatabase(openPassphrase)
            openResult.getOrElse { openError ->
                throw IllegalStateException(
                    "Imported DB was finalized but session open failed: ${openError.message}",
                    openError,
                )
            }
            breadcrumb(
                stage = "import_session_opened",
                data = mapOf("passphraseLength" to openPassphrase.length),
            )

            result = ImportIOResult.Completed(dbFile, encryptionPassphraseForImport)
        } catch (e: Exception) {
            logger.e(
                "DatabaseInitViewModel.executeImportIO",
                "Import copy/conversion failed; restoring from temp backup",
                e,
            )
            databaseEncryptionManager.restoreEncryptionPrefs()
            breadcrumb(stage = "import_failure_encryption_prefs_restored")
            val restoreAttempted = tempBackupDir != null
            val restoreSucceeded = tempBackupDir?.let { dir -> restoreFromTempBackup(dir) } ?: false
            breadcrumb(
                stage = "import_failure_restore_attempted",
                data = mapOf(
                    "restoreAttempted" to restoreAttempted,
                    "restoreSucceeded" to restoreSucceeded,
                ),
            )
            logger.i(
                "DatabaseInitViewModel.executeImportIO",
                "Restore attempt completed after import failure",
                mapOf(
                    "restoreAttempted" to restoreAttempted,
                    "restoreSucceeded" to restoreSucceeded,
                ),
            )
            result = ImportIOResult.Failed(e, restoreAttempted, restoreSucceeded)
        } finally {
            if (result !is ImportIOResult.NeedsPassphrase) {
                databaseEncryptionManager.clearEncryptionPrefsBackup()
                tempBackupDir?.let { dir -> deleteTempBackup(dir) }
                breadcrumb(stage = "import_cleanup_completed")
            }
        }
        result
    }

    private fun executeImportDatabase(sourceUri: Uri, onSuccess: () -> Unit) {
        logger.i(
            "DatabaseInitViewModel.executeImportDatabase",
            "Importing database",
            mapOf(
                "sourceUri" to sourceUri.toString(),
            ),
        )
        breadcrumb(
            stage = "import_started",
            data = mapOf("sourceUri" to sourceUri.toString()),
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null, restoreResult = null) }

            val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            val existingDatabaseFiles = getDatabaseArtifactFiles().filter { it.exists() }

            val result = executeImportIO(sourceUri, dbFile, existingDatabaseFiles)

            when (result) {
                is ImportIOResult.NeedsPassphrase -> {
                    pendingImportDbFile = result.dbFile
                    pendingImportTempBackupDir = result.tempBackupDir
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            awaitingImportPassphrase = true,
                            importPassphraseError = null,
                        )
                    }
                }
                is ImportIOResult.Completed -> {
                    delay(500)
                    _uiState.update { it.copy(isImporting = false) }
                    breadcrumb(stage = "import_success_callback")
                    onSuccess()
                }
                is ImportIOResult.Failed -> {
                    logger.e("DatabaseInitViewModel.executeImportDatabase", "Import failed", result.cause)
                    breadcrumb(
                        stage = "import_failed",
                        data = mapOf(
                            "errorType" to result.cause.javaClass.simpleName,
                            "errorMessage" to (result.cause.message ?: "unknown"),
                        ),
                    )
                    clearPendingImport()
                    val rawMessage = result.cause.message ?: "Unknown error"
                    val resolvedMessage = if (
                        rawMessage.contains("unable to open database", ignoreCase = true) ||
                        rawMessage.contains("cannot open database", ignoreCase = true)
                    ) {
                        context.getString(io.payanam.R.string.settings_import_error_encryption_convert_failed)
                    } else {
                        "Import failed: $rawMessage"
                    }
                    val restoreResult = when {
                        result.restoreAttempted && result.restoreSucceeded -> RestoreResult.RestoredOk
                        result.restoreAttempted && !result.restoreSucceeded -> RestoreResult.RestoreFailed
                        else -> null
                    }
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = if (result.restoreAttempted && result.restoreSucceeded) null else resolvedMessage,
                            restoreResult = restoreResult,
                        )
                    }
                }
            }
        }
    }

    fun resumeImportWithPassphrase(passphrase: String, onSuccess: () -> Unit) {
        logger.i("DatabaseInitViewModel.resumeImportWithPassphrase", "Resuming encrypted import with user passphrase")
        breadcrumb(
            stage = "resume_import_with_passphrase_started",
            data = mapOf("passphraseLength" to passphrase.length),
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importPassphraseError = null) }
            try {
                withContext(Dispatchers.IO) {
                    val dbFile = pendingImportDbFile
                        ?: throw IllegalStateException("No pending import DB to resume")
                    breadcrumb(
                        stage = "resume_import_pending_db_loaded",
                        data = mapOf("dbPath" to dbFile.absolutePath),
                    )

                    val canUnlock = DatabaseEncryptionMigrationSupport.canOpenWithSqlCipher(
                        context = context,
                        databaseFile = dbFile,
                        passphrase = passphrase,
                        logTag = "DatabaseInitViewModel.resumeImportWithPassphrase",
                    )
                    if (!canUnlock) {
                        throw IllegalStateException(
                            context.getString(io.payanam.R.string.db_import_passphrase_wrong),
                        )
                    }
                    breadcrumb(stage = "resume_import_passphrase_verified")

                    val configured = databaseEncryptionManager.configurePassphrase(passphrase)
                    if (!configured) {
                        throw IllegalStateException(context.getString(io.payanam.R.string.settings_import_error_encryption_convert_failed))
                    }
                    breadcrumb(stage = "resume_import_configure_passphrase_ok")

                    val postImportHealth = DatabaseHealthChecker.checkDatabaseHealth(
                        context = context,
                        sqlCipherPassphrase = passphrase,
                    )
                    if (!postImportHealth.isHealthy) {
                        throw IllegalStateException(
                            postImportHealth.errorMessage
                                ?: context.getString(io.payanam.R.string.loc_database_needs_repair),
                        )
                    }

                    markDatabaseInitCompletedDirect(dbFile, passphrase)
                    breadcrumb(stage = "resume_import_database_init_completed_marked")

                    val openResult = databaseSessionManager.openDatabase(passphrase)
                    openResult.getOrElse { openError ->
                        throw IllegalStateException(
                            "Encrypted import finalized but session open failed: ${openError.message}",
                            openError,
                        )
                    }
                    breadcrumb(stage = "resume_import_session_opened")

                    databaseEncryptionManager.clearEncryptionPrefsBackup()
                    breadcrumb(stage = "resume_import_encryption_backup_cleared")
                    val dir = pendingImportTempBackupDir
                    clearPendingImport()
                    dir?.let { deleteTempBackup(it) }
                    breadcrumb(stage = "resume_import_pending_state_cleared")
                }

                delay(500)
                _uiState.update { it.copy(isImporting = false, awaitingImportPassphrase = false) }
                breadcrumb(stage = "resume_import_success_callback")
                onSuccess()
            } catch (e: Exception) {
                logger.e("DatabaseInitViewModel.resumeImportWithPassphrase", "Failed to resume import with passphrase", e)
                breadcrumb(
                    stage = "resume_import_failed",
                    data = mapOf(
                        "errorType" to e.javaClass.simpleName,
                        "errorMessage" to (e.message ?: "unknown"),
                    ),
                )
                val isWrongPassphrase = e.message == context.getString(io.payanam.R.string.db_import_passphrase_wrong)
                if (isWrongPassphrase) {
                    _uiState.update { it.copy(isImporting = false, importPassphraseError = e.message) }
                } else {
                    val hadBackup = pendingImportTempBackupDir != null
                    var restoreSucceeded = false
                    withContext(Dispatchers.IO) {
                        databaseEncryptionManager.restoreEncryptionPrefs()
                        restoreSucceeded = pendingImportTempBackupDir?.let { dir -> restoreFromTempBackup(dir) } ?: false
                        clearPendingImport()
                    }
                    breadcrumb(
                        stage = "resume_import_failure_restore_complete",
                        data = mapOf("restoreSucceeded" to restoreSucceeded, "hadBackup" to hadBackup),
                    )
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            awaitingImportPassphrase = false,
                            errorMessage = e.message,
                            restoreResult = when {
                                hadBackup && restoreSucceeded -> RestoreResult.RestoredOk
                                hadBackup -> RestoreResult.RestoreFailed
                                else -> null
                            },
                        )
                    }
                }
            }
        }
    }

    fun cancelImportPassphrase() {
        logger.i("DatabaseInitViewModel.cancelImportPassphrase", "User cancelled imported DB passphrase prompt")
        breadcrumb(stage = "resume_import_cancelled_by_user")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                databaseEncryptionManager.restoreEncryptionPrefs()
                deleteAllDatabaseFiles()
                val dir = pendingImportTempBackupDir
                if (dir != null) {
                    restoreFromTempBackup(dir)
                }
                clearPendingImport()
            }
            breadcrumb(stage = "resume_import_cancel_cleanup_completed")
            _uiState.update {
                it.copy(
                    awaitingImportPassphrase = false,
                    isImporting = false,
                    importPassphraseError = null,
                )
            }
            checkDatabaseStatus()
        }
    }
    private fun deleteAllDatabaseFiles() = dbInitDeleteAllFiles(context)
    private fun getDatabaseArtifactFiles(): List<File> = dbInitGetArtifactFiles(context)
    private fun createSidecarSafeTempBackup(): File? = dbInitCreateSidecarSafeTempBackup(context)
    private fun restoreFromTempBackup(tempBackupDir: File): Boolean = dbInitRestoreFromTempBackup(context, tempBackupDir)
    private fun deleteTempBackup(tempBackupDir: File) = dbInitDeleteTempBackup(tempBackupDir)

    private fun markDatabaseInitCompletedDirect(dbFile: File, passphrase: String?) {
        dbInitMarkInitCompletedDirect(context, dbFile, passphrase)
    }

    private fun readDatabaseInitCompletedFlag(dbFile: File): Boolean = dbInitReadInitCompletedFlag(dbFile)

    fun continueWithExistingDatabase(onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting("database_init_completed", "true")
                logger.i("DatabaseInitViewModel.continueWithExistingDatabase", "Database init completed flag set")
                onSuccess()
            } catch (e: Exception) {
                logger.e("DatabaseInitViewModel.continueWithExistingDatabase", "Failed to set flag", e)
                onSuccess() // Still proceed
            }
        }
    }

    private fun classifyBootIssue(
        databaseArtifactsExist: Boolean,
        healthResult: DatabaseHealthChecker.HealthCheckResult,
    ): DatabaseBootIssue? = dbInitClassifyBootIssue(databaseArtifactsExist, healthResult)
}