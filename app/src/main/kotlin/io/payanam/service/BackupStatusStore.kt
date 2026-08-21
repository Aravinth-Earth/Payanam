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
 * Last-known backup state shown on the settings screen: most recent success
 * timestamp and the latest (dismissible) failure, if any.
 */
data class BackupStatusSnapshot(
    val lastSuccessAtMillis: Long = 0L,
    val lastSuccessDisplay: String? = null,
    val latestFailure: BackupFailureStatus? = null,
)

/**
 * A single backup failure shown to the user: message plus a pre-formatted
 * display timestamp.
 */
data class BackupFailureStatus(
    val message: String,
    val recordedAtDisplay: String?,
)

/**
 * Persisted store of the last auto-backup outcome (success timestamp and
 * latest failure), backed by SharedPreferences and exposed as state for the
 * settings screen.
 */
@Singleton
class BackupStatusStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val logger = UnifiedLogger.getInstance()
    private val prefs = context.getSharedPreferences(BACKUP_META_PREFS, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(loadSnapshot())
    val status: StateFlow<BackupStatusSnapshot> = _status.asStateFlow()
    /**
     * Persists a successful backup (clearing any recorded failure).
     */
    fun recordSuccess(recordedAtMillis: Long) {
        val effectiveMillis = recordedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
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
            mapOf("recordedAt" to displayTimestamp),
        )
    }
    /**
     * Records a failed backup attempt with its (truncated) error message.
     */
    fun recordFailure(message: String) {
        val recordedAtMillis = System.currentTimeMillis()
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
            mapOf("recordedAt" to displayTimestamp, "message" to message.take(200)),
        )
    }
    /**
     * Clears the persisted failure so it stops showing on the settings screen.
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
     * Re-reads the snapshot from preferences into state (after external changes).
     */
    fun refresh() {
        _status.value = loadSnapshot()
    }

    private fun loadSnapshot(): BackupStatusSnapshot {
        val successMillis = prefs.getLong(KEY_LAST_BACKUP_SUCCESS_AT_MILLIS, 0L)
        val successDisplay = prefs.getString(KEY_LAST_BACKUP_SUCCESS_DISPLAY, null)
        val failureMessage = prefs.getString(KEY_LAST_BACKUP_FAILURE_MESSAGE, null)?.takeIf { it.isNotBlank() }
        val failureDisplay = prefs.getString(KEY_LAST_BACKUP_FAILURE_DISPLAY, null)
        return BackupStatusSnapshot(
            lastSuccessAtMillis = successMillis,
            lastSuccessDisplay = successDisplay,
            latestFailure = failureMessage?.let {
                BackupFailureStatus(
                    message = it,
                    recordedAtDisplay = failureDisplay,
                )
            },
        )
    }

    companion object {
        const val BACKUP_META_PREFS = "payanam_backup_meta"
        const val KEY_BACKUP_ROTATION_ENABLED = "backup_rotation_enabled"
        const val KEY_BACKUP_ROTATION_COUNT = "backup_rotation_count"
        const val KEY_LAST_BACKUP_SUCCESS_AT_MILLIS = "last_backup_success_at_millis"
        const val KEY_LAST_BACKUP_SUCCESS_DISPLAY = "last_backup_success_display"
        const val KEY_LAST_BACKUP_FAILURE_AT_MILLIS = "last_backup_failure_at_millis"
        const val KEY_LAST_BACKUP_FAILURE_DISPLAY = "last_backup_failure_display"
        const val KEY_LAST_BACKUP_FAILURE_MESSAGE = "last_backup_failure_message"
        /**
         * Formats an epoch-millis timestamp for display ("yyyy-MM-dd HH:mm").
         */
        fun formatBackupTimestamp(millis: Long): String = Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
}
