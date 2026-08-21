//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseFileGuard
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DatabaseArtifactUiModel.
 */
data class DatabaseArtifactUiModel(
    val fileName: String,
    val sizeKb: Long,
    val lastModifiedLabel: String,
    val isActive: Boolean = true,
)

private val activeSuffixes = setOf("", "-wal", "-shm", "-journal")

/**
 * Is active artifact.
 */
fun isActiveArtifact(fileName: String, dbName: String = PayanamDatabase.DATABASE_NAME): Boolean = activeSuffixes.any { fileName == "$dbName$it" }

private val dbArtifactDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * List database artifact files.
 */
fun listDatabaseArtifactFiles(context: Context): List<File> {
    val logger = UnifiedLogger.getInstance()
    val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
    val dbDir = dbFile.parentFile ?: return listOf(dbFile).also {
        logger.w(
            "SettingsDatabaseArtifactSupport.listDatabaseArtifactFiles",
            "DB directory unavailable; returning primary DB file only",
            mapOf("dbPath" to dbFile.absolutePath),
        )
    }
    return dbDir.listFiles()?.filter { file ->
        file.isFile && file.name.startsWith(PayanamDatabase.DATABASE_NAME)
    }?.sortedBy { it.name }?.also { files ->
        logger.d(
            "SettingsDatabaseArtifactSupport.listDatabaseArtifactFiles",
            "Resolved DB artifact file list",
            mapOf("count" to files.size, "activeCount" to files.count { isActiveArtifact(it.name) }),
        )
    } ?: emptyList()
}

/**
 * File.
 */
fun File.toDatabaseArtifactUiModel(): DatabaseArtifactUiModel {
    val modifiedLabel = Instant.ofEpochMilli(lastModified())
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(dbArtifactDateFormatter)
    return DatabaseArtifactUiModel(
        fileName = name,
        sizeKb = length() / 1024,
        lastModifiedLabel = modifiedLabel,
        isActive = isActiveArtifact(name),
    )
}

/**
 * Delete stale artifact files.
 */
fun deleteStaleArtifactFiles(context: Context): Int {
    val logger = UnifiedLogger.getInstance()
    val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
    val dbDir = dbFile.parentFile ?: return 0.also {
        logger.w("SettingsDatabaseArtifactSupport.deleteStaleArtifactFiles", "Delete stale skipped: DB dir unavailable")
    }
    var deletedCount = 0
    dbDir.listFiles()?.filter { file ->
        file.isFile && file.name.startsWith(PayanamDatabase.DATABASE_NAME) && !isActiveArtifact(file.name)
    }?.forEach { file ->
        val deleted = DatabaseFileGuard.safeDelete(
            file,
            DatabaseFileGuard.DeleteIntent.ADMIN_ARTIFACT_CLEANUP,
            "SettingsDatabaseArtifactSupport.deleteStaleArtifactFiles",
        )
        if (deleted) {
            deletedCount++
        }
        logger.d(
            "SettingsDatabaseArtifactSupport.deleteStaleArtifactFiles",
            "Stale artifact delete attempt",
            mapOf("file" to file.name, "deleted" to deleted),
        )
    }
    logger.i(
        "SettingsDatabaseArtifactSupport.deleteStaleArtifactFiles",
        "Stale artifact cleanup completed",
        mapOf("deletedCount" to deletedCount),
    )
    return deletedCount
}

/**
 * Delete database artifact file.
 */
fun deleteDatabaseArtifactFile(context: Context, fileName: String): Boolean {
    val logger = UnifiedLogger.getInstance()
    val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
    val dbDir = dbFile.parentFile ?: return false.also {
        logger.w("SettingsDatabaseArtifactSupport.deleteDatabaseArtifactFile", "Delete artifact blocked: DB dir unavailable")
    }
    val target = File(dbDir, fileName)
    if (!target.name.startsWith(PayanamDatabase.DATABASE_NAME)) {
        logger.w(
            "SettingsDatabaseArtifactSupport.deleteDatabaseArtifactFile",
            "Delete artifact blocked: target outside DB namespace",
            mapOf("fileName" to fileName),
        )
        return false
    }
    val deleted = DatabaseFileGuard.safeDelete(
        target,
        DatabaseFileGuard.DeleteIntent.ADMIN_ARTIFACT_CLEANUP,
        "SettingsDatabaseArtifactSupport.deleteDatabaseArtifactFile",
    )
    logger.i(
        "SettingsDatabaseArtifactSupport.deleteDatabaseArtifactFile",
        "Database artifact delete attempted",
        mapOf("fileName" to fileName, "deleted" to deleted),
    )
    return deleted
}

