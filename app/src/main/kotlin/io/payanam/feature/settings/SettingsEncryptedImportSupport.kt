//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.settings

import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.database.DatabaseInitWalConsolidator
import io.payanam.shared.platform.DocumentSource
import io.payanam.presentation.database.DatabaseImportIntegritySupport
import io.payanam.presentation.database.DatabaseImportSupport
import io.payanam.presentation.settings.backupDatabaseArtifactFiles
import io.payanam.presentation.settings.BulkHabitMappingResult
import io.payanam.presentation.settings.cleanupDatabaseArtifactBackups
import io.payanam.presentation.settings.deleteRuntimeDatabaseArtifacts
import io.payanam.presentation.settings.ExportResult
import io.payanam.presentation.settings.ImportResult
import io.payanam.presentation.settings.listDatabaseArtifactFiles
import io.payanam.presentation.settings.restoreDatabaseArtifactFiles
import io.payanam.presentation.settings.UhabitsImportResult
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.database.isActiveArtifact
import io.payanam.shared.logging.CrashBreadcrumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Extension functions for SettingsViewModel handling encrypted database imports.
 * These are split out to keep SettingsViewModel.kt within the 700-line module limit.
 */
private fun SettingsViewModel.breadcrumb(stage: String, data: Map<String, Any?>? = null) {
    // orEmpty() because the shared record() takes a non-null map; the nullability stays here, on this
    // helper, where it is a convenience for its many call sites rather than a contract.
    CrashBreadcrumbs.record(
        source = "SettingsViewModel.import",
        stage = stage,
        data = data.orEmpty(),
    )
}



