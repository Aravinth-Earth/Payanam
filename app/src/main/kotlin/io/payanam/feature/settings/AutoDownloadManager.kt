//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import io.payanam.common.logging.UnifiedLogger
import java.io.File

/** States surfaced to the Settings UI for the auto-download flow. */
sealed class DownloadUiState {
    data object Idle : DownloadUiState()
    data class Downloading(
        val fileName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        /** Channel this download belongs to (enriched by the ViewModel). */
        val channelName: String = "",
        /** Full APK build name, e.g. "Payanam_Android_1568_20260812_193754.apk" (enriched). */
        val buildName: String = "",
    ) : DownloadUiState() {
        val progressPercent: Int
            get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
    }
    /** Paused by the system (e.g. waiting for Wi-Fi) — not an error. */
    data class Paused(val message: String) : DownloadUiState()
    data class Downloaded(val fileName: String, val localPath: String? = null) : DownloadUiState()
    data class Failed(val message: String) : DownloadUiState()
}

/**
 * Wraps DownloadManager for Payanam APK downloads into the app-private
 * external files dir (no storage permission needed on any API level).
 * Downloads land in: /sdcard/Android/data/<pkg>/files/downloads/
 */
object AutoDownloadManager {

    private const val SUBDIR = "downloads"
    private val logger = UnifiedLogger.getInstance()

    /**
     * Enqueue the APK download. [url] is the GitHub release asset URL.
     * [wifiOnly] restricts the download to unmetered networks (system
     * pauses/resumes automatically when network changes).
     * Returns the download ID, or null if enqueue failed.
     */
    fun enqueue(
        context: Context,
        url: String,
        fileName: String,
        wifiOnly: Boolean = false,
    ): Long? {
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Payanam #${buildNumberFromFileName(fileName)}")
                .setDescription("Downloading update APK")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, "$SUBDIR/$fileName")
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(!wifiOnly)
            val id = manager.enqueue(request)
            logger.d("AutoDownloadManager.enqueue", "Download enqueued", mapOf("downloadId" to id, "file" to fileName, "wifiOnly" to wifiOnly))
            id
        } catch (e: Exception) {
            logger.e("AutoDownloadManager.enqueue", "Enqueue failed", e)
            null
        }
    }

    /** Query progress for a download ID; returns null if the row is gone. */
    fun queryProgress(context: Context, downloadId: Long): DownloadUiState {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        var cursor: Cursor? = null
        return try {
            cursor = manager.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        logger.d("AutoDownloadManager.queryProgress", "Download complete", mapOf("uri" to (uri ?: "unknown")))
                        DownloadUiState.Downloaded(
                            fileName = uri?.substringAfterLast('/') ?: "unknown",
                            localPath = uri?.removePrefix("file://"),
                        )
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        logger.w("AutoDownloadManager.queryProgress", "Download failed", mapOf("reason" to reason))
                        DownloadUiState.Failed(failureMessage(reason))
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        DownloadUiState.Paused(pausedMessage(reason))
                    }
                    else -> DownloadUiState.Downloading(
                        fileName = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)),
                        bytesDownloaded = bytes,
                        totalBytes = total,
                    )
                }
            } else {
                DownloadUiState.Failed("download_not_found")
            }
        } catch (e: Exception) {
            logger.e("AutoDownloadManager.queryProgress", "Query failed", e)
            DownloadUiState.Failed("query_error")
        } finally {
            cursor?.close()
        }
    }

    /** Cancel a download and remove its partial file. */
    fun cancel(context: Context, downloadId: Long) {
        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.remove(downloadId)
            logger.d("AutoDownloadManager.cancel", "Download removed", mapOf("downloadId" to downloadId))
        } catch (e: Exception) {
            logger.e("AutoDownloadManager.cancel", "Cancel failed", e)
        }
    }

    /**
     * Keep only the [keepCount] newest APKs in the downloads dir, deleting older
     * ones. Skips files that don't exist or are not .apk. Best-effort; failures
     * are logged, not thrown.
     */
    fun cleanupOldApks(context: Context, keepCount: Int = 2) {
        try {
            val dir = context.getExternalFilesDir(null)?.let { File(it, SUBDIR) } ?: return
            if (!dir.exists()) return
            val apks = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.toList() ?: return
            if (apks.size <= keepCount) return
            val toDelete = apks.sortedByDescending { it.lastModified() }.drop(keepCount)
            toDelete.forEach { f ->
                if (f.delete()) {
                    logger.d("AutoDownloadManager.cleanupOldApks", "Deleted old APK", mapOf("file" to f.name))
                } else {
                    logger.w("AutoDownloadManager.cleanupOldApks", "Could not delete", mapOf("file" to f.name))
                }
            }
        } catch (e: Exception) {
            logger.e("AutoDownloadManager.cleanupOldApks", "Cleanup failed", e)
        }
    }

    /** Map a DownloadManager failure reason to a user-friendly message key. */
    internal fun failureMessage(reason: Int): String = downloadFailureMessage(reason)

    /** Map a DownloadManager paused reason to a user-friendly message key. */
    internal fun pausedMessage(reason: Int): String = downloadPausedMessage(reason)

    /** Register a completion receiver; returns the receiver for later unregister. */
    fun registerCompletionReceiver(
        context: Context,
        downloadId: Long,
        onComplete: () -> Unit,
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == downloadId) {
                    logger.d("AutoDownloadManager.receiver", "Completion broadcast", mapOf("downloadId" to id))
                    onComplete()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        return receiver
    }
}

/** Extract the build number from an APK filename ("Payanam_Android_1568_..." → "1568"). */
internal fun buildNumberFromFileName(fileName: String): String =
    Regex("""_(\d{4,6})_""").find(fileName)?.groupValues?.get(1) ?: "update"

/** Pure mapper (no object init) — unit-testable on plain JVM. */
internal fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_UNKNOWN -> "download_failed"
    DownloadManager.ERROR_FILE_ERROR -> "download_error_file"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "download_error_http"
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "download_error_http_data"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "download_error_redirects"
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "download_error_space"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "download_error_device"
    DownloadManager.ERROR_CANNOT_RESUME -> "download_error_resume"
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "download_error_exists"
    else -> "download_failed"
}

/** Pure mapper (no object init) — unit-testable on plain JVM. */
internal fun downloadPausedMessage(reason: Int): String = when (reason) {
    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "download_paused_wifi"
    DownloadManager.PAUSED_WAITING_TO_RETRY -> "download_paused_retry"
    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "download_paused_wifi"
    else -> "download_paused"
}
