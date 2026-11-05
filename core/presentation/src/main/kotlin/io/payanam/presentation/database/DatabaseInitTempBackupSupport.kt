//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.presentation.database

import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseHealthReport
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbs
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * The direct-file handle each platform supplies, because three steps of the DB-init flow happen BEFORE any
 * database session exists and cannot go through one.
 *
 * WHY THESE THREE ARE PORTS RATHER THAN CALLS: every step below is a question asked of a FILE, not of a
 * session. The mark writes a row into the file an import has just produced and before
 * `openDatabase` runs; the read answers "is this a bootstrap placeholder?" on a first launch, again
 * before anything is open; the WAL fold checkpoints a temp copy so the main file can be snapshotted.
 * `AppSettingsRepository` is the contract for a database that is OPEN, and it answers through
 * `sessionManager.requireDatabase()` — which throws when there is no session. Routing these three
 * through it would be a change of behaviour at exactly the moment a first launch has no session, so
 * they ask the file directly, and the handle (a `SQLiteDatabase` cursor on Android, a JDBC
 * `ResultSet` on the desktop) stays with the platform. This is the same split
 * [io.payanam.shared.database.WalCheckpointRule] already documents for the fold: the statement and the
 * decision are shared, the handle is not.
 */
fun interface DatabaseInitFlagWriter {
    /** Writes the init-completed row straight into [databaseFile], SQLCipher when [passphrase] is set. */
    fun markInitCompleted(
        databaseFile: File,
        passphrase: String?,
    )
}

/** Reads one `app_settings` value straight out of [databaseFile], or null when the row is absent. */
fun interface DatabaseInitFlagReader {
    /**
     * Answers the stored string for [key], or null when no such row exists — the platform's handle, the
     * shared caller's question. Null is "absent", never "the empty string", so a caller can tell a
     * missing flag from one that was deliberately cleared.
     */
    fun readSettingValue(
        databaseFile: File,
        key: String,
    ): String?
}

/** Folds the write-ahead log into [databaseFile] so the main file holds every write. */
fun interface DatabaseInitWalConsolidator {
    /**
     * True when the fold succeeded and the caller may treat the main file as complete. False is a real
     * answer, not a failure to report: the caller keeps the log rather than deleting it, because a
     * database whose log was dropped while a write was still pending loses that write.
     */
    fun consolidateWalAfterImport(
        databaseFile: File,
        logTag: String,
    ): Boolean
}

/** Every DB-artifact file for [databaseFile] — the main file first, then its sidecars. */
fun dbInitGetArtifactFiles(databaseFile: File): List<File> {
    val logger = AppLogger.current
    return DatabaseArtifacts.filesFor(databaseFile).also { artifacts ->
        logger.d(
            "DatabaseInitTempBackupSupport.dbInitGetArtifactFiles",
            "Resolved active DB artifact paths",
            mapOf(
                "artifactCount" to artifacts.size,
                "existingCount" to artifacts.count { it.exists() },
            ),
        )
    }
}

/**
 * Wipes the live database artifacts and the transient import/migration leftovers, and DELIBERATELY
 * preserves [DatabaseArtifacts.TEMP_BACKUP_DIR_NAME] — wiping is the very operation a crashed init is
 * trying to recover from, so removing the one good copy in the same breath is the failure this
 * exclusion exists to prevent.
 */
fun dbInitDeleteAllFiles(databaseFile: File) {
    val logger = AppLogger.current
    CrashBreadcrumbs.record(
        source = "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
        stage = "started",
    )
    val transientPrefix = DatabaseArtifacts.transientTempNamePrefix
    val transientNames = DatabaseArtifacts.transientImportTempSuffixes.map { transientPrefix + it }
    val exactTransientName = DatabaseArtifacts.exactTransientTempName
    var deletedFiles = 0
    dbInitGetArtifactFiles(databaseFile).forEach { file ->
        if (file.exists() && file.delete()) {
            deletedFiles++
            logger.d(
                "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
                "Deleted active DB artifact",
                mapOf("file" to file.name),
            )
        }
    }
    val dbDir = databaseFile.parentFile
    dbDir?.listFiles()?.forEach { entry ->
        val isTransient =
            entry.isFile &&
                (entry.name == exactTransientName || transientNames.any { entry.name.startsWith(it) })
        if (isTransient && entry.delete()) {
            deletedFiles++
            logger.d(
                "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
                "Deleted transient import/migration artifact",
                mapOf("file" to entry.name),
            )
        }
    }
    logger.i(
        "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
        "Runtime DB artifacts wiped",
        mapOf(
            "deletedEntries" to deletedFiles,
            "tempBackupPreserved" to
                (dbDir?.let { File(it, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME).exists() } ?: false),
        ),
    )
    CrashBreadcrumbs.record(
        source = "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
        stage = "completed",
        data = mapOf("deletedEntries" to deletedFiles),
    )
}

