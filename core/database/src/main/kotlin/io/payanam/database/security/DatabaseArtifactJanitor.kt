//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

@file:Suppress("MagicNumber")

package io.payanam.database.security

import android.content.Context
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import java.io.File

/**
 * DatabaseArtifactJanitor.
 */
object DatabaseArtifactJanitor {
    private val logger = UnifiedLogger.getInstance()
    private val countTables = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes")

    /**
     * Cleanup stale artifacts.
     */
    fun cleanupStaleArtifacts(
        /** Context. */
        context: Context,
        logTag: String = "DatabaseArtifactJanitor.cleanupStaleArtifacts",
    ) {
        /** Db dir. */
        val dbDir = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).parentFile ?: return
        /** Cache dir. */
        val cacheDir = context.cacheDir
        logger.i(
            /** Log tag. */
            logTag,
            "Starting database artifact cleanup",
            /** Map of. */
            mapOf(
                "dbDir" to dbDir.absolutePath,
                "cacheDir" to cacheDir.absolutePath,
            ),
        )

        /** Recovered. */
        val recovered = recoverFromRicherCorruptSnapshot(context, "$logTag.recover")
        /** Deleted. */
        var deleted = 0

        /** Temp backup dir. */
        val tempBackupDir = File(dbDir, "payanam_temp_backup")
        // Recover from temp backup if the primary DB is missing, then clean only truly orphaned temp backups.
        /** Restored from temp backup. */
        val restoredFromTempBackup = recoverFromTempBackupIfPrimaryMissing(context, tempBackupDir, logTag)
        /** Primary db exists after recovery. */
        val primaryDbExistsAfterRecovery = context.getDatabasePath(PayanamDatabase.DATABASE_NAME).exists()
        /** If. */
        if (tempBackupDir.exists() && !restoredFromTempBackup && primaryDbExistsAfterRecovery) {
            /** If. */
            if (tempBackupDir.deleteRecursively()) {
                deleted++
                logger.i(
                    /** Log tag. */
                    logTag,
                    "Cleaned orphaned temp backup dir",
                    /** Map of. */
                    mapOf("path" to tempBackupDir.absolutePath),
                )
            } else {
                logger.w(
                    /** Log tag. */
                    logTag,
                    "Failed to delete orphaned temp backup dir",
                    /** Map of. */
                    mapOf("path" to tempBackupDir.absolutePath),
                )
            }
        }

        deleted +=
            /** Delete matching. */
            deleteMatching(dbDir, logTag) { file ->
                file.name.endsWith(".enc.tmp") ||
                    file.name.contains(".enc.tmp-") ||
                    file.name == "${PayanamDatabase.DATABASE_NAME}.enc.tmp" ||
                    file.name == "${PayanamDatabase.DATABASE_NAME}.lck" ||
                    file.name.contains(".before_import_") ||
                    file.name.contains(".before_encrypt_")
            }
        deleted +=
            /** Delete matching. */
            deleteMatching(cacheDir, logTag) { file ->
                file.name.startsWith("${PayanamDatabase.DATABASE_NAME}.") &&
                    (file.name.contains(".enc.tmp") || file.name.endsWith(".lck") || file.name.endsWith(".bak"))
            }
        deleted += pruneCorruptSnapshots(dbDir)