/**
 * Delete all database artifact files.
 */
fun deleteAllDatabaseArtifactFiles(context: Context): Int {
    val logger = UnifiedLogger.getInstance()
    var deletedCount = 0
    val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
    val dbDir = dbFile.parentFile ?: return 0.also {
        logger.w("SettingsDatabaseArtifactSupport.deleteAllDatabaseArtifactFiles", "Delete-all blocked: DB dir unavailable")
    }
    // Wipe temp backup subfolder first (created during create-new / import flows)
    val tempBackupDir = File(dbDir, "payanam_temp_backup")
    if (tempBackupDir.exists()) {
        val deletedDir = DatabaseFileGuard.safeDeleteDir(
            tempBackupDir,
            DatabaseFileGuard.DeleteIntent.USER_DELETE_ALL,
            "SettingsDatabaseArtifactSupport.deleteAllDatabaseArtifactFiles",
        )
        if (deletedDir) {
            deletedCount++
            logger.i(
                "SettingsDatabaseArtifactSupport.deleteAllDatabaseArtifactFiles",
                "Deleted temp backup dir",
                mapOf("path" to tempBackupDir.absolutePath),
            )
        } else {
            logger.w(
                "SettingsDatabaseArtifactSupport.deleteAllDatabaseArtifactFiles",
                "Failed to delete temp backup dir",
                mapOf("path" to tempBackupDir.absolutePath),
            )
        }
    }
    // Wipe every file in DB dir (.db, -wal, -shm, -journal, .bak, tmp — everything)
    dbDir.listFiles()?.forEach { file ->
        if (file.isFile) {
            val deleted = DatabaseFileGuard.safeDelete(
                file,
                DatabaseFileGuard.DeleteIntent.USER_DELETE_ALL,
                "SettingsDatabaseArtifactSupport.deleteAllDatabaseArtifactFiles",
            )
            if (deleted) {
                deletedCount++
            }
            logger.d(
                "SettingsDatabaseArtifactSupport.deleteAllDatabaseArtifactFiles",
                "Delete-all artifact attempt",
                mapOf("file" to file.name, "deleted" to deleted),
            )
        }
    }
    logger.i(
        "SettingsDatabaseArtifactSupport.deleteAllDatabaseArtifactFiles",
        "Deleted all database artifacts for user wipe",
        mapOf("deletedCount" to deletedCount),
    )
    return deletedCount
}

/**
 * Delete runtime database artifacts.
 */
fun deleteRuntimeDatabaseArtifacts(context: Context): Int {
    val logger = UnifiedLogger.getInstance()
    var deletedCount = 0
    val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
    val dbDir = dbFile.parentFile ?: return 0.also {
        logger.w("SettingsDatabaseArtifactSupport.deleteRuntimeDatabaseArtifacts", "Runtime delete skipped: DB dir unavailable")
    }
    dbDir.listFiles()?.forEach { file ->
        if (!file.isFile) return@forEach
        val isActive = isActiveArtifact(file.name)
        val isTransientImportTemp = file.name == "${PayanamDatabase.DATABASE_NAME}.enc.tmp" ||
            file.name.startsWith("${PayanamDatabase.DATABASE_NAME}.enc.tmp-") ||
            file.name.startsWith("${PayanamDatabase.DATABASE_NAME}.wal_merge_tmp") ||
            file.name.startsWith("${PayanamDatabase.DATABASE_NAME}.import_decrypt.tmp")
        if (isActive || isTransientImportTemp) {
            val deleted = DatabaseFileGuard.safeDelete(
                file,
                DatabaseFileGuard.DeleteIntent.USER_DELETE_ALL,
                "SettingsDatabaseArtifactSupport.deleteRuntimeDatabaseArtifacts",
            )
            if (deleted) {
                deletedCount++
            }
            logger.d(
                "SettingsDatabaseArtifactSupport.deleteRuntimeDatabaseArtifacts",
                "Runtime artifact delete attempt",
                mapOf(
                    "file" to file.name,
                    "isActive" to isActive,
                    "isTransientImportTemp" to isTransientImportTemp,
                    "deleted" to deleted,
                ),
            )
        }
    }
    logger.i(
        "SettingsDatabaseArtifactSupport.deleteRuntimeDatabaseArtifacts",
        "Runtime database artifacts delete completed",
        mapOf("deletedCount" to deletedCount),
    )
    return deletedCount
}

