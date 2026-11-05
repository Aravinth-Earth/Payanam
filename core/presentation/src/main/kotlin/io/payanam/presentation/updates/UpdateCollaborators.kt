//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import io.payanam.presentation.download.DownloadUiState

/**
 * Verifies a downloaded APK and launches the system installer for it.
 *
 * Implementations check the file against the digest its release published, resolve
 * the FileProvider URI, create the ACTION_VIEW intent, and hand off to the platform
 * installer. Returns true only when the file verified AND the intent was launched.
 */
interface ApkInstaller {
    /**
     * Verify the APK at [apkPath] against the digest published at [sha256Url], then launch the system
     * installer for it.
     *
     * Suspend because the check fetches the release's small `.sha256` sidecar and hashes the file -
     * both real IO, never work for the caller's thread.
     *
     * [sha256Url] is a required parameter rather than something an implementation looks up: an
     * installer cannot discover which release a file on disk came from, and a default value would let
     * a caller silently skip the check. A caller that has no sidecar URL passes `null`, which fails
     * closed like any other verdict that is not a match.
     *
     * Returns `true` only when the digest matched and the intent was launched; `false` when the file
     * did not verify, the installer could not be resolved, or the intent could not be created. A file
     * that fails verification is never handed to the installer.
     */
    suspend fun install(
        apkPath: String,
        sha256Url: String?,
    ): Boolean
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
 * Reports whether the running build is the F-Droid channel build, rather than reading the install source.
 *
 * The install source cannot answer the question reliably: a probe matched only the exact installer
 * package, so builds installed by other F-Droid clients or by adb were misread as self-updatable. The
 * build-time flag answers for every install of that build, whatever installed it.
 */
interface InstallSourceReader {
    /**
     * Reports whether the RUNNING BUILD is the F-Droid channel build.
     *
     * Android answers from its build configuration (the flag stamped in when the APK was built for the
     * F-Droid channel), not from who installed the app. Returns `true` when this build must leave its
     * updates to the F-Droid client, which disables the in-app update hatch.
     *
     * The desktop answers `false`: it has no F-Droid channel and updates itself.
     */
    fun isFDroidBuild(): Boolean
}