/**
 * WAL-checkpoint the current DB into a clean state, then copy all DB dir files
 * into a `payanam_temp_backup/` subfolder. Returns the backup dir on success, null on failure.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; broad catch intentional
fun dbInitCreateSidecarSafeTempBackup(
    databaseFile: File,
    walConsolidator: DatabaseInitWalConsolidator,
): File? {
    val logger = AppLogger.current
    val logTag = "DatabaseInitTempBackupSupport.createSidecarSafeTempBackup"
    CrashBreadcrumbs.record(source = logTag, stage = "started")
    return try {
        val dbDir = databaseFile.parentFile ?: return null
        logger.i(
            logTag,
            "Creating sidecar-safe temp backup",
            mapOf(
                "dbPath" to databaseFile.absolutePath,
                "dbExists" to databaseFile.exists(),
                "dbSizeKB" to (databaseFile.length() / 1024),
            ),
        )

        walConsolidator.consolidateWalAfterImport(databaseFile = databaseFile, logTag = logTag)
        val tempDir = File(dbDir, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        val sourceArtifacts = dbInitGetArtifactFiles(databaseFile).filter { it.exists() && it.isFile }
        sourceArtifacts.forEach { file ->
            file.copyTo(File(tempDir, file.name), overwrite = true)
        }
        val backupDb = File(tempDir, databaseFile.name)
        if (!backupDb.exists() || backupDb.length() == 0L) {
            logger.w(
                logTag,
                "Backup .db missing or empty",
                mapOf("exists" to backupDb.exists()),
            )
            tempDir.deleteRecursively()
            return null
        }

        logger.i(
            logTag,
            "Temp backup created",
            mapOf(
                "dir" to tempDir.absolutePath,
                "files" to (tempDir.listFiles()?.size ?: 0),
                "sourceArtifacts" to sourceArtifacts.size,
            ),
        )
        CrashBreadcrumbs.record(
            source = logTag,
            stage = "completed",
            data = mapOf("dir" to tempDir.absolutePath, "sourceArtifacts" to sourceArtifacts.size),
        )
        tempDir
    } catch (e: IOException) {
        logger.e(logTag, "Failed to create temp backup", e)
        CrashBreadcrumbs.record(
            source = logTag,
            stage = "failed",
            data = mapOf("error" to (e.message ?: "unknown")),
        )
        null
    }
}

/**
 * Wipe any partial files from DB dir, then restore from [tempBackupDir].
 * Deletes the temp backup dir after restore regardless of outcome.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; broad catch intentional
fun dbInitRestoreFromTempBackup(
    databaseFile: File,
    tempBackupDir: File,
): Boolean {
    val logger = AppLogger.current
    CrashBreadcrumbs.record(
        source = "DatabaseInitTempBackupSupport.restoreFromTempBackup",
        stage = "started",
        data = mapOf("tempBackupDir" to tempBackupDir.absolutePath),
    )
    return try {
        if (!tempBackupDir.exists() || !tempBackupDir.isDirectory) {
            logger.w(
                "DatabaseInitTempBackupSupport.restoreFromTempBackup",
                "Temp backup directory missing; cannot restore",
                mapOf("path" to tempBackupDir.absolutePath),
            )
            return false
        }
        val dbDir = databaseFile.parentFile ?: return false
        val preExistingArtifacts = dbInitGetArtifactFiles(databaseFile).count { it.exists() }
        dbInitGetArtifactFiles(databaseFile).forEach { artifact ->
            if (artifact.exists()) {
                artifact.delete()
            }
        }
        var restoredCount = 0
        tempBackupDir.listFiles()?.forEach { src ->
            src.copyTo(File(dbDir, src.name), overwrite = true)
            restoredCount++
        }
        logger.i(
            "DatabaseInitTempBackupSupport.restoreFromTempBackup",
            "Restore complete",
            mapOf("restoredFiles" to restoredCount, "preExistingArtifacts" to preExistingArtifacts),
        )
        CrashBreadcrumbs.record(
            source = "DatabaseInitTempBackupSupport.restoreFromTempBackup",
            stage = "completed",
            data = mapOf("restoredFiles" to restoredCount),
        )
        tempBackupDir.deleteRecursively()
        restoredCount > 0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e("DatabaseInitTempBackupSupport.restoreFromTempBackup", "Restore failed", e)
        CrashBreadcrumbs.record(
            source = "DatabaseInitTempBackupSupport.restoreFromTempBackup",
            stage = "failed",
            data = mapOf("error" to (e.message ?: "unknown")),
        )
        false
    }
}

/**
 * Records first-run init as finished, writing straight into [databaseFile].
 *
 * [flagWriter] is the platform's direct-file handle, and it is a parameter rather than a
 * repository call for the reason named on [DatabaseInitFlagWriter]: this runs before the session for
 * the database being marked has ever been opened. The KEY and the VALUE still come from
 * [AppSettingsRepository] so the row written here is byte-for-byte the row a session-backed
 * `markDatabaseInitCompleted()` would write, and neither can flip the other's next launch.
 */
