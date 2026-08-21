//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.settings

import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.ui.viewmodel.DatabaseImportIntegritySupport
import io.payanam.ui.viewmodel.DatabaseImportSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Extension functions for SettingsViewModel handling encrypted database imports.
 * These are split out to keep SettingsViewModel.kt within the 700-line module limit.
 */
private fun SettingsViewModel.breadcrumb(stage: String, data: Map<String, Any?>? = null) {
    CrashSafeBreadcrumbs.record(
        context = context,
        source = "SettingsViewModel.import",
        stage = stage,
        data = data,
    )
}
/**
 * Updates the settings view model.
 */
fun SettingsViewModel.importDatabase(sourceUri: Uri) {
    logger.i("SettingsViewModel.importDatabase", "Import started", mapOf("sourceUri" to sourceUri.toString()))
    breadcrumb(stage = "settings_import_started", data = mapOf("sourceUri" to sourceUri.toString()))
    viewModelScope.launch {
        updateUiState { it.copy(isImporting = true, importResult = null) }

        // Flag to prevent backup cleanup when paused at passphrase gate
        var pausedForPassphrase = false

        try {
            withContext(Dispatchers.IO) {
                val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
                val existingDatabaseFiles = listDatabaseArtifactFiles(context)
                    .filter { it.exists() && isActiveArtifact(it.name) }
                val backupMappings = backupDatabaseArtifactFiles(existingDatabaseFiles)
                logger.i(
                    "SettingsViewModel.importDatabase",
                    "Import paths resolved",
                    mapOf(
                        "sourceUri" to sourceUri.toString(),
                        "targetPath" to dbFile.absolutePath,
                        "targetExists" to dbFile.exists(),
                        "existingArtifacts" to existingDatabaseFiles.size,
                    ),
                )
                if (backupMappings.isNotEmpty()) {
                    logger.i(
                        "SettingsViewModel.importDatabase",
                        "Created pre-import database backup snapshot",
                        mapOf(
                            "backupFiles" to backupMappings.size,
                        ),
                    )
                    breadcrumb(stage = "settings_import_backup_created", data = mapOf("backupFiles" to backupMappings.size))
                } else {
                    logger.d("SettingsViewModel.importDatabase", "No existing database artifacts to back up")
                }
                val deletedFiles = deleteRuntimeDatabaseArtifacts(context)
                logger.i(
                    "SettingsViewModel.importDatabase",
                    "Cleared existing database artifacts before import",
                    mapOf(
                        "filesDeleted" to deletedFiles,
                    ),
                )
                breadcrumb(stage = "settings_import_runtime_artifacts_deleted", data = mapOf("filesDeleted" to deletedFiles))
                databaseEncryptionManager.backupEncryptionPrefs()
                breadcrumb(stage = "settings_import_encryption_prefs_backed_up")
                try {
                    val copyResult = DatabaseImportSupport.copyDatabaseArtifacts(
                        context = context,
                        sourceUri = sourceUri,
                        targetDatabaseFile = dbFile,
                    )
                    val finalSizeKB = dbFile.length() / 1024
                    logger.i(
                        "SettingsViewModel.importDatabase",
                        "Database file copied successfully",
                        mapOf(
                            "bytesCopiedKB" to (copyResult.bytesCopied / 1024),
                            "finalFileSizeKB" to finalSizeKB,
                            "filePath" to dbFile.absolutePath,
                            "sourceKind" to copyResult.sourceKind,
                            "primaryFileName" to copyResult.primaryFileName,
                            "companionFilesCopied" to copyResult.companionFilesCopied,
                        ),
                    )
                    if (!dbFile.exists()) {
                        logger.e("SettingsViewModel.importDatabase", "Validation failed: File does not exist after copy")
                        throw Exception("Database file not created after copy")
                    }
                    if (dbFile.length() == 0L) {
                        logger.e("SettingsViewModel.importDatabase", "Validation failed: File size is 0 bytes")
                        throw Exception("Copied database file is empty (0 bytes)")
                    }
                    logger.i(
                        "SettingsViewModel.importDatabase",
                        "File validation passed",
                        mapOf(
                            "exists" to true,
                            "sizeKB" to finalSizeKB,
                        ),
                    )
                    breadcrumb(stage = "settings_import_copy_validated", data = mapOf("sizeKB" to finalSizeKB))

                    DatabaseImportSupport.consolidateWalAfterImport(
                        dbFile = dbFile,
                        logTag = "SettingsViewModel.importDatabase",
                    )

                    // Detect encrypted imports; pause and ask user for passphrase if needed.
                    val importedDbIsStandardSqlite = DatabaseImportSupport.isStandardSqliteFile(
                        databaseFile = dbFile,
                        logTag = "SettingsViewModel.importDatabase",
                    )
                    if (!importedDbIsStandardSqlite) {
                        val isEncrypted = DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(
                            context = context,
                            databaseFile = dbFile,
                            logTag = "SettingsViewModel.importDatabase",
                        )
                        if (isEncrypted) {
                            logger.i(
                                "SettingsViewModel.importDatabase",
                                "Encrypted import detected; pausing and awaiting user passphrase",
                            )
                            pausedForPassphrase = true
                            breadcrumb(stage = "settings_import_awaiting_passphrase")
                            pendingEncryptedImportDbFile = dbFile
                            pendingEncryptedImportBackupMappings = backupMappings
                            updateUiState {
                                it.copy(
                                    isImporting = false,
                                    awaitingImportPassphrase = true,
                                    importPassphraseError = null,
                                )
                            }
                            logger.i(
                                "SettingsViewModel.importDatabase",
                                "Import state updated to await passphrase",
                                mapOf("awaitingImportPassphrase" to true),
                            )
                            return@withContext
                        } else {
                            throw IllegalStateException(
                                context.getString(io.payanam.R.string.settings_import_error_unreadable_db),
                            )
                        }
                    }

                    // Plaintext DB path: validate schema compatibility, then re-encrypt if needed.
                    logger.i("SettingsViewModel.importDatabase", "Checking imported plaintext database schema support")
                    val importedSchemaVersion = DatabaseImportSupport.validateSupportedPlaintextImportSchema(
                        context = context,
                        databaseFile = dbFile,
                        logTag = "SettingsViewModel.importDatabase",
                    )
                    logger.i(
                        "SettingsViewModel.importDatabase",
                        "Schema support check complete",
                        mapOf(
                            "dbVersion" to importedSchemaVersion,
                        ),
                    )
                    breadcrumb(stage = "settings_import_schema_gate_done", data = mapOf("dbVersion" to importedSchemaVersion))
                    if (databaseEncryptionManager.isEncryptionEnabled()) {
                        val preEncryptionCounts = DatabaseImportIntegritySupport.readCoreCounts(context, dbFile, null)
                        val passphraseForOpen = sessionManager.requireOpenPassphrase()
                        val encrypted = DatabaseEncryptionMigrationSupport.ensureEncryptedWithPassphrase(
                            context = context,
                            databaseFile = dbFile,
                            passphrase = passphraseForOpen,
                            logTag = "SettingsViewModel.importDatabase",
                        )
                        val postEncryptionCounts = DatabaseImportIntegritySupport.readCoreCounts(context, dbFile, passphraseForOpen)
                        DatabaseImportIntegritySupport.validateCountsPreserved(preEncryptionCounts, postEncryptionCounts, "SettingsViewModel.importDatabase")
                        logger.i(
                            "SettingsViewModel.importDatabase",
                            "Encryption conversion check complete",
                            mapOf(
                                "convertedToEncrypted" to encrypted,
                            ),
                        )
                        breadcrumb(stage = "settings_import_reencrypted", data = mapOf("convertedToEncrypted" to encrypted))
                    }
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    if (!pausedForPassphrase) {
                        logger.e(
                            "SettingsViewModel.importDatabase",
                            "Copy/conversion failed, restoring backup",
                            e,
                            mapOf(
                                "error" to (e.message ?: "Unknown error"),
                                "backupFiles" to backupMappings.size,
                            ),
                        )
                        databaseEncryptionManager.restoreEncryptionPrefs()
                        breadcrumb(stage = "settings_import_failure_encryption_prefs_restored")
                        if (backupMappings.isNotEmpty()) {
                            val restoredFiles = restoreDatabaseArtifactFiles(backupMappings)
                            logger.i(
                                "SettingsViewModel.importDatabase",
                                "Backup restored after failure",
                                mapOf(
                                    "restoredFiles" to restoredFiles,
                                ),
                            )
                            breadcrumb(stage = "settings_import_failure_backup_restored", data = mapOf("restoredFiles" to restoredFiles))
                        }
                    }
                    throw e
                } finally {
                    // Do NOT clean up backup when paused for passphrase: it is stored in
                    // pendingEncryptedImportBackupMappings and cleaned up by
                    // resumeImportWithPassphrase or cancelImportPassphrase.
                    if (!pausedForPassphrase) {
                        databaseEncryptionManager.clearEncryptionPrefsBackup()
                        cleanupDatabaseArtifactBackups(backupMappings)
                        logger.i(
                            "SettingsViewModel.importDatabase",
                            "Import cleanup complete",
                            mapOf("pausedForPassphrase" to false, "backupFiles" to backupMappings.size),
                        )
                        breadcrumb(stage = "settings_import_cleanup_completed", data = mapOf("backupFiles" to backupMappings.size))
                    } else {
                        logger.i(
                            "SettingsViewModel.importDatabase",
                            "Import paused for passphrase; backups retained",
                            mapOf("backupFiles" to backupMappings.size),
                        )
                    }
                }
            }
            if (pausedForPassphrase) {
                logger.i("SettingsViewModel.importDatabase", "Import coroutine paused awaiting passphrase")
                return@launch
            }

            logger.i("SettingsViewModel.importDatabase", "Database file operations complete, loading stats")
            val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            val (tasksCount, timeEntriesCount, notesCount) = withContext(Dispatchers.IO) {
                val passphrase = if (databaseEncryptionManager.isEncryptionEnabled()) sessionManager.requireOpenPassphrase() else null
                val counts = DatabaseImportIntegritySupport.readCoreCounts(context, dbFile, passphrase)
                Triple(counts["tasks"] ?: 0, counts["time_entries"] ?: 0, counts["notes"] ?: 0)
            }
            val dbSizeKB = dbFile.length() / 1024
            logger.i(
                "SettingsViewModel.importDatabase",
                "Import completed successfully",
                mapOf(
                    "tasksImported" to tasksCount,
                    "timeEntriesImported" to timeEntriesCount,
                    "notesImported" to notesCount,
                    "databaseSizeKB" to dbSizeKB,
                ),
            )
            breadcrumb(stage = "settings_import_completed", data = mapOf("dbSizeKB" to dbSizeKB))
            updateUiState {
                it.copy(
                    taskCount = tasksCount,
                    timeEntryCount = timeEntriesCount,
                    noteCount = notesCount,
                    databaseSizeKb = dbSizeKB,
                    isImporting = false,
                    importResult = ImportResult.Success(
                        tasksImported = tasksCount,
                        timeEntriesImported = timeEntriesCount,
                        notesImported = notesCount,
                        requiresAppRestart = true,
                    ),
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "SettingsViewModel.importDatabase",
                "Import failed with exception",
                e,
                mapOf(
                    "errorMessage" to (e.message ?: "Unknown error"),
                    "errorType" to e.javaClass.simpleName,
                ),
            )
            breadcrumb(
                stage = "settings_import_failed",
                data = mapOf(
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "unknown"),
                ),
            )
            Timber.e(e, "Import: Failed with error")
            val rawMessage = e.message ?: "Import failed"
            val resolvedMessage = if (rawMessage.contains("unable to open database", ignoreCase = true)) {
                context.getString(io.payanam.R.string.settings_import_error_encryption_convert_failed)
            } else {
                rawMessage
            }
            updateUiState {
                it.copy(
                    isImporting = false,
                    importResult = ImportResult.Error(resolvedMessage),
                )
            }
        }
    }
}
/**
 * Updates the settings view model.
 */
