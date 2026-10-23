//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.settings

import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.text.uiStringsFor
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.platform.AndroidDatabaseEncryptionProbe
import io.payanam.platform.AndroidDocumentSource
import io.payanam.presentation.database.DatabaseImportIntegritySupport
import io.payanam.presentation.database.DatabaseImportSupport
import io.payanam.presentation.database.DatabaseInitWalConsolidator
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.logging.CrashBreadcrumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
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
 * The handle the promoted `DatabaseImportIntegritySupport` reads counts through.
 *
 * It is constructed here rather than injected into `SettingsViewModel` because the counts are read by these
 * import extensions, not by the ViewModel's own state — and this file is where the `Context` already lives.
 * `AndroidDatabaseEncryptionProbe` is a thin adapter over the same
 * `DatabaseEncryptionMigrationSupport` this file already calls, so the rules are unchanged: only the handle
 * moved. `SettingsViewModel` is the better home for this once its constructor can take the shared port
 * (`:core:presentation` cannot depend on an Android adapter, and a UI-state ViewModel is the wrong owner
 * of a file-reading port either way).
 */
private val SettingsViewModel.importEncryptionProbe: DatabaseEncryptionProbe
    get() = AndroidDatabaseEncryptionProbe(context)

/**
 * The copy table the promoted import support throws with.
 *
 * `SettingsViewModel` predates the shared [io.payanam.core.ui.text.UiStrings] constructor parameter that
 * `DatabaseInitViewModel` already takes, so the language is resolved here through the same
 * [uiStringsFor] the Hilt module uses - one resolution rule, not two. Injecting it properly is a
 * constructor change to a ViewModel this wave does not own; reading it here keeps the promoted code's
 * signature honest without editing the ViewModel's public API.
 */
private val SettingsViewModel.importStrings: io.payanam.core.ui.text.UiStrings
    get() = uiStringsFor(context)

/**
 * Runs `PRAGMA wal_checkpoint(TRUNCATE)` on the TEMP copy the shared orchestration hands over, and answers
 * whether the log was actually emptied.
 *
 * Platform-specific by necessity (a Room handle, or this one already-open read-only file check for a
 * plaintext temp file), and deliberately the SAME evidence the shared [WalCheckpointRule] defines: a fold
 * that opened without error but left frames behind is not a fold, because the caller deletes the
 * production `-wal` on a `true` and those frames exist nowhere else.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: a bad WAL can throw anything from the native layer
private fun consolidateWalOnTempCopy(
    dbFile: File,
    logTag: String,
): Boolean =
    try {
        var report: WalCheckpointRule.FoldReport = WalCheckpointRule.NO_ROW
        android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            android.database.DatabaseErrorHandler { /* no-op: temp file only, not production DB */ },
        ).use { db ->
            db.rawQuery(WalCheckpointRule.FOLD_STATEMENT, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    report =
                        WalCheckpointRule.reportOf(
                            intArrayOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)),
                        )
                } else {
                    report = WalCheckpointRule.NO_ROW
                }
            }
        }
        dbFile.exists() && dbFile.length() > 0 && WalCheckpointRule.walIsEmpty(report)
    } catch (e: Exception) {
        UnifiedLogger.getInstance().w(
            logTag,
            "WAL checkpoint on temp copy failed; preserving WAL to avoid data loss",
            mapOf("error" to (e.message ?: "Unknown"), "exception" to e.javaClass.simpleName),
        )
        false
    }

/**
 * Starts an ENCRYPTED database import from [sourceUri]: backs up the current DB + encryption prefs,
 * swaps in the imported file, and if the imported file is encrypted it pauses and surfaces
 * [awaitingImportPassphrase], resuming via [resumeImportWithPassphrase].
 *
 * A PLAINTEXT database is REFUSED, not converted (`DB-IMPORT-ENCRYPTED-ONLY`): Payanam's export IS
 * the encrypted file, so an unencrypted database is not a weaker export to accept but a file no
 * version of Payanam can produce. On any failure - including that refusal - the pre-import backup is
 * restored, so the user's own database survives byte-for-byte.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: settings pipeline; multiple failure modes
fun SettingsViewModel.importDatabase(sourceUri: Uri) {
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
    logger.i("SettingsViewModel.importDatabase", "Import started", mapOf("sourceUri" to sourceUri.toString()))
    breadcrumb(stage = "settings_import_started", data = mapOf("sourceUri" to sourceUri.toString()))
    viewModelScope.launch {
        updateUiState { it.copy(isImporting = true, importResult = null) }

        // Flag to prevent backup cleanup when paused at passphrase gate
        var pausedForPassphrase = false

        try {
            withContext(Dispatchers.IO) {
                val dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
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
                        documentSource = AndroidDocumentSource(context),
                        sourceToken = sourceUri.toString(),
                        targetDatabaseFile = dbFile,
                        strings = importStrings,
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
                        walConsolidator = DatabaseInitWalConsolidator { file, tag ->
                            consolidateWalOnTempCopy(dbFile = file, logTag = tag)
                        },
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
                        context.getString(io.payanam.R.string.settings_import_error_plaintext_db),
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
            val dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
            val (tasksCount, timeEntriesCount, notesCount) = withContext(Dispatchers.IO) {
                val passphrase = if (databaseEncryptionManager.isEncryptionEnabled()) sessionManager.requireOpenPassphrase() else null
                val counts =
                    DatabaseImportIntegritySupport.readCoreCounts(
                        databaseFile = dbFile,
                        passphrase = passphrase,
                        encryptionProbe = importEncryptionProbe,
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
                        requiresAppRestart = true,
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
 * Resumes a paused encrypted import: verifies [passphrase] can unlock the
 * imported DB, adopts it as the new local passphrase, then discards the prefs
 * + artifact backups and reports the imported counts. On a wrong passphrase it
 * stays on the gate; on any other failure it restores the backups.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: settings pipeline; multiple failure modes
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
            val dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
            val (tasksCount, timeEntriesCount, notesCount) = withContext(Dispatchers.IO) {
                val counts =
                    DatabaseImportIntegritySupport.readCoreCounts(
                        databaseFile = dbFile,
                        passphrase = passphrase,
                        encryptionProbe = importEncryptionProbe,
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
                        requiresAppRestart = true,
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
 * Aborts a paused encrypted-import passphrase gate: restores the original
 * encryption prefs, deletes the staged imported DB, restores any pre-import
 * artifact backup, and clears the passphrase UI state.
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
