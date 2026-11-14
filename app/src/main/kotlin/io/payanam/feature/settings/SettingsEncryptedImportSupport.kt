//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.settings

import androidx.annotation.VisibleForTesting
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.database.DatabaseImportIntegritySupport
import io.payanam.presentation.database.DatabaseImportSupport
import io.payanam.presentation.database.DatabaseInitWalConsolidator
import io.payanam.presentation.settings.BulkHabitMappingResult
import io.payanam.presentation.settings.ExportResult
import io.payanam.presentation.settings.ImportResult
import io.payanam.presentation.settings.UhabitsImportResult
import io.payanam.presentation.settings.backupDatabaseArtifactFiles
import io.payanam.presentation.settings.cleanupDatabaseArtifactBackups
import io.payanam.presentation.settings.deleteRuntimeDatabaseArtifacts
import io.payanam.presentation.settings.listDatabaseArtifactFiles
import io.payanam.presentation.settings.restoreDatabaseArtifactFiles
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.isActiveArtifact
import io.payanam.shared.logging.CrashBreadcrumbs
import io.payanam.shared.platform.DocumentSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
 * The import copies into a sibling staging file and only swaps it over the live database once the
 * imported passphrase has been verified.
 *
 * WHY A SIBLING AND NOT THE LIVE PATH: the file must be inspected (header probe, WAL fold, passphrase
 * verification) before anything is replaced. Writing it over the live path first is what forced the
 * session closed while a passphrase was still unknown, and it also made a refusal depend on a backup
 * restore rather than on never having touched the user's database.
 */
@VisibleForTesting
internal fun stagingDatabaseFile(liveFile: File): File = File(liveFile.parentFile, liveFile.name + ".import_staging")

/** Deletes the staged import and every companion the copy may have written beside it. */
@VisibleForTesting
internal fun deleteStagingArtifacts(staging: File) {
    DatabaseArtifacts.artifactSuffixes.forEach { suffix ->
        val file = File(staging.parentFile, staging.name + suffix)
        runCatching { if (file.exists()) file.delete() }
    }
}

/**
 * Moves the staged artifact family over the live one.
 *
 * The live artifacts are deleted under the closed session a moment earlier, so each target is free. A
 * rename keeps the bytes where they already are; the copy fallback covers a filesystem that refuses it.
 */
@VisibleForTesting
internal fun moveStagingArtifactsIntoPlace(
    staging: File,
    liveFile: File,
) {
    DatabaseArtifacts.artifactSuffixes.forEach { suffix ->
        val from = File(staging.parentFile, staging.name + suffix)
        if (!from.exists()) return@forEach
        val to = File(liveFile.parentFile, liveFile.name + suffix)
        if (to.exists()) to.delete()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }
}

/**
 * Puts the live database back after a swap that did not finish, then reopens the session the import
 * started from.
 *
 * Runs under [NonCancellable]: a cancelled import must not leave the files half-moved. If reopening the
 * original fails, the caller's `endInProcessSwap(false)` publishes the closed state and the normal
 * unlock gate appears.
 */
private suspend fun SettingsViewModel.rollbackImportSwap() {
    withContext(NonCancellable + Dispatchers.IO) {
        sessionManager.closeDatabase()
        databaseEncryptionManager.restoreEncryptionPrefs()
        val backupMappings = pendingEncryptedImportBackupMappings
        if (backupMappings.isNotEmpty()) {
            val restored = restoreDatabaseArtifactFiles(backupMappings)
            logger.i(
                "SettingsViewModel.importDatabase",
                "Restored the live database after a failed import swap",
                mapOf("restoredFiles" to restored, "backupFiles" to backupMappings.size),
            )
        }
        cleanupDatabaseArtifactBackups(backupMappings)
        pendingEncryptedImportBackupMappings = emptyList()
        pendingEncryptedImportDbFile?.let { deleteStagingArtifacts(it) }
        pendingEncryptedImportDbFile = null
        val originalPassphrase = pendingImportOriginalPassphrase
        if (originalPassphrase != null) {
            sessionManager
                .openDatabase(originalPassphrase)
                .onFailure { error ->
                    logger.e(
                        "SettingsViewModel.importDatabase",
                        "Failed to reopen the pre-import database session after a rollback",
                        error,
                    )
                }
        }
        pendingImportOriginalPassphrase = null
    }
}

