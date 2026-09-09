//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * parseReleases tests need real org.json (Robolectric provides it);
 * plain JVM stubs org.json with "not mocked" throws.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckerParseTest {

    // ── parseReleases (list endpoint parsing) ─────────────────────────────

    @Test
    fun `parseReleases extracts all channels with build numbers and asset urls`() {
        val body = """
            [
              {"tag_name":"dev-v1562","name":"Dev #1562","html_url":"https://github.com/Aravinth-Earth/Payanam/releases/tag/dev-v1562",
               "assets":[{"name":"Payanam_Android_1562_20260810.apk","browser_download_url":"https://github.com/Aravinth-Earth/Payanam/releases/download/dev-v1562/Payanam_Android_1562_20260810.apk"}]},
              {"tag_name":"beta-v1560","name":"Beta #1560","html_url":"https://github.com/Aravinth-Earth/Payanam/releases/tag/beta-v1560",
               "assets":[{"name":"Payanam_Android_1560_20260805.apk","browser_download_url":"https://github.com/Aravinth-Earth/Payanam/releases/download/beta-v1560/Payanam_Android_1560_20260805.apk"}]},
              {"tag_name":"v1558","name":"Stable #1558","html_url":"https://github.com/Aravinth-Earth/Payanam/releases/tag/v1558",
               "assets":[{"name":"Payanam_Android_1558_20260801.apk","browser_download_url":"https://github.com/Aravinth-Earth/Payanam/releases/download/v1558/Payanam_Android_1558_20260801.apk"}]}
            ]
        """.trimIndent()
        val statuses = parseReleases(body)
        assertEquals(3, statuses.size)
        val dev = statuses.first { it.channel == UpdateChannel.DEV }
        assertEquals(1562, dev.buildNumber)
        assertTrue(dev.apkDownloadUrl?.endsWith(".apk") == true)
        val beta = statuses.first { it.channel == UpdateChannel.BETA }
        assertEquals(1560, beta.buildNumber)
        val stable = statuses.first { it.channel == UpdateChannel.STABLE }
        assertEquals(1558, stable.buildNumber)
    }

    @Test
    fun `parseReleases ignores non-channel tags and missing assets`() {
        val body = """
            [
              {"tag_name":"v1.2.3","name":"Release v1.2.3","html_url":"url","assets":[]},
              {"tag_name":"dev-v1540","name":"Dev #1540","html_url":"url","assets":[]},
              {"tag_name":"latest-nightly","name":"Nightly","html_url":"url","assets":[]}
            ]
        """.trimIndent()
        val statuses = parseReleases(body)
        assertEquals(1, statuses.size)
        assertEquals(UpdateChannel.DEV, statuses[0].channel)
        assertNull(statuses[0].apkDownloadUrl)
    }

    @Test
    fun `parseReleases handles garbage body`() {
        assertEquals(emptyList<ChannelStatus>(), parseReleases("not json at all"))
        assertEquals(emptyList<ChannelStatus>(), parseReleases(""))
        assertEquals(emptyList<ChannelStatus>(), parseReleases("[]"))
    }

    @Test
    fun `parseReleases skips malformed entries`() {
        val body = """
            [
              {"tag_name":"dev-v1562","name":"Dev #1562","html_url":"url"},
              {"tag_name":123},
              "not-an-object"
            ]
        """.trimIndent()
        val statuses = parseReleases(body)
        assertEquals(1, statuses.size)
        assertEquals(UpdateChannel.DEV, statuses[0].channel)
    }

    @Test
    fun `parseReleases picks first apk asset only`() {
        val body = """
            [
              {"tag_name":"dev-v1562","name":"Dev #1562","html_url":"url",
               "assets":[
                 {"name":"checksum.txt","browser_download_url":"url/checksum.txt"},
                 {"name":"Payanam_Android_1562.apk","browser_download_url":"url/Payanam_Android_1562.apk"},
                 {"name":"Payanam_Android_1561.apk","browser_download_url":"url/Payanam_Android_1561.apk"}
               ]}
            ]
        """.trimIndent()
        val statuses = parseReleases(body)
        assertEquals(1, statuses.size)
        assertEquals("url/Payanam_Android_1562.apk", statuses[0].apkDownloadUrl)
    }

    @Test
    fun `parseReleases handles multiple persistent releases`() {
        val body = """
            [
              {"tag_name":"dev-v1704","name":"Dev #1704","html_url":"url-dev",
               "assets":[{"name":"Payanam_Android_1704.apk","browser_download_url":"url-dev.apk"}]},
              {"tag_name":"dev-v1703","name":"Dev #1703","html_url":"url-dev-old",
               "assets":[{"name":"Payanam_Android_1703.apk","browser_download_url":"url-dev-old.apk"}]},
              {"tag_name":"beta-v1703","name":"Beta #1703","html_url":"url-beta",
               "assets":[{"name":"Payanam_Android_1703.apk","browser_download_url":"url-beta.apk"}]}
            ]
        """.trimIndent()
        val statuses = parseReleases(body)
        // Both dev-v1704 and dev-v1703 map to DEV channel → 2 entries
        val devStatuses = statuses.filter { it.channel == UpdateChannel.DEV }
        assertEquals(2, devStatuses.size)
        // First one in the list wins for the selected channel
        assertEquals(1704, devStatuses[0].buildNumber)
        assertEquals(1, statuses.count { it.channel == UpdateChannel.BETA })
    }

    // ── Download failure/paused message mapping ───────────────────────────
    // DownloadManager constants are Android API values; using raw ints here
    // keeps these tests on plain JVM (no Robolectric needed).

    @Test
    fun `failure message maps known reasons`() {
        assertEquals("download_error_space", downloadFailureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        assertEquals("download_error_http", downloadFailureMessage(DownloadManager.ERROR_UNHANDLED_HTTP_CODE))
        assertEquals("download_failed", downloadFailureMessage(DownloadManager.ERROR_UNKNOWN))
        assertEquals("download_failed", downloadFailureMessage(99999))      // unknown
    }

    @Test
    fun `paused message maps wifi and retry reasons`() {
        assertEquals("download_paused_wifi", downloadPausedMessage(DownloadManager.PAUSED_WAITING_FOR_NETWORK))
        assertEquals("download_paused_retry", downloadPausedMessage(DownloadManager.PAUSED_WAITING_TO_RETRY))
        assertEquals("download_paused", downloadPausedMessage(99999))
    }
}
