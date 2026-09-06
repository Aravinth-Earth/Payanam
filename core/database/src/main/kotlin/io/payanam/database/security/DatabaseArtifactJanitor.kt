//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.security

import android.content.Context
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import java.io.File
object DatabaseArtifactJanitor {
    private val logger = UnifiedLogger.getInstance()
    private val countTables = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes")
    /**
     * Cleans up leftover database artifacts from failed import/encrypt flows:
     * restores the primary DB from a temp backup if the primary is missing,
     * deletes orphaned `.enc.tmp`/`.lck`/`.bak`/`.before_*` files, and prunes
     * old `.corrupt` snapshots beyond the 3 most recent.
     */
    fun cleanupStaleArtifacts(
        context: Context,
        logTag: String = "DatabaseArtifactJanitor.cleanupStaleArtifacts",
    ) {
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile ?: return
        val cacheDir = context.cacheDir
        logger.i(
            logTag,
            "Starting database artifact cleanup",
            mapOf(
                "dbDir" to dbDir.absolutePath,
                "cacheDir" to cacheDir.absolutePath,
            ),
        )
        val recovered = recoverFromRicherCorruptSnapshot(context, "$logTag.recover")
        var deleted = 0
        val tempBackupDir = File(dbDir, "payanam_temp_backup")
        // Recover from temp backup if the primary DB is missing, then clean only truly orphaned temp backups.
        val restoredFromTempBackup = recoverFromTempBackupIfPrimaryMissing(context, tempBackupDir, logTag)
        val primaryDbExistsAfterRecovery = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).exists()
        if (tempBackupDir.exists() && !restoredFromTempBackup && primaryDbExistsAfterRecovery) {
            if (tempBackupDir.deleteRecursively()) {
                deleted++
                logger.i(
                    logTag,
                    "Cleaned orphaned temp backup dir",
                    mapOf("path" to tempBackupDir.absolutePath),
                )
            } else {
                logger.w(
                    logTag,
                    "Failed to delete orphaned temp backup dir",
                    mapOf("path" to tempBackupDir.absolutePath),
                )
            }
        }

