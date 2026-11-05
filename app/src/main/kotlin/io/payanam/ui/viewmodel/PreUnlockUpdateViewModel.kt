//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.BuildConfig
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.ApkBuildType
import io.payanam.feature.settings.ApkChecksumVerifier
import io.payanam.feature.settings.installBlockKey
import io.payanam.feature.settings.logName
import io.payanam.presentation.download.DownloadUiState
import io.payanam.presentation.updates.ApkDownloader
import io.payanam.presentation.updates.ApkInstaller
import io.payanam.presentation.updates.InstallSourceReader
import io.payanam.presentation.updates.UpdateChecker
import io.payanam.shared.updates.UpdateCheckError
import io.payanam.shared.updates.UpdateOutcome
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
 * - no stored preference is read at all: the newest release is either newer than
 *   this install or it is not
 * - no auto-check, no auto-download, no prompt popup — every step is a manual
 *   user tap (design confirmed: pre-unlock = manual only)
 * - reuse: [UpdateChecker] (pure, DB-free), [ApkDownloader] (app-private
 *   dir, disk guard, retention), [ApkInstaller] (FileProvider handoff, same as Settings)
 *
 * Traces land under "PreUnlockUpdateChecker.*" so a stuck hatch session is
 * fully diagnosable from one log export.
 */
