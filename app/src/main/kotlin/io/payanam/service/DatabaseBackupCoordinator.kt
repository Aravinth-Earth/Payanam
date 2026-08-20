//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

package io.payanam.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers

/**
 * BackupTrigger.
 */
enum class BackupTrigger(val key: String) {
    /** Auto. */
    AUTO("auto"),
    /** Manual. */
    MANUAL("manual"),
    /** Export. */
    EXPORT("export"),
}

/**
 * BackupExecutionResult.
 */
data class BackupExecutionResult(
    /** Recorded at millis. */
    val recordedAtMillis: Long,
    /** Recorded at display. */
    val recordedAtDisplay: String,
    /** Destination path. */
    val destinationPath: String,
    /** Attempts used. */
    val attemptsUsed: Int,
)

private data class SnapshotAttemptResult(
    /** Snapshot file. */
    val snapshotFile: File,
    /** Attempts used. */
    val attemptsUsed: Int,
)

@Singleton
/**
 * DatabaseBackupCoordinator.
 */
class DatabaseBackupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: DatabaseSessionManager,
    private val backupStatusStore: BackupStatusStore,
) {
    private val logger = UnifiedLogger.getInstance()

    /**
     * Backup to app backup directory.
     */
    suspend fun backupToAppBackupDirectory(trigger: BackupTrigger): BackupExecutionResult = withContext(Dispatchers.IO) {
        /** Policy. */
        val policy = retryPolicyFor(trigger)
        runCatching {
            /** Snapshot result. */
            val snapshotResult = createSnapshot(trigger, policy.maxAttempts, policy.retryDelayMs)
            /** Snapshot. */
            val snapshot = snapshotResult.snapshotFile
            try {
                /** Backup root dir. */
                val backupRootDir = getBackupDirectory().apply { mkdirs() }
                /** Session dir. */
                val sessionDir = File(backupRootDir, buildBackupFolderName()).apply { mkdirs() }
                /** Destination. */
                val destination = File(sessionDir, PayanamDatabase.DATABASE_NAME)
                snapshot.copyTo(destination, overwrite = true)
                /** Recorded at millis. */
                val recordedAtMillis = destination.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                /** Result. */
                val result = BackupExecutionResult(
                    recordedAtMillis = recordedAtMillis,
                    recordedAtDisplay = BackupStatusStore.formatBackupTimestamp(recordedAtMillis),
                    destinationPath = sessionDir.absolutePath,
                    attemptsUsed = snapshotResult.attemptsUsed,
                )
                backupStatusStore.recordSuccess(recordedAtMillis)
                /** Cleanup old backups. */
                cleanupOldBackups(backupRootDir)
                /** Result. */
                result
            } finally {
                snapshot.delete()
            }
        }.getOrElse { error ->
            /** If. */
            if (error is CancellationException) throw error
            /** Final message. */
            val finalMessage = context.getString(
                R.string.backup_failure_message_template,
                /** Trigger display name. */
                triggerDisplayName(trigger),
                policy.maxAttempts,
                error.message ?: error::class.java.simpleName,
            )
            backupStatusStore.recordFailure(finalMessage)
            throw IllegalStateException(finalMessage, error)
        }
    }

    /**
     * Export snapshot to uri.
     */
    suspend fun exportSnapshotToUri(destinationUri: Uri): Long = withContext(Dispatchers.IO) {
        /** Trigger. */
        val trigger = BackupTrigger.EXPORT
        /** Policy. */
        val policy = retryPolicyFor(trigger)
        runCatching {
            /** Snapshot result. */
            val snapshotResult = createSnapshot(trigger, policy.maxAttempts, policy.retryDelayMs)
            /** Snapshot. */
            val snapshot = snapshotResult.snapshotFile
            try {
                /** Bytes copied. */
                var bytesCopied = 0L
                context.contentResolver.openOutputStream(destinationUri, "w")?.use { outputStream ->
                    /** File input stream. */
                    FileInputStream(snapshot).use { inputStream ->
                        bytesCopied = inputStream.copyTo(outputStream)
                    }
                } ?: throw IllegalStateException("Could not open output stream")
                backupStatusStore.recordSuccess(System.currentTimeMillis())
                /** Bytes copied. */
                bytesCopied
            } finally {
                snapshot.delete()
            }
        }.getOrElse { error ->
            /** If. */
            if (error is CancellationException) throw error
            /** Final message. */
            val finalMessage = context.getString(
                R.string.backup_failure_message_template,
                /** Trigger display name. */
                triggerDisplayName(trigger),
                policy.maxAttempts,
                error.message ?: error::class.java.simpleName,
            )
            backupStatusStore.recordFailure(finalMessage)
            throw IllegalStateException(finalMessage, error)
        }
    }

    private suspend fun createSnapshot(
        /** Trigger. */
        trigger: BackupTrigger,
        /** Max attempts. */
        maxAttempts: Int,
        /** Retry delay ms. */
        retryDelayMs: Long,
    ): SnapshotAttemptResult {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        /** If. */
        if (!dbFile.exists()) {
            throw IllegalStateException("Database file not found at ${dbFile.absolutePath}")
        }

        /** Last error. */
        var lastError: Exception? = null
        /** Repeat. */
        repeat(maxAttempts) { index ->
            /** Attempt number. */
            val attemptNumber = index + 1
            /** Temp snapshot. */
            val tempSnapshot = File(context.cacheDir, "backup_snapshot_${trigger.key}_${System.currentTimeMillis()}_$attemptNumber.db")
            try {
                logger.i(
                    "DatabaseBackupCoordinator.createSnapshot",
                    "Backup snapshot attempt started",
                    /** Map of. */
                    mapOf(
                        "trigger" to trigger.key,
                        "attempt" to attemptNumber,
                        "maxAttempts" to maxAttempts,
                    ),
                )
                /** Checkpoint if possible. */
                checkpointIfPossible(trigger, attemptNumber, maxAttempts)
                /** Wal file. */
                val walFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal")
                /** If. */
                if (walFile.exists() && walFile.length() > 0L) {
                    throw IllegalStateException(
                        /** If. */
                        if (sessionManager.isDbOpen()) {
                            "WAL still contains pending pages after checkpoint"
                        } else {
                            "Database session is closed and WAL still has pending data"
                        },
                    )
                }
                dbFile.copyTo(tempSnapshot, overwrite = true)
                /** Verify snapshot. */
                verifySnapshot(tempSnapshot)
                logger.i(
                    "DatabaseBackupCoordinator.createSnapshot",
                    "Backup snapshot created successfully",
                    /** Map of. */
                    mapOf(
                        "trigger" to trigger.key,
                        "attempt" to attemptNumber,
                        "sizeKB" to (tempSnapshot.length() / 1024),
                    ),
                )
                return SnapshotAttemptResult(
                    snapshotFile = tempSnapshot,
                    attemptsUsed = attemptNumber,
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
                /** If. */
                if (error is CancellationException) throw error
                tempSnapshot.delete()
                lastError = error
                logger.w(
                    "DatabaseBackupCoordinator.createSnapshot",
                    "Backup snapshot attempt failed",
                    /** Map of. */
                    mapOf(
                        "trigger" to trigger.key,
                        "attempt" to attemptNumber,
                        "maxAttempts" to maxAttempts,
                        "error" to (error.message ?: error::class.java.simpleName),
                    ),
                )
                /** If. */
                if (attemptNumber < maxAttempts) {
                    /** Delay. */
                    delay(retryDelayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Backup snapshot failed for unknown reason")
    }

    private fun checkpointIfPossible(
        /** Trigger. */
        trigger: BackupTrigger,
        /** Attempt number. */
        attemptNumber: Int,
        /** Max attempts. */
        maxAttempts: Int,
    ) {
        /** If. */
        if (!sessionManager.isDbOpen()) {
            logger.d(
                "DatabaseBackupCoordinator.checkpointIfPossible",
                "Skipping checkpoint because DB session is closed",
                /** Map of. */
                mapOf(
                    "trigger" to trigger.key,
                    "attempt" to attemptNumber,
                    "maxAttempts" to maxAttempts,
                ),
            )
            /** Return. */
            return
        }

        /** Cursor. */
        val cursor = sessionManager.requireDatabase().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
        /** Busy. */
        var busy = -1
        /** Log pages. */
        var logPages = -1
        /** Checkpointed pages. */
        var checkpointedPages = -1
        /** If. */
        if (cursor.moveToFirst()) {
            busy = cursor.getInt(0)
            logPages = cursor.getInt(1)
            checkpointedPages = cursor.getInt(2)
        }
        cursor.close()
        logger.i(
            "DatabaseBackupCoordinator.checkpointIfPossible",
            "Checkpoint attempt completed",
            /** Map of. */
            mapOf(
                "trigger" to trigger.key,
                "attempt" to attemptNumber,
                "maxAttempts" to maxAttempts,
                "busy" to busy,
                "logPages" to logPages,
                "checkpointedPages" to checkpointedPages,
            ),
        )
        /** If. */
        if (busy > 0) {
            throw IllegalStateException("Database is busy during checkpoint")
        }
    }

    private fun verifySnapshot(snapshot: File) {
        /** If. */
        if (!snapshot.exists() || snapshot.length() == 0L) {
            throw IllegalStateException("Snapshot file was empty")
        }
        /** If. */
        if (hasStandardSqliteHeader(snapshot)) {
            SQLiteDatabase.openDatabase(
                snapshot.absolutePath,
                /** Null. */
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                /** If. */
                if (db.version <= 0) {
                    throw IllegalStateException("Snapshot verification failed to read schema version")
                }
            }
        }
    }

    private fun hasStandardSqliteHeader(databaseFile: File): Boolean {
        return try {
            /** Header. */
            val header = ByteArray(16)
            /** Bytes read. */
            val bytesRead = databaseFile.inputStream().use { it.read(header) }
            /** Sqlite magic. */
            val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
            bytesRead >= sqliteMagic.size && header.copyOf(sqliteMagic.size).contentEquals(sqliteMagic)
        } catch (_: Exception) {
            /** False. */
            false
        }
    }

    private fun retryPolicyFor(trigger: BackupTrigger): BackupRetryPolicy = when (trigger) {
        BackupTrigger.AUTO -> BackupRetryPolicy(maxAttempts = 10, retryDelayMs = 3_000L)
        BackupTrigger.MANUAL -> BackupRetryPolicy(maxAttempts = 5, retryDelayMs = 1_500L)
        BackupTrigger.EXPORT -> BackupRetryPolicy(maxAttempts = 5, retryDelayMs = 1_500L)
    }

    private fun triggerDisplayName(trigger: BackupTrigger): String = when (trigger) {
        BackupTrigger.AUTO -> context.getString(R.string.backup_trigger_auto)
        BackupTrigger.MANUAL -> context.getString(R.string.backup_trigger_manual)
        BackupTrigger.EXPORT -> context.getString(R.string.backup_trigger_export)
    }

    private fun buildBackupFolderName(): String {
        /** Timestamp. */
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        /** Build num. */
        val buildNum = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }
        return "auto_bk_${buildNum}_$timestamp"
    }

    private fun getBackupDirectory(): File {
        /** Documents dir. */
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        /** Suffix. */
        val suffix = if (context.packageName.endsWith(".debug")) "-debug" else ""
        return File(documentsDir, "Payanam$suffix/data/export")
    }

    private fun cleanupOldBackups(backupDir: File) {
        try {
            /** Prefs. */
            val prefs = context.getSharedPreferences(BackupStatusStore.BACKUP_META_PREFS, Context.MODE_PRIVATE)
            /** Rotation enabled. */
            val rotationEnabled = prefs.getBoolean(BackupStatusStore.KEY_BACKUP_ROTATION_ENABLED, false)
            /** Max backups. */
            val maxBackups = prefs.getInt(BackupStatusStore.KEY_BACKUP_ROTATION_COUNT, 50).coerceIn(1, 999)

            logger.i(
                "DatabaseBackupCoordinator.cleanupOldBackups",
                "Cleanup invoked",
                /** Map of. */
                mapOf(
                    "rotationEnabled" to rotationEnabled,
                    "maxBackups" to maxBackups,
                    "backupDir" to backupDir.absolutePath,
                ),
            )

            /** If. */
            if (!rotationEnabled) {
                logger.i(
                    "DatabaseBackupCoordinator.cleanupOldBackups",
                    "Rotation disabled in config — 0 backups deleted",
                )
                /** Return. */
                return
            }

            /** All dirs. */
            val allDirs =
                /** Backup dir. */
                backupDir
                    .listFiles { file -> file.isDirectory && file.name.startsWith("auto_bk_") }
                    ?.toList() ?: emptyList()

            logger.i(
                "DatabaseBackupCoordinator.cleanupOldBackups",
                "Backup dirs scanned",
                /** Map of. */
                mapOf("totalDirs" to allDirs.size),
            )

            /** Sorted. */
            val sorted = allDirs.sortedByDescending { it.lastModified() }
            /** To delete. */
            val toDelete = sorted.drop(maxBackups)

            /** If. */
            if (toDelete.isEmpty()) {
                logger.i(
                    "DatabaseBackupCoordinator.cleanupOldBackups",
                    "$maxBackups dirs found, maxBackups=$maxBackups — 0 to delete (under limit)",
                    /** Map of. */
                    mapOf("totalDirs" to allDirs.size, "maxBackups" to maxBackups),
                )
                /** Return. */
                return
            }

            logger.i(
                "DatabaseBackupCoordinator.cleanupOldBackups",
                "Retention plan",
                /** Map of. */
                mapOf(
                    "totalDirs" to allDirs.size,
                    "keepCount" to maxBackups,
                    "deleteCount" to toDelete.size,
                    "newestDir" to (sorted.firstOrNull()?.name ?: ""),
                    "oldestKept" to (sorted.getOrNull(maxBackups - 1)?.name ?: ""),
                    "oldestDeleted" to (toDelete.lastOrNull()?.name ?: ""),
                ),
            )

            /** Deleted. */
            var deleted = 0
            /** Failed. */
            var failed = 0
            /** Failed names. */
            val failedNames = mutableListOf<String>()

            toDelete.forEach { dir ->
                /** Ok. */
                val ok = dir.deleteRecursively()
                /** If. */
                if (ok) {
                    deleted++
                } else {
                    failed++
                    failedNames.add(dir.name)
                    logger.w(
                        "DatabaseBackupCoordinator.cleanupOldBackups",
                        "Delete failed",
                        /** Map of. */
                        mapOf("dir" to dir.name),
                    )
                }
            }

            logger.i(
                "DatabaseBackupCoordinator.cleanupOldBackups",
                "Cleanup result",
                /** Map of. */
                mapOf(
                    "attempted" to toDelete.size,
                    "deleted" to deleted,
                    "failed" to failed,
                    "failedDirsSample" to when {
                        failedNames.size <= 10 -> failedNames.joinToString(",")
                        else -> {
                            /** First. */
                            val first = failedNames.take(5).joinToString(",")
                            /** Last. */
                            val last = failedNames.takeLast(5).joinToString(",")
                            "$first ... (${failedNames.size - 10} skipped) ... $last"
                        }
                    },
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
            logger.e("DatabaseBackupCoordinator.cleanupOldBackups", "Backup cleanup failed", error)
        }
    }

    private data class BackupRetryPolicy(
        /** Max attempts. */
        val maxAttempts: Int,
        /** Retry delay ms. */
        val retryDelayMs: Long,
    )
}
