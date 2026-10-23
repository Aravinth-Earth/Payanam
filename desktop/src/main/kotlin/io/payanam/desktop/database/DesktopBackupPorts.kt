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
import io.payanam.presentation.preferences.AppPreferenceKeys
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

/** How many backup directories to keep, and whether to prune the rest at all. */
internal data class BackupRotationPolicy(
    val enabled: Boolean,
    val keepCount: Int = DEFAULT_KEEP_COUNT,
) {
    init {
        require(keepCount >= 1) { "keepCount must be at least 1, was $keepCount" }
    }

    companion object {
        /** Android's default row value for `backup_rotation_count`, when the row is absent. */
        const val DEFAULT_KEEP_COUNT = 50

        /**
         * The upper bound Android coerces a retention count into, beside its lower bound of 1.
         *
         * Named rather than inlined because the number is Android's rule, not a taste: a desktop that
         * clamped to a different ceiling would delete backups on a row value Android keeps, and one that
         * did not clamp at all would hand `0` to a constructor that rejects it - AFTER a backup succeeded.
         */
        const val MAX_KEEP_COUNT = 999
    }
}

/**
 * Reads the rotation policy from the shared `app_settings` rows, exactly as Android's coordinator
 * reads them from its own preferences: absent means disabled, the count falls back to the shared
 * default, and the count is clamped to Android's own 1..999 range BEFORE it can reach a constructor
 * that rejects a smaller one.
 *
 * The row NAMES come from [AppPreferenceKeys], so this cannot drift from the settings screen that
 * writes them or from the Android worker that reads the same two keys. Deriving the numbers here
 * rather than at each call site is what makes the scheduled path and the backup port read rotation
 * identically rather than twice, differently.
 *
 * A row that cannot be parsed is clamped, never thrown: rotation runs after a backup the user is
 * relying on, and a malformed retention row must not turn that backup into a failed one.
 */
internal fun rotationPolicyFromBackupRows(rows: Map<String, String?>): BackupRotationPolicy =
    BackupRotationPolicy(
        enabled = rows[AppPreferenceKeys.KEY_BACKUP_ROTATION_ENABLED]?.toBoolean() ?: false,
        keepCount =
            rows[AppPreferenceKeys.KEY_BACKUP_ROTATION_COUNT]
                ?.toIntOrNull()
                ?.coerceIn(1, BackupRotationPolicy.MAX_KEEP_COUNT)
                ?: BackupRotationPolicy.DEFAULT_KEEP_COUNT,
    )

/**
 * Prunes the desktop's backup artifacts the way Android's coordinator does: the newest
 * [BackupRotationPolicy.keepCount] artifacts are kept and the rest are deleted, oldest first.
 *
 * Android reads the same two settings rows the desktop's settings screen writes; without this the
 * desktop wrote a rotation toggle no code path ever read, so the toggle did nothing. Only artifacts
 * are considered — see [isBackupArtifact] — so anything else sitting in the exports folder is never
 * deleted.
 *
 * Returns the paths deleted, oldest first, so a caller can report what happened and a test can
 * assert exactly which artifacts went.
 */
internal fun pruneOldBackupDirectories(
    backupDirectory: java.io.File,
    policy: BackupRotationPolicy,
    logger: UnifiedLogger,
): List<java.io.File> {
    if (!policy.enabled) return emptyList()
    val backupDirs =
        backupDirectory
            .listFiles { file -> file.isBackupArtifact() }
            ?.toList()
            .orEmpty()
    if (backupDirs.size <= policy.keepCount) return emptyList()
    val doomed = backupDirs.sortedByDescending { it.lastModified() }.drop(policy.keepCount)
    val deleted = mutableListOf<java.io.File>()
    doomed.forEach { candidate ->
        // A directory that cannot be removed is reported and skipped: one locked folder must not
        // stop the rest of the rotation, and must not fail the backup that triggered it.
        val removed = if (candidate.deleteRecursively()) candidate else null
        if (removed != null) {
            deleted += removed
            logger.i(
                "pruneOldBackupDirectories",
                "Pruned an old backup",
                mapOf("path" to removed.absolutePath, "keepCount" to policy.keepCount),
            )
        } else {
            logger.i(
                "pruneOldBackupDirectories",
                "Could not prune an old backup — left in place",
                mapOf("path" to candidate.absolutePath),
            )
        }
    }
    return deleted
}

/**
 * The prefix both platforms name a backup artifact with, and the extension the artifacts carry.
 *
 * One prefix for both platforms, not one per platform: rotation matches on it, so an artifact this
 * app writes and does not recognise is an artifact rotation can never prune, whatever the count row
 * says. Android writes a directory per backup; this app writes a flat file.
 */
internal const val BACKUP_ARTIFACT_PREFIX = "auto_bk_"

/** The extension a backup artifact carries on both platforms. */
internal const val BACKUP_ARTIFACT_EXTENSION = "db"

/**
 * The artifact name a scheduled backup writes.
 *
 * [stamp] is an already-formatted timestamp. The name is derived here, beside the matcher rotation
 * prunes with, because a name written at its call site is a name that can drift away from it.
 */
internal fun scheduledBackupArtifactName(stamp: String): String = "$BACKUP_ARTIFACT_PREFIX$stamp.$BACKUP_ARTIFACT_EXTENSION"

/**
 * Whether this file is a backup artifact this rotation owns: a session directory, or a flat artifact
 * file. Both shapes exist on Android — its own backup listing accepts a legacy flat file and a
 * session directory alike — so a matcher that accepted only one of them would prune a backup family
 * and leave the rest to accumulate forever.
 */
private fun java.io.File.isBackupArtifact(): Boolean =
    name.startsWith(BACKUP_ARTIFACT_PREFIX) &&
        (isDirectory || name.endsWith(BACKUP_ARTIFACT_EXTENSION))

/**
 * The desktop's [DatabaseBackupCoordinator]: one backup, on demand, with rotation.
 *
 * The snapshot MECHANISM is injected ([performBackup] returns the destination path) rather than
 * built here: the desktop's export machinery already knows how to checkpoint and copy the encrypted
 * database, and the wiring that lands with the settings screen is where it will be bound. This class
 * owns the policy the shared contract states — record the outcome, wrap the failure, report how many
 * attempts the run took — so both platforms answer the same questions after a backup.
 *
 * [rotationPolicy] and [backupDirectory] supply the two rotation rows the desktop's settings screen
 * writes. Rotation runs after a SUCCESSFUL backup only, so a failed attempt can never be the reason
 * an existing backup is deleted.
 */
internal class DesktopDatabaseBackupPort(
    private val statusStore: DesktopBackupStatusPort,
    private val performBackup: suspend () -> String,
    private val logger: UnifiedLogger,
    private val rotationPolicy: () -> BackupRotationPolicy = { BackupRotationPolicy(enabled = false) },
    private val backupDirectory: java.io.File? = null,
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
            backupDirectory?.let { directory ->
                val policy = rotationPolicy()
                val pruned = pruneOldBackupDirectories(directory, policy, logger)
                if (pruned.isNotEmpty()) {
                    logger.i(
                        "DesktopDatabaseBackupPort.backupToAppBackupDirectory",
                        "Rotation pruned old backups",
                        mapOf("deleted" to pruned.size, "keepCount" to policy.keepCount),
                    )
                }
            }
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