@HiltViewModel
class PreUnlockUpdateViewModel @Inject constructor(
    private val installSourceReader: InstallSourceReader,
    private val apkDownloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    private val checksumVerifier: ApkChecksumVerifier,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()

    /** F-Droid installs are updated by the F-Droid client — the hatch must not offer self-updates. */
    val isFDroidBuild: Boolean = installSourceReader.isFDroidBuild()

    private val _downloadState = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val downloadState: StateFlow<DownloadUiState> = _downloadState.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _checkResultMessage = MutableStateFlow<String?>(null)
    val checkResultMessage: StateFlow<String?> = _checkResultMessage.asStateFlow()

    private var latestReleaseUrl: String? = null
    private var latestApkUrl: String? = null

    /**
     * The published `.sha256` sidecar URL for [latestApkUrl], or null when the release published none.
     *
     * Held in memory rather than persisted: this hatch runs BEFORE the database is unlocked, so it
     * reads and writes no database-backed preference (see the class doc). That is not a weaker guard —
     * a persisted download URL is not read back here either, so the install gate and the download it
     * guards always belong to the same check.
     */
    private var latestSha256Url: String? = null
    private var pollJob: Job? = null

    /** Cooldown guard: 60s between manual checks (mirrors Settings rate guards). */
    private var lastCheckTimestampMs = 0L
    private val checkCooldownMs = 60_000L
    val currentBuildNumber: Int = BuildConfig.VERSION_CODE

    /** User tapped "Check for update" (manual only). */
    @Suppress("TooGenericExceptionCaught") // Intentional: UpdateChecker.check network + JSON parsing
    fun checkForUpdate() {
        if (isFDroidBuild) {
            logger.i("PreUnlockUpdateChecker.check", "F-Droid install; in-app update hatch disabled")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastCheckTimestampMs < checkCooldownMs) {
            logger.d(
                "PreUnlockUpdateChecker.check",
                "Check skipped — cooldown active",
                mapOf("elapsedMs" to (now - lastCheckTimestampMs)),
            )
            return
        }
        lastCheckTimestampMs = now
        viewModelScope.launch {
            _checking.value = true
            _checkResultMessage.value = null
            logger.i(
                "PreUnlockUpdateChecker.check",
                "Manual update check requested (pre-unlock hatch)",
                mapOf("currentBuild" to currentBuildNumber),
            )
            try {
                val result = UpdateChecker.check(currentBuildNumber, ApkBuildType.running())
                when (result.outcome) {
                    UpdateOutcome.FAILED -> {
                        logger.e(
                            "PreUnlockUpdateChecker.check",
                            "Check failed",
                            null,
                            mapOf("error" to (result.error?.name ?: UpdateCheckError.UNKNOWN.name)),
                        )
                        _checkResultMessage.value = "check_failed_${result.error?.name ?: UpdateCheckError.UNKNOWN.name}"
                    }

                    UpdateOutcome.UPDATE_AVAILABLE -> {
                        latestReleaseUrl = result.releaseUrl
                        latestApkUrl = result.apkDownloadUrl
                        // The sidecar URL travels with the APK URL it belongs to: the install gate can
                        // only verify this download against the digest this check published.
                        latestSha256Url = result.apkSha256Url
                        logger.i(
                            "PreUnlockUpdateChecker.check",
                            "Update available",
                            mapOf(
                                "latestVersion" to (result.latestVersionName ?: "none"),
                                "releaseUrl" to (result.releaseUrl ?: ""),
                                "hasApkUrl" to (latestApkUrl != null),
                                "hasSha256Url" to (latestSha256Url != null),
                            ),
                        )
                        _checkResultMessage.value = "update_available_${result.latestVersionName ?: "unknown"}"
                    }

                    UpdateOutcome.TYPE_MISMATCH -> {
                        // Fail closed: the newest release ships no APK for this
                        // install's build type. The hatch must name the actual
                        // reason — never fall through to "up to date".
                        latestReleaseUrl = null
                        latestApkUrl = null
                        latestSha256Url = null
                        logger.i(
                            "PreUnlockUpdateChecker.check",
                            "Check failed closed: the release ships a different build type",
                            mapOf("runningType" to ApkBuildType.running()),
                        )
                        _checkResultMessage.value = "type_mismatch"
                    }

                    UpdateOutcome.NO_RELEASE -> {
                        latestReleaseUrl = null
                        latestApkUrl = null
                        latestSha256Url = null
                        logger.i("PreUnlockUpdateChecker.check", "Check complete: no release with a version tag yet")
                        _checkResultMessage.value = "no_release"
                    }

                    UpdateOutcome.INDETERMINATE -> {
                        latestReleaseUrl = null
                        latestApkUrl = null
                        latestSha256Url = null
                        logger.i(
                            "PreUnlockUpdateChecker.check",
                            "Check inconclusive — the scan stopped before the release list ended",
                            mapOf("outcome" to result.outcome.name),
                        )
                        _checkResultMessage.value = "check_failed_parse"
                    }

                    UpdateOutcome.UP_TO_DATE -> {
                        latestReleaseUrl = null
                        latestApkUrl = null
                        latestSha256Url = null
                        logger.i("PreUnlockUpdateChecker.check", "Up to date")
                        _checkResultMessage.value = "up_to_date"
                    }
                }
            } catch (e: Exception) {
                logger.e("PreUnlockUpdateChecker.check", "Check threw", e)
                _checkResultMessage.value = "check_failed_exception"
            } finally {
                _checking.value = false
            }
        }
    }

    /** User tapped "Download & install" (manual only). */
    fun download() {
        if (isFDroidBuild) {
            logger.i("PreUnlockUpdateChecker.download", "F-Droid install; in-app update hatch disabled")
            return
        }
        val url = latestApkUrl ?: run {
            logger.d("PreUnlockUpdateChecker.download", "No download URL available — check first")
            return
        }
        val fileName = url.substringAfterLast('/')
        viewModelScope.launch {
            _downloadState.value = DownloadUiState.Idle
            logger.i(
                "PreUnlockUpdateChecker.download",
                "Manual download requested",
                mapOf("fileName" to fileName),
            )
            val id = apkDownloader.enqueue(url, fileName, wifiOnly = false)
            if (id == null) {
                _downloadState.value = DownloadUiState.Failed("enqueue_failed")
                logger.e("PreUnlockUpdateChecker.download", "Enqueue failed", null)
                return@launch
            }
            pollJob?.cancel()
            pollJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val state = apkDownloader.queryProgress(id)
                    _downloadState.value = state
                    if (state is DownloadUiState.Downloaded) {
                        logger.i(
                            "PreUnlockUpdateChecker.download",
                            "Download complete",
                            mapOf("fileName" to fileName),
                        )
                        break
                    }
                    if (state is DownloadUiState.Failed) {
                        logger.e("PreUnlockUpdateChecker.download", "Download failed", null, mapOf("fileName" to fileName))
                        break
                    }
                    delay(1_000L)
                }
            }
        }
    }

    /**
     * User tapped "Install now" — verify the downloaded APK, then launch the system installer (manual only).
     *
     * Fail-closed: only a verified download may reach the installer. Every other verdict leaves the
     * downloaded file in place and reports its own reason key through [downloadState], which is the same
     * mechanism this hatch already reports every other download failure through. The installer collaborator
     * repeats the check at its own seam, so a file swapped between this check and the handoff is still
     * refused there.
     */
    fun install() {
        val path = (_downloadState.value as? DownloadUiState.Downloaded)?.localPath ?: run {
            logger.d("PreUnlockUpdateChecker.install", "No downloaded file — nothing to install")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            _downloadState.value = DownloadUiState.Failed("file_missing")
            logger.e("PreUnlockUpdateChecker.install", "File missing at install time", null, mapOf("path" to path))
            return
        }
        viewModelScope.launch {
            val verdict = checksumVerifier.verify(file, latestSha256Url)
            logger.i(
                "PreUnlockUpdateChecker.install",
                "Install gate verdict",
                mapOf("verdict" to verdict.logName()),
            )
            val blockKey = verdict.installBlockKey()
            if (blockKey != null) {
                // The APK is the user's only copy, so it is left untouched: the reason key explains
                // why it is not being installed.
                _downloadState.value = DownloadUiState.Failed(blockKey)
                return@launch
            }
            launchInstaller(path)
        }
    }

    /** Hand [path] to the platform installer, which verifies the digest again before its own handoff. */
    @Suppress("TooGenericExceptionCaught") // Intentional: installer handoff may throw
    private suspend fun launchInstaller(path: String) {
        try {
            val success = apkInstaller.install(path, latestSha256Url)
            if (!success) {
                _downloadState.value = DownloadUiState.Failed("install_launch_failed")
                logger.e("PreUnlockUpdateChecker.install", "Install launch failed", null)
            }
        } catch (e: Exception) {
            _downloadState.value = DownloadUiState.Failed("install_launch_failed")
            logger.e("PreUnlockUpdateChecker.install", "Install launch failed", e)
        }
    }

    /**
     * Cancels the download progress polling when the ViewModel dies.
     */
    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
