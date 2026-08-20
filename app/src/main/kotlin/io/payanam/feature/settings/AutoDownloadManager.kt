//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

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
    /**
     * Downloading.
     */
    data class Downloading(
        /** File name. */
        val fileName: String,
        /** Bytes downloaded. */
        val bytesDownloaded: Long,
        /** Total bytes. */
        val totalBytes: Long,
        /** Channel this download belongs to (enriched by the ViewModel). */
        val channelName: String = "",
        /** Full APK build name, e.g. "Payanam_Android_1568_20260812_193754.apk" (enriched). */
        val buildName: String = "",
    ) : DownloadUiState() {
        /** Progress percent. */
        val progressPercent: Int
            /** Get. */
            get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
    }
    /** Paused by the system (e.g. waiting for Wi-Fi) — not an error. */
    data class Paused(val message: String) : DownloadUiState()
    /**
     * Downloaded.
     */
    data class Downloaded(val fileName: String, val localPath: String? = null) : DownloadUiState()
    /**
     * Failed.
     */
    data class Failed(val message: String) : DownloadUiState()
}

/**
 * Wraps DownloadManager for Payanam APK downloads into the app-private
 * external files dir (no storage permission needed on any API level).
 * Downloads land in: /sdcard/Android/data/<pkg>/files/downloads/
 */
object AutoDownloadManager {

    internal const val SUBDIR = "downloads"
    private val logger = UnifiedLogger.getInstance()

