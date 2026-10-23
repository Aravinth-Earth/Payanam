//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.domain.port.BackupExecutionResult
import io.payanam.domain.port.BackupFailureStatus
import io.payanam.domain.port.BackupStatusSnapshot
import io.payanam.domain.port.BackupStatusStore
import io.payanam.domain.port.BackupTrigger
import io.payanam.domain.port.DatabaseBackupCoordinator
import io.payanam.domain.port.formatBackupDisplayTimestamp
import io.payanam.shared.logging.UnifiedLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * The desktop's [BackupStatusStore]: the last backup outcome, held in memory.
 *
 * Android's store persists its snapshot in SharedPreferences; the desktop deliberately has no second
 * persistence mechanism (its own settings stores are being retired, and the encrypted database is the
 * only data layer), so persistence for this state is an open item: the snapshot lives for the app's
 * run, which is all the settings screen that renders it needs. When the desktop's periodic backup is
 * wired, the writers below are what it will record through.
 */
internal class DesktopBackupStatusPort : BackupStatusStore {
    private val _status = MutableStateFlow(BackupStatusSnapshot())

    /** The in-memory snapshot; emits on every recorded outcome. */
    override val status: StateFlow<BackupStatusSnapshot> = _status.asStateFlow()

    /** The shared display format — one implementation, both platforms. */
    override fun formatBackupTimestamp(millis: Long): String = formatBackupDisplayTimestamp(millis)

    /** Records a successful backup, clearing any recorded failure — Android's semantics. */
    fun recordSuccess(recordedAtMillis: Long) {
        val effectiveMillis = recordedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        _status.value =
            BackupStatusSnapshot(
                lastSuccessAtMillis = effectiveMillis,
                lastSuccessDisplay = formatBackupDisplayTimestamp(effectiveMillis),
                latestFailure = null,
            )
    }

    /** Records a failed attempt with its (truncated) message, keeping the last success. */
    fun recordFailure(message: String) {
        _status.value =
            _status.value.copy(
                latestFailure =
                    BackupFailureStatus(
                        message = message.take(MESSAGE_LIMIT),
                        recordedAtDisplay = formatBackupDisplayTimestamp(System.currentTimeMillis()),
                    ),
            )
    }

    /** Clears the recorded failure so it stops showing. */
    fun dismissLatestFailure() {
        _status.value = _status.value.copy(latestFailure = null)
    }

    private companion object {
        /** Matches the Android store's truncation of failure messages. */
        private const val MESSAGE_LIMIT = 400
    }
}

/**
 * The desktop's [DatabaseBackupCoordinator]: one backup, on demand.
 *
 * The snapshot MECHANISM is injected ([performBackup] returns the destination path) rather than
 * built here: the desktop's export machinery already knows how to checkpoint and copy the encrypted
 * database, and the wiring that lands with the settings screen is where it will be bound. This class
 * owns the policy the shared contract states — record the outcome, wrap the failure, report how many
 * attempts the run took — so both platforms answer the same questions after a backup.
 */
internal class DesktopDatabaseBackupPort(
    private val statusStore: DesktopBackupStatusPort,
    private val performBackup: suspend () -> String,
    private val logger: UnifiedLogger,
) : DatabaseBackupCoordinator {
    /** Runs the injected backup, records the outcome, and reports it — never swallowing a failure. */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun backupToAppBackupDirectory(trigger: BackupTrigger): BackupExecutionResult {
        logger.i(
            "DesktopDatabaseBackupPort.backupToAppBackupDirectory",
            "Desktop backup requested",
            mapOf("trigger" to trigger.key),
        )
        return try {
            val destinationPath = performBackup()
            val recordedAtMillis = System.currentTimeMillis()
            statusStore.recordSuccess(recordedAtMillis)
            BackupExecutionResult(
                recordedAtMillis = recordedAtMillis,
                recordedAtDisplay = statusStore.formatBackupTimestamp(recordedAtMillis),
                destinationPath = destinationPath,
                attemptsUsed = 1,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val message = "Desktop backup failed (${trigger.key}): ${error.message ?: error::class.java.simpleName}"
            statusStore.recordFailure(message)
            throw IllegalStateException(message, error)
        }
    }
}
