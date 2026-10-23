//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "UndocumentedPublicProperty")

package io.payanam.presentation.download

/** States surfaced to the Settings UI for the auto-download flow. */
sealed class DownloadUiState {
    data object Idle : DownloadUiState()

    /**
     * APK download in flight with byte progress and a derived percentage.
     */
    data class Downloading(
        val fileName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        /** Channel this download belongs to (enriched by the ViewModel). */
        val channelName: String = "",
        /** Full APK build name, e.g. "Payanam_Android_1568_debug_20260812_193754.apk" (enriched). */
        val buildName: String = "",
    ) : DownloadUiState() {
        val progressPercent: Int
            get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
    }

    /** Paused by the system (e.g. waiting for Wi-Fi) — not an error. */
    data class Paused(
        val message: String,
    ) : DownloadUiState()

    /**
     * Download finished; [fileName] plus its on-disk path when resolvable.
     */
    data class Downloaded(
        val fileName: String,
        val localPath: String? = null,
    ) : DownloadUiState()

    /**
     * Download failed; [message] is a user-displayable reason key.
     */
    data class Failed(
        val message: String,
    ) : DownloadUiState()
}
