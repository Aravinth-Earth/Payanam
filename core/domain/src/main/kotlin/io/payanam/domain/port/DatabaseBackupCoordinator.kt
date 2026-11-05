//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

/**
 * What started a backup: scheduled auto, user-initiated manual, or a settings-screen export.
 * Android uses it to pick the retry policy; both platforms label the recorded outcome with it.
 */
enum class BackupTrigger(val key: String) {
    AUTO("auto"),
    MANUAL("manual"),
    EXPORT("export"),
}

/**
 * Outcome of a completed backup: when it was recorded, where it landed, and how many snapshot
 * attempts it took.
 */
data class BackupExecutionResult(
    val recordedAtMillis: Long,
    val recordedAtDisplay: String,
    val destinationPath: String,
    val attemptsUsed: Int,
)

/**
 * Runs a database backup into the app's own backup location and reports the outcome.
 *
 * SUPPORTED on both platforms. The contract STATES the semantics both implementations keep, so the
 * two cannot quietly diverge in what "a backup" means:
 * - the write-ahead log is folded (checkpointed) before the snapshot is taken;
 * - the snapshot is verified before it is treated as a backup;
 * - retention rotation runs after a successful backup;
 * - every outcome — success or failure — is recorded in [BackupStatusStore];
 * - a failure is wrapped and rethrown, never swallowed.
 *
 * Android additionally has trigger-specific retry policies and a SAF export path beyond this
 * contract; those stay platform-side.
 */
interface DatabaseBackupCoordinator {
    /**
     * Backs the database up into the app's backup directory, recording the outcome.
     *
     * @throws IllegalStateException when the backup fails; the recorded failure carries the reason.
     */
    suspend fun backupToAppBackupDirectory(trigger: BackupTrigger): BackupExecutionResult
}
