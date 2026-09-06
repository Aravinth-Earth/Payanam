//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import java.io.File
import java.io.IOException

internal fun dbInitGetArtifactFiles(context: Context): List<File> {
    val logger = UnifiedLogger.getInstance()
    val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
    return listOf(
        dbFile,
        File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal"),
        File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-shm"),
        File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-journal"),
    ).also { artifacts ->
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

internal fun dbInitDeleteAllFiles(context: Context) {
    val logger = UnifiedLogger.getInstance()
    CrashSafeBreadcrumbs.record(
        context = context,
        source = "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
        stage = "started",
    )
    val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
    var deletedFiles = 0
    dbInitGetArtifactFiles(context).forEach { file ->
        if (file.exists() && file.delete()) {
            deletedFiles++
            logger.d(
                "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
                "Deleted active DB artifact",
                mapOf("file" to file.name),
            )
        }
    }
    val dbDir = dbFile.parentFile
    dbDir?.listFiles()?.forEach { entry ->
        if (
            entry.isFile && (
                entry.name == "${PayanamDatabase.DATABASE_NAME}.enc.tmp" ||
                    entry.name.startsWith("${PayanamDatabase.DATABASE_NAME}.enc.tmp-") ||
                    entry.name.startsWith("${PayanamDatabase.DATABASE_NAME}.wal_merge_tmp") ||
                    entry.name.startsWith("${PayanamDatabase.DATABASE_NAME}.import_decrypt.tmp")
                )
        ) {
            if (entry.delete()) {
                deletedFiles++
                logger.d(
                    "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
                    "Deleted transient import/migration artifact",
                    mapOf("file" to entry.name),
                )
            }
        }
    }
    logger.i(
        "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
        "Runtime DB artifacts wiped",
        mapOf(
            "deletedEntries" to deletedFiles,
            "tempBackupPreserved" to (dbFile.parentFile?.let { File(it, "payanam_temp_backup").exists() } ?: false),
        ),
    )
    CrashSafeBreadcrumbs.record(
        context = context,
        source = "DatabaseInitTempBackupSupport.dbInitDeleteAllFiles",
        stage = "completed",
        data = mapOf("deletedEntries" to deletedFiles),
    )
}

/**
 * WAL-checkpoint the current DB into a clean state, then copy all DB dir files
 * into a `payanam_temp_backup/` subfolder. Returns the backup dir on success, null on failure.
 */
@Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; broad catch intentional
internal fun dbInitCreateSidecarSafeTempBackup(context: Context): File? {
    val logger = UnifiedLogger.getInstance()
    CrashSafeBreadcrumbs.record(
        context = context,
        source = "DatabaseInitTempBackupSupport.createSidecarSafeTempBackup",
        stage = "started",
    )
    return try {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        val dbDir = dbFile.parentFile ?: return null
        logger.i(
            "DatabaseInitTempBackupSupport.createSidecarSafeTempBackup",
            "Creating sidecar-safe temp backup",
            mapOf(
                "dbPath" to dbFile.absolutePath,
                "dbExists" to dbFile.exists(),
                "dbSizeKB" to (dbFile.length() / 1024),
            ),
        )

        DatabaseImportSupport.consolidateWalAfterImport(
            dbFile = dbFile,
            logTag = "DatabaseInitTempBackupSupport.createSidecarSafeTempBackup",
        )
        val tempDir = File(dbDir, "payanam_temp_backup")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        val sourceArtifacts = dbInitGetArtifactFiles(context).filter { it.exists() && it.isFile }
        sourceArtifacts.forEach { file ->
            file.copyTo(File(tempDir, file.name), overwrite = true)
        }
        val backupDb = File(tempDir, PayanamDatabase.DATABASE_NAME)
        if (!backupDb.exists() || backupDb.length() == 0L) {
            logger.w(
                "DatabaseInitTempBackupSupport.createSidecarSafeTempBackup",
                "Backup .db missing or empty",
                mapOf("exists" to backupDb.exists()),
            )
            tempDir.deleteRecursively()
            return null
        }

        logger.i(
            "DatabaseInitTempBackupSupport.createSidecarSafeTempBackup",
            "Temp backup created",
            mapOf(
                "dir" to tempDir.absolutePath,
                "files" to (tempDir.listFiles()?.size ?: 0),
                "sourceArtifacts" to sourceArtifacts.size,
            ),
        )
        CrashSafeBreadcrumbs.record(
            context = context,
            source = "DatabaseInitTempBackupSupport.createSidecarSafeTempBackup",
            stage = "completed",
            data = mapOf("dir" to tempDir.absolutePath, "sourceArtifacts" to sourceArtifacts.size),
        )
        tempDir
    } catch (e: IOException) {
        logger.e(
            "DatabaseInitTempBackupSupport.createSidecarSafeTempBackup",
            "Failed to create temp backup",
            e,
        )
        CrashSafeBreadcrumbs.record(
            context = context,
            source = "DatabaseInitTempBackupSupport.createSidecarSafeTempBackup",
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
@Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; broad catch intentional
internal fun dbInitRestoreFromTempBackup(context: Context, tempBackupDir: File): Boolean {
    val logger = UnifiedLogger.getInstance()
    CrashSafeBreadcrumbs.record(
        context = context,
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
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        val dbDir = dbFile.parentFile ?: return false
        val preExistingArtifacts = dbInitGetArtifactFiles(context).count { it.exists() }
        dbInitGetArtifactFiles(context).forEach { artifact ->
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
        CrashSafeBreadcrumbs.record(
            context = context,
            source = "DatabaseInitTempBackupSupport.restoreFromTempBackup",
            stage = "completed",
            data = mapOf("restoredFiles" to restoredCount),
        )
        tempBackupDir.deleteRecursively()
        restoredCount > 0
    } catch (e: Exception) {
        logger.e("DatabaseInitTempBackupSupport.restoreFromTempBackup", "Restore failed", e)
        CrashSafeBreadcrumbs.record(
            context = context,
            source = "DatabaseInitTempBackupSupport.restoreFromTempBackup",
            stage = "failed",
            data = mapOf("error" to (e.message ?: "unknown")),
        )
        false
    }
}

internal fun dbInitMarkInitCompletedDirect(context: Context, dbFile: File, passphrase: String?) {
    val logger = UnifiedLogger.getInstance()
    CrashSafeBreadcrumbs.record(
        context = context,
        source = "DatabaseInitTempBackupSupport.dbInitMarkInitCompletedDirect",
        stage = "started",
        data = mapOf("dbPath" to dbFile.absolutePath, "hasPassphrase" to (passphrase != null)),
    )
    logger.i(
        "DatabaseInitTempBackupSupport.dbInitMarkInitCompletedDirect",
        "Marking database_init_completed in app_settings",
        mapOf("dbPath" to dbFile.absolutePath, "hasPassphrase" to (passphrase != null)),
    )
    DatabaseEncryptionMigrationSupport.markDatabaseInitCompleted(
        context = context,
        databaseFile = dbFile,
        passphrase = passphrase,
        logTag = "DatabaseInitViewModel.markDatabaseInitCompletedDirect",
    )
    logger.i(
        "DatabaseInitTempBackupSupport.dbInitMarkInitCompletedDirect",
        "database_init_completed mark operation finished",
    )
    CrashSafeBreadcrumbs.record(
        context = context,
        source = "DatabaseInitTempBackupSupport.dbInitMarkInitCompletedDirect",
        stage = "completed",
    )
}

@Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; broad catch intentional
internal fun dbInitReadInitCompletedFlag(dbFile: File): Boolean {
    val logger = UnifiedLogger.getInstance()
    return try {
        val result = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT value FROM app_settings WHERE key = ? LIMIT 1",
                arrayOf("database_init_completed"),
            ).use { c ->
                if (c.moveToFirst()) c.getString(0)?.toBoolean() ?: false else false
            }
        }
        logger.i(
            "DatabaseInitTempBackupSupport.dbInitReadInitCompletedFlag",
            "Read database_init_completed from DB file",
            mapOf("dbPath" to dbFile.absolutePath, "value" to result),
        )
        result
    } catch (e: Exception) {
        logger.w(
            "DatabaseInitViewModel.readDatabaseInitCompletedFlag",
            "Failed to read DB init flag; defaulting to false",
            mapOf("error" to (e.message ?: "unknown")),
        )
        false
    }
}

internal fun dbInitClassifyBootIssue(
    databaseArtifactsExist: Boolean,
    healthResult: DatabaseHealthChecker.HealthCheckResult,
): DatabaseBootIssue? {
    val logger = UnifiedLogger.getInstance()
    if (!databaseArtifactsExist || healthResult.isHealthy) return null
    val message = healthResult.errorMessage?.trim()
    val normalized = message.orEmpty().lowercase()
    val type = when {
        normalized.contains("sidecar") && normalized.contains("primary") ->
            DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING

        normalized.contains("too old") ->
            DatabaseBootIssueType.DB_TOO_OLD

        normalized.contains("newer than app supports") || normalized.contains("please update the app") ->
            DatabaseBootIssueType.DB_TOO_NEW

        normalized.contains("missing tables") || normalized.contains("schema issues") ->
            DatabaseBootIssueType.SCHEMA_INVALID

        normalized.contains("cannot open database") ->
            DatabaseBootIssueType.OPEN_FAILED

        healthResult.needsRepair ->
            DatabaseBootIssueType.REPAIRABLE_GENERIC

        else ->
            DatabaseBootIssueType.NON_REPAIRABLE_GENERIC
    }
    return DatabaseBootIssue(
        type = type,
        detailMessage = message,
        detectedVersion = healthResult.currentVersion,
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
@Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; broad catch intentional
internal fun dbInitDeleteTempBackup(tempBackupDir: File) {
    val logger = UnifiedLogger.getInstance()
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
    } catch (e: Exception) {
        logger.w(
            "DatabaseInitTempBackupSupport.deleteTempBackup",
            "Failed to delete temp backup",
            mapOf("error" to (e.message ?: "")),
        )
    }
}