    /**
     * Enqueue the APK download. [url] is the GitHub release asset URL.
     * [wifiOnly] restricts the download to unmetered networks (system
     * pauses/resumes automatically when network changes).
     * Returns the download ID, or null if enqueue failed.
     */
    fun enqueue(
        /** Context. */
        context: Context,
        /** Url. */
        url: String,
        /** File name. */
        fileName: String,
        wifiOnly: Boolean = false,
    ): Long? {
        /** Log downloads dir state. */
        logDownloadsDirState(context, "enqueue_before")
        return try {
            /** Manager. */
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            /** Request. */
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Payanam #${buildNumberFromFileName(fileName)}")
                .setDescription("Downloading update APK")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, "$SUBDIR/$fileName")
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(!wifiOnly)
            /** Id. */
            val id = manager.enqueue(request)
            logger.d("AutoDownloadManager.enqueue", "Download enqueued", mapOf("downloadId" to id, "file" to fileName, "wifiOnly" to wifiOnly))
            /** Id. */
            id
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("AutoDownloadManager.enqueue", "Enqueue failed", e)
            /** Null. */
            null
        }
    }

    /** Query progress for a download ID; returns null if the row is gone. */
    fun queryProgress(context: Context, downloadId: Long): DownloadUiState {
        /** Manager. */
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        /** Query. */
        val query = DownloadManager.Query().setFilterById(downloadId)
        /** Cursor. */
        var cursor: Cursor? = null
        return try {
            cursor = manager.query(query)
            /** If. */
            if (cursor != null && cursor.moveToFirst()) {
                /** Status. */
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                /** Bytes. */
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                /** Total. */
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                /** When. */
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        /** Uri. */
                        val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        logger.d("AutoDownloadManager.queryProgress", "Download complete", mapOf("uri" to (uri ?: "unknown")))
                        /** Log downloads dir state. */
                        logDownloadsDirState(context, "after_complete")
                        DownloadUiState.Downloaded(
                            fileName = uri?.substringAfterLast('/') ?: "unknown",
                            localPath = uri?.removePrefix("file://"),
                        )
                    }
                    DownloadManager.STATUS_FAILED -> {
                        /** Reason. */
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        logger.w("AutoDownloadManager.queryProgress", "Download failed", mapOf("reason" to reason))
                        DownloadUiState.Failed(failureMessage(reason))
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        /** Reason. */
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
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("AutoDownloadManager.queryProgress", "Query failed", e)
            DownloadUiState.Failed("query_error")
        } finally {
            cursor?.close()
        }
    }

    /** Cancel a download and remove its partial file. */
    fun cancel(context: Context, downloadId: Long) {
        try {
            /** Manager. */
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.remove(downloadId)
            logger.d("AutoDownloadManager.cancel", "Download removed", mapOf("downloadId" to downloadId))
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("AutoDownloadManager.cancel", "Cancel failed", e)
        }
    }

    /**
     * Keep only the [keepCount] newest APKs in the downloads dir, deleting older
     * ones. Skips files that don't exist or are not .apk. Best-effort; failures
     * are logged, not thrown.
     */
    fun cleanupOldApks(context: Context, keepCount: Int = 2) {
        /** Log downloads dir state. */
        logDownloadsDirState(context, "cleanup_before")
        try {
            /** Dir. */
            val dir = context.getExternalFilesDir(null)?.let { File(it, SUBDIR) } ?: return
            /** If. */
            if (!dir.exists()) return
            /** Apks. */
            val apks = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.toList() ?: return
            /** If. */
            if (apks.size <= keepCount) return
            /** To delete. */
            val toDelete = apks.sortedByDescending { it.lastModified() }.drop(keepCount)
            toDelete.forEach { f ->
                /** If. */
                if (f.delete()) {
                    logger.d("AutoDownloadManager.cleanupOldApks", "Deleted old APK", mapOf("file" to f.name))
                } else {
                    logger.w("AutoDownloadManager.cleanupOldApks", "Could not delete", mapOf("file" to f.name))
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("AutoDownloadManager.cleanupOldApks", "Cleanup failed", e)
        }
        /** Log downloads dir state. */
        logDownloadsDirState(context, "cleanup_after")
    }

    /**
     * Trace-only: log the current downloads-dir state (per-file name+size,
     * total bytes, count) under a caller-supplied tag. No behavior change —
     * diagnostics for the re-download/accumulation investigation.
     */
    internal fun logDownloadsDirState(context: Context, tag: String) {
        try {
            /** Dir. */
            val dir = context.getExternalFilesDir(null)?.let { File(it, SUBDIR) } ?: return
            /** Apks. */
            val apks = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.toList() ?: emptyList()
            /** Total bytes. */
            val totalBytes = apks.sumOf { it.length() }
            /** Files. */
            val files = apks.sortedByDescending { it.lastModified() }
                .joinToString(" | ") { "${it.name} (${it.length() / 1024 / 1024}MB)" }
            logger.d(
                "AutoDownloadManager.logDownloadsDirState",
                "Downloads dir state",
                /** Map of. */
                mapOf("tag" to tag, "count" to apks.size, "totalMB" to (totalBytes / 1024 / 1024), "files" to files),
            )
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("AutoDownloadManager.logDownloadsDirState", "Dir state log failed", e)
        }
    }

    /** Map a DownloadManager failure reason to a user-friendly message key. */
    internal fun failureMessage(reason: Int): String = downloadFailureMessage(reason)

    /** Map a DownloadManager paused reason to a user-friendly message key. */
    internal fun pausedMessage(reason: Int): String = downloadPausedMessage(reason)

    /** Register a completion receiver; returns the receiver for later unregister. */
    fun registerCompletionReceiver(
        /** Context. */
        context: Context,
        /** Download id. */
        downloadId: Long,
        onComplete: () -> Unit,
    ): BroadcastReceiver {
        /** Receiver. */
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                /** Id. */
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                /** If. */
                if (id == downloadId) {
                    logger.d("AutoDownloadManager.receiver", "Completion broadcast", mapOf("downloadId" to id))
                    /** On complete. */
                    onComplete()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        return receiver
    }
}

/** Build-number extraction (pure, unit-testable on plain JVM). */
internal fun buildNumberFromFileName(fileName: String): String =
    /** Regex. */
    Regex("""_(\d{4,6})_""").find(fileName)?.groupValues?.get(1).orEmpty()

/**
 * Resolve the absolute path of a previously downloaded APK inside the
 * app-private downloads dir, or null if it's no longer on disk.
 */
internal fun AutoDownloadManager.findDownloadedApk(context: Context, fileName: String): String? {
    /** Dir. */
    val dir = context.getExternalFilesDir(null)?.let { File(it, AutoDownloadManager.SUBDIR) } ?: return null
    /** File. */
    val file = File(dir, fileName)
    return if (file.exists() && file.length() > 0) file.absolutePath else null
}

/**
 * Scan the app-private downloads dir for an already-downloaded APK of the
 * given build number. Returns the absolute path, or null if not present.
 */
internal fun AutoDownloadManager.findApkForBuild(context: Context, buildNumber: String): String? {
    /** Dir. */
    val dir = context.getExternalFilesDir(null)?.let { File(it, AutoDownloadManager.SUBDIR) } ?: return null
    /** Files. */
    val files = dir.listFiles() ?: return null
    return files.firstOrNull { it.isFile && it.name.contains("_${buildNumber}_") && it.length() > 0 }?.absolutePath
}

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
