//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.BuildConfig
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.AutoDownloadManager
import io.payanam.feature.settings.DownloadUiState
import io.payanam.feature.settings.UpdateChannel
import io.payanam.feature.settings.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Pre-unlock update hatch: manual check → download → install for the DB-LOCKED
 * state. Deliberately reads NO database-backed preferences:
 *
 * - channel is hardcoded to DEV (the rescue channel; beta/stable selection is a
 *   post-unlock Settings preference)
 * - no auto-check, no auto-download, no prompt popup — every step is a manual
 *   user tap (design confirmed: pre-unlock = manual only)
 * - reuse: [UpdateChecker] (pure, DB-free), [AutoDownloadManager] (app-private
 *   dir, disk guard, retention), FileProvider install (same as Settings)
 *
 * Traces land under "PreUnlockUpdateChecker.*" so a stuck hatch session is
 * fully diagnosable from one log export.
 */
@HiltViewModel
/**
 * PreUnlockUpdateViewModel.
 */
class PreUnlockUpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()

    private val _downloadState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    /** Download state. */
    val downloadState: StateFlow<DownloadUiState> = _downloadState.asStateFlow()

    private val _checking = MutableStateFlow(false)
    /** Checking. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _checkResultMessage = MutableStateFlow<String?>(null)
    /** Check result message. */
    val checkResultMessage: StateFlow<String?> = _checkResultMessage.asStateFlow()

    /** Latest build number found by the last check (DEV channel). */
    private var latestBuildNumber: Int? = null
    private var latestReleaseUrl: String? = null
    private var latestApkUrl: String? = null
    private var pollJob: Job? = null

    /** Cooldown guard: 60s between manual checks (mirrors Settings rate guards). */
    private var lastCheckTimestampMs = 0L
    private val checkCooldownMs = 60_000L

    /** Current build number. */
    val currentBuildNumber: Int = BuildConfig.VERSION_CODE

    /** User tapped "Check for update" (manual only). */
    fun checkForUpdate() {
        /** Now. */
        val now = System.currentTimeMillis()
        /** If. */
        if (now - lastCheckTimestampMs < checkCooldownMs) {
            logger.d(
                "PreUnlockUpdateChecker.check",
                "Check skipped — cooldown active",
                /** Map of. */
                mapOf("elapsedMs" to (now - lastCheckTimestampMs)),
            )
            /** Return. */
            return
        }
        lastCheckTimestampMs = now
        viewModelScope.launch {
            _checking.value = true
            _checkResultMessage.value = null
            logger.i(
                "PreUnlockUpdateChecker.check",
                "Manual update check requested (pre-unlock hatch)",
                /** Map of. */
                mapOf("currentBuild" to currentBuildNumber, "channel" to UpdateChannel.DEV.name),
            )
            try {
                /** Result. */
                val result = UpdateChecker.check(currentBuildNumber, UpdateChannel.DEV)
                /** If. */
                if (result.error != null) {
                    logger.e(
                        "PreUnlockUpdateChecker.check",
                        "Check failed",
                        /** Null. */
                        null,
                        /** Map of. */
                        mapOf("error" to result.error.name),
                    )
                    _checkResultMessage.value = "check_failed_${result.error.name}"
                } else if (result.isUpdateAvailable) {
                    latestBuildNumber = result.latestBuildNumber
                    latestReleaseUrl = result.releaseUrl
                    /** Selected. */
                    val selected = result.channelStatuses.firstOrNull { it.channel == UpdateChannel.DEV }
                    latestApkUrl = selected?.apkDownloadUrl
                    logger.i(
                        "PreUnlockUpdateChecker.check",
                        "Update available",
                        /** Map of. */
                        mapOf(
                            "latestBuild" to (result.latestBuildNumber ?: -1),
                            "releaseUrl" to (result.releaseUrl ?: ""),
                            "hasApkUrl" to (latestApkUrl != null),
                        ),
                    )
                    _checkResultMessage.value = "update_available_${result.latestBuildNumber}"
                } else {
                    latestBuildNumber = null
                    latestReleaseUrl = null
                    latestApkUrl = null
                    logger.i("PreUnlockUpdateChecker.check", "Up to date on dev channel")
                    _checkResultMessage.value = "up_to_date"
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("PreUnlockUpdateChecker.check", "Check threw", e)
                _checkResultMessage.value = "check_failed_exception"
            } finally {
                _checking.value = false
            }
        }
    }

    /** User tapped "Download & install" (manual only). */
    fun download() {
        /** Url. */
        val url = latestApkUrl ?: run {
            logger.d("PreUnlockUpdateChecker.download", "No download URL available — check first")
            /** Return. */
            return
        }
        /** File name. */
        val fileName = url.substringAfterLast('/')
        viewModelScope.launch {
            _downloadState.value = DownloadUiState.Idle
            logger.i(
                "PreUnlockUpdateChecker.download",
                "Manual download requested",
                /** Map of. */
                mapOf("fileName" to fileName),
            )
            /** Id. */
            val id = AutoDownloadManager.enqueue(context, url, fileName, wifiOnly = false)
            /** If. */
            if (id == null) {
                _downloadState.value = DownloadUiState.Failed("enqueue_failed")
                logger.e("PreUnlockUpdateChecker.download", "Enqueue failed", null)
                return@launch
            }
            pollJob?.cancel()
            pollJob = viewModelScope.launch(Dispatchers.IO) {
                /** While. */
                while (isActive) {
                    /** State. */
                    val state = AutoDownloadManager.queryProgress(context, id)
                    _downloadState.value = state
                    /** If. */
                    if (state is DownloadUiState.Downloaded) {
                        logger.i(
                            "PreUnlockUpdateChecker.download",
                            "Download complete",
                            /** Map of. */
                            mapOf("fileName" to fileName),
                        )
                        /** Break. */
                        break
                    }
                    /** If. */
                    if (state is DownloadUiState.Failed) {
                        logger.e("PreUnlockUpdateChecker.download", "Download failed", null, mapOf("fileName" to fileName))
                        /** Break. */
                        break
                    }
                    /** Delay. */
                    delay(1_000L)
                }
            }
        }
    }

    /** User tapped "Install now" — launch the system installer (manual only). */
    fun install() {
        /** Path. */
        val path = (_downloadState.value as? DownloadUiState.Downloaded)?.localPath ?: run {
            logger.d("PreUnlockUpdateChecker.install", "No downloaded file — nothing to install")
            /** Return. */
            return
        }
        /** File. */
        val file = File(path)
        /** If. */
        if (!file.exists()) {
            _downloadState.value = DownloadUiState.Failed("file_missing")
            logger.e("PreUnlockUpdateChecker.install", "File missing at install time", null, mapOf("path" to path))
            /** Return. */
            return
        }
        try {
            /** Uri. */
            val uri: Uri = FileProvider.getUriForFile(
                /** Context. */
                context,
                "${context.packageName}.fileprovider",
                /** File. */
                file,
            )
            /** Intent. */
            val intent = Intent(Intent.ACTION_VIEW).apply {
                /** Set data and type. */
                setDataAndType(uri, "application/vnd.android.package-archive")
                /** Add flags. */
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                /** Add flags. */
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            /** Resolved. */
            val resolved: android.content.ComponentName? = intent.resolveActivity(context.packageManager)
            /** Resolved installer name. */
            val resolvedInstallerName = resolved?.className ?: "none"
            logger.i(
                "PreUnlockUpdateChecker.install",
                "Install handoff to system installer",
                /** Map of. */
                mapOf(
                    "fileName" to file.name,
                    "resolvedInstaller" to resolvedInstallerName,
                ),
            )
            context.startActivity(intent)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            _downloadState.value = DownloadUiState.Failed("install_launch_failed")
            logger.e("PreUnlockUpdateChecker.install", "Install launch failed", e)
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