fun dbInitMarkInitCompletedDirect(
    databaseFile: File,
    passphrase: String?,
    flagWriter: DatabaseInitFlagWriter,
) {
    val logger = AppLogger.current
    CrashBreadcrumbs.record(
        source = "DatabaseInitTempBackupSupport.dbInitMarkInitCompletedDirect",
        stage = "started",
        data = mapOf("dbPath" to databaseFile.absolutePath, "hasPassphrase" to (passphrase != null)),
    )
    logger.i(
        "DatabaseInitTempBackupSupport.dbInitMarkInitCompletedDirect",
        "Marking database_init_completed in app_settings",
        mapOf("dbPath" to databaseFile.absolutePath, "hasPassphrase" to (passphrase != null)),
    )
    flagWriter.markInitCompleted(databaseFile = databaseFile, passphrase = passphrase)
    logger.i(
        "DatabaseInitTempBackupSupport.dbInitMarkInitCompletedDirect",
        "database_init_completed mark operation finished",
    )
    CrashBreadcrumbs.record(
        source = "DatabaseInitTempBackupSupport.dbInitMarkInitCompletedDirect",
        stage = "completed",
    )
}

/**
 * Whether first-run init already finished, read from [databaseFile] itself.
 *
 * The parsing, the logging and the fail-to-`false` behaviour are shared; [flagReader] owns only the
 * handle. The key is [AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY] and the value is read with
 * [String.toBoolean], so an absent row, a null and an unparseable value all read as `false` — which is
 * what a first launch needs.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: any read failure means "not completed yet"
fun dbInitReadInitCompletedFlag(
    databaseFile: File,
    flagReader: DatabaseInitFlagReader,
): Boolean {
    val logger = AppLogger.current
    return try {
        val result =
            flagReader
                .readSettingValue(databaseFile, AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY)
                ?.toBoolean() ?: false
        logger.i(
            "DatabaseInitTempBackupSupport.dbInitReadInitCompletedFlag",
            "Read database_init_completed from DB file",
            mapOf("dbPath" to databaseFile.absolutePath, "value" to result),
        )
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(
            "DatabaseInitViewModel.readDatabaseInitCompletedFlag",
            "Failed to read DB init flag; defaulting to false",
            mapOf("error" to (e.message ?: "unknown")),
        )
        false
    }
}

/**
 * Classifies a boot problem from the health report.
 *
 * Takes the SHARED [DatabaseHealthReport] rather than Android's own result type: the three fields it reads
 * (isHealthy, needsRepair, errorMessage) are all in the shared report, so the classification rules are
 * now the same code on both platforms and neither keeps a private copy of them.
 */
fun dbInitClassifyBootIssue(
    databaseArtifactsExist: Boolean,
    healthResult: DatabaseHealthReport,
): DatabaseBootIssue? {
    val logger = AppLogger.current
    if (!databaseArtifactsExist || healthResult.isHealthy) return null
    val message = healthResult.errorMessage?.trim()
    val normalized = message.orEmpty().lowercase()
    val type =
        when {
            normalized.contains("sidecar") && normalized.contains("primary") -> {
                DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING
            }

            normalized.contains("too old") -> {
                DatabaseBootIssueType.DB_TOO_OLD
            }

            normalized.contains("newer than app supports") || normalized.contains("please update the app") -> {
                DatabaseBootIssueType.DB_TOO_NEW
            }

            normalized.contains("missing tables") || normalized.contains("schema issues") -> {
                DatabaseBootIssueType.SCHEMA_INVALID
            }

            normalized.contains("cannot open database") -> {
                DatabaseBootIssueType.OPEN_FAILED
            }

            healthResult.needsRepair -> {
                DatabaseBootIssueType.REPAIRABLE_GENERIC
            }

            else -> {
                DatabaseBootIssueType.NON_REPAIRABLE_GENERIC
            }
        }
    return DatabaseBootIssue(
        type = type,
        detailMessage = message,
        // DatabaseBootIssue.detectedVersion is a non-null Int, and 0 is a real (too old) version rather
        // than "unknown" - so the issue's own default stands unless a version was actually measured.
        detectedVersion = healthResult.detectedVersion ?: 0,
    ).also { issue ->
        logger.i(
            "DatabaseInitTempBackupSupport.dbInitClassifyBootIssue",
            "Classified boot issue",
            mapOf(
                "type" to issue.type.name,
                "databaseArtifactsExist" to databaseArtifactsExist,
                "detectedVersion" to issue.detectedVersion,
            ),
        )
    }
}

/** Delete the temp backup dir (called on successful create/import). */
@Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; broad catch intentional
fun dbInitDeleteTempBackup(tempBackupDir: File) {
    val logger = AppLogger.current
    try {
        if (tempBackupDir.exists()) {
            tempBackupDir.deleteRecursively()
            logger.i(
                "DatabaseInitTempBackupSupport.deleteTempBackup",
                "Temp backup deleted",
                mapOf("dir" to tempBackupDir.absolutePath),
            )
        } else {
            logger.d(
                "DatabaseInitTempBackupSupport.deleteTempBackup",
                "Temp backup delete skipped because directory does not exist",
                mapOf("dir" to tempBackupDir.absolutePath),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(
            "DatabaseInitTempBackupSupport.deleteTempBackup",
            "Failed to delete temp backup",
            mapOf("error" to (e.message ?: "")),
        )
    }
}
