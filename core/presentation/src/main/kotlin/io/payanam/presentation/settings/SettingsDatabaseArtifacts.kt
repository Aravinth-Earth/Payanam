//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.settings

import io.payanam.database.security.DatabaseFileGuard
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.database.isActiveArtifact
import io.payanam.shared.logging.AppLogger
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Context-free database-artifact helpers promoted from the app's
 * `SettingsDatabaseArtifactSupport` so they resolve under a shared module
 * (both platforms consume `core:presentation`). Paths are injected by the
 * callers — nothing here derives a database path from platform context
 * (Layer 3 ruling 4: shared logic, platform-specific paths at the edges).
 *
 * Logging uses [AppLogger.current] rather than a direct `UnifiedLogger`
 * lookup: on Android the real logger is installed through
 * `AppLogger.install(...)` at app startup (PayanamApp), so messages are
 * emitted identically while this module stays platform-neutral.
 */
private val dbArtifactDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Lists the database files (main db and its companion suffixes) present in the
 * app's database directory, sorted by name. Falls back to the primary db file
 * when the directory is unavailable.
 */
fun listDatabaseArtifactFiles(dbFile: File): List<File> {
    val logger = AppLogger.current
    val dbDir =
        dbFile.parentFile ?: return listOf(dbFile).also {
            logger.w(
                "SettingsDatabaseArtifactSupport.listDatabaseArtifactFiles",
                "DB directory unavailable; returning primary DB file only",
                mapOf("dbPath" to dbFile.absolutePath),
            )
        }
    return dbDir
        .listFiles()
        ?.filter { file ->
            file.isFile && file.name.startsWith(PayanamDatabaseSchema.DATABASE_NAME)
        }?.sortedBy { it.name }
        ?.also { files ->
            logger.d(
                "SettingsDatabaseArtifactSupport.listDatabaseArtifactFiles",
                "Resolved DB artifact file list",
                mapOf("count" to files.size, "activeCount" to files.count { isActiveArtifact(it.name) }),
            )
        } ?: emptyList()
}

/**
 * Maps this [File] to a [DatabaseArtifactUiModel], formatting its size (KB) and
 * last-modified timestamp and flagging whether it is an active db artifact.
 */
fun File.toDatabaseArtifactUiModel(): DatabaseArtifactUiModel {
    val modifiedLabel =
        Instant
            .ofEpochMilli(lastModified())
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
 * Deletes the live db files and transient import temp files (`.enc.tmp*`,
 * `.wal_merge_tmp*`, `.import_decrypt.tmp*`) to recover from a crashed or
 * interrupted import. Returns the number of files removed.
 */
fun deleteRuntimeDatabaseArtifacts(dbFile: File): Int {
    val logger = AppLogger.current
    var deletedCount = 0
    val dbDir =
        dbFile.parentFile ?: return 0.also {
            logger.w("SettingsDatabaseArtifactSupport.deleteRuntimeDatabaseArtifacts", "Runtime delete skipped: DB dir unavailable")
        }
    dbDir.listFiles()?.forEach { file ->
        if (!file.isFile) return@forEach
        val isActive = isActiveArtifact(file.name)
        val isTransientImportTemp =
            file.name == DatabaseArtifacts.exactTransientTempName ||
                DatabaseArtifacts.transientImportTempSuffixes.any { suffix ->
                    file.name.startsWith(DatabaseArtifacts.transientTempNamePrefix + suffix)
                }
        if (isActive || isTransientImportTemp) {
            val deleted =
                DatabaseFileGuard.safeDelete(
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
 * Copies each file to `<name>.before_import_<timestamp>.bak` before an import,
 * returning the source → backup pairs so the operation can be rolled back.
 */
fun backupDatabaseArtifactFiles(files: List<File>): List<Pair<File, File>> {
    val logger = AppLogger.current
    val timestamp =
        java.time.LocalDateTime
            .now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
    return files
        .map { original ->
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
 * Restores database files from a prior [backupDatabaseArtifactFiles] snapshot,
 * copying each backup back over its original. Returns the number restored;
 * missing backups are skipped with a warning.
 */
fun restoreDatabaseArtifactFiles(mappings: List<Pair<File, File>>): Int {
    val logger = AppLogger.current
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
 * Deletes the `.bak` backup files created by [backupDatabaseArtifactFiles]
 * once the import is committed or rolled back. Counts only successful deletes.
 */
fun cleanupDatabaseArtifactBackups(mappings: List<Pair<File, File>>) {
    val logger = AppLogger.current
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