fun SettingsViewModel.resumeImportWithPassphrase(passphrase: String) {
    logger.i("SettingsViewModel.resumeImportWithPassphrase", "Resuming encrypted import with user passphrase")
    breadcrumb(
        stage = "settings_resume_import_started",
        data = mapOf("passphraseLength" to passphrase.length),
    )
    viewModelScope.launch {
        updateUiState { it.copy(isImporting = true, importPassphraseError = null) }
        try {
            withContext(Dispatchers.IO) {
                val dbFile = pendingEncryptedImportDbFile
                    ?: throw IllegalStateException("No pending encrypted import DB to resume")
                val canUnlock = DatabaseEncryptionMigrationSupport.canOpenWithSqlCipher(
                    context = context,
                    databaseFile = dbFile,
                    passphrase = passphrase,
                    logTag = "SettingsViewModel.resumeImportWithPassphrase",
                )
                if (!canUnlock) {
                    logger.w(
                        "SettingsViewModel.resumeImportWithPassphrase",
                        "Encrypted import passphrase verification failed",
                    )
                    throw IllegalStateException(
                        context.getString(io.payanam.R.string.db_import_passphrase_wrong),
                    )
                }
                breadcrumb(stage = "settings_resume_import_passphrase_verified")

                // Adopt the imported DB's passphrase as the new local passphrase.
                val configured = databaseEncryptionManager.configurePassphrase(passphrase)
                if (!configured) {
                    throw IllegalStateException(
                        context.getString(io.payanam.R.string.settings_import_error_encryption_convert_failed),
                    )
                }
                breadcrumb(stage = "settings_resume_import_passphrase_configured")

                // Success: discard encryption prefs backup and artifact backups.
                databaseEncryptionManager.clearEncryptionPrefsBackup()
                val backupMappings = pendingEncryptedImportBackupMappings
                pendingEncryptedImportDbFile = null
                pendingEncryptedImportBackupMappings = emptyList()
                cleanupDatabaseArtifactBackups(backupMappings)
                logger.i(
                    "SettingsViewModel.resumeImportWithPassphrase",
                    "Finalized encrypted import resume cleanup",
                    mapOf("backupFiles" to backupMappings.size),
                )
                breadcrumb(stage = "settings_resume_import_cleanup_done", data = mapOf("backupFiles" to backupMappings.size))
            }
            val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            val (tasksCount, timeEntriesCount, notesCount) = withContext(Dispatchers.IO) {
                val counts = DatabaseImportIntegritySupport.readCoreCounts(context, dbFile, passphrase)
                Triple(counts["tasks"] ?: 0, counts["time_entries"] ?: 0, counts["notes"] ?: 0)
            }
            val dbSizeKB = dbFile.length() / 1024
            logger.i(
                "SettingsViewModel.resumeImportWithPassphrase",
                "Encrypted import completed",
                mapOf(
                    "tasksImported" to tasksCount,
                    "timeEntriesImported" to timeEntriesCount,
                    "notesImported" to notesCount,
                    "databaseSizeKB" to dbSizeKB,
                ),
            )
            breadcrumb(stage = "settings_resume_import_completed", data = mapOf("dbSizeKB" to dbSizeKB))
            updateUiState {
                it.copy(
                    taskCount = tasksCount,
                    timeEntryCount = timeEntriesCount,
                    noteCount = notesCount,
                    databaseSizeKb = dbSizeKB,
                    isImporting = false,
                    awaitingImportPassphrase = false,
                    importResult = ImportResult.Success(
                        tasksImported = tasksCount,
                        timeEntriesImported = timeEntriesCount,
                        notesImported = notesCount,
                        requiresAppRestart = true,
                    ),
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("SettingsViewModel.resumeImportWithPassphrase", "Failed to resume import with passphrase", e)
            breadcrumb(
                stage = "settings_resume_import_failed",
                data = mapOf(
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "unknown"),
                ),
            )
            val isWrongPassphrase = e.message == context.getString(io.payanam.R.string.db_import_passphrase_wrong)
            if (isWrongPassphrase) {
                logger.w(
                    "SettingsViewModel.resumeImportWithPassphrase",
                    "Wrong import passphrase entered; staying on passphrase gate",
                )
                updateUiState { it.copy(isImporting = false, importPassphraseError = e.message) }
            } else {
                withContext(Dispatchers.IO) {
                    databaseEncryptionManager.restoreEncryptionPrefs()
                    val backupMappings = pendingEncryptedImportBackupMappings
                    pendingEncryptedImportDbFile = null
                    pendingEncryptedImportBackupMappings = emptyList()
                    if (backupMappings.isNotEmpty()) {
                        val restored = restoreDatabaseArtifactFiles(backupMappings)
                        logger.i(
                            "SettingsViewModel.resumeImportWithPassphrase",
                            "Restored backups after resume failure",
                            mapOf("restoredFiles" to restored, "backupFiles" to backupMappings.size),
                        )
                        breadcrumb(stage = "settings_resume_import_restore_done", data = mapOf("restoredFiles" to restored))
                    }
                    databaseEncryptionManager.clearEncryptionPrefsBackup()
                }
                updateUiState {
                    it.copy(
                        isImporting = false,
                        awaitingImportPassphrase = false,
                        importResult = ImportResult.Error(e.message ?: "Import failed"),
                    )
                }
            }
        }
    }
}
/**
 * Updates the settings view model.
 */
fun SettingsViewModel.cancelImportPassphrase() {
    logger.i("SettingsViewModel.cancelImportPassphrase", "User cancelled encrypted import passphrase prompt")
    breadcrumb(stage = "settings_resume_import_cancelled")
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            databaseEncryptionManager.restoreEncryptionPrefs()
            val deleted = deleteRuntimeDatabaseArtifacts(context)
            val backupMappings = pendingEncryptedImportBackupMappings
            pendingEncryptedImportDbFile = null
            pendingEncryptedImportBackupMappings = emptyList()
            if (backupMappings.isNotEmpty()) {
                val restored = restoreDatabaseArtifactFiles(backupMappings)
                logger.i(
                    "SettingsViewModel.cancelImportPassphrase",
                    "Restored backups while cancelling import passphrase gate",
                    mapOf("restoredFiles" to restored, "backupFiles" to backupMappings.size),
                )
            }
            databaseEncryptionManager.clearEncryptionPrefsBackup()
            logger.i(
                "SettingsViewModel.cancelImportPassphrase",
                "Cancelled import passphrase gate cleanup complete",
                mapOf("deletedRuntimeArtifacts" to deleted),
            )
            breadcrumb(stage = "settings_resume_import_cancel_cleanup_done", data = mapOf("deletedRuntimeArtifacts" to deleted))
        }
        updateUiState {
            it.copy(
                awaitingImportPassphrase = false,
                isImporting = false,
                importPassphraseError = null,
            )
        }
    }
}
