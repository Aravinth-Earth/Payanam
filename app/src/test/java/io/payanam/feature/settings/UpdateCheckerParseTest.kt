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
/**
 * UpdateCheckerParseTest.
 */
class UpdateCheckerParseTest {

    // ── parseReleases (list endpoint parsing) ─────────────────────────────

    @Test
    fun `parseReleases extracts all channels with build numbers and asset urls`() {
        /** Body. */
        val body = """
            [
              {"tag_name":"latest-dev","name":"Latest Dev Build (#1562)","html_url":"https://github.com/Aravinth-Earth/Payanam/releases/tag/latest-dev",
               "assets":[{"name":"Payanam_Android_1562_20260810.apk","browser_download_url":"https://github.com/Aravinth-Earth/Payanam/releases/download/latest-dev/Payanam_Android_1562_20260810.apk"}]},
              {"tag_name":"latest-beta","name":"Latest Beta Build (#1560)","html_url":"https://github.com/Aravinth-Earth/Payanam/releases/tag/latest-beta",
               "assets":[{"name":"Payanam_Android_1560_20260805.apk","browser_download_url":"https://github.com/Aravinth-Earth/Payanam/releases/download/latest-beta/Payanam_Android_1560_20260805.apk"}]},
              {"tag_name":"latest-stable","name":"Latest Stable Build (#1558)","html_url":"https://github.com/Aravinth-Earth/Payanam/releases/tag/latest-stable",
               "assets":[{"name":"Payanam_Android_1558_20260801.apk","browser_download_url":"https://github.com/Aravinth-Earth/Payanam/releases/download/latest-stable/Payanam_Android_1558_20260801.apk"}]}
            ]
        """.trimIndent()
        /** Statuses. */
        val statuses = parseReleases(body)
        /** Assert equals. */
        assertEquals(3, statuses.size)
        /** Dev. */
        val dev = statuses.first { it.channel == UpdateChannel.DEV }
        /** Assert equals. */
        assertEquals(1562, dev.buildNumber)
        /** Assert true. */
        assertTrue(dev.apkDownloadUrl?.endsWith(".apk") == true)
        /** Beta. */
        val beta = statuses.first { it.channel == UpdateChannel.BETA }
        /** Assert equals. */
        assertEquals(1560, beta.buildNumber)
        /** Stable. */
        val stable = statuses.first { it.channel == UpdateChannel.STABLE }
        /** Assert equals. */
        assertEquals(1558, stable.buildNumber)
    }

    @Test
    fun `parseReleases ignores non-channel tags and missing assets`() {
        /** Body. */
        val body = """
            [
              {"tag_name":"v1.2.3","name":"Release v1.2.3","html_url":"url","assets":[]},
              {"tag_name":"latest-dev","name":"Latest Dev Build (#1540)","html_url":"url","assets":[]},
              {"tag_name":"latest-nightly","name":"Nightly","html_url":"url","assets":[]}
            ]
        """.trimIndent()
        /** Statuses. */
        val statuses = parseReleases(body)
        /** Assert equals. */
        assertEquals(1, statuses.size)
        /** Assert equals. */
        assertEquals(UpdateChannel.DEV, statuses[0].channel)
        /** Assert null. */
        assertNull(statuses[0].apkDownloadUrl)
    }

    @Test
    fun `parseReleases handles garbage body`() {
        /** Assert equals. */
        assertEquals(emptyList<ChannelStatus>(), parseReleases("not json at all"))
        /** Assert equals. */
        assertEquals(emptyList<ChannelStatus>(), parseReleases(""))
        /** Assert equals. */
        assertEquals(emptyList<ChannelStatus>(), parseReleases("[]"))
    }

    @Test
    fun `parseReleases skips malformed entries`() {
        /** Body. */
        val body = """
            [
              {"tag_name":"latest-dev","name":"Latest Dev Build (#1562)","html_url":"url"},
              {"tag_name":123},
              "not-an-object"
            ]
        """.trimIndent()
        /** Statuses. */
        val statuses = parseReleases(body)
        /** Assert equals. */
        assertEquals(1, statuses.size)
        /** Assert equals. */
        assertEquals(UpdateChannel.DEV, statuses[0].channel)
    }

    @Test
    fun `parseReleases picks first apk asset only`() {
        /** Body. */
        val body = """
            [
              {"tag_name":"latest-dev","name":"Latest Dev Build (#1562)","html_url":"url",
               "assets":[
                 {"name":"checksum.txt","browser_download_url":"url/checksum.txt"},
                 {"name":"Payanam_Android_1562.apk","browser_download_url":"url/Payanam_Android_1562.apk"},
                 {"name":"Payanam_Android_1561.apk","browser_download_url":"url/Payanam_Android_1561.apk"}
               ]}
            ]
        """.trimIndent()
        /** Statuses. */
        val statuses = parseReleases(body)
        /** Assert equals. */
        assertEquals(1, statuses.size)
        /** Assert equals. */
        assertEquals("url/Payanam_Android_1562.apk", statuses[0].apkDownloadUrl)
    }

    // ── Download failure/paused message mapping ───────────────────────────
    // DownloadManager constants are Android API values; using raw ints here
    // keeps these tests on plain JVM (no Robolectric needed).

    @Test
    fun `failure message maps known reasons`() {
        /** Assert equals. */
        assertEquals("download_error_space", downloadFailureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        /** Assert equals. */
        assertEquals("download_error_http", downloadFailureMessage(DownloadManager.ERROR_UNHANDLED_HTTP_CODE))
        /** Assert equals. */
        assertEquals("download_failed", downloadFailureMessage(DownloadManager.ERROR_UNKNOWN))
        /** Assert equals. */
        assertEquals("download_failed", downloadFailureMessage(99999))      // unknown
    }

    @Test
    fun `paused message maps wifi and retry reasons`() {
        /** Assert equals. */
        assertEquals("download_paused_wifi", downloadPausedMessage(DownloadManager.PAUSED_WAITING_FOR_NETWORK))
        /** Assert equals. */
        assertEquals("download_paused_retry", downloadPausedMessage(DownloadManager.PAUSED_WAITING_TO_RETRY))
        /** Assert equals. */
        assertEquals("download_paused", downloadPausedMessage(99999))
    }
}