/**
 * Wipe temp backup dir.
 */
fun wipeTempBackupDir(context: Context): Boolean {
    val logger = UnifiedLogger.getInstance()
    val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile ?: return false.also {
        logger.w("SettingsDatabaseArtifactSupport.wipeTempBackupDir", "Temp backup wipe skipped: DB dir unavailable")
    }
    val dir = File(dbDir, "payanam_temp_backup")
    val existedBefore = dir.exists()
    val deleted = if (existedBefore) dir.deleteRecursively() else true
    logger.i(
        "SettingsDatabaseArtifactSupport.wipeTempBackupDir",
        "Temp backup directory wipe requested",
        mapOf("dir" to dir.absolutePath, "dirExisted" to existedBefore, "deleted" to deleted),
    )
    return deleted
}

/**
 * Backup database artifact files.
 */
fun backupDatabaseArtifactFiles(files: List<File>): List<Pair<File, File>> {
    val logger = UnifiedLogger.getInstance()
    val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
    return files.map { original ->
        val backup = File(original.parent, "${original.name}.before_import_$timestamp.bak")
        original.copyTo(backup, overwrite = true)
        logger.d(
            "SettingsDatabaseArtifactSupport.backupDatabaseArtifactFiles",
            "Created artifact backup",
            mapOf("source" to original.name, "backup" to backup.name),
        )
        original to backup
    }.also { mappings ->
        logger.i(
            "SettingsDatabaseArtifactSupport.backupDatabaseArtifactFiles",
            "Created DB artifact backup mappings",
            mapOf("sourceCount" to files.size, "backupCount" to mappings.size),
        )
    }
}

/**
 * Restore database artifact files.
 */
fun restoreDatabaseArtifactFiles(mappings: List<Pair<File, File>>): Int {
    val logger = UnifiedLogger.getInstance()
    var restored = 0
    mappings.forEach { (original, backup) ->
        if (backup.exists()) {
            backup.copyTo(original, overwrite = true)
            restored++
            logger.d(
                "SettingsDatabaseArtifactSupport.restoreDatabaseArtifactFiles",
                "Restored artifact from backup",
                mapOf("sourceBackup" to backup.name, "target" to original.name),
            )
        } else {
            logger.w(
                "SettingsDatabaseArtifactSupport.restoreDatabaseArtifactFiles",
                "Backup mapping missing during restore",
                mapOf("sourceBackup" to backup.name, "target" to original.name),
            )
        }
    }
    logger.i(
        "SettingsDatabaseArtifactSupport.restoreDatabaseArtifactFiles",
        "Restore from DB artifact backup mappings completed",
        mapOf("mappingCount" to mappings.size, "restoredCount" to restored),
    )
    return restored
}

/**
 * Cleanup database artifact backups.
 */
fun cleanupDatabaseArtifactBackups(mappings: List<Pair<File, File>>) {
    val logger = UnifiedLogger.getInstance()
    var deleted = 0
    mappings.forEach { (_, backup) ->
        if (backup.exists()) {
            val removed = backup.delete()
            if (removed) {
                deleted++
            } else {
                logger.w(
                    "SettingsDatabaseArtifactSupport.cleanupDatabaseArtifactBackups",
                    "Failed to delete backup artifact",
                    mapOf("backup" to backup.name),
                )
            }
        }
    }
    logger.i(
        "SettingsDatabaseArtifactSupport.cleanupDatabaseArtifactBackups",
        "Cleanup of DB artifact backup mappings completed",
        mapOf("mappingCount" to mappings.size, "deletedCount" to deleted),
    )
}
