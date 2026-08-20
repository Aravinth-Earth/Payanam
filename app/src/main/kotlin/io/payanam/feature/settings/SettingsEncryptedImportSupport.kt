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
 * Settings view model.
 */
fun SettingsViewModel.importDatabase(sourceUri: Uri) {
    logger.i("SettingsViewModel.importDatabase", "Import started", mapOf("sourceUri" to sourceUri.toString()))
    /** Breadcrumb. */
    breadcrumb(stage = "settings_import_started", data = mapOf("sourceUri" to sourceUri.toString()))
    viewModelScope.launch {
        updateUiState { it.copy(isImporting = true, importResult = null) }

        // Flag to prevent backup cleanup when paused at passphrase gate
        /** Paused for passphrase. */
        var pausedForPassphrase = false

        try {
            /** With context. */
            withContext(Dispatchers.IO) {
                /** Db file. */
                val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
                /** Existing database files. */
                val existingDatabaseFiles = listDatabaseArtifactFiles(context)
                    .filter { it.exists() && isActiveArtifact(it.name) }
                /** Backup mappings. */
                val backupMappings = backupDatabaseArtifactFiles(existingDatabaseFiles)
                logger.i(
                    "SettingsViewModel.importDatabase",
                    "Import paths resolved",
                    /** Map of. */
                    mapOf(
                        "sourceUri" to sourceUri.toString(),
                        "targetPath" to dbFile.absolutePath,
                        "targetExists" to dbFile.exists(),
                        "existingArtifacts" to existingDatabaseFiles.size,
                    ),
                )
                /** If. */
                if (backupMappings.isNotEmpty()) {
                    logger.i(
                        "SettingsViewModel.importDatabase",
                        "Created pre-import database backup snapshot",
                        /** Map of. */
                        mapOf(
                            "backupFiles" to backupMappings.size,
                        ),
                    )
                    /** Breadcrumb. */
                    breadcrumb(stage = "settings_import_backup_created", data = mapOf("backupFiles" to backupMappings.size))
                } else {
                    logger.d("SettingsViewModel.importDatabase", "No existing database artifacts to back up")
                }
                /** Deleted files. */
                val deletedFiles = deleteRuntimeDatabaseArtifacts(context)
                logger.i(
                    "SettingsViewModel.importDatabase",
                    "Cleared existing database artifacts before import",
                    /** Map of. */
                    mapOf(
                        "filesDeleted" to deletedFiles,
                    ),
                )
                /** Breadcrumb. */
                breadcrumb(stage = "settings_import_runtime_artifacts_deleted", data = mapOf("filesDeleted" to deletedFiles))
                databaseEncryptionManager.backupEncryptionPrefs()
                /** Breadcrumb. */
                breadcrumb(stage = "settings_import_encryption_prefs_backed_up")
                try {
                    /** Copy result. */
                    val copyResult = DatabaseImportSupport.copyDatabaseArtifacts(
                        context = context,
                        sourceUri = sourceUri,
                        targetDatabaseFile = dbFile,
                    )
                    /** Final size kb. */
                    val finalSizeKB = dbFile.length() / 1024
                    logger.i(
                        "SettingsViewModel.importDatabase",
                        "Database file copied successfully",
                        /** Map of. */
                        mapOf(
                            "bytesCopiedKB" to (copyResult.bytesCopied / 1024),
                            "finalFileSizeKB" to finalSizeKB,
                            "filePath" to dbFile.absolutePath,
                            "sourceKind" to copyResult.sourceKind,
                            "primaryFileName" to copyResult.primaryFileName,
                            "companionFilesCopied" to copyResult.companionFilesCopied,
                        ),
                    )
                    /** If. */
                    if (!dbFile.exists()) {
                        logger.e("SettingsViewModel.importDatabase", "Validation failed: File does not exist after copy")
                        throw Exception("Database file not created after copy")
                    }
                    /** If. */
                    if (dbFile.length() == 0L) {
                        logger.e("SettingsViewModel.importDatabase", "Validation failed: File size is 0 bytes")
                        throw Exception("Copied database file is empty (0 bytes)")
                    }
                    logger.i(
                        "SettingsViewModel.importDatabase",
                        "File validation passed",
                        /** Map of. */
                        mapOf(
                            "exists" to true,
                            "sizeKB" to finalSizeKB,
                        ),
                    )
                    /** Breadcrumb. */
                    breadcrumb(stage = "settings_import_copy_validated", data = mapOf("sizeKB" to finalSizeKB))

                    DatabaseImportSupport.consolidateWalAfterImport(
                        dbFile = dbFile,
                        logTag = "SettingsViewModel.importDatabase",
                    )

                    // Detect encrypted imports; pause and ask user for passphrase if needed.
                    /** Imported db is standard sqlite. */
                    val importedDbIsStandardSqlite = DatabaseImportSupport.isStandardSqliteFile(
                        databaseFile = dbFile,
                        logTag = "SettingsViewModel.importDatabase",
                    )
                    /** If. */
                    if (!importedDbIsStandardSqlite) {
                        /** Is encrypted. */
                        val isEncrypted = DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(
                            context = context,
                            databaseFile = dbFile,
                            logTag = "SettingsViewModel.importDatabase",
                        )
                        /** If. */
                        if (isEncrypted) {
                            logger.i(
                                "SettingsViewModel.importDatabase",
                                "Encrypted import detected; pausing and awaiting user passphrase",
                            )
                            pausedForPassphrase = true
                            /** Breadcrumb. */
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
                                /** Map of. */
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
                    /** Imported schema version. */
                    val importedSchemaVersion = DatabaseImportSupport.validateSupportedPlaintextImportSchema(
                        context = context,
                        databaseFile = dbFile,
                        logTag = "SettingsViewModel.importDatabase",
                    )
                    logger.i(
                        "SettingsViewModel.importDatabase",
                        "Schema support check complete",
                        /** Map of. */
                        mapOf(
                            "dbVersion" to importedSchemaVersion,
                        ),
                    )
                    /** Breadcrumb. */
                    breadcrumb(stage = "settings_import_schema_gate_done", data = mapOf("dbVersion" to importedSchemaVersion))
                    /** If. */
                    if (databaseEncryptionManager.isEncryptionEnabled()) {
                        /** Pre encryption counts. */
                        val preEncryptionCounts = DatabaseImportIntegritySupport.readCoreCounts(context, dbFile, null)
                        /** Passphrase for open. */
                        val passphraseForOpen = sessionManager.requireOpenPassphrase()
                        /** Encrypted. */
                        val encrypted = DatabaseEncryptionMigrationSupport.ensureEncryptedWithPassphrase(
                            context = context,
                            databaseFile = dbFile,
                            passphrase = passphraseForOpen,
                            logTag = "SettingsViewModel.importDatabase",
                        )
                        /** Post encryption counts. */
                        val postEncryptionCounts = DatabaseImportIntegritySupport.readCoreCounts(context, dbFile, passphraseForOpen)
                        DatabaseImportIntegritySupport.validateCountsPreserved(preEncryptionCounts, postEncryptionCounts, "SettingsViewModel.importDatabase")
                        logger.i(
                            "SettingsViewModel.importDatabase",
                            "Encryption conversion check complete",
                            /** Map of. */
                            mapOf(
                                "convertedToEncrypted" to encrypted,
                            ),
                        )
                        /** Breadcrumb. */
                        breadcrumb(stage = "settings_import_reencrypted", data = mapOf("convertedToEncrypted" to encrypted))
                    }
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    /** If. */
                    if (!pausedForPassphrase) {
                        logger.e(
                            "SettingsViewModel.importDatabase",
                            "Copy/conversion failed, restoring backup",
                            /** E. */
                            e,
                            /** Map of. */
                            mapOf(
                                "error" to (e.message ?: "Unknown error"),
                                "backupFiles" to backupMappings.size,
                            ),
                        )
                        databaseEncryptionManager.restoreEncryptionPrefs()
                        /** Breadcrumb. */
                        breadcrumb(stage = "settings_import_failure_encryption_prefs_restored")
                        /** If. */
                        if (backupMappings.isNotEmpty()) {
                            /** Restored files. */
                            val restoredFiles = restoreDatabaseArtifactFiles(backupMappings)
                            logger.i(
                                "SettingsViewModel.importDatabase",
                                "Backup restored after failure",
                                /** Map of. */
                                mapOf(
                                    "restoredFiles" to restoredFiles,
                                ),
                            )
                            /** Breadcrumb. */
                            breadcrumb(stage = "settings_import_failure_backup_restored", data = mapOf("restoredFiles" to restoredFiles))
                        }
                    }
                    throw e
                } finally {
                    // Do NOT clean up backup when paused for passphrase: it is stored in
                    // pendingEncryptedImportBackupMappings and cleaned up by
                    // resumeImportWithPassphrase or cancelImportPassphrase.
                    /** If. */
                    if (!pausedForPassphrase) {
                        databaseEncryptionManager.clearEncryptionPrefsBackup()
                        /** Cleanup database artifact backups. */
                        cleanupDatabaseArtifactBackups(backupMappings)
                        logger.i(
                            "SettingsViewModel.importDatabase",
                            "Import cleanup complete",
                            /** Map of. */
                            mapOf("pausedForPassphrase" to false, "backupFiles" to backupMappings.size),
                        )
                        /** Breadcrumb. */
                        breadcrumb(stage = "settings_import_cleanup_completed", data = mapOf("backupFiles" to backupMappings.size))
                    } else {
                        logger.i(
                            "SettingsViewModel.importDatabase",
                            "Import paused for passphrase; backups retained",
                            /** Map of. */
                            mapOf("backupFiles" to backupMappings.size),
                        )
                    }
                }
            }

            /** If. */
            if (pausedForPassphrase) {
                logger.i("SettingsViewModel.importDatabase", "Import coroutine paused awaiting passphrase")
                return@launch
            }

            logger.i("SettingsViewModel.importDatabase", "Database file operations complete, loading stats")
            /** Db file. */
            val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            /** Val. */
            val (tasksCount, timeEntriesCount, notesCount) = withContext(Dispatchers.IO) {
                /** Passphrase. */
                val passphrase = if (databaseEncryptionManager.isEncryptionEnabled()) sessionManager.requireOpenPassphrase() else null
                /** Counts. */
                val counts = DatabaseImportIntegritySupport.readCoreCounts(context, dbFile, passphrase)
                /** Triple. */
                Triple(counts["tasks"] ?: 0, counts["time_entries"] ?: 0, counts["notes"] ?: 0)
            }
            /** Db size kb. */
            val dbSizeKB = dbFile.length() / 1024
            logger.i(
                "SettingsViewModel.importDatabase",
                "Import completed successfully",
                /** Map of. */
                mapOf(
                    "tasksImported" to tasksCount,
                    "timeEntriesImported" to timeEntriesCount,
                    "notesImported" to notesCount,
                    "databaseSizeKB" to dbSizeKB,
                ),
            )
            /** Breadcrumb. */
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
                /** E. */
                e,
                /** Map of. */
                mapOf(
                    "errorMessage" to (e.message ?: "Unknown error"),
                    "errorType" to e.javaClass.simpleName,
                ),
            )
            /** Breadcrumb. */
            breadcrumb(
                stage = "settings_import_failed",
                data = mapOf(
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "unknown"),
                ),
            )
            Timber.e(e, "Import: Failed with error")
            /** Raw message. */
            val rawMessage = e.message ?: "Import failed"
            /** Resolved message. */
            val resolvedMessage = if (rawMessage.contains("unable to open database", ignoreCase = true)) {
                context.getString(io.payanam.R.string.settings_import_error_encryption_convert_failed)
            } else {
                /** Raw message. */
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
 * Settings view model.
 */
fun SettingsViewModel.resumeImportWithPassphrase(passphrase: String) {
    logger.i("SettingsViewModel.resumeImportWithPassphrase", "Resuming encrypted import with user passphrase")
    /** Breadcrumb. */
    breadcrumb(
        stage = "settings_resume_import_started",
        data = mapOf("passphraseLength" to passphrase.length),
    )
    viewModelScope.launch {
        updateUiState { it.copy(isImporting = true, importPassphraseError = null) }
        try {
            /** With context. */
            withContext(Dispatchers.IO) {
                /** Db file. */
                val dbFile = pendingEncryptedImportDbFile
                    ?: throw IllegalStateException("No pending encrypted import DB to resume")

                /** Can unlock. */
                val canUnlock = DatabaseEncryptionMigrationSupport.canOpenWithSqlCipher(
                    context = context,
                    databaseFile = dbFile,
                    passphrase = passphrase,
                    logTag = "SettingsViewModel.resumeImportWithPassphrase",
                )
                /** If. */
                if (!canUnlock) {
                    logger.w(
                        "SettingsViewModel.resumeImportWithPassphrase",
                        "Encrypted import passphrase verification failed",
                    )
                    throw IllegalStateException(
                        context.getString(io.payanam.R.string.db_import_passphrase_wrong),
                    )
                }
                /** Breadcrumb. */
                breadcrumb(stage = "settings_resume_import_passphrase_verified")

                // Adopt the imported DB's passphrase as the new local passphrase.
                /** Configured. */
                val configured = databaseEncryptionManager.configurePassphrase(passphrase)
                /** If. */
                if (!configured) {
                    throw IllegalStateException(
                        context.getString(io.payanam.R.string.settings_import_error_encryption_convert_failed),
                    )
                }
                /** Breadcrumb. */
                breadcrumb(stage = "settings_resume_import_passphrase_configured")

                // Success: discard encryption prefs backup and artifact backups.
                databaseEncryptionManager.clearEncryptionPrefsBackup()
                /** Backup mappings. */
                val backupMappings = pendingEncryptedImportBackupMappings
                pendingEncryptedImportDbFile = null
                pendingEncryptedImportBackupMappings = emptyList()
                /** Cleanup database artifact backups. */
                cleanupDatabaseArtifactBackups(backupMappings)
                logger.i(
                    "SettingsViewModel.resumeImportWithPassphrase",
                    "Finalized encrypted import resume cleanup",
                    /** Map of. */
                    mapOf("backupFiles" to backupMappings.size),
                )
                /** Breadcrumb. */
                breadcrumb(stage = "settings_resume_import_cleanup_done", data = mapOf("backupFiles" to backupMappings.size))
            }

            /** Db file. */
            val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            /** Val. */
            val (tasksCount, timeEntriesCount, notesCount) = withContext(Dispatchers.IO) {
                /** Counts. */
                val counts = DatabaseImportIntegritySupport.readCoreCounts(context, dbFile, passphrase)
                /** Triple. */
                Triple(counts["tasks"] ?: 0, counts["time_entries"] ?: 0, counts["notes"] ?: 0)
            }
            /** Db size kb. */
            val dbSizeKB = dbFile.length() / 1024
            logger.i(
                "SettingsViewModel.resumeImportWithPassphrase",
                "Encrypted import completed",
                /** Map of. */
                mapOf(
                    "tasksImported" to tasksCount,
                    "timeEntriesImported" to timeEntriesCount,
                    "notesImported" to notesCount,
                    "databaseSizeKB" to dbSizeKB,
                ),
            )
            /** Breadcrumb. */
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
            /** Breadcrumb. */
            breadcrumb(
                stage = "settings_resume_import_failed",
                data = mapOf(
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "unknown"),
                ),
            )
            /** Is wrong passphrase. */
            val isWrongPassphrase = e.message == context.getString(io.payanam.R.string.db_import_passphrase_wrong)
            /** If. */
            if (isWrongPassphrase) {
                logger.w(
                    "SettingsViewModel.resumeImportWithPassphrase",
                    "Wrong import passphrase entered; staying on passphrase gate",
                )
                updateUiState { it.copy(isImporting = false, importPassphraseError = e.message) }
            } else {
                /** With context. */
                withContext(Dispatchers.IO) {
                    databaseEncryptionManager.restoreEncryptionPrefs()
                    /** Backup mappings. */
                    val backupMappings = pendingEncryptedImportBackupMappings
                    pendingEncryptedImportDbFile = null
                    pendingEncryptedImportBackupMappings = emptyList()
                    /** If. */
                    if (backupMappings.isNotEmpty()) {
                        /** Restored. */
                        val restored = restoreDatabaseArtifactFiles(backupMappings)
                        logger.i(
                            "SettingsViewModel.resumeImportWithPassphrase",
                            "Restored backups after resume failure",
                            /** Map of. */
                            mapOf("restoredFiles" to restored, "backupFiles" to backupMappings.size),
                        )
                        /** Breadcrumb. */
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
 * Settings view model.
 */
fun SettingsViewModel.cancelImportPassphrase() {
    logger.i("SettingsViewModel.cancelImportPassphrase", "User cancelled encrypted import passphrase prompt")
    /** Breadcrumb. */
    breadcrumb(stage = "settings_resume_import_cancelled")
    viewModelScope.launch {
        /** With context. */
        withContext(Dispatchers.IO) {
            databaseEncryptionManager.restoreEncryptionPrefs()
            /** Deleted. */
            val deleted = deleteRuntimeDatabaseArtifacts(context)
            /** Backup mappings. */
            val backupMappings = pendingEncryptedImportBackupMappings
            pendingEncryptedImportDbFile = null
            pendingEncryptedImportBackupMappings = emptyList()
            /** If. */
            if (backupMappings.isNotEmpty()) {
                /** Restored. */
                val restored = restoreDatabaseArtifactFiles(backupMappings)
                logger.i(
                    "SettingsViewModel.cancelImportPassphrase",
                    "Restored backups while cancelling import passphrase gate",
                    /** Map of. */
                    mapOf("restoredFiles" to restored, "backupFiles" to backupMappings.size),
                )
            }
            databaseEncryptionManager.clearEncryptionPrefsBackup()
            logger.i(
                "SettingsViewModel.cancelImportPassphrase",
                "Cancelled import passphrase gate cleanup complete",
                /** Map of. */
                mapOf("deletedRuntimeArtifacts" to deleted),
            )
            /** Breadcrumb. */
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
