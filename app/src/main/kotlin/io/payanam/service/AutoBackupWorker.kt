//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.domain.repository.AppSettingsRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for automatic database backup.
 * Exports database to Documents/Payanam/data/export/auto_bk_*.db
 */
class AutoBackupWorker(
    /** App context. */
    appContext: Context,
    /** Worker params. */
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val logger = UnifiedLogger.getInstance()

    override suspend fun doWork(): Result {
        /** Trigger. */
        val trigger = backupTrigger()
        /** Work id. */
        val workId = id.toString()
        /** Started at millis. */
        val startedAtMillis = System.currentTimeMillis()
        logger.i(
            "AutoBackupWorker.doWork",
            "Starting database backup",
            /** Map of. */
            mapOf(
                "trigger" to trigger,
                "workId" to workId,
            ),
        )

        return try {
            /** Backup coordinator. */
            val backupCoordinator = entryPoint().databaseBackupCoordinator()
            /** Trigger type. */
            val triggerType = if (trigger == BACKUP_TRIGGER_MANUAL) BackupTrigger.MANUAL else BackupTrigger.AUTO
            /** Result. */
            val result = backupCoordinator.backupToAppBackupDirectory(triggerType)

            /** Elapsed ms. */
            val elapsedMs = System.currentTimeMillis() - startedAtMillis
            logger.i(
                "AutoBackupWorker.doWork",
                "Database backup completed successfully",
                /** Map of. */
                mapOf(
                    "trigger" to trigger,
                    "workId" to workId,
                    "backupPath" to result.destinationPath,
                    "attemptsUsed" to result.attemptsUsed,
                    "recordedAt" to result.recordedAtDisplay,
                    "elapsedMs" to elapsedMs,
                ),
            )

            Result.success()
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            /** Elapsed ms. */
            val elapsedMs = System.currentTimeMillis() - startedAtMillis
            logger.e(
                "AutoBackupWorker.doWork",
                "Database backup failed",
                /** E. */
                e,
                /** Map of. */
                mapOf(
                    "trigger" to trigger,
                    "workId" to workId,
                    "error" to (e.message ?: "Unknown error"),
                    "elapsedMs" to elapsedMs,
                ),
            )
            Result.failure()
        }
    }

    private fun backupTrigger(): String = inputData.getString(KEY_BACKUP_TRIGGER)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: BACKUP_TRIGGER_AUTO

    private fun entryPoint(): BackupWorkerEntryPoint = EntryPointAccessors.fromApplication(
        /** Application context. */
        applicationContext,
        BackupWorkerEntryPoint::class.java,
    )

    companion object {
        /**
         * AutoBackupFailureStatus.
         */
        data class AutoBackupFailureStatus(
            /** Message. */
            val message: String,
            /** Recorded at display. */
            val recordedAtDisplay: String?,
        )

        private const val WORK_NAME = "auto_backup"
        private const val KEY_BACKUP_TRIGGER = "backup_trigger"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_AUTO_BACKUP_INTERVAL = "auto_backup_interval"
        private const val BACKUP_TRIGGER_AUTO = "auto"
        private const val BACKUP_TRIGGER_MANUAL = "manual"

        /**
         * Schedule or update the auto-backup work.
         * @param context Application context
         * @param intervalMinutes Interval in minutes (minimum 15)
         */
        fun schedule(context: Context, intervalMinutes: Long) {
            /** Logger. */
            val logger = UnifiedLogger.getInstance()
            /** Effective interval. */
            val effectiveInterval = intervalMinutes.coerceAtLeast(15)

            logger.i(
                "AutoBackupWorker.schedule",
                "Scheduling auto-backup",
                /** Map of. */
                mapOf(
                    "intervalMinutes" to effectiveInterval,
                ),
            )

            /** Constraints. */
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            /** Work request. */
            val workRequest = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                /** Effective interval. */
                effectiveInterval,
                TimeUnit.MINUTES,
            )
                .setInputData(
                    Data.Builder()
                        .putString(KEY_BACKUP_TRIGGER, BACKUP_TRIGGER_AUTO)
                        .build(),
                )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                /** Work name. */
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                /** Work request. */
                workRequest,
            )
        }

        /**
         * Cancel scheduled auto-backup work.
         */
        fun cancel(context: Context) {
            /** Logger. */
            val logger = UnifiedLogger.getInstance()
            logger.i("AutoBackupWorker.cancel", "Cancelling auto-backup")
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Run backup immediately (one-time).
         * After success, reschedules periodic work so next auto backup fires at now + interval.
         */
        fun runNow(context: Context): UUID {
            /** Logger. */
            val logger = UnifiedLogger.getInstance()
            logger.i("AutoBackupWorker.runNow", "Running manual backup immediately")

            /** Work request. */
            val workRequest = OneTimeWorkRequestBuilder<AutoBackupWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_BACKUP_TRIGGER, BACKUP_TRIGGER_MANUAL)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
            logger.d(
                "AutoBackupWorker.runNow",
                "Enqueued one-time backup work",
                /** Map of. */
                mapOf(
                    "workId" to workRequest.id.toString(),
                    "trigger" to BACKUP_TRIGGER_MANUAL,
                ),
            )
            return workRequest.id
        }

        /**
         * Reschedule periodic auto-backup from now so that the next auto backup fires
         * at (now + interval) rather than the original schedule. Called after a successful
         * manual backup to honour the user's expectation: interval since last backup.
         */
        fun rescheduleFromNow(context: Context, appSettingsRepository: AppSettingsRepository) {
            /** Logger. */
            val logger = UnifiedLogger.getInstance()
            kotlinx.coroutines.runBlocking {
                /** Enabled. */
                val enabled = appSettingsRepository.getSetting(KEY_AUTO_BACKUP_ENABLED)?.toBoolean() ?: false
                /** If. */
                if (!enabled) return@runBlocking
                /** Interval minutes. */
                val intervalMinutes = intervalKeyToMinutes(appSettingsRepository.getSetting(KEY_AUTO_BACKUP_INTERVAL))
                logger.i(
                    "AutoBackupWorker.rescheduleFromNow",
                    "Rescheduling periodic backup from now after manual backup",
                    /** Map of. */
                    mapOf("intervalMinutes" to intervalMinutes),
                )
                /** Schedule. */
                schedule(context, intervalMinutes)
            }
        }

        /**
         * Reconcile schedule.
         */
        suspend fun reconcileSchedule(context: Context, appSettingsRepository: AppSettingsRepository) {
            /** Logger. */
            val logger = UnifiedLogger.getInstance()
            /** Enabled. */
            val enabled = appSettingsRepository.getSetting(KEY_AUTO_BACKUP_ENABLED)?.toBoolean() ?: false
            /** If. */
            if (!enabled) {
                logger.d("AutoBackupWorker.reconcileSchedule", "Auto-backup disabled in settings; cancelling worker")
                /** Cancel. */
                cancel(context)
                /** Return. */
                return
            }

            /** Interval minutes. */
            val intervalMinutes = intervalKeyToMinutes(appSettingsRepository.getSetting(KEY_AUTO_BACKUP_INTERVAL))
            logger.i(
                "AutoBackupWorker.reconcileSchedule",
                "Auto-backup enabled in settings; scheduling worker",
                /** Map of. */
                mapOf(
                    "intervalMinutes" to intervalMinutes,
                ),
            )
            /** Schedule. */
            schedule(context, intervalMinutes)
        }

        /**
         * Get the backup directory path.
         */
        fun getBackupDirectory(): File {
            /** Documents dir. */
            val documentsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            /** Suffix. */
            val suffix = if (io.payanam.BuildConfig.DEBUG) "-debug" else ""
            return File(documentsDir, "Payanam$suffix/data/export")
        }

        /**
         * Get list of auto-backup files.
         */
        fun getBackupFiles(): List<File> {
            /** Backup dir. */
            val backupDir = getBackupDirectory()
            /** If. */
            if (!backupDir.exists()) return emptyList()

            /** Legacy flat files. */
            val legacyFlatFiles = backupDir.listFiles { file ->
                file.isFile && file.name.startsWith("auto_bk_") && file.name.endsWith(".db")
            }?.toList() ?: emptyList()

            /** Session directory files. */
            val sessionDirectoryFiles = backupDir.listFiles { file ->
                file.isDirectory && file.name.startsWith("auto_bk_")
            }?.mapNotNull { sessionDir ->
                /** File. */
                File(sessionDir, PayanamDatabase.DATABASE_NAME).takeIf { it.exists() }
            } ?: emptyList()

            /** Return. */
            return (legacyFlatFiles + sessionDirectoryFiles).sortedByDescending { it.lastModified() }
        }

        /**
         * Get latest backup success millis.
         */
        fun getLatestBackupSuccessMillis(context: Context): Long {
            /** Prefs. */
            val prefs = context.getSharedPreferences(BackupStatusStore.BACKUP_META_PREFS, Context.MODE_PRIVATE)
            return prefs.getLong(BackupStatusStore.KEY_LAST_BACKUP_SUCCESS_AT_MILLIS, 0L)
        }

        /**
         * Get latest backup last run display.
         */
        fun getLatestBackupLastRunDisplay(context: Context): String? {
            /** Latest file millis. */
            val latestFileMillis = getBackupFiles()
                .firstOrNull()
                ?.lastModified()
                ?.takeIf { it > 0L }
            /** Prefs. */
            val prefs = context.getSharedPreferences(BackupStatusStore.BACKUP_META_PREFS, Context.MODE_PRIVATE)
            /** Stored millis. */
            val storedMillis = prefs.getLong(BackupStatusStore.KEY_LAST_BACKUP_SUCCESS_AT_MILLIS, 0L).takeIf { it > 0L }
            /** Stored display. */
            val storedDisplay = prefs.getString(BackupStatusStore.KEY_LAST_BACKUP_SUCCESS_DISPLAY, null)
            /** Effective millis. */
            val effectiveMillis = listOfNotNull(latestFileMillis, storedMillis).maxOrNull()
            return when {
                effectiveMillis == null -> storedDisplay
                effectiveMillis == storedMillis && !storedDisplay.isNullOrBlank() -> storedDisplay
                else -> BackupStatusStore.formatBackupTimestamp(effectiveMillis)
            }
        }

        /**
         * Get latest backup failure status.
         */
        fun getLatestBackupFailureStatus(context: Context): AutoBackupFailureStatus? {
            /** Prefs. */
            val prefs = context.getSharedPreferences(BackupStatusStore.BACKUP_META_PREFS, Context.MODE_PRIVATE)
            /** Message. */
            val message = prefs.getString(BackupStatusStore.KEY_LAST_BACKUP_FAILURE_MESSAGE, null)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            /** Display. */
            val display = prefs.getString(BackupStatusStore.KEY_LAST_BACKUP_FAILURE_DISPLAY, null)
            return AutoBackupFailureStatus(
                message = message,
                recordedAtDisplay = display,
            )
        }

        /**
         * Dismiss latest backup failure.
         */
        fun dismissLatestBackupFailure(context: Context) {
            context.getSharedPreferences(BackupStatusStore.BACKUP_META_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(BackupStatusStore.KEY_LAST_BACKUP_FAILURE_AT_MILLIS)
                .remove(BackupStatusStore.KEY_LAST_BACKUP_FAILURE_DISPLAY)
                .remove(BackupStatusStore.KEY_LAST_BACKUP_FAILURE_MESSAGE)
                .apply()
            UnifiedLogger.getInstance().i(
                "AutoBackupWorker.dismissLatestBackupFailure",
                "Dismissed latest auto-backup failure message",
            )
        }

        private fun intervalKeyToMinutes(key: String?): Long = when (key) {
            "15m" -> 15L
            "30m" -> 30L
            "60m" -> 60L
            "2h" -> 120L
            "6h" -> 360L
            "12h" -> 720L
            "24h" -> 1440L
            else -> 60L
        }
    }
}
