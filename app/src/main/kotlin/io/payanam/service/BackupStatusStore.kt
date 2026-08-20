//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BackupFailureStatus.
 */
data class BackupFailureStatus(
    /** Message. */
    val message: String,
    /** Recorded at display. */
    val recordedAtDisplay: String?,
)

/**
 * BackupStatusSnapshot.
 */
data class BackupStatusSnapshot(
    /** Last success at millis. */
    val lastSuccessAtMillis: Long = 0L,
    /** Last success display. */
    val lastSuccessDisplay: String? = null,
    /** Latest failure. */
    val latestFailure: BackupFailureStatus? = null,
)

@Singleton
/**
 * BackupStatusStore.
 */
class BackupStatusStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val logger = UnifiedLogger.getInstance()
    private val prefs = context.getSharedPreferences(BACKUP_META_PREFS, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(loadSnapshot())
    /** Status. */
    val status: StateFlow<BackupStatusSnapshot> = _status.asStateFlow()

    /**
     * Record success.
     */
    fun recordSuccess(recordedAtMillis: Long) {
        /** Effective millis. */
        val effectiveMillis = recordedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        /** Display timestamp. */
        val displayTimestamp = formatBackupTimestamp(effectiveMillis)
        prefs.edit()
            .putLong(KEY_LAST_BACKUP_SUCCESS_AT_MILLIS, effectiveMillis)
            .putString(KEY_LAST_BACKUP_SUCCESS_DISPLAY, displayTimestamp)
            .remove(KEY_LAST_BACKUP_FAILURE_AT_MILLIS)
            .remove(KEY_LAST_BACKUP_FAILURE_DISPLAY)
            .remove(KEY_LAST_BACKUP_FAILURE_MESSAGE)
            .apply()
        _status.value = loadSnapshot()
        logger.i(
            "BackupStatusStore.recordSuccess",
            "Recorded backup success status",
            /** Map of. */
            mapOf("recordedAt" to displayTimestamp),
        )
    }

    /**
     * Record failure.
     */
    fun recordFailure(message: String) {
        /** Recorded at millis. */
        val recordedAtMillis = System.currentTimeMillis()
        /** Display timestamp. */
        val displayTimestamp = formatBackupTimestamp(recordedAtMillis)
        prefs.edit()
            .putLong(KEY_LAST_BACKUP_FAILURE_AT_MILLIS, recordedAtMillis)
            .putString(KEY_LAST_BACKUP_FAILURE_DISPLAY, displayTimestamp)
            .putString(KEY_LAST_BACKUP_FAILURE_MESSAGE, message.take(400))
            .apply()
        _status.value = loadSnapshot()
        logger.w(
            "BackupStatusStore.recordFailure",
            "Recorded backup failure status",
            /** Map of. */
            mapOf("recordedAt" to displayTimestamp, "message" to message.take(200)),
        )
    }

    /**
     * Dismiss latest failure.
     */
    fun dismissLatestFailure() {
        prefs.edit()
            .remove(KEY_LAST_BACKUP_FAILURE_AT_MILLIS)
            .remove(KEY_LAST_BACKUP_FAILURE_DISPLAY)
            .remove(KEY_LAST_BACKUP_FAILURE_MESSAGE)
            .apply()
        _status.value = loadSnapshot()
        logger.i("BackupStatusStore.dismissLatestFailure", "Dismissed persisted backup failure message")
    }

    /**
     * Refresh.
     */
    fun refresh() {
        _status.value = loadSnapshot()
    }

    private fun loadSnapshot(): BackupStatusSnapshot {
        /** Success millis. */
        val successMillis = prefs.getLong(KEY_LAST_BACKUP_SUCCESS_AT_MILLIS, 0L)
        /** Success display. */
        val successDisplay = prefs.getString(KEY_LAST_BACKUP_SUCCESS_DISPLAY, null)
        /** Failure message. */
        val failureMessage = prefs.getString(KEY_LAST_BACKUP_FAILURE_MESSAGE, null)?.takeIf { it.isNotBlank() }
        /** Failure display. */
        val failureDisplay = prefs.getString(KEY_LAST_BACKUP_FAILURE_DISPLAY, null)
        return BackupStatusSnapshot(
            lastSuccessAtMillis = successMillis,
            lastSuccessDisplay = successDisplay,
            latestFailure = failureMessage?.let {
                /** Backup failure status. */
                BackupFailureStatus(
                    message = it,
                    recordedAtDisplay = failureDisplay,
                )
            },
        )
    }

    companion object {
        /** Backup meta prefs. */
        const val BACKUP_META_PREFS = "payanam_backup_meta"
        /** Key backup rotation enabled. */
        const val KEY_BACKUP_ROTATION_ENABLED = "backup_rotation_enabled"
        /** Key backup rotation count. */
        const val KEY_BACKUP_ROTATION_COUNT = "backup_rotation_count"
        /** Key last backup success at millis. */
        const val KEY_LAST_BACKUP_SUCCESS_AT_MILLIS = "last_backup_success_at_millis"
        /** Key last backup success display. */
        const val KEY_LAST_BACKUP_SUCCESS_DISPLAY = "last_backup_success_display"
        /** Key last backup failure at millis. */
        const val KEY_LAST_BACKUP_FAILURE_AT_MILLIS = "last_backup_failure_at_millis"
        /** Key last backup failure display. */
        const val KEY_LAST_BACKUP_FAILURE_DISPLAY = "last_backup_failure_display"
        /** Key last backup failure message. */
        const val KEY_LAST_BACKUP_FAILURE_MESSAGE = "last_backup_failure_message"

        /**
         * Format backup timestamp.
         */
        fun formatBackupTimestamp(millis: Long): String = Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
}
