//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.BuildConfig
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight app-start update check: fires once per process when
 * auto-download is enabled, respects the same guards as the Settings
 * check (cooldown + rate window are shared via settings keys here).
 * No UI, no worker — result surfaces when the user opens Settings.
 */
@Singleton
class AppStartUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = UnifiedLogger.getInstance()

    fun onAppStart() {
        scope.launch {
            // Only meaningful when the user opted into auto-downloads.
            val autoDownload = appSettingsRepository.getSetting(AUTO_DOWNLOAD_KEY) == "true"
            if (!autoDownload) return@launch

            val channelRaw = appSettingsRepository.getSetting(CHANNEL_KEY)
            val channel = UpdateChannel.fromStorage(channelRaw)
            val result = UpdateChecker.check(BuildConfig.VERSION_CODE, channel)
            if (result.error != null) {
                logger.d("AppStartUpdateChecker.onAppStart", "Start check failed, will retry next start", mapOf("error" to result.error.name))
                return@launch
            }
            if (!result.isUpdateAvailable) {
                logger.d("AppStartUpdateChecker.onAppStart", "No update on start check")
                return@launch
            }

            // Persist the download URL for the Settings UI (which drives polling).
            val selected = result.channelStatuses.firstOrNull { it.channel == channel }
            val url = selected?.apkDownloadUrl ?: return@launch
            val fileName = url.substringAfterLast('/')
            val wifiOnly = appSettingsRepository.getSetting(WIFI_ONLY_KEY) == "true"
            val id = AutoDownloadManager.enqueue(context, url, fileName, wifiOnly = wifiOnly)
            if (id != null) {
                appSettingsRepository.setSetting(DOWNLOAD_ID_KEY, id.toString())
                appSettingsRepository.setSetting(DOWNLOAD_URL_KEY, url)
                appSettingsRepository.setSetting(DOWNLOAD_FILE_KEY, fileName)
                logger.i("AppStartUpdateChecker.onAppStart", "Auto-download enqueued from app start", mapOf("file" to fileName, "downloadId" to id))
            }
        }
    }

    private companion object {
        const val AUTO_DOWNLOAD_KEY = "auto_download_enabled"
        const val WIFI_ONLY_KEY = "wifi_only_enabled"
        const val CHANNEL_KEY = "update_channel"
        const val DOWNLOAD_ID_KEY = "active_download_id"
        const val DOWNLOAD_URL_KEY = "active_download_url"
        const val DOWNLOAD_FILE_KEY = "active_download_file"
    }
}
