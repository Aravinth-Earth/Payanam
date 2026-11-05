//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import com.google.common.truth.Truth.assertThat
import io.payanam.presentation.download.DownloadUiState
import org.junit.Test

/**
 * Contract tests for [ApkDownloader].
 *
 * The real implementation manages a download lifecycle via Android's DownloadManager —
 * framework code that cannot run on the JVM. These tests pin the interface contract
 * via a fake: the ViewModel branches on the null-ness of [ApkDownloader.enqueue]'s
 * return and on the terminal-ness of [ApkDownloader.queryProgress]'s state, so the
 * fake must faithfully record calls and replay configured outcomes.
 */
class ApkDownloaderTest {
    /** A fake [ApkDownloader] that records calls and returns configurable results. */
    private class FakeApkDownloader(
        private val enqueueResult: String?,
        private val progressResult: DownloadUiState,
    ) : ApkDownloader {
        val enqueueCalls = mutableListOf<Triple<String, String, Boolean>>()
        val progressCalls = mutableListOf<String>()

        override fun enqueue(
            url: String,
            fileName: String,
            wifiOnly: Boolean,
        ): String? {
            enqueueCalls.add(Triple(url, fileName, wifiOnly))
            return enqueueResult
        }

        override fun queryProgress(downloadId: String): DownloadUiState {
            progressCalls.add(downloadId)
            return progressResult
        }
    }

    @Test
    fun `enqueue returns the download id when the download is enqueued`() {
        val fake = FakeApkDownloader(enqueueResult = "dl-1", progressResult = DownloadUiState.Idle)
        assertThat(fake.enqueue("https://d/apk", "payanam.apk", false)).isEqualTo("dl-1")
    }

    @Test
    fun `enqueue returns null when the download cannot be enqueued`() {
        val fake = FakeApkDownloader(enqueueResult = null, progressResult = DownloadUiState.Idle)
        assertThat(fake.enqueue("https://d/apk", "payanam.apk", true)).isNull()
    }

    @Test
    fun `enqueue records url fileName and wifiOnly`() {
        val fake = FakeApkDownloader(enqueueResult = "dl-1", progressResult = DownloadUiState.Idle)
        fake.enqueue("https://d/apk", "payanam.apk", true)
        assertThat(fake.enqueueCalls).containsExactly(Triple("https://d/apk", "payanam.apk", true))
    }

    @Test
    fun `queryProgress returns the configured state`() {
        val fake =
            FakeApkDownloader(
                enqueueResult = "dl-1",
                progressResult = DownloadUiState.Downloaded("payanam.apk", "/data/app/payanam.apk"),
            )
        assertThat(fake.queryProgress("dl-1"))
            .isEqualTo(DownloadUiState.Downloaded("payanam.apk", "/data/app/payanam.apk"))
    }

    @Test
    fun `queryProgress records the downloadId it was called with`() {
        val fake = FakeApkDownloader(enqueueResult = "dl-1", progressResult = DownloadUiState.Idle)
        fake.queryProgress("dl-1")
        fake.queryProgress("dl-2")
        assertThat(fake.progressCalls).containsExactly("dl-1", "dl-2").inOrder()
    }

    @Test
    fun `queryProgress returns a failed state for an unknown download id`() {
        val fake = FakeApkDownloader(enqueueResult = "dl-1", progressResult = DownloadUiState.Failed("unknown"))
        assertThat(fake.queryProgress("unknown-id")).isEqualTo(DownloadUiState.Failed("unknown"))
    }
}
