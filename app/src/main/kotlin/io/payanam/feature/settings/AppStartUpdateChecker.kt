//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.feature.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.BuildConfig
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.presentation.updates.UpdateChecker
import io.payanam.presentation.updates.UpdatePrefKeys
import io.payanam.shared.updates.UpdateChannel
import io.payanam.shared.updates.UpdateCheckError
import io.payanam.shared.updates.UpdateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-unlock update check: fires ONCE when the encrypted DB session
 * opens (user entered passphrase), never at Application.onCreate —
 * the prefs live in the locked DB and must not be touched before unlock.
 *
 * Uses the SAME preference pipeline as the Settings UI
 * (AppSettingsRepository + shared UpdatePrefKeys) — no duplication.
 */
@Singleton
class AppStartUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettingsRepository: AppSettingsRepository,
    private val sessionManager: DatabaseSessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger = UnifiedLogger.getInstance()

    /** The F-Droid channel build is updated by the F-Droid client — the in-app updater is disabled. */
    private val isFDroidBuild: Boolean = FdroidBuildCheck.isFDroidBuild()

    /**
     * Checks for app updates after the DB session unlocks. Safety net: catches
     * all exceptions so a failed check never crashes the app.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: app-level defensive catch
    fun onAppStart() {
        scope.launch {
            try {
                // F-Droid owns updates for its installs; the in-app updater must
                // not run (same gate as the Settings update UI).
                if (isFDroidBuild) {
                    logger.i("AppStartUpdateChecker.onAppStart", "F-Droid channel build; in-app update check disabled")
                    return@launch
                }
                // Wait for the DB to unlock before touching any preference.
                // The DB is passphrase-locked at app start; reading settings
                // earlier crashes the app (requireDatabase on closed session).
                val isOpen = sessionManager.isOpen.first { it }
                if (!isOpen) return@launch

                // Only run when the user opted into the post-unlock auto check.
                val autoCheck = appSettingsRepository.getSetting(UpdatePrefKeys.AUTO_CHECK) == "true"
                if (!autoCheck) {
                    logger.d("AppStartUpdateChecker.onAppStart", "Auto-check disabled, skipping start check")
                    return@launch
                }
                val channelRaw = appSettingsRepository.getSetting(UpdatePrefKeys.UPDATE_CHANNEL)
                val channel = UpdateChannel.fromStorage(channelRaw)
                val result = UpdateChecker.check(BuildConfig.VERSION_CODE, channel, ApkBuildType.running())
                when (result.outcome) {
                    UpdateOutcome.UPDATE_AVAILABLE -> Unit

                    // handled below
                    UpdateOutcome.FAILED -> {
                        logger.d("AppStartUpdateChecker.onAppStart", "Start check failed, will retry next start", mapOf("error" to (result.error?.name ?: UpdateCheckError.UNKNOWN.name)))
                        return@launch
                    }

                    UpdateOutcome.TYPE_MISMATCH -> {
                        // Fail-closed mismatch: the channel's newest release has
                        // no APK for this install's build type. Logged at `i`
                        // (release builds turn `d` off) so a skipped auto-update
                        // is diagnosable.
                        logger.i(
                            "AppStartUpdateChecker.onAppStart",
                            "Start check failed closed: channel ships a different build type",
                            mapOf("channel" to channel.name, "runningType" to ApkBuildType.running()),
                        )
                        return@launch
                    }

                    UpdateOutcome.NO_RELEASE_ON_CHANNEL, UpdateOutcome.RELEASE_UNREADABLE, UpdateOutcome.INDETERMINATE -> {
                        logger.d("AppStartUpdateChecker.onAppStart", "No comparable release on start check", mapOf("outcome" to result.outcome.name))
                        return@launch
                    }

                    UpdateOutcome.UP_TO_DATE -> {
                        logger.d("AppStartUpdateChecker.onAppStart", "No update on start check")
                        return@launch
                    }
                }

                // Check found an update. Enqueue only when auto-download is ON;
                // otherwise just trace — the Settings UI surfaces the result.
                val autoDownload = appSettingsRepository.getSetting(UpdatePrefKeys.AUTO_DOWNLOAD) == "true"
                if (!autoDownload) {
                    logger.d("AppStartUpdateChecker.onAppStart", "Update available on start check, auto-download off", mapOf("latestBuild" to (result.latestBuildNumber ?: -1)))
                    return@launch
                }

                // Persist the download URL for the Settings UI (which drives polling).
                val selected = result.channelStatuses.firstOrNull { it.channel == channel }
                val url = selected?.apkDownloadUrl ?: return@launch
                val fileName = url.substringAfterLast('/')
                val wifiOnly = appSettingsRepository.getSetting(UpdatePrefKeys.WIFI_ONLY) == "true"
                // Trace-only diagnostics: what does the start path actually see
                // before enqueueing (disk state, markers, target presence)?
                AutoDownloadManager.logDownloadsDirState(context, "start_check_pre_enqueue")
                val targetOnDisk = AutoDownloadManager.findApkForBuild(
                    context,
                    (result.latestBuildNumber ?: -1).toString(),
                )
                val lastBuild = appSettingsRepository.getSetting(UpdatePrefKeys.LAST_DOWNLOADED_BUILD)
                val lastAtMs = appSettingsRepository.getSetting(UpdatePrefKeys.LAST_DOWNLOADED_AT)
                logger.d(
                    "AppStartUpdateChecker.onAppStart",
                    "Pre-enqueue probe",
                    mapOf(
                        "latestBuild" to (result.latestBuildNumber ?: -1),
                        "targetOnDisk" to (targetOnDisk != null),
                        "targetPath" to (targetOnDisk ?: "none"),
                        "lastDownloadedBuild" to (lastBuild ?: "none"),
                        "lastDownloadedAtMs" to (lastAtMs ?: "none"),
                    ),
                )
                val id = AutoDownloadManager.enqueue(context, url, fileName, wifiOnly = wifiOnly)
                if (id != null) {
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_ID, id.toString())
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_URL, url)
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_FILE, fileName)
                    // Persist the checksum sidecar URL too: the Settings install gate verifies the
                    // download against it, and this path owns no in-memory check result to fall back on.
                    appSettingsRepository.setSetting(UpdatePrefKeys.ACTIVE_DOWNLOAD_SHA256_URL, selected?.apkSha256Url)
                    logger.i("AppStartUpdateChecker.onAppStart", "Auto-download enqueued after unlock", mapOf("file" to fileName, "downloadId" to id))
                }
            } catch (e: Exception) {
                // Safety net: never let the startup check crash the app.
                logger.e("AppStartUpdateChecker.onAppStart", "Start check failed safely", e)
            }
        }
    }
}
