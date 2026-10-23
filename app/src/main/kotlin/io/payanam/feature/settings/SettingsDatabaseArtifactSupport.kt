//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.security.DatabaseFileGuard
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.database.isActiveArtifact
import java.io.File

/**
 * Deletes database artifacts that are NOT part of the live set (orphaned
 * `-wal`/`-shm`/`-journal`/`.bak` files left behind by a failed operation).
 * Returns the number of files deleted. Active files are never touched.
 */
fun deleteStaleArtifactFiles(context: Context): Int {
    val logger = UnifiedLogger.getInstance()
    val dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
    val dbDir = dbFile.parentFile ?: return 0.also {
        logger.w("SettingsDatabaseArtifactSupport.deleteStaleArtifactFiles", "Delete stale skipped: DB dir unavailable")
    }
    var deletedCount = 0
    dbDir.listFiles()?.filter { file ->
        file.isFile && file.name.startsWith(PayanamDatabaseSchema.DATABASE_NAME) && !isActiveArtifact(file.name)
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
 * Deletes a single named database artifact, but only when [fileName] stays
 * within the database namespace (prevents path/name escape). Returns whether
 * the delete succeeded.
 */
fun deleteDatabaseArtifactFile(context: Context, fileName: String): Boolean {
    val logger = UnifiedLogger.getInstance()
    val dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
    val dbDir = dbFile.parentFile ?: return false.also {
        logger.w("SettingsDatabaseArtifactSupport.deleteDatabaseArtifactFile", "Delete artifact blocked: DB dir unavailable")
    }
    val target = File(dbDir, fileName)
    if (!target.name.startsWith(PayanamDatabaseSchema.DATABASE_NAME)) {
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
 * Wipes every database artifact for a full user-initiated wipe: first removes
 * the temp backup folder (from create-new/import flows), then deletes every
 * file in the db directory. Returns the number of items removed.
 */
fun deleteAllDatabaseArtifactFiles(context: Context): Int {
    val logger = UnifiedLogger.getInstance()
    var deletedCount = 0
    val dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
    val dbDir = dbFile.parentFile ?: return 0.also {
        logger.w("SettingsDatabaseArtifactSupport.deleteAllDatabaseArtifactFiles", "Delete-all blocked: DB dir unavailable")
    }
    // Wipe temp backup subfolder first (created during create-new / import flows)
    val tempBackupDir = File(dbDir, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
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
 * Deletes the `payanam_temp_backup` folder used by create-new/import flows.
 * Returns whether the folder was removed (true when it was already absent).
 */
fun wipeTempBackupDir(context: Context): Boolean {
    val logger = UnifiedLogger.getInstance()
    val dbDir = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME).parentFile ?: return false.also {
        logger.w("SettingsDatabaseArtifactSupport.wipeTempBackupDir", "Temp backup wipe skipped: DB dir unavailable")
    }
    val dir = File(dbDir, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)
    val existedBefore = dir.exists()
    val deleted = if (existedBefore) dir.deleteRecursively() else true
    logger.i(
        "SettingsDatabaseArtifactSupport.wipeTempBackupDir",
        "Temp backup directory wipe requested",
        mapOf("dir" to dir.absolutePath, "dirExisted" to existedBefore, "deleted" to deleted),
    )
    return deleted
}
