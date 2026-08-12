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

/** States surfaced to the Settings UI for the auto-download flow. */
sealed class DownloadUiState {
    data object Idle : DownloadUiState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : DownloadUiState() {
        val progressPercent: Int
            get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
    }
    data class Downloaded(val fileName: String) : DownloadUiState()
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
     * Returns the download ID, or null if enqueue failed.
     */
    fun enqueue(
        context: Context,
        url: String,
        fileName: String,
    ): Long? {
        return try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Payanam $fileName")
                .setDescription("Downloading update APK")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "$SUBDIR/$fileName")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            val id = manager.enqueue(request)
            logger.d("AutoDownloadManager.enqueue", "Download enqueued", mapOf("downloadId" to id, "file" to fileName))
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
                        DownloadUiState.Downloaded(uri ?: "unknown")
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        logger.w("AutoDownloadManager.queryProgress", "Download failed", mapOf("reason" to reason))
                        DownloadUiState.Failed("download_failed_$reason")
                    }
                    else -> DownloadUiState.Downloading(bytesDownloaded = bytes, totalBytes = total)
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
