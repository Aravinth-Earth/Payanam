//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import io.payanam.presentation.download.DownloadUiState

/**
 * Launches the system installer for a downloaded APK.
 *
 * Implementations resolve the FileProvider URI, create the ACTION_VIEW intent,
 * and hand off to the platform installer. Returns true when the intent was
 * launched successfully.
 */
interface ApkInstaller {
    /**
     * Launches the system installer for the APK at [apkPath].
     *
     * Implementations resolve a FileProvider URI, create an ACTION_VIEW intent
     * with the correct MIME type, and hand off to the platform installer.
     * Returns `true` when the intent was launched successfully; `false` when
     * the installer could not be resolved or the intent could not be created.
     */
    fun install(apkPath: String): Boolean
}

/**
 * Enqueues an APK download and reports progress.
 *
 * Implementations manage the download lifecycle (enqueue, poll, cancel) and
 * emit [DownloadUiState] updates. The download ID returned by [enqueue] is
 * passed back to [queryProgress] to poll for updates.
 */
interface ApkDownloader {
    /**
     * Enqueues an APK download from [url] saved as [fileName].
     *
     * Implementations manage the download lifecycle (enqueue, poll, cancel)
     * and honour [wifiOnly] to restrict downloads to unmetered networks.
     * Returns a download ID that must be passed back to [queryProgress] to
     * poll for updates; returns `null` when the download could not be enqueued
     * (e.g. network constraint violation, storage error, or duplicate request).
     */
    fun enqueue(
        url: String,
        fileName: String,
        wifiOnly: Boolean,
    ): String?

    /**
     * Polls the download progress for the download identified by [downloadId].
     *
     * Implementations return a [DownloadUiState] reflecting the current state
     * of the download (pending, running, paused, completed, or failed). The
     * caller is expected to poll this method periodically until a terminal
     * state is reached. An unknown [downloadId] yields a failed state.
     */
    fun queryProgress(downloadId: String): DownloadUiState
}

/**
 * Reads the install source to determine whether self-update is allowed.
 *
 * Implementations check whether the app was installed from a source that
 * manages its own updates (e.g. F-Droid), in which case the in-app update
 * hatch must be disabled.
 */
interface InstallSourceReader {
    /**
     * Checks whether the app was installed from F-Droid.
     *
     * Implementations inspect the install source (e.g. via
     * `PackageManager.getInstallSourceInfo`) to determine whether the app
     * was installed by F-Droid. Returns `true` when the install source is
     * F-Droid, indicating the in-app update hatch must be disabled because
     * F-Droid manages its own updates. Returns `false` for all other install
     * sources (Play Store, sideloaded, etc.).
     */
    fun isFDroidBuild(): Boolean
}
