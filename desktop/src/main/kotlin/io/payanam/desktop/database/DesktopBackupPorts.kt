//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.desktop.DesktopAppPaths
import io.payanam.desktop.DesktopBuildInfo
import io.payanam.desktop.DesktopKeyValueEntry
import io.payanam.domain.port.BackupExecutionResult
import io.payanam.domain.port.BackupFailureStatus
import io.payanam.domain.port.BackupStatusSnapshot
import io.payanam.domain.port.BackupStatusStore
import io.payanam.domain.port.BackupTrigger
import io.payanam.domain.port.DatabaseBackupCoordinator
import io.payanam.domain.port.formatBackupDisplayTimestamp
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import io.payanam.shared.logging.autoBackupFileName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import java.util.Properties
import kotlin.coroutines.cancellation.CancellationException

/**
 * The desktop's [BackupStatusStore]: the last backup outcome, persisted across runs.
 *
 * Android's store keeps its snapshot in app-private `SharedPreferences`; this is the desktop's
 * equivalent of that — an owner-only properties file written through [DesktopKeyValueEntry], which is
 * already how DesktopSecurityStore keeps its own snapshot. It is deliberately NOT a database row: the
 * screen that renders the last outcome can be reached BEFORE the database is unlocked, and the
 * encrypted database is not open at that point, so a row could not be read when it is needed.
 *
 * It mirrors Android's four operations exactly (`service/BackupStatusStore.kt`): a success CLEARS the
 * recorded failure and leaves both success keys; a failure writes its three keys and leaves the success
 * keys alone; a dismiss removes exactly the three failure keys; and a BLANK persisted message loads as
 * no failure at all — that last rule is the one a look-alike store usually gets wrong.
 */
internal class DesktopBackupStatusPort(
    preferencesDirectory: Path = DesktopAppPaths.resolvePreferencesDirectory(),
    private val persistenceEntry: DesktopKeyValueEntry =
        DesktopKeyValueEntry(backupStatusFileIn(preferencesDirectory)),
) : BackupStatusStore {
    private val logger = AppLogger.current

    /** Seeded from a REAL read, so a restart reports the last outcome instead of an empty snapshot. */
    private val _status = MutableStateFlow(readSnapshot())

    /** The persisted snapshot; emits on every recorded outcome. */
    override val status: StateFlow<BackupStatusSnapshot> = _status.asStateFlow()

    /** The shared display format — one implementation, both platforms. */
    override fun formatBackupTimestamp(millis: Long): String = formatBackupDisplayTimestamp(millis)

    /** Records a successful backup, clearing any recorded failure — Android's semantics. */
    fun recordSuccess(recordedAtMillis: Long) {
        val effectiveMillis = recordedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val properties = readProperties()
        properties.setProperty(KEY_SUCCESS_AT_MILLIS, effectiveMillis.toString())
        properties.setProperty(KEY_SUCCESS_DISPLAY, formatBackupDisplayTimestamp(effectiveMillis))
        removeFailureKeys(properties)
        save(properties)
        _status.value = readSnapshot()
    }

    /** Records a failed attempt with its (truncated) message, keeping the last success. */
    fun recordFailure(message: String) {
        val recordedAtMillis = System.currentTimeMillis()
        val properties = readProperties()
        properties.setProperty(KEY_FAILURE_AT_MILLIS, recordedAtMillis.toString())
        properties.setProperty(KEY_FAILURE_DISPLAY, formatBackupDisplayTimestamp(recordedAtMillis))
        properties.setProperty(KEY_FAILURE_MESSAGE, message.take(MESSAGE_LIMIT))
        save(properties)
        _status.value = readSnapshot()
    }

    /** Clears the recorded failure so it stops showing. */
    fun dismissLatestFailure() {
        val properties = readProperties()
        removeFailureKeys(properties)
        save(properties)
        _status.value = readSnapshot()
    }

    private fun removeFailureKeys(properties: Properties) {
        properties.remove(KEY_FAILURE_AT_MILLIS)
        properties.remove(KEY_FAILURE_DISPLAY)
        properties.remove(KEY_FAILURE_MESSAGE)
    }

    /**
     * Reads the five persisted keys. An absent, unreadable or unparsable entry yields the EMPTY
     * snapshot rather than throwing: the screen that renders this must render something.
     */
    private fun readSnapshot(): BackupStatusSnapshot {
        val properties = readProperties()
        val message = properties.getProperty(KEY_FAILURE_MESSAGE).orEmpty()
        return BackupStatusSnapshot(
            lastSuccessAtMillis = properties.getProperty(KEY_SUCCESS_AT_MILLIS)?.toLongOrNull() ?: 0L,
            lastSuccessDisplay = properties.getProperty(KEY_SUCCESS_DISPLAY),
            latestFailure =
                message.takeIf { it.isNotBlank() }?.let { recorded ->
                    BackupFailureStatus(
                        message = recorded,
                        recordedAtDisplay = properties.getProperty(KEY_FAILURE_DISPLAY),
                    )
                },
        )
    }

    private fun readProperties(): Properties {
        val payload = readEntryOrNull() ?: return Properties()
        return Properties().also { properties ->
            if (payload.isNotBlank()) {
                StringReader(payload).use(properties::load)
            }
        }
    }

    private fun readEntryOrNull(): String? =
        try {
            persistenceEntry.readEntry()
        } catch (error: IOException) {
            logger.e(
                SOURCE,
                "The persisted backup status could not be read; reporting no recorded outcome",
                error,
                mapOf("entryKey" to STATE_ENTRY_KEY, "failure" to error::class.simpleName.orEmpty()),
            )
            null
        }

    private fun save(properties: Properties) {
        val payload =
            StringWriter().use { writer ->
                properties.store(writer, "Payanam Backup Status")
                writer.toString()
            }
        try {
            persistenceEntry.writeEntry(payload)
        } catch (error: IOException) {
            logger.e(
                SOURCE,
                "The backup status could not be persisted; it will not survive a restart",
                error,
                mapOf("entryKey" to STATE_ENTRY_KEY, "failure" to error::class.simpleName.orEmpty()),
            )
        }
    }

    internal companion object {
        internal const val STATE_ENTRY_KEY = "desktop/backup-status"

        /**
         * The status file, named for what it holds. The directory is the platform-resolved one, and the
         * name is stable so a restart finds it — which is the entire point: the in-memory entry did not.
         */
        fun backupStatusFileIn(directory: Path): Path = directory.resolve(DESKTOP_BACKUP_STATUS_FILE_NAME)

        private const val SOURCE = "DesktopBackupStatusPort"

        // Android's five keys, spelled identically so both stores describe the same facts.
        private const val KEY_SUCCESS_AT_MILLIS = "last_backup_success_at_millis"
        private const val KEY_SUCCESS_DISPLAY = "last_backup_success_display"
        private const val KEY_FAILURE_AT_MILLIS = "last_backup_failure_at_millis"
        private const val KEY_FAILURE_DISPLAY = "last_backup_failure_display"
        private const val KEY_FAILURE_MESSAGE = "last_backup_failure_message"

        /** Matches the Android store's truncation of failure messages. */
        private const val MESSAGE_LIMIT = 400
    }
}

