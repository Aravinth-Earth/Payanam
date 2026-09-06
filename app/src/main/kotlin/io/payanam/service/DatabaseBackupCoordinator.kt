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
 * What started a backup: scheduled auto, user-initiated manual, or a
 * settings-screen export. Drives the retry policy.
 */
enum class BackupTrigger(val key: String) {
    AUTO("auto"),
    MANUAL("manual"),
    EXPORT("export"),
}
/**
 * Outcome of a completed backup: when it was recorded, where it landed, and
 * how many snapshot attempts it took.
 */
data class BackupExecutionResult(
    val recordedAtMillis: Long,
    val recordedAtDisplay: String,
    val destinationPath: String,
    val attemptsUsed: Int,
)

private data class SnapshotAttemptResult(
    val snapshotFile: File,
    val attemptsUsed: Int,
)

/**
 * Owns database backup execution: creates WAL-checkpointed, integrity-verified
 * snapshots with trigger-specific retry policies, writes them to the Documents
 * backup folder or a SAF destination, applies retention rotation, and records
 * every outcome in [BackupStatusStore].
 */
@Singleton
class DatabaseBackupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: DatabaseSessionManager,
    private val backupStatusStore: BackupStatusStore,
) {
    private val logger = UnifiedLogger.getInstance()
    /**
     * Runs a full backup into the app's Documents backup folder: WAL
     * checkpoint + verified snapshot with retries, then rotation cleanup.
     * Records success/failure in [BackupStatusStore].
     */
    suspend fun backupToAppBackupDirectory(trigger: BackupTrigger): BackupExecutionResult = withContext(Dispatchers.IO) {
        val policy = retryPolicyFor(trigger)
        runCatching {
            val snapshotResult = createSnapshot(trigger, policy.maxAttempts, policy.retryDelayMs)
            val snapshot = snapshotResult.snapshotFile
            try {
                val backupRootDir = getBackupDirectory().apply { mkdirs() }
                val sessionDir = File(backupRootDir, buildBackupFolderName()).apply { mkdirs() }
                val destination = File(sessionDir, PayanamDatabase.DATABASE_NAME)
                snapshot.copyTo(destination, overwrite = true)
                val recordedAtMillis = destination.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                val result = BackupExecutionResult(
                    recordedAtMillis = recordedAtMillis,
                    recordedAtDisplay = BackupStatusStore.formatBackupTimestamp(recordedAtMillis),
                    destinationPath = sessionDir.absolutePath,
                    attemptsUsed = snapshotResult.attemptsUsed,
                )
                backupStatusStore.recordSuccess(recordedAtMillis)
                cleanupOldBackups(backupRootDir)
                result
            } finally {
                snapshot.delete()
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val finalMessage = context.getString(
                R.string.backup_failure_message_template,
                triggerDisplayName(trigger),
                policy.maxAttempts,
                error.message ?: error::class.java.simpleName,
            )
            backupStatusStore.recordFailure(finalMessage)
            throw IllegalStateException(finalMessage, error)
        }
    }
    /**
     * Streams a verified snapshot to a user-chosen [destinationUri] via SAF,
     * returning the bytes written.
     */
    suspend fun exportSnapshotToUri(destinationUri: Uri): Long = withContext(Dispatchers.IO) {
        val trigger = BackupTrigger.EXPORT
        val policy = retryPolicyFor(trigger)
        runCatching {
            val snapshotResult = createSnapshot(trigger, policy.maxAttempts, policy.retryDelayMs)
            val snapshot = snapshotResult.snapshotFile
            try {
                var bytesCopied = 0L
                context.contentResolver.openOutputStream(destinationUri, "w")?.use { outputStream ->
                    FileInputStream(snapshot).use { inputStream ->
                        bytesCopied = inputStream.copyTo(outputStream)
                    }
                } ?: throw IllegalStateException("Could not open output stream")
                backupStatusStore.recordSuccess(System.currentTimeMillis())
                bytesCopied
            } finally {
                snapshot.delete()
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            val finalMessage = context.getString(
                R.string.backup_failure_message_template,
                triggerDisplayName(trigger),
                policy.maxAttempts,
                error.message ?: error::class.java.simpleName,
            )
            backupStatusStore.recordFailure(finalMessage)
            throw IllegalStateException(finalMessage, error)
        }
    }

    private suspend fun createSnapshot(
        trigger: BackupTrigger,
        maxAttempts: Int,
        retryDelayMs: Long,
    ): SnapshotAttemptResult {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        if (!dbFile.exists()) {
            throw IllegalStateException("Database file not found at ${dbFile.absolutePath}")
        }
        var lastError: Exception? = null
        repeat(maxAttempts) { index ->
            val attemptNumber = index + 1
            val tempSnapshot = File(context.cacheDir, "backup_snapshot_${trigger.key}_${System.currentTimeMillis()}_$attemptNumber.db")
            try {
                logger.i(
                    "DatabaseBackupCoordinator.createSnapshot",
                    "Backup snapshot attempt started",
                    mapOf(
                        "trigger" to trigger.key,
                        "attempt" to attemptNumber,
                        "maxAttempts" to maxAttempts,
                    ),
                )
                checkpointIfPossible(trigger, attemptNumber, maxAttempts)
                val walFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal")
                if (walFile.exists() && walFile.length() > 0L) {
                    throw IllegalStateException(
                        if (sessionManager.isDbOpen()) {
                            "WAL still contains pending pages after checkpoint"
                        } else {
                            "Database session is closed and WAL still has pending data"
                        },
                    )
                }
                dbFile.copyTo(tempSnapshot, overwrite = true)
                verifySnapshot(tempSnapshot)
                logger.i(
                    "DatabaseBackupCoordinator.createSnapshot",
                    "Backup snapshot created successfully",
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
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                if (error is CancellationException) throw error
                tempSnapshot.delete()
                lastError = error
                logger.w(
                    "DatabaseBackupCoordinator.createSnapshot",
                    "Backup snapshot attempt failed",
                    mapOf(
                        "trigger" to trigger.key,
                        "attempt" to attemptNumber,
                        "maxAttempts" to maxAttempts,
                        "error" to (error.message ?: error::class.java.simpleName),
                    ),
                )
                if (attemptNumber < maxAttempts) {
                    delay(retryDelayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Backup snapshot failed for unknown reason")
    }

    private fun checkpointIfPossible(
        trigger: BackupTrigger,
        attemptNumber: Int,
        maxAttempts: Int,
    ) {
        if (!sessionManager.isDbOpen()) {
            logger.d(
                "DatabaseBackupCoordinator.checkpointIfPossible",
                "Skipping checkpoint because DB session is closed",
                mapOf(
                    "trigger" to trigger.key,
                    "attempt" to attemptNumber,
                    "maxAttempts" to maxAttempts,
                ),
            )
            return
        }
        val cursor = sessionManager.requireDatabase().openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)")
        var busy = -1
        var logPages = -1
        var checkpointedPages = -1
        if (cursor.moveToFirst()) {
            busy = cursor.getInt(0)
            logPages = cursor.getInt(1)
            checkpointedPages = cursor.getInt(2)
        }
        cursor.close()
        logger.i(
            "DatabaseBackupCoordinator.checkpointIfPossible",
            "Checkpoint attempt completed",
            mapOf(
                "trigger" to trigger.key,
                "attempt" to attemptNumber,
                "maxAttempts" to maxAttempts,
                "busy" to busy,
                "logPages" to logPages,
                "checkpointedPages" to checkpointedPages,
            ),
        )
        if (busy > 0) {
            throw IllegalStateException("Database is busy during checkpoint")
        }
    }

    private fun verifySnapshot(snapshot: File) {
        if (!snapshot.exists() || snapshot.length() == 0L) {
            throw IllegalStateException("Snapshot file was empty")
        }
        if (hasStandardSqliteHeader(snapshot)) {
            SQLiteDatabase.openDatabase(
                snapshot.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                if (db.version <= 0) {
                    throw IllegalStateException("Snapshot verification failed to read schema version")
                }
            }
        }
    }

    private fun hasStandardSqliteHeader(databaseFile: File): Boolean {
        return try {
            val header = ByteArray(16)
            val bytesRead = databaseFile.inputStream().use { it.read(header) }
            val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
            bytesRead >= sqliteMagic.size && header.copyOf(sqliteMagic.size).contentEquals(sqliteMagic)
        } catch (_: Exception) {
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
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val buildNum = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }
        return "auto_bk_${buildNum}_$timestamp"
    }

    private fun getBackupDirectory(): File {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val suffix = if (context.packageName.endsWith(".debug")) "-debug" else ""
        return File(documentsDir, "Payanam$suffix/data/export")
    }

    private fun cleanupOldBackups(backupDir: File) {
        try {
            val prefs = context.getSharedPreferences(BackupStatusStore.BACKUP_META_PREFS, Context.MODE_PRIVATE)
            val rotationEnabled = prefs.getBoolean(BackupStatusStore.KEY_BACKUP_ROTATION_ENABLED, false)
            val maxBackups = prefs.getInt(BackupStatusStore.KEY_BACKUP_ROTATION_COUNT, 50).coerceIn(1, 999)

            logger.i(
                "DatabaseBackupCoordinator.cleanupOldBackups",
                "Cleanup invoked",
                mapOf(
                    "rotationEnabled" to rotationEnabled,
                    "maxBackups" to maxBackups,
                    "backupDir" to backupDir.absolutePath,
                ),
            )
            if (!rotationEnabled) {
                logger.i(
                    "DatabaseBackupCoordinator.cleanupOldBackups",
                    "Rotation disabled in config — 0 backups deleted",
                )
                return
            }
            val allDirs =
                backupDir
                    .listFiles { file -> file.isDirectory && file.name.startsWith("auto_bk_") }
                    ?.toList() ?: emptyList()

            logger.i(
                "DatabaseBackupCoordinator.cleanupOldBackups",
                "Backup dirs scanned",
                mapOf("totalDirs" to allDirs.size),
            )
            val sorted = allDirs.sortedByDescending { it.lastModified() }
            val toDelete = sorted.drop(maxBackups)
            if (toDelete.isEmpty()) {
                logger.i(
                    "DatabaseBackupCoordinator.cleanupOldBackups",
                    "$maxBackups dirs found, maxBackups=$maxBackups — 0 to delete (under limit)",
                    mapOf("totalDirs" to allDirs.size, "maxBackups" to maxBackups),
                )
                return
            }

            logger.i(
                "DatabaseBackupCoordinator.cleanupOldBackups",
                "Retention plan",
                mapOf(
                    "totalDirs" to allDirs.size,
                    "keepCount" to maxBackups,
                    "deleteCount" to toDelete.size,
                    "newestDir" to (sorted.firstOrNull()?.name ?: ""),
                    "oldestKept" to (sorted.getOrNull(maxBackups - 1)?.name ?: ""),
                    "oldestDeleted" to (toDelete.lastOrNull()?.name ?: ""),
                ),
            )
            var deleted = 0
            var failed = 0
            val failedNames = mutableListOf<String>()

            toDelete.forEach { dir ->
                val ok = dir.deleteRecursively()
                if (ok) {
                    deleted++
                } else {
                    failed++
                    failedNames.add(dir.name)
                    logger.w(
                        "DatabaseBackupCoordinator.cleanupOldBackups",
                        "Delete failed",
                        mapOf("dir" to dir.name),
                    )
                }
            }

            logger.i(
                "DatabaseBackupCoordinator.cleanupOldBackups",
                "Cleanup result",
                mapOf(
                    "attempted" to toDelete.size,
                    "deleted" to deleted,
                    "failed" to failed,
                    "failedDirsSample" to when {
                        failedNames.size <= 10 -> failedNames.joinToString(",")
                        else -> {
                            val first = failedNames.take(5).joinToString(",")
                            val last = failedNames.takeLast(5).joinToString(",")
                            "$first ... (${failedNames.size - 10} skipped) ... $last"
                        }
                    },
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            logger.e("DatabaseBackupCoordinator.cleanupOldBackups", "Backup cleanup failed", error)
        }
    }

    private data class BackupRetryPolicy(
        val maxAttempts: Int,
        val retryDelayMs: Long,
    )
}
