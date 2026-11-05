//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.backup

import io.payanam.domain.port.BackupCheckpointPort
import io.payanam.domain.port.BackupExecutionResult
import io.payanam.domain.port.BackupRetryPolicy
import io.payanam.domain.port.BackupTrigger
import io.payanam.domain.port.formatBackupDisplayTimestamp
import io.payanam.presentation.backup.pruneBackupArtifacts
import io.payanam.presentation.backup.rotationPolicyFromBackupRows
import io.payanam.shared.database.BackupSnapshotVerification
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.logging.UnifiedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Bytes in a kilobyte - the log line reports snapshot size in KB. */
private const val BYTES_PER_KB: Long = 1024

/**
 * Shared backup-snapshot logic: the fold-WAL → check-WAL-empty → copy → verify sequence
 * that every Payanam platform must run before treating a database file as a backup.
 * Per-OS paths ([dbFile], [tempSnapshotDir]) and the database session ([checkpointPort])
 * are injected at build time, so this module contains zero Android imports and zero
 * runtime branching — the Android coordinator delegates here, and the desktop backup
 * path can do the same.
 */
class BackupCoordinatorCore(
    private val dbFile: File,
    private val tempSnapshotDir: File,
    private val checkpointPort: BackupCheckpointPort,
    private val retryPolicy: BackupRetryPolicy,
    private val logger: UnifiedLogger,
    private val backupDirectory: File,
    private val sessionFolderName: () -> String,
    private val destinationFileName: String,
    private val rotationRows: suspend () -> Map<String, String?>,
    private val failureMessage: (trigger: BackupTrigger, maxAttempts: Int, error: Throwable) -> String,
    private val recordSuccess: (recordedAtMillis: Long) -> Unit,
    private val recordFailure: (finalMessage: String) -> Unit,
) {
    /**
     * Result of a successful snapshot attempt.
     */
    data class SnapshotAttemptResult(
        val snapshotFile: File,
        val attemptsUsed: Int,
    )

    /**
     * Creates a verified snapshot of [dbFile] with retries.
     *
     * Each attempt: checkpoint → WAL check → copy → verify. On failure, the temp file is
     * deleted and the next attempt runs after [BackupRetryPolicy.retryDelayMs].
     *
     * @throws IllegalStateException if all attempts fail or the database file does not exist.
     */
    suspend fun createSnapshot(trigger: BackupTrigger): SnapshotAttemptResult {
        if (!dbFile.exists()) {
            throw IllegalStateException("Database file not found at ${dbFile.absolutePath}")
        }
        var lastError: Exception? = null
        repeat(retryPolicy.maxAttempts) { index ->
            val attemptNumber = index + 1
            val tempSnapshot = File(tempSnapshotDir, "backup_snapshot_${trigger.key}_${System.currentTimeMillis()}_$attemptNumber.db")
            try {
                logger.i(
                    "BackupCoordinatorCore.createSnapshot",
                    "Backup snapshot attempt started",
                    mapOf(
                        "trigger" to trigger.key,
                        "attempt" to attemptNumber,
                        "maxAttempts" to retryPolicy.maxAttempts,
                    ),
                )
                checkpointIfPossible(trigger, attemptNumber, retryPolicy.maxAttempts)
                // The WAL alone, by name: the check is "did the checkpoint actually flush", and the
                // question is asked of one specific sidecar rather than of the family — a surviving
                // `-shm` or `-journal` beside a checkpointed database is normal and must not fail a
                // backup. The suffix itself comes from the shared declaration, so it is the same
                // `-wal` every other platform checks, not a second spelling.
                val walFile = File(dbFile.parent, dbFile.name + DatabaseArtifacts.WAL_SUFFIX)
                if (walFile.exists() && walFile.length() > 0L) {
                    throw IllegalStateException(
                        if (checkpointPort.isDbOpen()) {
                            "WAL still contains pending pages after checkpoint"
                        } else {
                            "Database session is closed and WAL still has pending data"
                        },
                    )
                }
                dbFile.copyTo(tempSnapshot, overwrite = true)
                BackupSnapshotVerification.verifySnapshot(tempSnapshot)
                logger.i(
                    "BackupCoordinatorCore.createSnapshot",
                    "Backup snapshot created successfully",
                    mapOf(
                        "trigger" to trigger.key,
                        "attempt" to attemptNumber,
                        "sizeKB" to (tempSnapshot.length() / BYTES_PER_KB),
                    ),
                )
                return SnapshotAttemptResult(
                    snapshotFile = tempSnapshot,
                    attemptsUsed = attemptNumber,
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                if (error is CancellationException) throw error
                tempSnapshot.delete()
                lastError = error
                logger.w(
                    "BackupCoordinatorCore.createSnapshot",
                    "Backup snapshot attempt failed",
                    mapOf(
                        "trigger" to trigger.key,
                        "attempt" to attemptNumber,
                        "maxAttempts" to retryPolicy.maxAttempts,
                        "error" to (error.message ?: error::class.java.simpleName),
                    ),
                )
                if (attemptNumber < retryPolicy.maxAttempts) {
                    delay(retryPolicy.retryDelayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Backup snapshot failed for unknown reason")
    }

    /**
     * Folds the write-ahead log if the database session is open, and refuses to proceed
     * if the log is not empty after the fold.
     */
    private fun checkpointIfPossible(
        trigger: BackupTrigger,
        attemptNumber: Int,
        maxAttempts: Int,
    ) {
        if (!checkpointPort.isDbOpen()) {
            logger.d(
                "BackupCoordinatorCore.checkpointIfPossible",
                "Skipping checkpoint because DB session is closed",
                mapOf(
                    "trigger" to trigger.key,
                    "attempt" to attemptNumber,
                    "maxAttempts" to maxAttempts,
                ),
            )
            return
        }
        val report = checkpointPort.foldWal()
        logger.i(
            "BackupCoordinatorCore.checkpointIfPossible",
            "Checkpoint attempt completed",
            mapOf(
                "trigger" to trigger.key,
                "attempt" to attemptNumber,
                "maxAttempts" to maxAttempts,
                "busy" to report.busy,
                "logPages" to report.logFrames,
                "checkpointedPages" to report.checkpointedFrames,
                "walIsEmpty" to report.walIsEmpty,
            ),
        )
        // A backup REFUSES a log it could not empty, and this is the refusing half of the shared rule.
        // The old check was `busy > 0` alone, so a fold that ran and left frames behind passed here and
        // produced a snapshot missing whatever was still sitting in the log. The `-wal` size check in
        // createSnapshot caught most of those, but it cannot tell a genuinely non-WAL database from a
        // fold that silently left pages behind.
        WalCheckpointRule.requireWalEmpty(report, "backup snapshot for ${trigger.key}")
    }

    /**
     * Runs a database backup into [backupDirectory] and reports the outcome through the injected
     * status seams.
     *
     * Moved from Android's `DatabaseBackupCoordinator.backupToAppBackupDirectory` (app service,
     * parity reference): the order is snapshot -> copy into the session folder -> record success ->
     * rotate old artifacts -> return the result, with a failure recording [recordFailure] and
     * rethrowing, and cancellation never swallowed. The session-folder naming, the failure text and
     * the status writers are per-OS seams injected at construction, so this class holds only the
     * shared sequence.
     */
    suspend fun backupToAppBackupDirectory(trigger: BackupTrigger): BackupExecutionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshotResult = createSnapshot(trigger)
                val snapshot = snapshotResult.snapshotFile
                try {
                    val backupRootDir = backupDirectory.apply { mkdirs() }
                    val sessionDir = File(backupRootDir, sessionFolderName()).apply { mkdirs() }
                    val destination = File(sessionDir, destinationFileName)
                    snapshot.copyTo(destination, overwrite = true)
                    val recordedAtMillis =
                        destination.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                    val result =
                        BackupExecutionResult(
                            recordedAtMillis = recordedAtMillis,
                            recordedAtDisplay = formatBackupDisplayTimestamp(recordedAtMillis),
                            destinationPath = sessionDir.absolutePath,
                            attemptsUsed = snapshotResult.attemptsUsed,
                        )
                    recordSuccess(recordedAtMillis)
                    cleanupOldBackups(backupRootDir)
                    result
                } finally {
                    snapshot.delete()
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                val finalMessage = failureMessage(trigger, retryPolicy.maxAttempts, error)
                recordFailure(finalMessage)
                throw IllegalStateException(finalMessage, error)
            }
        }

    /**
     * Prunes old backup artifacts under [backupDir] according to the injected rotation rows.
     *
     * Moved from Android's `DatabaseBackupCoordinator.cleanupOldBackups` (parity reference): rows
     * come from the per-OS settings source via [rotationRows], the policy and the prune are the
     * shared ones, a cleanup failure is logged and never breaks the backup that already succeeded.
     */
    private suspend fun cleanupOldBackups(backupDir: File) {
        try {
            val policy = rotationPolicyFromBackupRows(rotationRows())
            logger.i(
                "BackupCoordinatorCore.cleanupOldBackups",
                "Cleanup invoked",
                mapOf(
                    "rotationEnabled" to policy.enabled,
                    "maxBackups" to policy.keepCount,
                    "backupDir" to backupDir.absolutePath,
                ),
            )
            if (!policy.enabled) {
                logger.i(
                    "BackupCoordinatorCore.cleanupOldBackups",
                    "Rotation disabled in config — 0 backups deleted",
                )
                return
            }
            val deleted =
                pruneBackupArtifacts(
                    backupDirectory = backupDir,
                    policy = policy,
                    isArtifact = { file -> file.isDirectory && file.name.startsWith("auto_bk_") },
                    logger = logger,
                )
            logger.i(
                "BackupCoordinatorCore.cleanupOldBackups",
                "Cleanup result",
                mapOf(
                    "deletedCount" to deleted.size,
                    "deletedDirs" to deleted.joinToString(",") { it.name },
                ),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") error: Exception,
        ) {
            logger.e("BackupCoordinatorCore.cleanupOldBackups", "Backup cleanup failed", error)
        }
    }
}