/** The status file's name, beside the database it describes. */
private const val DESKTOP_BACKUP_STATUS_FILE_NAME = "desktop-backup-status.properties"

/**
 * How many backup directories to keep, and whether to prune the rest at all.
 *
 * The policy, its row reader, and the prune mechanics now live in
 * `core:presentation` (shared by both platforms per the Layer 3 ruling);
 * this typealias keeps every desktop call site compiling unchanged.
 */
internal typealias BackupRotationPolicy = io.payanam.presentation.backup.BackupRotationPolicy

/**
 * Delegates to the shared row reader: same two [AppPreferenceKeys] rows, same
 * clamp, one implementation for both platforms.
 */
internal fun rotationPolicyFromBackupRows(rows: Map<String, String?>): BackupRotationPolicy =
    io.payanam.presentation.backup
        .rotationPolicyFromBackupRows(rows)

/**
 * Delegates the rotation mechanics to the shared prune, passing this platform's
 * artifact matcher ([isBackupArtifact]) so only artifacts this app owns are ever
 * considered. Returns the paths deleted, oldest first.
 */
internal fun pruneOldBackupDirectories(
    backupDirectory: java.io.File,
    policy: BackupRotationPolicy,
    logger: UnifiedLogger,
): List<java.io.File> =
    io.payanam.presentation.backup.pruneBackupArtifacts(
        backupDirectory = backupDirectory,
        policy = policy,
        isArtifact = { file -> file.isBackupArtifact() },
        logger = logger,
    )

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
 * The artifact name a scheduled backup writes: the shared `auto_bk_` name carrying version, sha and
 * build type, so it matches Android's automatic backup and stays visible to the matcher below.
 *
 * [stamp] is an already-formatted timestamp. Kept as a seam because rotation's fixtures build their
 * artifacts through it.
 */
internal fun scheduledBackupArtifactName(stamp: String): String =
    autoBackupFileName(
        DesktopBuildInfo.PLATFORM,
        DesktopBuildInfo.VERSION_NAME,
        DesktopBuildInfo.BUILD_SHORT_SHA,
        DesktopBuildInfo.IS_DEBUG_BUILD,
        stamp,
    )

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
    private val checkpoint: () -> Boolean,
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
            if (!checkpoint()) {
                throw IllegalStateException(
                    "Desktop backup aborted (${trigger.key}): the write-ahead log could not be folded, so no snapshot was taken",
                )
            }
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