/**
 * Starts an ENCRYPTED database import from [sourceUri]: backs up the current DB + encryption prefs,
 * swaps in the imported file, and if the imported file is encrypted it pauses and surfaces
 * [awaitingImportPassphrase], resuming via [resumeImportWithPassphrase].
 *
 * A PLAINTEXT database is REFUSED, not converted (the encrypted-only import rule): Payanam's export IS
 * the encrypted file, so an unencrypted database is not a weaker export to accept but a file no
 * version of Payanam can produce. On any failure - including that refusal - the pre-import backup is
 * restored, so the user's own database survives byte-for-byte.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: settings pipeline; multiple failure modes
fun SettingsViewModel.importDatabase(
    sourceToken: String,
    databaseFile: java.io.File,
    probe: DatabaseEncryptionProbe,
    strings: UiStrings,
    documentSource: DocumentSource,
    walConsolidator: DatabaseInitWalConsolidator,
) {
    // Re-entry guard, at the top so a rejected call emits neither the "Import started" line below nor
    // the settings_import_started breadcrumb. `isImporting || awaitingImportPassphrase`, not
    // `isImporting` alone: while the encrypted-import passphrase prompt is open `isImporting` is
    // false, but the staged state (pendingEncryptedImportDbFile / pendingEncryptedImportBackupMappings)
    // is live, and a second import would clobber the in-memory mapping reference
    // (SettingsViewModel.kt:80) — orphaning the timestamp-named `.bak` files the first import wrote
    // (SettingsDatabaseArtifactSupport.kt:287), which could then never be restored or cleaned up.
    // That window's real UI barrier is the modal ImportEncryptedDbPassphraseDialog (SettingsScreen.kt):
    // it owns the screen, and the surrounding controls' `enabled` flags do not all exclude
    // awaitingImportPassphrase, so this guard is not merely the UI check repeated. Mirrors
    // DatabaseInitViewModel's import_reentry_rejected guard.
    val isImporting = uiState.value.isImporting
    val awaitingImportPassphrase = uiState.value.awaitingImportPassphrase
    if (isImporting || awaitingImportPassphrase) {
        // Payload is deliberately the two booleans only — the onboarding twin also logs sourceUri.
        // Observability asked for parity; Security preferred the narrower payload. Reviewed,
        // recorded, kept as-is (not an oversight).
        logger.i(
            "SettingsViewModel.importDatabase",
            "settings_import_reentry_rejected: an import is already in progress; ignoring the new request",
            mapOf(
                "isImporting" to isImporting.toString(),
                "awaitingImportPassphrase" to awaitingImportPassphrase.toString(),
            ),
        )
        return
    }
    logger.i("SettingsViewModel.importDatabase", "Import started", mapOf("sourceUri" to sourceToken))
    breadcrumb(stage = "settings_import_started", data = mapOf("sourceUri" to sourceToken))
    scope.launch {
        updateUiState { it.copy(isImporting = true, importResult = null) }

        // Flag to prevent backup cleanup when paused at passphrase gate
        var pausedForPassphrase = false

        try {
            withContext(Dispatchers.IO) {
                val dbFile = databaseFile
                val existingDatabaseFiles = listDatabaseArtifactFiles(localDatabaseFile)
                    .filter { it.exists() && isActiveArtifact(it.name) }
                val backupMappings = backupDatabaseArtifactFiles(existingDatabaseFiles)
                logger.i(
                    "SettingsViewModel.importDatabase",
                    "Import paths resolved",
                    mapOf(
                        "sourceUri" to sourceToken,
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
                val deletedFiles = deleteRuntimeDatabaseArtifacts(localDatabaseFile)
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
                        documentSource = documentSource,
                        sourceToken = sourceToken,
                        targetDatabaseFile = dbFile,
                        strings = strings,
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
                        walConsolidator = walConsolidator,
                    )

                    // Detect encrypted imports; pause and ask user for passphrase if needed.
                    val importedDbIsStandardSqlite = DatabaseImportSupport.isStandardSqliteFile(
                        databaseFile = dbFile,
                        logTag = "SettingsViewModel.importDatabase",
                    )
                    if (!importedDbIsStandardSqlite) {
                        val isEncrypted = probe.isDetectablyEncrypted(
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
                                strings.settingsImportErrorUnreadableDb,
                            )
                        }
                    }

                    // The file carries the SQLite plaintext magic, so it is an UNENCRYPTED database, and
                    // Payanam exports IS the encrypted file - no version of Payanam can produce this one.
                    // It is refused, not converted: converting it would make the import path the only
                    // route by which unencrypted data entered the app. The throw leaves through the
                    // catch below, which restores the pre-import backup, so the user's own database is
                    // left byte-identical rather than replaced by a file we just declined to accept.
                    logger.w(
                        "SettingsViewModel.importDatabase",
                        "Refusing an unencrypted database: Payanam imports encrypted databases only",
                        mapOf("file" to dbFile.name, "sizeBytes" to dbFile.length()),
                    )
                    breadcrumb(stage = "settings_import_refused_plaintext", data = mapOf("file" to dbFile.name))
                    throw IllegalStateException(
                        strings.settingsImportErrorPlaintextDb,
                    )
                } catch (e: Exception) {
                    logger.e(
                        "SettingsViewModel.importDatabase",
                        "IMPORT_PROBE_CATCH_FILE_STATE",
                        e,
                        mapOf(
                            "file" to dbFile.name,
                            "exists" to dbFile.exists(),
                            "sizeBytes" to dbFile.length(),
                        ),
                    )
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
            val dbFile = databaseFile
            val (tasksCount, timeEntriesCount, notesCount) = withContext(Dispatchers.IO) {
                // The FILE decides whether a passphrase is needed to read it, not the local mode: an
                // imported file that is detectably encrypted needs the open passphrase to count its rows,
                // and one that is not needs none - whatever mode this device happens to be in.
                val passphrase =
                    if (probe.isDetectablyEncrypted(dbFile, "SettingsViewModel.importDatabase")) {
                        sessionManager.requireOpenPassphrase()
                    } else {
                        null
                    }
                val counts =
                    DatabaseImportIntegritySupport.readCoreCounts(
                        databaseFile = dbFile,
                        passphrase = passphrase,
                        encryptionProbe = probe,
                    )
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
                    ),
                )
            }
        } catch (e: Exception) {
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
            val resolvedMessage =
                if (rawMessage.contains("unable to open database", ignoreCase = true) ||
                    rawMessage.contains("cannot open database", ignoreCase = true) ||
                    rawMessage.contains("unreadable", ignoreCase = true)
                ) {
                    strings.settingsImportErrorEncryptionConvertFailed
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
 * Resumes a paused encrypted import: verifies [passphrase] can unlock the
 * imported DB, adopts it as the new local passphrase, then discards the prefs
 * + artifact backups and reports the imported counts. On a wrong passphrase it
 * stays on the gate; on any other failure it restores the backups.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: settings pipeline; multiple failure modes
fun SettingsViewModel.resumeImportWithPassphrase(
    passphrase: String,
    databaseFile: java.io.File,
    probe: DatabaseEncryptionProbe,
    strings: UiStrings,
    documentSource: DocumentSource,
    walConsolidator: DatabaseInitWalConsolidator,
) {
    logger.i("SettingsViewModel.resumeImportWithPassphrase", "Resuming encrypted import with user passphrase")
    breadcrumb(
        stage = "settings_resume_import_started",
        data = mapOf("passphraseProvided" to passphrase.isNotEmpty()),
    )
    scope.launch {
        updateUiState { it.copy(isImporting = true, importPassphraseError = null) }
        try {
            withContext(Dispatchers.IO) {
                val dbFile = pendingEncryptedImportDbFile
                    ?: throw IllegalStateException("No pending encrypted import DB to resume")
                val canUnlock = probe.canOpen(
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
                        strings.dbImportPassphraseWrong,
                    )
                }
                breadcrumb(stage = "settings_resume_import_passphrase_verified")

                // Adopt the imported DB's passphrase as the new local passphrase.
                val configured = databaseEncryptionManager.configurePassphrase(passphrase)
                if (!configured) {
                    throw IllegalStateException(
                        strings.settingsImportErrorEncryptionConvertFailed,
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
            val dbFile = databaseFile
            val (tasksCount, timeEntriesCount, notesCount) = withContext(Dispatchers.IO) {
                val counts =
                    DatabaseImportIntegritySupport.readCoreCounts(
                        databaseFile = dbFile,
                        passphrase = passphrase,
                        encryptionProbe = probe,
                    )
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
                    ),
                )
            }
        } catch (e: Exception) {
            logger.e("SettingsViewModel.resumeImportWithPassphrase", "Failed to resume import with passphrase", e)
            breadcrumb(
                stage = "settings_resume_import_failed",
                data = mapOf(
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "unknown"),
                ),
            )
            val isWrongPassphrase = e.message == strings.dbImportPassphraseWrong
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
 * Aborts a paused encrypted-import passphrase gate: restores the original
 * encryption prefs, deletes the staged imported DB, restores any pre-import
 * artifact backup, and clears the passphrase UI state.
 */
fun SettingsViewModel.cancelImportPassphrase() {
    logger.i("SettingsViewModel.cancelImportPassphrase", "User cancelled encrypted import passphrase prompt")
    breadcrumb(stage = "settings_resume_import_cancelled")
    scope.launch {
        withContext(Dispatchers.IO) {
            databaseEncryptionManager.restoreEncryptionPrefs()
            val deleted = deleteRuntimeDatabaseArtifacts(localDatabaseFile)
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
