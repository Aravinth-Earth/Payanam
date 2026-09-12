//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * parseReleases tests need real org.json (Robolectric provides it);
 * plain JVM stubs org.json with "not mocked" throws.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckerParseTest {

    @Before
    fun initializeLogger() {
        // The pure selection path's fail-closed trace is guarded, but
        // AutoDownloadManager holds an eager UnifiedLogger field — initialize
        // once before any test touches it (Robolectric supplies the Context).
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
    }

    // ── parseReleases: type-aware asset selection ─────────────────────────

    @Test
    fun `parseReleases selects the running type's apk and its sha256 sibling`() {
        val body = """
            [
              {"tag_name":"dev-v1562","name":"Dev #1562","html_url":"https://example.test/dev-v1562",
               "assets":[
                 {"name":"Payanam_Android_1562_debug_20260810_120000.apk","browser_download_url":"https://example.test/dev-v1562/Payanam_Android_1562_debug_20260810_120000.apk"},
                 {"name":"Payanam_Android_1562_debug_20260810_120000.apk.sha256","browser_download_url":"https://example.test/dev-v1562/Payanam_Android_1562_debug_20260810_120000.apk.sha256"}
               ]},
              {"tag_name":"beta-v1560","name":"Beta #1560","html_url":"https://example.test/beta-v1560",
               "assets":[{"name":"Payanam_Android_1560_release_20260805_090000.apk","browser_download_url":"https://example.test/beta-v1560/Payanam_Android_1560_release_20260805_090000.apk"}]},
              {"tag_name":"v1558","name":"Stable #1558","html_url":"https://example.test/v1558",
               "assets":[{"name":"Payanam_Android_1558_release_20260801_080000.apk","browser_download_url":"https://example.test/v1558/Payanam_Android_1558_release_20260801_080000.apk"}]}
            ]
        """.trimIndent()
        val statuses = parseReleases(body, "debug")
        assertEquals(3, statuses.size)

        val dev = statuses.first { it.channel == UpdateChannel.DEV }
        assertEquals(1562, dev.buildNumber)
        assertEquals("https://example.test/dev-v1562/Payanam_Android_1562_debug_20260810_120000.apk", dev.apkDownloadUrl)
        assertEquals(dev.apkDownloadUrl + ".sha256", dev.apkSha256Url)
        assertFalse(dev.typeMismatch)

        // A debug install can never take the release-only beta/stable assets.
        val beta = statuses.first { it.channel == UpdateChannel.BETA }
        assertEquals(1560, beta.buildNumber)
        assertNull(beta.apkDownloadUrl)
        assertTrue(beta.typeMismatch)
        val stable = statuses.first { it.channel == UpdateChannel.STABLE }
        assertEquals(1558, stable.buildNumber)
        assertTrue(stable.typeMismatch)
    }

    @Test
    fun `parseReleases flags a release with no expected-type apk as fail-closed`() {
        val body = """
            [
              {"tag_name":"dev-v1562","name":"Dev #1562","html_url":"https://example.test/dev-v1562",
               "assets":[{"name":"Payanam_Android_1562_release_20260810_120000.apk","browser_download_url":"https://example.test/dev-v1562/Payanam_Android_1562_release_20260810_120000.apk"}]}
            ]
        """.trimIndent()
        // Debug install: the dev release ships only a release-type APK.
        val debugView = parseReleases(body, "debug")
        assertEquals(1, debugView.size)
        assertTrue(debugView[0].typeMismatch)
        assertNull(debugView[0].apkDownloadUrl)
        assertNull(debugView[0].apkSha256Url)

        // Release install: the same release selects cleanly.
        val releaseView = parseReleases(body, "release")
        assertEquals("https://example.test/dev-v1562/Payanam_Android_1562_release_20260810_120000.apk", releaseView[0].apkDownloadUrl)
        assertFalse(releaseView[0].typeMismatch)
    }

    @Test
    fun `parseReleases never selects the sha256 sibling as the apk`() {
        val shaFirst = """
            [
              {"tag_name":"dev-v1562","name":"Dev #1562","html_url":"url",
               "assets":[
                 {"name":"Payanam_Android_1562_debug_20260810_120000.apk.sha256","browser_download_url":"url/sha"},
                 {"name":"Payanam_Android_1562_debug_20260810_120000.apk","browser_download_url":"url/apk"}
               ]}
            ]
        """.trimIndent()
        val statuses = parseReleases(shaFirst, "debug")
        assertEquals("url/apk", statuses[0].apkDownloadUrl)
        assertEquals("url/sha", statuses[0].apkSha256Url)
        assertFalse(statuses[0].typeMismatch)

        val shaOnly = """
            [
              {"tag_name":"dev-v1562","name":"Dev #1562","html_url":"url",
               "assets":[{"name":"Payanam_Android_1562_debug_20260810_120000.apk.sha256","browser_download_url":"url/sha"}]}
            ]
        """.trimIndent()
        val shaOnlyStatuses = parseReleases(shaOnly, "debug")
        assertNull(shaOnlyStatuses[0].apkDownloadUrl)
        assertTrue(shaOnlyStatuses[0].typeMismatch)
    }

    @Test
    fun `parseReleases skips legacy untyped names with no fallback`() {
        val body = """
            [
              {"tag_name":"dev-v1562","name":"Dev #1562","html_url":"url",
               "assets":[{"name":"Payanam_Android_1562_20260810.apk","browser_download_url":"url/legacy.apk"}]}
            ]
        """.trimIndent()
        val statuses = parseReleases(body, "debug")
        assertNull(statuses[0].apkDownloadUrl)
        assertTrue(statuses[0].typeMismatch)
    }

    @Test
    fun `parseReleases picks the expected type when both types are present`() {
        val body = """
            [
              {"tag_name":"dev-v1562","name":"Dev #1562","html_url":"url",
               "assets":[
                 {"name":"Payanam_Android_1562_release_20260810_120000.apk","browser_download_url":"url/release"},
                 {"name":"Payanam_Android_1562_debug_20260810_120000.apk","browser_download_url":"url/debug"}
               ]}
            ]
        """.trimIndent()
        assertEquals("url/debug", parseReleases(body, "debug")[0].apkDownloadUrl)
        assertEquals("url/release", parseReleases(body, "release")[0].apkDownloadUrl)
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
        val statuses = parseReleases(body, "debug")
        assertEquals(1, statuses.size)
        assertEquals(UpdateChannel.DEV, statuses[0].channel)
        assertNull(statuses[0].apkDownloadUrl)
        // No assets at all: nothing selectable — fail closed.
        assertTrue(statuses[0].typeMismatch)
    }

    @Test
    fun `parseReleases handles garbage body`() {
        assertEquals(emptyList<ChannelStatus>(), parseReleases("not json at all", "debug"))
        assertEquals(emptyList<ChannelStatus>(), parseReleases("", "debug"))
        assertEquals(emptyList<ChannelStatus>(), parseReleases("[]", "debug"))
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
        val statuses = parseReleases(body, "debug")
        assertEquals(1, statuses.size)
        assertEquals(UpdateChannel.DEV, statuses[0].channel)
    }

    @Test
    fun `parseReleases handles multiple persistent releases`() {
        val body = """
            [
              {"tag_name":"dev-v1704","name":"Dev #1704","html_url":"url-dev",
               "assets":[{"name":"Payanam_Android_1704_debug_20260901_120000.apk","browser_download_url":"url-dev.apk"}]},
              {"tag_name":"dev-v1703","name":"Dev #1703","html_url":"url-dev-old",
               "assets":[{"name":"Payanam_Android_1703_debug_20260830_120000.apk","browser_download_url":"url-dev-old.apk"}]},
              {"tag_name":"beta-v1703","name":"Beta #1703","html_url":"url-beta",
               "assets":[{"name":"Payanam_Android_1703_release_20260830_120000.apk","browser_download_url":"url-beta.apk"}]}
            ]
        """.trimIndent()
        val statuses = parseReleases(body, "debug")
        // Both dev-v1704 and dev-v1703 map to DEV channel → 2 entries
        val devStatuses = statuses.filter { it.channel == UpdateChannel.DEV }
        assertEquals(2, devStatuses.size)
        // First one in the list wins for the selected channel
        assertEquals(1704, devStatuses[0].buildNumber)
        assertEquals(1, statuses.count { it.channel == UpdateChannel.BETA })
    }

    // ── findApkForBuild (downloads-dir scan) ──────────────────────────────

    @Test
    fun `findApkForBuild returns the typed apk and never the sha sibling or legacy names`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.getExternalFilesDir(null), AutoDownloadManager.SUBDIR)
        assertTrue(dir.exists() || dir.mkdirs())
        val apk = File(dir, "Payanam_Android_1568_debug_20260812_193754.apk").apply { writeText("apk") }
        val sha = File(dir, "Payanam_Android_1568_debug_20260812_193754.apk.sha256").apply { writeText("sha") }
        val legacy = File(dir, "Payanam_Android_1568_20260812_193754.apk").apply { writeText("legacy") }
        val otherType = File(dir, "Payanam_Android_1568_release_20260812_193754.apk").apply { writeText("release") }
        try {
            // Debug install: only the debug-typed APK matches — the sha sibling
            // (same tokens) and the legacy name must never be returned.
            assertEquals(apk.absolutePath, AutoDownloadManager.findApkForBuild(context, "1568", "debug"))
            // Release install: the release-typed APK matches.
            assertEquals(otherType.absolutePath, AutoDownloadManager.findApkForBuild(context, "1568", "release"))
            // Unknown build: nothing.
            assertNull(AutoDownloadManager.findApkForBuild(context, "9999", "debug"))
        } finally {
            apk.delete()
            sha.delete()
            legacy.delete()
            otherType.delete()
        }
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
