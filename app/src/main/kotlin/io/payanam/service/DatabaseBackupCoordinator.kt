//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

package io.payanam.service

import android.content.Context
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.BuildConfig
import io.payanam.R
import io.payanam.backup.BackupCoordinatorCore
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.port.BackupCheckpointPort
import io.payanam.domain.port.BackupExecutionResult
import io.payanam.domain.port.BackupRetryPolicy
import io.payanam.domain.port.BackupTrigger
import io.payanam.domain.port.retryPolicyFor
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.logging.autoBackupDirName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns database backup execution on Android: supplies the platform-specific seams
 * (backup directory, folder naming, SAF export, failure text, rotation rows) to
 * [BackupCoordinatorCore], which holds the shared snapshot/rotation logic.
 */
@Singleton
class DatabaseBackupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: DatabaseSessionManager,
    private val backupStatusStore: BackupStatusStore,
) : io.payanam.domain.port.DatabaseBackupCoordinator {
    private val logger = UnifiedLogger.getInstance()

    private val checkpointPort: BackupCheckpointPort = object : BackupCheckpointPort {
        override fun isDbOpen() = sessionManager.isDbOpen()
        override fun foldWal(): WalCheckpointRule.FoldReport = sessionManager.requireDatabase().openHelper.writableDatabase
            .query(WalCheckpointRule.FOLD_STATEMENT)
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    WalCheckpointRule.reportOf(
                        intArrayOf(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)),
                    )
                } else {
                    WalCheckpointRule.NO_ROW
                }
            }
    }

    private val dbFile: File = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
    private val tempSnapshotDir: File = context.cacheDir

    /**
     * Runs a full backup into the app's Documents backup folder: WAL
     * checkpoint + verified snapshot with retries, then rotation cleanup.
     * Records success/failure in [BackupStatusStore].
     */
    override suspend fun backupToAppBackupDirectory(trigger: BackupTrigger): BackupExecutionResult {
        val policy = retryPolicyFor(trigger)
        return buildCore(policy).backupToAppBackupDirectory(trigger)
    }

    /**
     * Streams a verified snapshot to a user-chosen [destinationUri] via SAF,
     * returning the bytes written.
     */
    suspend fun exportSnapshotToUri(destinationUri: Uri): Long = withContext(Dispatchers.IO) {
        val trigger = BackupTrigger.EXPORT
        val policy = retryPolicyFor(trigger)
        val core = buildCore(policy)
        runCatching {
            val snapshotResult = core.createSnapshot(trigger)
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

    private fun buildCore(policy: BackupRetryPolicy): BackupCoordinatorCore = BackupCoordinatorCore(
        dbFile = dbFile,
        tempSnapshotDir = tempSnapshotDir,
        checkpointPort = checkpointPort,
        retryPolicy = policy,
        logger = logger,
        backupDirectory = getBackupDirectory(),
        sessionFolderName = { buildBackupFolderName() },
        destinationFileName = PayanamDatabaseSchema.DATABASE_NAME,
        rotationRows = { readRotationRows() },
        failureMessage = { trigger, maxAttempts, error ->
            context.getString(
                R.string.backup_failure_message_template,
                triggerDisplayName(trigger),
                maxAttempts,
                error.message ?: error::class.java.simpleName,
            )
        },
        recordSuccess = { recordedAtMillis -> backupStatusStore.recordSuccess(recordedAtMillis) },
        recordFailure = { finalMessage -> backupStatusStore.recordFailure(finalMessage) },
    )

    private fun triggerDisplayName(trigger: BackupTrigger): String = when (trigger) {
        BackupTrigger.AUTO -> context.getString(R.string.backup_trigger_auto)
        BackupTrigger.MANUAL -> context.getString(R.string.backup_trigger_manual)
        BackupTrigger.EXPORT -> context.getString(R.string.backup_trigger_export)
    }

    private fun buildBackupFolderName(): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return autoBackupDirName(
            "android",
            BuildConfig.VERSION_NAME,
            BuildConfig.BUILD_SHORT_SHA,
            BuildConfig.DEBUG,
            timestamp,
        )
    }

    private fun getBackupDirectory(): File {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val suffix = if (context.packageName.endsWith(".debug")) "-debug" else ""
        return File(documentsDir, "Payanam$suffix/data/export")
    }

    private fun readRotationRows(): Map<String, String?> {
        val prefs = context.getSharedPreferences(BackupStatusStore.BACKUP_META_PREFS, Context.MODE_PRIVATE)
        return mapOf(
            BackupStatusStore.KEY_BACKUP_ROTATION_ENABLED to
                prefs.getBoolean(BackupStatusStore.KEY_BACKUP_ROTATION_ENABLED, false).toString(),
            BackupStatusStore.KEY_BACKUP_ROTATION_COUNT to
                prefs.getInt(BackupStatusStore.KEY_BACKUP_ROTATION_COUNT, 50).toString(),
        )
    }
}
