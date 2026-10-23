//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The download states the Settings update flow renders on both platforms.
 *
 * `progressPercent` is the one piece of behaviour here, and it guards a division: the download manager reports
 * `totalBytes = 0` until the server sends a length, so the percentage must be 0 rather than an arithmetic
 * exception or a bogus 100. The rest of the file pins the state set, because the UI branches on which state it
 * holds — a new state added without a branch would render nothing at all.
 */
class DownloadUiStateTest {
    @Test
    fun `progress is zero while the total size is unknown`() {
        val state = DownloadUiState.Downloading(fileName = "Payanam.apk", bytesDownloaded = 4096, totalBytes = 0)

        assertThat(state.progressPercent).isEqualTo(0)
    }

    @Test
    fun `progress is the byte ratio, truncated`() {
        val state = DownloadUiState.Downloading(fileName = "Payanam.apk", bytesDownloaded = 512, totalBytes = 2048)

        assertThat(state.progressPercent).isEqualTo(25)
    }

    @Test
    fun `progress is a raw ratio, so a byte overshoot reports above one hundred`() {
        // The manager can report more bytes than the last known total (a size that grew mid-download). The
        // percentage is deliberately not clamped here; the UI decides how to draw an over-100 figure.
        val state = DownloadUiState.Downloading(fileName = "Payanam.apk", bytesDownloaded = 4096, totalBytes = 2048)

        assertThat(state.progressPercent).isEqualTo(200)
    }

    @Test
    fun `a downloading state carries the channel and build it was enriched with`() {
        val state =
            DownloadUiState.Downloading(
                fileName = "Payanam.apk",
                bytesDownloaded = 1,
                totalBytes = 2,
                channelName = "Dev",
                buildName = "Payanam_Android_1887_debug_20260921_051900.apk",
            )

        assertThat(state.channelName).isEqualTo("Dev")
        assertThat(state.buildName).isEqualTo("Payanam_Android_1887_debug_20260921_051900.apk")
    }

    @Test
    fun `the states are the five the flow branches on`() {
        val states =
            listOf(
                DownloadUiState.Idle,
                DownloadUiState.Downloading("a.apk", 1, 2),
                DownloadUiState.Paused("waiting for Wi-Fi"),
                DownloadUiState.Downloaded("a.apk", "/tmp/a.apk"),
                DownloadUiState.Failed("no space"),
            )

        assertThat(states.map { it::class.simpleName })
            .containsExactly("Idle", "Downloading", "Paused", "Downloaded", "Failed")
            .inOrder()
        assertThat(DownloadUiState.Downloaded("a.apk").localPath).isNull()
    }
}
