//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.AutoDownloadManager
import io.payanam.presentation.download.DownloadUiState
import io.payanam.presentation.updates.ApkDownloader
import io.payanam.presentation.updates.ApkInstaller
import io.payanam.presentation.updates.InstallSourceReader
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val F_DROID_INSTALLER = "org.fdroid.fdroid"

/** Android [InstallSourceReader] backed by the removed [InstallerChecker] logic. */
@Singleton
class AndroidInstallSourceReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstallSourceReader {
    override fun isFDroidBuild(): Boolean = try {
        context.packageManager.getInstallerPackageName(context.packageName) == F_DROID_INSTALLER
    } catch (e: PackageManager.NameNotFoundException) {
        UnifiedLogger.getInstance().e("InstallerChecker", "Failed to determine installer package", e)
        false
    }
}

/** Android [ApkDownloader] backed by the removed [AutoDownloadManager] logic. */
@Singleton
class AndroidApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApkDownloader {
    override fun enqueue(url: String, fileName: String, wifiOnly: Boolean): String? =
        AutoDownloadManager.enqueue(context, url, fileName, wifiOnly)?.toString()

    override fun queryProgress(downloadId: String): DownloadUiState {
        val id = downloadId.toLongOrNull() ?: return DownloadUiState.Failed("download_not_found")
        return AutoDownloadManager.queryProgress(context, id)
    }
}

/** Android [ApkInstaller] backed by the removed inline FileProvider + ACTION_VIEW logic. */
@Singleton
class AndroidApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApkInstaller {
    @Suppress("TooGenericExceptionCaught") // Intentional: FileProvider + startActivity system installer handoff
    override fun install(apkPath: String): Boolean {
        val logger = UnifiedLogger.getInstance()
        return try {
            val file = File(apkPath)
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
