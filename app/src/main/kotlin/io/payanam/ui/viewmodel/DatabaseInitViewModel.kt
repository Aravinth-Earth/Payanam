//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

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

/**
 * RestoreResult.
 */
sealed class RestoreResult {
    /**
     * RestoredOk.
     */
    object RestoredOk : RestoreResult()
    /**
     * RestoreFailed.
     */
    object RestoreFailed : RestoreResult()
}

/**
 * DatabaseInitUiState.
 */
data class DatabaseInitUiState(
    /** Is checking. */
    val isChecking: Boolean = true,
    /** Database exists. */
    val databaseExists: Boolean = false,
    /** Database corrupted. */
    val databaseCorrupted: Boolean = false,
    /** Boot issue. */
    val bootIssue: DatabaseBootIssue? = null,
    /** Corruption message. */
    val corruptionMessage: String? = null,
    /** Database size kb. */
    val databaseSizeKB: Long = 0,
    /** Last modified. */
    val lastModified: Long? = null,
    /** Task count. */
    val taskCount: Int = 0,
    /** Time entry count. */
    val timeEntryCount: Int = 0,
    /** Journey entry count. */
    val journeyEntryCount: Int = 0,
    /** Note count. */
    val noteCount: Int = 0,
    /** Database created. */
    val databaseCreated: Long? = null,
    /** Database schema version. */
    val databaseSchemaVersion: Int = 0,
    /** Is creating. */
    val isCreating: Boolean = false,
    /** Is importing. */
    val isImporting: Boolean = false,
    /** Error message. */
    val errorMessage: String? = null,
    /** Show create new wipe confirm. */
    val showCreateNewWipeConfirm: Boolean = false,
    /** Show import wipe confirm. */
    val showImportWipeConfirm: Boolean = false,
    /** Restore result. */
    val restoreResult: RestoreResult? = null,
    /** Awaiting import passphrase. */
    val awaitingImportPassphrase: Boolean = false,
    /** Import passphrase error. */
    val importPassphraseError: String? = null,
    /** Awaiting dimension setup. */
    val awaitingDimensionSetup: Boolean = false,
)

/**
 * DatabaseBootIssueType.
 */
enum class DatabaseBootIssueType {
    /** Sidecar primary missing. */
    SIDECAR_PRIMARY_MISSING,
    /** Db too old. */
    DB_TOO_OLD,
    /** Db too new. */
    DB_TOO_NEW,
    /** Schema invalid. */
    SCHEMA_INVALID,
    /** Open failed. */
    OPEN_FAILED,
    /** Repairable generic. */
    REPAIRABLE_GENERIC,
    /** Non repairable generic. */
    NON_REPAIRABLE_GENERIC,
}

/**
 * DatabaseBootIssue.
 */
data class DatabaseBootIssue(
    /** Type. */
    val type: DatabaseBootIssueType,
    /** Detail message. */
    val detailMessage: String? = null,
    /** Detected version. */
    val detectedVersion: Int = 0,
)

@HiltViewModel
/**
 * DatabaseInitViewModel.
 */
class DatabaseInitViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val databaseEncryptionManager: DatabaseEncryptionManager,
    private val databaseSessionManager: DatabaseSessionManager,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(DatabaseInitUiState())
    /** Ui state. */
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
        /** Check database status. */
        checkDatabaseStatus()
    }

    private suspend fun detectBootstrapPlaceholder(dbFile: java.io.File): Boolean {
        /** Is encrypted. */
        val isEncrypted = databaseEncryptionManager.isEncryptionEnabled()
        /** If. */
        if (isEncrypted) return false
        /** Database init completed. */
        val databaseInitCompleted = readDatabaseInitCompletedFlag(dbFile)
        /** Counts. */
        val counts = withContext(Dispatchers.IO) {
            /** Count map. */
            val countMap = DatabaseEncryptionMigrationSupport.readTableCounts(
                context = context,
                databaseFile = dbFile,
                passphrase = null,
                tableNames = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes"),
            )
            /** Database table counts. */
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
            /** Map of. */
            mapOf(
                "taskCount" to counts.taskCount,
                "timeEntryCount" to counts.timeEntryCount,
                "journalEntryCount" to counts.journalEntryCount,
                "noteCount" to counts.noteCount,
            ),
        )
        /** Has user data. */
        val hasUserData = counts.taskCount > 0 || counts.timeEntryCount > 0 ||
            counts.journalEntryCount > 0 || counts.noteCount > 0
        return !databaseInitCompleted && !hasUserData
    }

    private fun checkDatabaseStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }

            try {
                /** Health result. */
                val healthResult = DatabaseHealthChecker.checkDatabaseHealth(context, null)

                logger.i(
                    "DatabaseInitViewModel.checkDatabaseStatus",
                    "Health check complete",
                    /** Map of. */
                    mapOf(
                        "isHealthy" to healthResult.isHealthy,
                        "needsRepair" to healthResult.needsRepair,
                        "errorMessage" to (healthResult.errorMessage ?: "N/A"),
                    ),
                )

                /** If. */
                if (!healthResult.isHealthy) {
                    /** Db file. */
                    val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
                    /** Exists. */
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

                /** Db file. */
                val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
                /** Exists. */
                val exists = DatabaseHealthChecker.hasDatabaseArtifacts(context)

                /** If. */
                if (exists) {
                    /** Size kb. */
                    val sizeKB = dbFile.length() / 1024
                    /** Last modified. */
                    val lastModified = dbFile.lastModified()

                    logger.i(
                        "DatabaseInitViewModel.checkDatabaseStatus",
                        "Database file info",
                        /** Map of. */
                        mapOf("sizeKB" to sizeKB, "lastModified" to lastModified, "lastModifiedDate" to Date(lastModified).toString()),
                    )

                    /** Is bootstrap placeholder. */
                    val isBootstrapPlaceholder = detectBootstrapPlaceholder(dbFile)
                    /** If. */
                    if (isBootstrapPlaceholder) {
                        logger.w(
                            "DatabaseInitViewModel.checkDatabaseStatus",
                            "Detected bootstrap placeholder DB created before init choice; treating as no existing user database",
                            /** Map of. */
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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

    /**
     * Retry database status check.
     */
    fun retryDatabaseStatusCheck() {
        logger.i("DatabaseInitViewModel.retryDatabaseStatusCheck", "Retrying database status check")
        /** Check database status. */
        checkDatabaseStatus()
    }

    /**
     * Create new database.
     */
    fun createNewDatabase(passphrase: String) {
        logger.i("DatabaseInitViewModel.createNewDatabase", "Create new database requested")
        /** Existing files. */
        val existingFiles = getDatabaseArtifactFiles().filter { it.exists() }
        /** If. */
        if (existingFiles.isNotEmpty()) {
            logger.i(
                "DatabaseInitViewModel.createNewDatabase",
                "Existing DB artifacts found; showing wipe confirm",
                /** Map of. */
                mapOf("fileCount" to existingFiles.size),
            )
            _uiState.update { it.copy(showCreateNewWipeConfirm = true) }
            /** Return. */
            return
        }
        /** Begin mandatory dimension setup. */
        beginMandatoryDimensionSetup(passphrase = passphrase, needsWipe = false)
    }

    /**
     * Confirm create new.
     */
    fun confirmCreateNew(passphrase: String) {
        logger.i("DatabaseInitViewModel.confirmCreateNew", "User confirmed create new with wipe")
        _uiState.update { it.copy(showCreateNewWipeConfirm = false) }
        /** Begin mandatory dimension setup. */
        beginMandatoryDimensionSetup(passphrase = passphrase, needsWipe = true)
    }

    /**
     * Cancel create new wipe.
     */
    fun cancelCreateNewWipe() {
        logger.i("DatabaseInitViewModel.cancelCreateNewWipe", "User cancelled create new wipe confirm")
        _uiState.update { it.copy(showCreateNewWipeConfirm = false) }
    }

    /**
     * Dismiss restore result.
     */
    fun dismissRestoreResult() {
        _uiState.update { it.copy(restoreResult = null) }
        /** Check database status. */
        checkDatabaseStatus()
    }

    private fun beginMandatoryDimensionSetup(passphrase: String, needsWipe: Boolean) {
        pendingCreatePassphrase = passphrase
        pendingCreateNeedsWipe = needsWipe
        logger.i(
            "DatabaseInitViewModel.beginMandatoryDimensionSetup",
            "Passphrase accepted; waiting for mandatory dimension setup before DB creation",
            /** Map of. */
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

    /**
     * Complete new database dimension setup.
     */
    fun completeNewDatabaseDimensionSetup(
        dimensionInputs: List<NewDatabaseDimensionInput>,
        onSuccess: () -> Unit,
    ) {
        logger.i(
            "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
            "Persisting mandatory life-dimension setup",
            /** Map of. */
            mapOf(
                "inputCount" to dimensionInputs.size,
                "hasPendingPassphrase" to (pendingCreatePassphrase != null),
                "pendingNeedsWipe" to pendingCreateNeedsWipe,
            ),
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, errorMessage = null) }
            /** Temp backup dir. */
            var tempBackupDir: File? = null
            try {
                /** With context. */
                withContext(Dispatchers.IO) {
                    /** Create passphrase. */
                    val createPassphrase = pendingCreatePassphrase
                        ?: throw IllegalStateException("Missing pending passphrase. Please set passphrase again.")
                    /** If. */
                    if (pendingCreateNeedsWipe) {
                        /** Existing files. */
                        val existingFiles = getDatabaseArtifactFiles().filter { it.exists() }
                        /** If. */
                        if (existingFiles.isNotEmpty()) {
                            tempBackupDir = createSidecarSafeTempBackup()
                            /** If. */
                            if (tempBackupDir == null) {
                                throw IllegalStateException("Could not create a safe backup of the current database before wiping.")
                            }
                            logger.i(
                                "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                                "Temp backup created before wipe",
                                /** Map of. */
                                mapOf("dir" to tempBackupDir!!.absolutePath),
                            )
                            /** Delete all database files. */
                            deleteAllDatabaseFiles()
                        }
                    }
                    /** Configured. */
                    val configured = databaseEncryptionManager.configurePassphrase(createPassphrase)
                    /** If. */
                    if (!configured) throw IllegalStateException("Failed to configure passphrase for new database")
                    /** Open result. */
                    val openResult = databaseSessionManager.openDatabase(createPassphrase)
                    openResult.getOrElse { throw IllegalStateException("Failed to open new DB session: ${it.message}", it) }
                    /** Persist new database dimension setup. */
                    persistNewDatabaseDimensionSetup(
                        context = context,
                        databaseSessionManager = databaseSessionManager,
                        appSettingsRepository = appSettingsRepository,
                        dimensionInputs = dimensionInputs,
                    )
                    tempBackupDir?.let { deleteTempBackup(it) }
                    /** Clear pending create. */
                    clearPendingCreate()
                }
                logger.i(
                    "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                    "Dimension setup saved and DB init marked complete",
                )
                _uiState.update { it.copy(isCreating = false, awaitingDimensionSetup = false) }
                /** On success. */
                onSuccess()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                    "Failed to persist mandatory dimension setup",
                    /** E. */
                    e,
                )
                /** Restored. */
                val restored = withContext(Dispatchers.IO) {
                    databaseEncryptionManager.resetEncryptionState()
                    databaseSessionManager.closeDatabase()
                    /** Dir. */
                    val dir = tempBackupDir
                    /** If. */
                    if (dir != null) restoreFromTempBackup(dir) else false
                }
                // Keep the pending create passphrase so the user can retry the
                // dimension setup without re-entering the passphrase flow.
                logger.i(
                    "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                    "Dimension setup failed; pending passphrase retained for retry",
                    /** Map of. */
                    mapOf(
                        "restoredFromBackup" to restored,
                        "pendingPassphraseRetained" to (pendingCreatePassphrase != null),
                    ),
                )
                _uiState.update { it.copy(isCreating = false, errorMessage = e.message) }
                /** If. */
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

    /**
     * Import database.
     */
    fun importDatabase(sourceUri: Uri, onSuccess: () -> Unit) {
        logger.i(
            "DatabaseInitViewModel.importDatabase",
            "Import database requested",
            /** Map of. */
            mapOf(
                "sourceUri" to sourceUri.toString(),
            ),
        )
        /** Existing files. */
        val existingFiles = getDatabaseArtifactFiles().filter { it.exists() }
        /** If. */
        if (existingFiles.isNotEmpty()) {
            logger.i(
                "DatabaseInitViewModel.importDatabase",
                "Existing DB artifacts found; showing wipe confirm",
                /** Map of. */
                mapOf("fileCount" to existingFiles.size),
            )
            pendingImportUri = sourceUri
            pendingImportOnSuccess = onSuccess
            _uiState.update { it.copy(showImportWipeConfirm = true) }
            /** Return. */
            return
        }
        /** Execute import database. */
        executeImportDatabase(sourceUri, onSuccess)
    }

    /**
     * Confirm import after wipe.
     */
    fun confirmImportAfterWipe(onSuccess: () -> Unit) {
        logger.i("DatabaseInitViewModel.confirmImportAfterWipe", "User confirmed import with wipe")
        _uiState.update { it.copy(showImportWipeConfirm = false) }
        /** Uri. */
        val uri = pendingImportUri ?: return
        /** Cb. */
        val cb = pendingImportOnSuccess ?: onSuccess
        pendingImportUri = null
        pendingImportOnSuccess = null
        /** Execute import database. */
        executeImportDatabase(uri, cb)
    }

    /**
     * Cancel import wipe.
     */
    fun cancelImportWipe() {
        logger.i("DatabaseInitViewModel.cancelImportWipe", "User cancelled import wipe confirm")
        pendingImportUri = null
        pendingImportOnSuccess = null
        _uiState.update { it.copy(showImportWipeConfirm = false) }
    }

    private sealed class ImportIOResult {
        /**
         * NeedsPassphrase.
         */
        data class NeedsPassphrase(val dbFile: File, val tempBackupDir: File?) : ImportIOResult()
        /**
         * Completed.
         */
        data class Completed(val dbFile: File, val passphrase: String?) : ImportIOResult()
        /**
         * Failed.
         */
        data class Failed(
            /** Cause. */
            val cause: Throwable,
            /** Restore attempted. */
            val restoreAttempted: Boolean,
            /** Restore succeeded. */
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
        /** Source uri. */
        sourceUri: Uri,
        /** Db file. */
        dbFile: File,
        existingDatabaseFiles: List<File>,
    ): ImportIOResult = withContext(Dispatchers.IO) {
        /** Temp backup dir. */
        var tempBackupDir: File? = null
        /** Result. */
        var result: ImportIOResult = ImportIOResult.Failed(IllegalStateException("unreachable"), false, false)
        try {
            /** If. */
            if (existingDatabaseFiles.isNotEmpty()) {
                tempBackupDir = createSidecarSafeTempBackup()
                /** If. */
                if (tempBackupDir == null) {
                    throw IllegalStateException("Could not create a safe backup of the current database before importing.")
                }
                /** Breadcrumb. */
                breadcrumb(
                    stage = "temp_backup_created",
                    data = mapOf("path" to tempBackupDir!!.absolutePath),
                )
                logger.i(
                    "DatabaseInitViewModel.executeImportIO",
                    "Temp backup created",
                    /** Map of. */
                    mapOf("dir" to tempBackupDir!!.absolutePath),
                )
            }

            databaseEncryptionManager.backupEncryptionPrefs()
            /** Breadcrumb. */
            breadcrumb(stage = "encryption_prefs_backed_up")
            /** Delete all database files. */
            deleteAllDatabaseFiles()
            /** Breadcrumb. */
            breadcrumb(stage = "runtime_artifacts_deleted")

            /** Copy result. */
            val copyResult = DatabaseImportSupport.copyDatabaseArtifacts(
                context = context,
                sourceUri = sourceUri,
                targetDatabaseFile = dbFile,
            )
            /** Breadcrumb. */
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
                /** Map of. */
                mapOf(
                    "bytesCopiedKB" to (copyResult.bytesCopied / 1024),
                    "filePath" to dbFile.absolutePath,
                    "sourceKind" to copyResult.sourceKind,
                    "primaryFileName" to copyResult.primaryFileName,
                    "companionFilesCopied" to copyResult.companionFilesCopied,
                ),
            )

            /** If. */
            if (!dbFile.exists() || dbFile.length() == 0L) {
                throw Exception(context.getString(io.payanam.R.string.settings_import_error_empty_db))
            }

            DatabaseImportSupport.consolidateWalAfterImport(
                dbFile = dbFile,
                logTag = "DatabaseInitViewModel.executeImportIO",
            )
            /** Breadcrumb. */
            breadcrumb(
                stage = "wal_consolidation_done",
                data = mapOf("dbSizeKB" to (dbFile.length() / 1024)),
            )

            /** Imported db is standard sqlite. */
            val importedDbIsStandardSqlite = DatabaseImportSupport.isStandardSqliteFile(
                databaseFile = dbFile,
                logTag = "DatabaseInitViewModel.executeImportIO",
            )
            /** If. */
            if (!importedDbIsStandardSqlite) {
                /** Is encrypted. */
                val isEncrypted = DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(
                    context = context,
                    databaseFile = dbFile,
                    logTag = "DatabaseInitViewModel.executeImportIO",
                )
                /** If. */
                if (isEncrypted) {
                    logger.i(
                        "DatabaseInitViewModel.executeImportIO",
                        "Encrypted import detected; pausing and awaiting user passphrase",
                    )
                    /** Breadcrumb. */
                    breadcrumb(stage = "awaiting_import_passphrase")
                    result = ImportIOResult.NeedsPassphrase(dbFile, tempBackupDir)
                    return@withContext result
                } else {
                    throw IllegalStateException(
                        context.getString(io.payanam.R.string.settings_import_error_unreadable_db),
                    )
                }
            }

            /** Imported schema version. */
            val importedSchemaVersion = DatabaseImportSupport.validateSupportedPlaintextImportSchema(
                context = context,
                databaseFile = dbFile,
                logTag = "DatabaseInitViewModel.executeImportIO",
            )
            /** Breadcrumb. */
            breadcrumb(
                stage = "plaintext_schema_gate_done",
                data = mapOf("dbVersion" to importedSchemaVersion),
            )

            /** Encryption passphrase for import. */
            val encryptionPassphraseForImport = if (databaseEncryptionManager.isEncryptionEnabled()) {
                runCatching { databaseSessionManager.requireOpenPassphrase() }.getOrElse {
                    throw IllegalStateException("Encrypted mode active but no open passphrase session is available.")
                }
            } else {
                /** Null. */
                null
            }
            /** If. */
            if (encryptionPassphraseForImport != null) {
                DatabaseEncryptionMigrationSupport.ensureEncryptedWithPassphrase(
                    context = context,
                    databaseFile = dbFile,
                    passphrase = encryptionPassphraseForImport,
                    logTag = "DatabaseInitViewModel.executeImportIO",
                )
                /** Breadcrumb. */
                breadcrumb(stage = "re_encrypted_with_session_passphrase")
            }

            /** Post import health. */
            val postImportHealth = DatabaseHealthChecker.checkDatabaseHealth(
                context = context,
                sqlCipherPassphrase = encryptionPassphraseForImport,
            )
            /** If. */
            if (!postImportHealth.isHealthy) {
                throw IllegalStateException(
                    postImportHealth.errorMessage
                        ?: context.getString(io.payanam.R.string.loc_database_needs_repair),
                )
            }

            /** Mark database init completed direct. */
            markDatabaseInitCompletedDirect(dbFile, encryptionPassphraseForImport)
            /** Breadcrumb. */
            breadcrumb(stage = "database_init_completed_marked")

            /** Open passphrase. */
            val openPassphrase = encryptionPassphraseForImport ?: ""
            /** Open result. */
            val openResult = databaseSessionManager.openDatabase(openPassphrase)
            openResult.getOrElse { openError ->
                throw IllegalStateException(
                    "Imported DB was finalized but session open failed: ${openError.message}",
                    /** Open error. */
                    openError,
                )
            }
            /** Breadcrumb. */
            breadcrumb(
                stage = "import_session_opened",
                data = mapOf("passphraseLength" to openPassphrase.length),
            )

            result = ImportIOResult.Completed(dbFile, encryptionPassphraseForImport)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "DatabaseInitViewModel.executeImportIO",
                "Import copy/conversion failed; restoring from temp backup",
                /** E. */
                e,
            )
            databaseEncryptionManager.restoreEncryptionPrefs()
            /** Breadcrumb. */
            breadcrumb(stage = "import_failure_encryption_prefs_restored")
            /** Restore attempted. */
            val restoreAttempted = tempBackupDir != null
            /** Restore succeeded. */
            val restoreSucceeded = tempBackupDir?.let { dir -> restoreFromTempBackup(dir) } ?: false
            /** Breadcrumb. */
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
                /** Map of. */
                mapOf(
                    "restoreAttempted" to restoreAttempted,
                    "restoreSucceeded" to restoreSucceeded,
                ),
            )
            result = ImportIOResult.Failed(e, restoreAttempted, restoreSucceeded)
        } finally {
            /** If. */
            if (result !is ImportIOResult.NeedsPassphrase) {
                databaseEncryptionManager.clearEncryptionPrefsBackup()
                tempBackupDir?.let { dir -> deleteTempBackup(dir) }
                /** Breadcrumb. */
                breadcrumb(stage = "import_cleanup_completed")
            }
        }
        /** Result. */
        result
    }

    private fun executeImportDatabase(sourceUri: Uri, onSuccess: () -> Unit) {
        logger.i(
            "DatabaseInitViewModel.executeImportDatabase",
            "Importing database",
            /** Map of. */
            mapOf(
                "sourceUri" to sourceUri.toString(),
            ),
        )
        /** Breadcrumb. */
        breadcrumb(
            stage = "import_started",
            data = mapOf("sourceUri" to sourceUri.toString()),
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null, restoreResult = null) }

            /** Db file. */
            val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            /** Existing database files. */
            val existingDatabaseFiles = getDatabaseArtifactFiles().filter { it.exists() }

            /** Result. */
            val result = executeImportIO(sourceUri, dbFile, existingDatabaseFiles)

            /** When. */
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
                    /** Delay. */
                    delay(500)
                    _uiState.update { it.copy(isImporting = false) }
                    /** Breadcrumb. */
                    breadcrumb(stage = "import_success_callback")
                    /** On success. */
                    onSuccess()
                }
                is ImportIOResult.Failed -> {
                    logger.e("DatabaseInitViewModel.executeImportDatabase", "Import failed", result.cause)
                    /** Breadcrumb. */
                    breadcrumb(
                        stage = "import_failed",
                        data = mapOf(
                            "errorType" to result.cause.javaClass.simpleName,
                            "errorMessage" to (result.cause.message ?: "unknown"),
                        ),
                    )
                    /** Clear pending import. */
                    clearPendingImport()
                    /** Raw message. */
                    val rawMessage = result.cause.message ?: "Unknown error"
                    /** Resolved message. */
                    val resolvedMessage = if (
                        rawMessage.contains("unable to open database", ignoreCase = true) ||
                        rawMessage.contains("cannot open database", ignoreCase = true)
                    ) {
                        context.getString(io.payanam.R.string.settings_import_error_encryption_convert_failed)
                    } else {
                        "Import failed: $rawMessage"
                    }
                    /** Restore result. */
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

    /**
     * Resume import with passphrase.
     */
    fun resumeImportWithPassphrase(passphrase: String, onSuccess: () -> Unit) {
        logger.i("DatabaseInitViewModel.resumeImportWithPassphrase", "Resuming encrypted import with user passphrase")
        /** Breadcrumb. */
        breadcrumb(
            stage = "resume_import_with_passphrase_started",
            data = mapOf("passphraseLength" to passphrase.length),
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importPassphraseError = null) }
            try {
                /** With context. */
                withContext(Dispatchers.IO) {
                    /** Db file. */
                    val dbFile = pendingImportDbFile
                        ?: throw IllegalStateException("No pending import DB to resume")
                    /** Breadcrumb. */
                    breadcrumb(
                        stage = "resume_import_pending_db_loaded",
                        data = mapOf("dbPath" to dbFile.absolutePath),
                    )

                    /** Can unlock. */
                    val canUnlock = DatabaseEncryptionMigrationSupport.canOpenWithSqlCipher(
                        context = context,
                        databaseFile = dbFile,
                        passphrase = passphrase,
                        logTag = "DatabaseInitViewModel.resumeImportWithPassphrase",
                    )
                    /** If. */
                    if (!canUnlock) {
                        throw IllegalStateException(
                            context.getString(io.payanam.R.string.db_import_passphrase_wrong),
                        )
                    }
                    /** Breadcrumb. */
                    breadcrumb(stage = "resume_import_passphrase_verified")

                    /** Configured. */
                    val configured = databaseEncryptionManager.configurePassphrase(passphrase)
                    /** If. */
                    if (!configured) {
                        throw IllegalStateException(context.getString(io.payanam.R.string.settings_import_error_encryption_convert_failed))
                    }
                    /** Breadcrumb. */
                    breadcrumb(stage = "resume_import_configure_passphrase_ok")

                    /** Post import health. */
                    val postImportHealth = DatabaseHealthChecker.checkDatabaseHealth(
                        context = context,
                        sqlCipherPassphrase = passphrase,
                    )
                    /** If. */
                    if (!postImportHealth.isHealthy) {
                        throw IllegalStateException(
                            postImportHealth.errorMessage
                                ?: context.getString(io.payanam.R.string.loc_database_needs_repair),
                        )
                    }

                    /** Mark database init completed direct. */
                    markDatabaseInitCompletedDirect(dbFile, passphrase)
                    /** Breadcrumb. */
                    breadcrumb(stage = "resume_import_database_init_completed_marked")

                    /** Open result. */
                    val openResult = databaseSessionManager.openDatabase(passphrase)
                    openResult.getOrElse { openError ->
                        throw IllegalStateException(
                            "Encrypted import finalized but session open failed: ${openError.message}",
                            /** Open error. */
                            openError,
                        )
                    }
                    /** Breadcrumb. */
                    breadcrumb(stage = "resume_import_session_opened")

                    databaseEncryptionManager.clearEncryptionPrefsBackup()
                    /** Breadcrumb. */
                    breadcrumb(stage = "resume_import_encryption_backup_cleared")
                    /** Dir. */
                    val dir = pendingImportTempBackupDir
                    /** Clear pending import. */
                    clearPendingImport()
                    dir?.let { deleteTempBackup(it) }
                    /** Breadcrumb. */
                    breadcrumb(stage = "resume_import_pending_state_cleared")
                }

                /** Delay. */
                delay(500)
                _uiState.update { it.copy(isImporting = false, awaitingImportPassphrase = false) }
                /** Breadcrumb. */
                breadcrumb(stage = "resume_import_success_callback")
                /** On success. */
                onSuccess()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("DatabaseInitViewModel.resumeImportWithPassphrase", "Failed to resume import with passphrase", e)
                /** Breadcrumb. */
                breadcrumb(
                    stage = "resume_import_failed",
                    data = mapOf(
                        "errorType" to e.javaClass.simpleName,
                        "errorMessage" to (e.message ?: "unknown"),
                    ),
                )
                /** Is wrong passphrase. */
                val isWrongPassphrase = e.message == context.getString(io.payanam.R.string.db_import_passphrase_wrong)
                /** If. */
                if (isWrongPassphrase) {
                    _uiState.update { it.copy(isImporting = false, importPassphraseError = e.message) }
                } else {
                    /** Had backup. */
                    val hadBackup = pendingImportTempBackupDir != null
                    /** Restore succeeded. */
                    var restoreSucceeded = false
                    /** With context. */
                    withContext(Dispatchers.IO) {
                        databaseEncryptionManager.restoreEncryptionPrefs()
                        restoreSucceeded = pendingImportTempBackupDir?.let { dir -> restoreFromTempBackup(dir) } ?: false
                        /** Clear pending import. */
                        clearPendingImport()
                    }
                    /** Breadcrumb. */
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

    /**
     * Cancel import passphrase.
     */
    fun cancelImportPassphrase() {
        logger.i("DatabaseInitViewModel.cancelImportPassphrase", "User cancelled imported DB passphrase prompt")
        /** Breadcrumb. */
        breadcrumb(stage = "resume_import_cancelled_by_user")
        viewModelScope.launch {
            /** With context. */
            withContext(Dispatchers.IO) {
                databaseEncryptionManager.restoreEncryptionPrefs()
                /** Delete all database files. */
                deleteAllDatabaseFiles()
                /** Dir. */
                val dir = pendingImportTempBackupDir
                /** If. */
                if (dir != null) {
                    /** Restore from temp backup. */
                    restoreFromTempBackup(dir)
                }
                /** Clear pending import. */
                clearPendingImport()
            }
            /** Breadcrumb. */
            breadcrumb(stage = "resume_import_cancel_cleanup_completed")
            _uiState.update {
                it.copy(
                    awaitingImportPassphrase = false,
                    isImporting = false,
                    importPassphraseError = null,
                )
            }
            /** Check database status. */
            checkDatabaseStatus()
        }
    }
    private fun deleteAllDatabaseFiles() = dbInitDeleteAllFiles(context)
    private fun getDatabaseArtifactFiles(): List<File> = dbInitGetArtifactFiles(context)
    private fun createSidecarSafeTempBackup(): File? = dbInitCreateSidecarSafeTempBackup(context)
    private fun restoreFromTempBackup(tempBackupDir: File): Boolean = dbInitRestoreFromTempBackup(context, tempBackupDir)
    private fun deleteTempBackup(tempBackupDir: File) = dbInitDeleteTempBackup(tempBackupDir)

    private fun markDatabaseInitCompletedDirect(dbFile: File, passphrase: String?) {
        /** Db init mark init completed direct. */
        dbInitMarkInitCompletedDirect(context, dbFile, passphrase)
    }

    private fun readDatabaseInitCompletedFlag(dbFile: File): Boolean = dbInitReadInitCompletedFlag(dbFile)

    /**
     * Continue with existing database.
     */
    fun continueWithExistingDatabase(onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting("database_init_completed", "true")
                logger.i("DatabaseInitViewModel.continueWithExistingDatabase", "Database init completed flag set")
                /** On success. */
                onSuccess()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("DatabaseInitViewModel.continueWithExistingDatabase", "Failed to set flag", e)
                /** On success. */
                onSuccess() // Still proceed
            }
        }
    }

    private fun classifyBootIssue(
        /** Database artifacts exist. */
        databaseArtifactsExist: Boolean,
        healthResult: DatabaseHealthChecker.HealthCheckResult,
    ): DatabaseBootIssue? = dbInitClassifyBootIssue(databaseArtifactsExist, healthResult)
}