        deleted +=
            deleteMatching(dbDir, logTag) { file ->
                file.name.endsWith(".enc.tmp") ||
                    file.name.contains(".enc.tmp-") ||
                    file.name == "${PayanamDatabase.DATABASE_NAME}.enc.tmp" ||
                    file.name == "${PayanamDatabase.DATABASE_NAME}.lck" ||
                    file.name.contains(".before_import_") ||
                    file.name.contains(".before_encrypt_")
            }
        deleted +=
            deleteMatching(cacheDir, logTag) { file ->
                file.name.startsWith("${PayanamDatabase.DATABASE_NAME}.") &&
                    (file.name.contains(".enc.tmp") || file.name.endsWith(".lck") || file.name.endsWith(".bak"))
            }
        deleted += pruneCorruptSnapshots(dbDir)
        if (deleted > 0 || recovered || restoredFromTempBackup) {
            logger.i(
                logTag,
                "Database artifact cleanup completed",
                mapOf(
                    "deletedFiles" to deleted,
                    "recovered" to recovered,
                    "restoredFromTempBackup" to restoredFromTempBackup,
                ),
            )
        } else {
            logger.d(logTag, "No stale database artifacts found")
        }
    }

    private fun recoverFromRicherCorruptSnapshot(
        context: Context,
        logTag: String,
    ): Boolean {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        if (!dbFile.exists()) {
            logger.d(logTag, "Skipping richer-corrupt recovery: primary DB file missing")
            return false
        }
        val dbDir = dbFile.parentFile ?: return false
        val encryptionManager = DatabaseEncryptionManager(context)
        if (encryptionManager.isEncryptionEnabled()) {
            logger.i(
                logTag,
                "Skipping richer-corrupt recovery for encrypted mode at cold boot (no framework-open probes allowed)",
            )
            return false
        }
        val passphrase: String? = null
        val primaryTotal = readTotalCount(context, dbFile, passphrase)
        if (primaryTotal > 0) {
            logger.d(
                logTag,
                "Skipping richer-corrupt recovery: primary DB has non-zero content counts",
                mapOf("primaryTotal" to primaryTotal),
            )
            return false
        }
        val candidateBases =
            dbDir.listFiles().orEmpty().filter {
                it.isFile &&
                    it.name.startsWith("${PayanamDatabase.DATABASE_NAME}.corrupt") &&
                    !it.name.endsWith("-wal") &&
                    !it.name.endsWith("-shm") &&
                    !it.name.endsWith("-journal")
            }
        if (candidateBases.isEmpty()) {
            logger.d(logTag, "Skipping richer-corrupt recovery: no corrupt snapshot candidates found")
            return false
        }
        val bestCandidate =
            candidateBases
                .map { candidate -> candidate to readTotalCount(context, candidate, passphrase) }
                .maxByOrNull { it.second }
                ?.takeIf { (_, total) -> total > primaryTotal }
                ?: return false
        val candidateBase = bestCandidate.first
        val candidateTotal = bestCandidate.second
        logger.i(
            logTag,
            "Selected richer-corrupt candidate for recovery",
            mapOf(
                "candidateName" to candidateBase.name,
                "candidateTotal" to candidateTotal,
                "primaryTotal" to primaryTotal,
            ),
        )
        val timestamp = System.currentTimeMillis()
        val backups = mutableListOf<Pair<File, File>>()
        try {
            listOf(dbFile, sidecarFor(dbFile, "wal"), sidecarFor(dbFile, "shm"), sidecarFor(dbFile, "journal"))
                .filter { it.exists() }
                .forEach { file ->
                    val backup = File(dbDir, "${file.name}.janitor_backup_$timestamp.bak")
                    file.copyTo(backup, overwrite = true)
                    backups += file to backup
                }

            candidateBase.copyTo(dbFile, overwrite = true)
            replaceFromCandidateSidecar(candidateBase, dbFile, "wal")
            replaceFromCandidateSidecar(candidateBase, dbFile, "shm")
            replaceFromCandidateSidecar(candidateBase, dbFile, "journal")
            val restoredTotal = readTotalCount(context, dbFile, passphrase)
            check(restoredTotal > primaryTotal) {
                "Recovered database did not improve table counts."
            }

            logger.w(
                logTag,
                "Recovered primary database from richer corrupt snapshot",
                mapOf(
                    "primaryTotalBefore" to primaryTotal,
                    "candidateTotal" to candidateTotal,
                    "restoredTotal" to restoredTotal,
                    "candidateName" to candidateBase.name,
                ),
            )
            deleteCorruptFamily(candidateBase)
            backups.forEach { (_, backup) -> if (backup.exists()) backup.delete() }
            return true
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
            backups.forEach { (original, backup) ->
                if (backup.exists()) {
                    backup.copyTo(original, overwrite = true)
                    backup.delete()
                }
            }
            logger.e(logTag, "Failed to recover database from corrupt snapshot", error)
            return false
        }
    }

    private fun readTotalCount(
        context: Context,
        databaseFile: File,
        passphrase: String?,
    ): Int {
        val counts =
            DatabaseEncryptionMigrationSupport.readTableCounts(
                context = context,
                databaseFile = databaseFile,
                passphrase = passphrase,
                tableNames = countTables,
            )
        val total = countTables.sumOf { key -> counts[key] ?: 0 }
        logger.d(
            "DatabaseArtifactJanitor.readTotalCount",
            "Computed aggregate table counts for candidate file",
            mapOf(
                "file" to databaseFile.name,
                "total" to total,
            ),
        )
        return total
    }

    private fun replaceFromCandidateSidecar(
        candidateBase: File,
        targetBase: File,
        suffix: String,
    ) {
        val candidateSidecar = sidecarFor(candidateBase, suffix)
        val targetSidecar = sidecarFor(targetBase, suffix)
        if (candidateSidecar.exists()) {
            candidateSidecar.copyTo(targetSidecar, overwrite = true)
            logger.d(
                "DatabaseArtifactJanitor.replaceFromCandidateSidecar",
                "Copied candidate sidecar",
                mapOf("suffix" to suffix, "source" to candidateSidecar.name, "target" to targetSidecar.name),
            )
        } else if (targetSidecar.exists()) {
            targetSidecar.delete()
            logger.d(
                "DatabaseArtifactJanitor.replaceFromCandidateSidecar",
                "Removed target sidecar because candidate sidecar missing",
                mapOf("suffix" to suffix, "target" to targetSidecar.name),
            )
        }
    }

    private fun sidecarFor(
        baseFile: File,
        suffix: String,
    ): File = File(baseFile.parentFile, "${baseFile.name}-$suffix")

    private fun deleteCorruptFamily(candidateBase: File) {
        listOf(
            candidateBase,
            sidecarFor(candidateBase, "wal"),
            sidecarFor(candidateBase, "shm"),
            sidecarFor(candidateBase, "journal"),
        ).forEach { file ->
            if (file.exists()) {
                val deleted = file.delete()
                logger.d(
                    "DatabaseArtifactJanitor.deleteCorruptFamily",
                    "Corrupt snapshot family cleanup entry",
                    mapOf("file" to file.name, "deleted" to deleted),
                )
            }
        }
    }

    private fun deleteMatching(
        dir: File,
        logTag: String = "DatabaseArtifactJanitor",
        predicate: (File) -> Boolean,
    ): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var removed = 0
        dir.listFiles().orEmpty().forEach { file ->
            if (file.isFile && predicate(file)) {
                val deleted = file.delete()
                if (deleted) {
                    removed++
                    logger.d(logTag, "Deleted stale artifact", mapOf("file" to file.name))
                } else {
                    logger.w(logTag, "Failed to delete stale artifact", mapOf("file" to file.name))
                }
            }
        }
        return removed
    }

    private fun pruneCorruptSnapshots(dbDir: File): Int {
        if (!dbDir.exists() || !dbDir.isDirectory) return 0
        val corruptFiles =
            dbDir.listFiles().orEmpty().filter {
                it.isFile && it.name.startsWith("${PayanamDatabase.DATABASE_NAME}.corrupt")
            }
        if (corruptFiles.size <= 3) {
            logger.d(
                "DatabaseArtifactJanitor.pruneCorruptSnapshots",
                "Corrupt snapshot prune skipped: threshold not exceeded",
                mapOf("snapshotCount" to corruptFiles.size),
            )
            return 0
        }
        val sorted = corruptFiles.sortedByDescending { it.lastModified() }
        var removed = 0
        sorted.drop(3).forEach { file ->
            val deleted = file.delete()
            if (deleted) {
                removed++
                logger.d(
                    "DatabaseArtifactJanitor.pruneCorruptSnapshots",
                    "Deleted old corrupt snapshot",
                    mapOf("file" to file.name),
                )
            } else {
                logger.w(
                    "DatabaseArtifactJanitor.pruneCorruptSnapshots",
                    "Failed to delete old corrupt snapshot",
                    mapOf("file" to file.name),
                )
            }
        }
        logger.i(
            "DatabaseArtifactJanitor.pruneCorruptSnapshots",
            "Corrupt snapshot prune completed",
            mapOf("removed" to removed, "totalSnapshots" to corruptFiles.size),
        )
        return removed
    }

    private fun recoverFromTempBackupIfPrimaryMissing(
        context: Context,
        tempBackupDir: File,
        logTag: String,
    ): Boolean {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        if (dbFile.exists() || !tempBackupDir.exists()) {
            logger.d(
                logTag,
                "Skipping temp-backup recovery",
                mapOf("dbExists" to dbFile.exists(), "tempBackupExists" to tempBackupDir.exists()),
            )
            return false
        }
        val backupDb = File(tempBackupDir, PayanamDatabase.DATABASE_NAME)
        if (!backupDb.exists()) {
            logger.w(
                logTag,
                "Temp backup dir exists but no primary DB artifact found inside; leaving dir for manual inspection",
                mapOf("dir" to tempBackupDir.absolutePath),
            )
            return false
        }
        val dbDir = dbFile.parentFile ?: return false
        return runCatching {
            backupDb.copyTo(dbFile, overwrite = true)
            listOf("wal", "shm", "journal").forEach { suffix ->
                val src = File(tempBackupDir, "${PayanamDatabase.DATABASE_NAME}-$suffix")
                val dst = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-$suffix")
                if (src.exists()) {
                    src.copyTo(dst, overwrite = true)
                } else if (dst.exists()) {
                    dst.delete()
                }
            }
            tempBackupDir.deleteRecursively()
            logger.w(
                logTag,
                "Recovered primary DB artifacts from temp backup after missing-primary detection",
                mapOf("backupDir" to tempBackupDir.absolutePath),
            )
            true
        }.getOrElse { error ->
            logger.e(logTag, "Failed to recover primary DB from temp backup", error)
            false
        }
    }
}