/**
 * Starts an ENCRYPTED database import from [sourceUri]: copies the selected file to a staging path,
 * backs up the encryption preferences, and if the file is encrypted it pauses and surfaces
 * [awaitingImportPassphrase], resuming via [resumeImportWithPassphrase].
 *
 * The live database, its artifacts and the open session are untouched until the imported passphrase
 * has been verified. A PLAINTEXT database is REFUSED, not converted (the encrypted-only import rule):
 * Payanam's export IS the encrypted file, so an unencrypted database is not a weaker export to accept
 * but a file no version of Payanam can produce. On any failure - including that refusal - the staged
 * file is deleted and the user's database is left byte-for-byte as it was.
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

        val stagingFile = stagingDatabaseFile(databaseFile)
        // Flag to prevent staging cleanup when paused at the passphrase gate.
        var pausedForPassphrase = false

        try {
            withContext(Dispatchers.IO) {
                // A previous attempt that died before it could clean up leaves this behind.
                deleteStagingArtifacts(stagingFile)
                databaseEncryptionManager.backupEncryptionPrefs()
                breadcrumb(stage = "settings_import_encryption_prefs_backed_up")
                logger.i(
                    "SettingsViewModel.importDatabase",
                    "Import paths resolved",
                    mapOf(
                        "sourceUri" to sourceToken,
                        "targetPath" to databaseFile.absolutePath,
                        "stagingPath" to stagingFile.absolutePath,
                        "targetExists" to databaseFile.exists(),
                    ),
                )

                val copyResult =
                    DatabaseImportSupport.copyDatabaseArtifacts(
                        documentSource = documentSource,
                        sourceToken = sourceToken,
                        targetDatabaseFile = stagingFile,
                        strings = strings,
                    )
                val stagedSizeKB = stagingFile.length() / 1024
                logger.i(
                    "SettingsViewModel.importDatabase",
                    "Database file copied to staging",
                    mapOf(
                        "bytesCopiedKB" to (copyResult.bytesCopied / 1024),
                        "stagedSizeKB" to stagedSizeKB,
                        "filePath" to stagingFile.absolutePath,
                        "sourceKind" to copyResult.sourceKind,
                        "primaryFileName" to copyResult.primaryFileName,
                        "companionFilesCopied" to copyResult.companionFilesCopied,
                    ),
                )
                if (!stagingFile.exists()) {
                    logger.e("SettingsViewModel.importDatabase", "Validation failed: staged file does not exist after copy")
                    throw Exception("Database file not created after copy")
                }
                if (stagingFile.length() == 0L) {
                    logger.e("SettingsViewModel.importDatabase", "Validation failed: staged file size is 0 bytes")
                    throw Exception("Copied database file is empty (0 bytes)")
                }
                breadcrumb(stage = "settings_import_copy_validated", data = mapOf("sizeKB" to stagedSizeKB))

                DatabaseImportSupport.consolidateWalAfterImport(
                    dbFile = stagingFile,
                    logTag = "SettingsViewModel.importDatabase",
                    walConsolidator = walConsolidator,
                )

                // Detect encrypted imports; pause and ask user for passphrase if needed.
                val stagedIsStandardSqlite =
                    DatabaseImportSupport.isStandardSqliteFile(
                        databaseFile = stagingFile,
                        logTag = "SettingsViewModel.importDatabase",
                    )
                if (!stagedIsStandardSqlite) {
                    val isEncrypted =
                        probe.isDetectablyEncrypted(
                            databaseFile = stagingFile,
                            logTag = "SettingsViewModel.importDatabase",
                        )
                    if (isEncrypted) {
                        logger.i(
                            "SettingsViewModel.importDatabase",
                            "Encrypted import detected; pausing and awaiting user passphrase",
                        )
                        pausedForPassphrase = true
                        breadcrumb(stage = "settings_import_awaiting_passphrase")
                        pendingEncryptedImportDbFile = stagingFile
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
                    }
                    throw IllegalStateException(
                        strings.settingsImportErrorUnreadableDb,
                    )
                }

                // The file carries the SQLite plaintext magic, so it is an UNENCRYPTED database, and
                // Payanam exports IS the encrypted file - no version of Payanam can produce this one.
                // It is refused, not converted: converting it would make the import path the only
                // route by which unencrypted data entered the app.
                logger.w(
                    "SettingsViewModel.importDatabase",
                    "Refusing an unencrypted database: Payanam imports encrypted databases only",
                    mapOf("file" to stagingFile.name, "sizeBytes" to stagingFile.length()),
                )
                breadcrumb(stage = "settings_import_refused_plaintext", data = mapOf("file" to stagingFile.name))
                throw IllegalStateException(
                    strings.settingsImportErrorPlaintextDb,
                )
            }
            if (pausedForPassphrase) {
                logger.i("SettingsViewModel.importDatabase", "Import coroutine paused awaiting passphrase")
                return@launch
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(
                "SettingsViewModel.importDatabase",
                "Import failed before the live database was touched",
                e,
                mapOf(
                    "errorMessage" to (e.message ?: "Unknown error"),
                    "errorType" to e.javaClass.simpleName,
                    "pausedForPassphrase" to pausedForPassphrase,
                ),
            )
            breadcrumb(
                stage = "settings_import_failed",
                data = mapOf(
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "unknown"),
                ),
            )
            if (!pausedForPassphrase) {
                withContext(Dispatchers.IO) {
                    deleteStagingArtifacts(stagingFile)
                    pendingEncryptedImportDbFile = null
                    databaseEncryptionManager.clearEncryptionPrefsBackup()
                }
            }
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
 * Resumes a paused encrypted import: verifies [passphrase] can unlock the staged database, reads its
 * counts, then swaps it over the live database and opens it. On a wrong passphrase it stays on the
 * gate with the live session untouched; on any other failure it restores the pre-import files and
 * session.
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
        val stagingFile =
            pendingEncryptedImportDbFile
                ?: run {
                    updateUiState { it.copy(isImporting = false, importResult = ImportResult.Error("No staged import to resume")) }
                    return@launch
                }
        var swapStarted = false
        try {
            // Verify and count on the staged file while the live database is still open and untouched.
            val (tasksCount, timeEntriesCount, notesCount) =
                withContext(Dispatchers.IO) {
                    val canUnlock =
                        probe.canOpen(
                            databaseFile = stagingFile,
                            passphrase = passphrase,
                            logTag = "SettingsViewModel.resumeImportWithPassphrase",
                        )
                    if (!canUnlock) {
                        logger.w(
                            "SettingsViewModel.resumeImportWithPassphrase",
                            "Encrypted import passphrase verification failed",
                        )
                        throw IllegalStateException(strings.dbImportPassphraseWrong)
                    }
                    breadcrumb(stage = "settings_resume_import_passphrase_verified")
                    val counts =
                        DatabaseImportIntegritySupport.readCoreCounts(
                            databaseFile = stagingFile,
                            passphrase = passphrase,
                            encryptionProbe = probe,
                        )
                    Triple(counts["tasks"] ?: 0, counts["time_entries"] ?: 0, counts["notes"] ?: 0)
                }

            // From here the files change. The close is withheld from the UI as a maintenance window so
            // the Settings screen (and this prompt) is not replaced by the unlock gate mid-swap.
            sessionManager.beginInProcessSwap()
            swapStarted = true
            withContext(Dispatchers.IO) {
                val originalPassphrase =
                    if (sessionManager.isDbOpen()) sessionManager.requireOpenPassphrase() else null
                pendingImportOriginalPassphrase = originalPassphrase
                val configured = databaseEncryptionManager.configurePassphrase(passphrase)
                if (!configured) {
                    throw IllegalStateException(strings.settingsImportErrorEncryptionConvertFailed)
                }
                breadcrumb(stage = "settings_resume_import_passphrase_configured")

                sessionManager.checkpoint()
                sessionManager.closeDatabase()
                // The list is built AFTER the close: closing the last connection can remove the -wal and
                // -shm files, and a pre-close list would ask the backup to copy paths that no longer exist.
                val liveArtifacts =
                    listDatabaseArtifactFiles(databaseFile)
                        .filter { it.exists() && isActiveArtifact(it.name) }
                val backupMappings = backupDatabaseArtifactFiles(liveArtifacts)
                pendingEncryptedImportBackupMappings = backupMappings
                if (backupMappings.isNotEmpty()) {
                    logger.i(
                        "SettingsViewModel.resumeImportWithPassphrase",
                        "Created pre-import database backup snapshot",
                        mapOf("backupFiles" to backupMappings.size),
                    )
                    breadcrumb(stage = "settings_import_backup_created", data = mapOf("backupFiles" to backupMappings.size))
                }
                val deletedFiles = deleteRuntimeDatabaseArtifacts(databaseFile)
                breadcrumb(stage = "settings_import_runtime_artifacts_deleted", data = mapOf("filesDeleted" to deletedFiles))
                moveStagingArtifactsIntoPlace(stagingFile, databaseFile)

                val openResult = sessionManager.openDatabase(passphrase)
                if (openResult.isFailure) {
                    throw IllegalStateException(
                        "Imported database could not be opened after passphrase verification",
                        openResult.exceptionOrNull(),
                    )
                }
                pendingImportOriginalPassphrase = null
                breadcrumb(stage = "settings_resume_import_session_opened")

                // Success: the rollback material is no longer needed.
                databaseEncryptionManager.clearEncryptionPrefsBackup()
                cleanupDatabaseArtifactBackups(backupMappings)
                pendingEncryptedImportDbFile = null
                pendingEncryptedImportBackupMappings = emptyList()
                deleteStagingArtifacts(stagingFile)
                logger.i(
                    "SettingsViewModel.resumeImportWithPassphrase",
                    "Finalized encrypted import swap",
                    mapOf("backupFiles" to backupMappings.size),
                )
            }

            val dbSizeKB = databaseFile.length() / 1024
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("SettingsViewModel.resumeImportWithPassphrase", "Failed to resume import with passphrase", e)
            breadcrumb(
                stage = "settings_resume_import_failed",
                data = mapOf(
                    "errorType" to e.javaClass.simpleName,
                    "errorMessage" to (e.message ?: "unknown"),
                ),
            )
            if (e.message == strings.dbImportPassphraseWrong) {
                logger.w(
                    "SettingsViewModel.resumeImportWithPassphrase",
                    "Wrong import passphrase entered; staying on passphrase gate",
                )
                updateUiState { it.copy(isImporting = false, importPassphraseError = e.message) }
            } else {
                if (swapStarted) {
                    rollbackImportSwap()
                } else {
                    withContext(Dispatchers.IO) {
                        deleteStagingArtifacts(stagingFile)
                        pendingEncryptedImportDbFile = null
                        databaseEncryptionManager.restoreEncryptionPrefs()
                        databaseEncryptionManager.clearEncryptionPrefsBackup()
                    }
                }
                updateUiState {
                    it.copy(
                        isImporting = false,
                        awaitingImportPassphrase = false,
                        importResult = ImportResult.Error(e.message ?: "Import failed"),
                    )
                }
            }
        } finally {
            if (swapStarted) {
                sessionManager.endInProcessSwap(sessionManager.isDbOpen())
            }
        }
    }
}

/**
 * Aborts a paused encrypted-import passphrase gate: deletes the staged import, restores the original
 * encryption prefs, and clears the passphrase UI state.
 *
 * The live database, its artifacts and the open session were never touched while the gate was up, so
 * there is nothing to restore and no session to reopen.
 */
fun SettingsViewModel.cancelImportPassphrase() {
    logger.i("SettingsViewModel.cancelImportPassphrase", "User cancelled encrypted import passphrase prompt")
    breadcrumb(stage = "settings_resume_import_cancelled")
    scope.launch {
        withContext(Dispatchers.IO) {
            val stagingFile = pendingEncryptedImportDbFile
            if (stagingFile != null) deleteStagingArtifacts(stagingFile)
            pendingEncryptedImportDbFile = null
            pendingEncryptedImportBackupMappings = emptyList()
            pendingImportOriginalPassphrase = null
            databaseEncryptionManager.restoreEncryptionPrefs()
            databaseEncryptionManager.clearEncryptionPrefsBackup()
            logger.i(
                "SettingsViewModel.cancelImportPassphrase",
                "Cancelled import passphrase gate cleanup complete",
                mapOf("deletedStagedImport" to (stagingFile != null)),
            )
            breadcrumb(stage = "settings_resume_import_cancel_cleanup_done")
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