        /** If. */
        if (deleted > 0 || recovered || restoredFromTempBackup) {
            logger.i(
                /** Log tag. */
                logTag,
                "Database artifact cleanup completed",
                /** Map of. */
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
        /** Context. */
        context: Context,
        /** Log tag. */
        logTag: String,
    ): Boolean {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        /** If. */
        if (!dbFile.exists()) {
            logger.d(logTag, "Skipping richer-corrupt recovery: primary DB file missing")
            return false
        }
        /** Db dir. */
        val dbDir = dbFile.parentFile ?: return false
        /** Encryption manager. */
        val encryptionManager = DatabaseEncryptionManager(context)
        /** If. */
        if (encryptionManager.isEncryptionEnabled()) {
            logger.i(
                /** Log tag. */
                logTag,
                "Skipping richer-corrupt recovery for encrypted mode at cold boot (no framework-open probes allowed)",
            )
            return false
        }
        /** Passphrase. */
        val passphrase: String? = null

        /** Primary total. */
        val primaryTotal = readTotalCount(context, dbFile, passphrase)
        /** If. */
        if (primaryTotal > 0) {
            logger.d(
                /** Log tag. */
                logTag,
                "Skipping richer-corrupt recovery: primary DB has non-zero content counts",
                /** Map of. */
                mapOf("primaryTotal" to primaryTotal),
            )
            return false
        }

        /** Candidate bases. */
        val candidateBases =
            dbDir.listFiles().orEmpty().filter {
                it.isFile &&
                    it.name.startsWith("${PayanamDatabase.DATABASE_NAME}.corrupt") &&
                    !it.name.endsWith("-wal") &&
                    !it.name.endsWith("-shm") &&
                    !it.name.endsWith("-journal")
            }
        /** If. */
        if (candidateBases.isEmpty()) {
            logger.d(logTag, "Skipping richer-corrupt recovery: no corrupt snapshot candidates found")
            return false
        }

        /** Best candidate. */
        val bestCandidate =
            /** Candidate bases. */
            candidateBases
                .map { candidate -> candidate to readTotalCount(context, candidate, passphrase) }
                .maxByOrNull { it.second }
                ?.takeIf { (_, total) -> total > primaryTotal }
                ?: return false

        /** Candidate base. */
        val candidateBase = bestCandidate.first
        /** Candidate total. */
        val candidateTotal = bestCandidate.second
        logger.i(
            /** Log tag. */
            logTag,
            "Selected richer-corrupt candidate for recovery",
            /** Map of. */
            mapOf(
                "candidateName" to candidateBase.name,
                "candidateTotal" to candidateTotal,
                "primaryTotal" to primaryTotal,
            ),
        )
        /** Timestamp. */
        val timestamp = System.currentTimeMillis()
        /** Backups. */
        val backups = mutableListOf<Pair<File, File>>()
        try {
            /** List of. */
            listOf(dbFile, sidecarFor(dbFile, "wal"), sidecarFor(dbFile, "shm"), sidecarFor(dbFile, "journal"))
                .filter { it.exists() }
                .forEach { file ->
                    /** Backup. */
                    val backup = File(dbDir, "${file.name}.janitor_backup_$timestamp.bak")
                    file.copyTo(backup, overwrite = true)
                    backups += file to backup
                }

            candidateBase.copyTo(dbFile, overwrite = true)
            /** Replace from candidate sidecar. */
            replaceFromCandidateSidecar(candidateBase, dbFile, "wal")
            /** Replace from candidate sidecar. */
            replaceFromCandidateSidecar(candidateBase, dbFile, "shm")
            /** Replace from candidate sidecar. */
            replaceFromCandidateSidecar(candidateBase, dbFile, "journal")

            /** Restored total. */
            val restoredTotal = readTotalCount(context, dbFile, passphrase)
            /** Check. */
            check(restoredTotal > primaryTotal) {
                "Recovered database did not improve table counts."
            }

            logger.w(
                /** Log tag. */
                logTag,
                "Recovered primary database from richer corrupt snapshot",
                /** Map of. */
                mapOf(
                    "primaryTotalBefore" to primaryTotal,
                    "candidateTotal" to candidateTotal,
                    "restoredTotal" to restoredTotal,
                    "candidateName" to candidateBase.name,
                ),
            )
            /** Delete corrupt family. */
            deleteCorruptFamily(candidateBase)
            backups.forEach { (_, backup) -> if (backup.exists()) backup.delete() }
            return true
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
            backups.forEach { (original, backup) ->
                /** If. */
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
        /** Context. */
        context: Context,
        /** Database file. */
        databaseFile: File,
        passphrase: String?,
    ): Int {
        /** Counts. */
        val counts =
            DatabaseEncryptionMigrationSupport.readTableCounts(
                context = context,
                databaseFile = databaseFile,
                passphrase = passphrase,
                tableNames = countTables,
            )
        /** Total. */
        val total = countTables.sumOf { key -> counts[key] ?: 0 }
        logger.d(
            "DatabaseArtifactJanitor.readTotalCount",
            "Computed aggregate table counts for candidate file",
            /** Map of. */
            mapOf(
                "file" to databaseFile.name,
                "total" to total,
            ),
        )
        return total
    }

    private fun replaceFromCandidateSidecar(
        /** Candidate base. */
        candidateBase: File,
        /** Target base. */
        targetBase: File,
        /** Suffix. */
        suffix: String,
    ) {
        /** Candidate sidecar. */
        val candidateSidecar = sidecarFor(candidateBase, suffix)
        /** Target sidecar. */
        val targetSidecar = sidecarFor(targetBase, suffix)
        /** If. */
        if (candidateSidecar.exists()) {
            candidateSidecar.copyTo(targetSidecar, overwrite = true)
            logger.d(
                "DatabaseArtifactJanitor.replaceFromCandidateSidecar",
                "Copied candidate sidecar",
                /** Map of. */
                mapOf("suffix" to suffix, "source" to candidateSidecar.name, "target" to targetSidecar.name),
            )
        } else if (targetSidecar.exists()) {
            targetSidecar.delete()
            logger.d(
                "DatabaseArtifactJanitor.replaceFromCandidateSidecar",
                "Removed target sidecar because candidate sidecar missing",
                /** Map of. */
                mapOf("suffix" to suffix, "target" to targetSidecar.name),
            )
        }
    }

    private fun sidecarFor(
        /** Base file. */
        baseFile: File,
        /** Suffix. */
        suffix: String,
    ): File = File(baseFile.parentFile, "${baseFile.name}-$suffix")

    private fun deleteCorruptFamily(candidateBase: File) {
        /** List of. */
        listOf(
            /** Candidate base. */
            candidateBase,
            /** Sidecar for. */
            sidecarFor(candidateBase, "wal"),
            /** Sidecar for. */
            sidecarFor(candidateBase, "shm"),
            /** Sidecar for. */
            sidecarFor(candidateBase, "journal"),
        ).forEach { file ->
            /** If. */
            if (file.exists()) {
                /** Deleted. */
                val deleted = file.delete()
                logger.d(
                    "DatabaseArtifactJanitor.deleteCorruptFamily",
                    "Corrupt snapshot family cleanup entry",
                    /** Map of. */
                    mapOf("file" to file.name, "deleted" to deleted),
                )
            }
        }
    }

    private fun deleteMatching(
        /** Dir. */
        dir: File,
        logTag: String = "DatabaseArtifactJanitor",
        predicate: (File) -> Boolean,
    ): Int {
        /** If. */
        if (!dir.exists() || !dir.isDirectory) return 0
        /** Removed. */
        var removed = 0
        dir.listFiles().orEmpty().forEach { file ->
            /** If. */
            if (file.isFile && predicate(file)) {
                /** Deleted. */
                val deleted = file.delete()
                /** If. */
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
        /** If. */
        if (!dbDir.exists() || !dbDir.isDirectory) return 0
        /** Corrupt files. */
        val corruptFiles =
            dbDir.listFiles().orEmpty().filter {
                it.isFile && it.name.startsWith("${PayanamDatabase.DATABASE_NAME}.corrupt")
            }
        /** If. */
        if (corruptFiles.size <= 3) {
            logger.d(
                "DatabaseArtifactJanitor.pruneCorruptSnapshots",
                "Corrupt snapshot prune skipped: threshold not exceeded",
                /** Map of. */
                mapOf("snapshotCount" to corruptFiles.size),
            )
            return 0
        }
        /** Sorted. */
        val sorted = corruptFiles.sortedByDescending { it.lastModified() }
        /** Removed. */
        var removed = 0
        sorted.drop(3).forEach { file ->
            /** Deleted. */
            val deleted = file.delete()
            /** If. */
            if (deleted) {
                removed++
                logger.d(
                    "DatabaseArtifactJanitor.pruneCorruptSnapshots",
                    "Deleted old corrupt snapshot",
                    /** Map of. */
                    mapOf("file" to file.name),
                )
            } else {
                logger.w(
                    "DatabaseArtifactJanitor.pruneCorruptSnapshots",
                    "Failed to delete old corrupt snapshot",
                    /** Map of. */
                    mapOf("file" to file.name),
                )
            }
        }
        logger.i(
            "DatabaseArtifactJanitor.pruneCorruptSnapshots",
            "Corrupt snapshot prune completed",
            /** Map of. */
            mapOf("removed" to removed, "totalSnapshots" to corruptFiles.size),
        )
        return removed
    }

    private fun recoverFromTempBackupIfPrimaryMissing(
        /** Context. */
        context: Context,
        /** Temp backup dir. */
        tempBackupDir: File,
        /** Log tag. */
        logTag: String,
    ): Boolean {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        /** If. */
        if (dbFile.exists() || !tempBackupDir.exists()) {
            logger.d(
                /** Log tag. */
                logTag,
                "Skipping temp-backup recovery",
                /** Map of. */
                mapOf("dbExists" to dbFile.exists(), "tempBackupExists" to tempBackupDir.exists()),
            )
            return false
        }
        /** Backup db. */
        val backupDb = File(tempBackupDir, PayanamDatabase.DATABASE_NAME)
        /** If. */
        if (!backupDb.exists()) {
            logger.w(
                /** Log tag. */
                logTag,
                "Temp backup dir exists but no primary DB artifact found inside; leaving dir for manual inspection",
                /** Map of. */
                mapOf("dir" to tempBackupDir.absolutePath),
            )
            return false
        }
        /** Db dir. */
        val dbDir = dbFile.parentFile ?: return false
        return runCatching {
            backupDb.copyTo(dbFile, overwrite = true)
            /** List of. */
            listOf("wal", "shm", "journal").forEach { suffix ->
                /** Src. */
                val src = File(tempBackupDir, "${PayanamDatabase.DATABASE_NAME}-$suffix")
                /** Dst. */
                val dst = File(dbDir, "${PayanamDatabase.DATABASE_NAME}-$suffix")
                /** If. */
                if (src.exists()) {
                    src.copyTo(dst, overwrite = true)
                } else if (dst.exists()) {
                    dst.delete()
                }
            }
            tempBackupDir.deleteRecursively()
            logger.w(
                /** Log tag. */
                logTag,
                "Recovered primary DB artifacts from temp backup after missing-primary detection",
                /** Map of. */
                mapOf("backupDir" to tempBackupDir.absolutePath),
            )
            /** True. */
            true
        }.getOrElse { error ->
            logger.e(logTag, "Failed to recover primary DB from temp backup", error)
            /** False. */
            false
        }
    }
}
