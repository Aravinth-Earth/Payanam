//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.ApkChecksumVerifier
import io.payanam.feature.settings.AutoDownloadManager
import io.payanam.feature.settings.ChecksumVerdict
import io.payanam.feature.settings.FdroidBuildCheck
import io.payanam.feature.settings.logName
import io.payanam.presentation.download.DownloadUiState
import io.payanam.presentation.updates.ApkDownloader
import io.payanam.presentation.updates.ApkInstaller
import io.payanam.presentation.updates.InstallSourceReader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Android [InstallSourceReader]: this build's F-Droid channel fact, delegated to [FdroidBuildCheck]. */
@Singleton
class AndroidInstallSourceReader @Inject constructor() : InstallSourceReader {
    override fun isFDroidBuild(): Boolean = FdroidBuildCheck.isFDroidBuild()
}

/** Android [ApkDownloader] backed by the removed [AutoDownloadManager] logic. */
@Singleton
class AndroidApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApkDownloader {
    override fun enqueue(url: String, fileName: String, wifiOnly: Boolean): String? = AutoDownloadManager.enqueue(context, url, fileName, wifiOnly)?.toString()

    override fun queryProgress(downloadId: String): DownloadUiState {
        val id = downloadId.toLongOrNull() ?: return DownloadUiState.Failed("download_not_found")
        return AutoDownloadManager.queryProgress(context, id)
    }
}

/** Android [ApkInstaller]: the checksum gate, then the FileProvider + ACTION_VIEW handoff. */
@Singleton
class AndroidApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checksumVerifier: ApkChecksumVerifier,
) : ApkInstaller {
    @Suppress("TooGenericExceptionCaught") // Intentional: FileProvider + startActivity system installer handoff
    override suspend fun install(
        apkPath: String,
        sha256Url: String?,
    ): Boolean {
        val logger = UnifiedLogger.getInstance()
        val file = File(apkPath)
        // THE GATE SITS HERE, BEFORE ANY INTENT IS BUILT, so every caller of this installer passes it:
        // there is no install path in this app that can reach the system installer without the file
        // having been hashed against the digest its release published. Only a match proceeds; a
        // mismatch, an absent sidecar and a check that could not complete all refuse. The file is never
        // deleted - it is the user's only copy, and the caller reports why it was not installed.
        val verdict = checksumVerifier.verify(file, sha256Url)
        logger.i(
            "AndroidApkInstaller.install",
            "Install gate verdict",
            mapOf("verdict" to verdict.logName()),
        )
        if (verdict != ChecksumVerdict.Verified) return false
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val resolved: android.content.ComponentName? = intent.resolveActivity(context.packageManager)
            val resolvedInstallerName = resolved?.className ?: "none"
            logger.i(
                "PreUnlockUpdateChecker.install",
                "Install handoff to system installer",
                mapOf(
                    "fileName" to file.name,
                    "resolvedInstaller" to resolvedInstallerName,
                ),
            )
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            logger.e("PreUnlockUpdateChecker.install", "Install launch failed", e)
            false
        }
    }
}
