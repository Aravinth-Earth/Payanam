//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.presentation.updates.parseReleases
import io.payanam.shared.updates.ReleaseStatus
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
 * parseReleases reads the release body with kotlinx.serialization, which `:core:presentation`
 * already ships, so the parse tests no longer depend on a real org.json implementation.
 * Robolectric stays because the rest of this class (AutoDownloadManager, the downloads-dir scan)
 * needs a real Context.
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
              {"tag_name":"v1.0.0","name":"Payanam 1.0.0","html_url":"https://example.test/v1.0.0",
               "assets":[
                 {"name":"Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk","browser_download_url":"${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk"},
                 {"name":"Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk.sha256","browser_download_url":"${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk.sha256"}
               ]},
              {"tag_name":"v0.9.0","name":"Payanam 0.9.0","html_url":"https://example.test/v0.9.0",
               "assets":[{"name":"Payanam_Android_0.9.0_e4f5a6b_release_20260805_090000.apk","browser_download_url":"${ASSET_BASE}/v0.9.0/Payanam_Android_0.9.0_e4f5a6b_release_20260805_090000.apk"}]},
              {"tag_name":"v0.8.0","name":"Payanam 0.8.0","html_url":"https://example.test/v0.8.0",
               "assets":[{"name":"Payanam_Android_0.8.0_7c8d9e0_release_20260801_080000.apk","browser_download_url":"${ASSET_BASE}/v0.8.0/Payanam_Android_0.8.0_7c8d9e0_release_20260801_080000.apk"}]}
            ]
        """.trimIndent()
        val statuses = requireNotNull(parseReleases(body, "debug"))
        assertEquals(3, statuses.size)

        val newest = statuses.first { it.versionName == "1.0.0" }
        assertEquals(1_000_000, newest.versionCode)
        assertEquals("${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk", newest.apkDownloadUrl)
        assertEquals(newest.apkDownloadUrl + ".sha256", newest.apkSha256Url)
        assertFalse(newest.typeMismatch)

        // A debug install can never take the release-only 0.9.0/0.8.0 assets.
        val older = statuses.first { it.versionName == "0.9.0" }
        assertEquals(9_000, older.versionCode)
        assertNull(older.apkDownloadUrl)
        assertTrue(older.typeMismatch)
        val oldest = statuses.first { it.versionName == "0.8.0" }
        assertEquals(8_000, oldest.versionCode)
        assertTrue(oldest.typeMismatch)
    }

    @Test
    fun `parseReleases flags a release with no expected-type apk as fail-closed`() {
        val body = """
            [
              {"tag_name":"v1.0.0","name":"Payanam 1.0.0","html_url":"https://example.test/v1.0.0",
               "assets":[{"name":"Payanam_Android_1.0.0_a1b2c3d_release_20260810_120000.apk","browser_download_url":"${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_release_20260810_120000.apk"}]}
            ]
        """.trimIndent()
        // Debug install: the release ships only a release-type APK.
        val debugView = requireNotNull(parseReleases(body, "debug"))
        assertEquals(1, debugView.size)
        assertTrue(debugView[0].typeMismatch)
        assertNull(debugView[0].apkDownloadUrl)
        assertNull(debugView[0].apkSha256Url)

        // Release install: the same release selects cleanly.
        val releaseView = requireNotNull(parseReleases(body, "release"))
        assertEquals("${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_release_20260810_120000.apk", releaseView[0].apkDownloadUrl)
        assertFalse(releaseView[0].typeMismatch)
    }

    @Test
    fun `parseReleases never selects the sha256 sibling as the apk`() {
        val shaFirst = """
            [
              {"tag_name":"v1.0.0","name":"Payanam 1.0.0","html_url":"url",
               "assets":[
                 {"name":"Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk.sha256","browser_download_url":"${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk.sha256"},
                 {"name":"Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk","browser_download_url":"${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk"}
               ]}
            ]
        """.trimIndent()
        val statuses = requireNotNull(parseReleases(shaFirst, "debug"))
        assertEquals("${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk", statuses[0].apkDownloadUrl)
        assertEquals("${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk.sha256", statuses[0].apkSha256Url)
        assertFalse(statuses[0].typeMismatch)

        val shaOnly = """
            [
              {"tag_name":"v1.0.0","name":"Payanam 1.0.0","html_url":"url",
               "assets":[{"name":"Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk.sha256","browser_download_url":"${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk.sha256"}]}
            ]
        """.trimIndent()
        val shaOnlyStatuses = requireNotNull(parseReleases(shaOnly, "debug"))
        assertNull(shaOnlyStatuses[0].apkDownloadUrl)
        assertTrue(shaOnlyStatuses[0].typeMismatch)
    }

    @Test
    fun `parseReleases skips legacy untyped names with no fallback`() {
        val body = """
            [
              {"tag_name":"v1.0.0","name":"Payanam 1.0.0","html_url":"url",
               "assets":[{"name":"Payanam_Android_1562_20260810.apk","browser_download_url":"url/legacy.apk"}]}
            ]
        """.trimIndent()
        val statuses = requireNotNull(parseReleases(body, "debug"))
        assertNull(statuses[0].apkDownloadUrl)
        assertTrue(statuses[0].typeMismatch)
    }

    @Test
    fun `parseReleases picks the expected type when both types are present`() {
        val body = """
            [
              {"tag_name":"v1.0.0","name":"Payanam 1.0.0","html_url":"url",
               "assets":[
                 {"name":"Payanam_Android_1.0.0_a1b2c3d_release_20260810_120000.apk","browser_download_url":"${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_release_20260810_120000.apk"},
                 {"name":"Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk","browser_download_url":"${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk"}
               ]}
            ]
        """.trimIndent()
        assertEquals("${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_debug_20260810_120000.apk", requireNotNull(parseReleases(body, "debug"))[0].apkDownloadUrl)
        assertEquals("${ASSET_BASE}/v1.0.0/Payanam_Android_1.0.0_a1b2c3d_release_20260810_120000.apk", requireNotNull(parseReleases(body, "release"))[0].apkDownloadUrl)
    }

    @Test
    fun `parseReleases skips tags that carry no version and keeps ones that do`() {
        val body = """
            [
              {"tag_name":"dev-v1540","name":"Dev #1540","html_url":"url","assets":[]},
              {"tag_name":"v1.0.0","name":"Payanam 1.0.0","html_url":"url","assets":[]},
              {"tag_name":"latest-nightly","name":"Nightly","html_url":"url","assets":[]},
              {"tag_name":"v1","name":"Payanam 1","html_url":"url","assets":[]}
            ]
        """.trimIndent()
        val statuses = requireNotNull(parseReleases(body, "debug"))
        assertEquals(1, statuses.size)
        assertEquals("1.0.0", statuses[0].versionName)
        assertEquals(1_000_000, statuses[0].versionCode)
        assertNull(statuses[0].apkDownloadUrl)
        // No assets at all: nothing selectable — fail closed.
        assertTrue(statuses[0].typeMismatch)
    }

    @Test
    fun `parseReleases fails closed on a malformed body and accepts a valid empty list`() {
        assertNull(parseReleases("not json at all", "debug"))
        assertNull(parseReleases("", "debug"))
        assertEquals(emptyList<ReleaseStatus>(), parseReleases("[]", "debug"))
    }

    @Test
    fun `parseReleases skips malformed entries`() {
        val body = """
            [
              {"tag_name":"v1.0.0","name":"Payanam 1.0.0","html_url":"url"},
              {"tag_name":123},
              "not-an-object"
            ]
        """.trimIndent()
        val statuses = requireNotNull(parseReleases(body, "debug"))
        assertEquals(1, statuses.size)
        assertEquals("1.0.0", statuses[0].versionName)
    }

    @Test
    fun `parseReleases keeps every release of one series in the order the list carries them`() {
        val body = """
            [
              {"tag_name":"v1.0.4","name":"Payanam 1.0.4","html_url":"url-dev",
               "assets":[{"name":"Payanam_Android_1.0.4_a1b2c3d_debug_20260901_120000.apk","browser_download_url":"${ASSET_BASE}/v1.0.4/Payanam_Android_1.0.4_a1b2c3d_debug_20260901_120000.apk"}]},
              {"tag_name":"v1.0.3","name":"Payanam 1.0.3","html_url":"url-dev-old",
               "assets":[{"name":"Payanam_Android_1.0.3_b2c3d4e_debug_20260830_120000.apk","browser_download_url":"${ASSET_BASE}/v1.0.3/Payanam_Android_1.0.3_b2c3d4e_debug_20260830_120000.apk"}]},
              {"tag_name":"v0.9.3","name":"Payanam 0.9.3","html_url":"url-beta",
               "assets":[{"name":"Payanam_Android_0.9.3_c3d4e5f_release_20260830_120000.apk","browser_download_url":"${ASSET_BASE}/v0.9.3/Payanam_Android_0.9.3_c3d4e5f_release_20260830_120000.apk"}]}
            ]
        """.trimIndent()
        val statuses = requireNotNull(parseReleases(body, "debug"))
        assertEquals(listOf("1.0.4", "1.0.3", "0.9.3"), statuses.map { it.versionName })
        assertEquals(listOf(1_000_004, 1_000_003, 9_003), statuses.map { it.versionCode })
        assertEquals(listOf(false, false, true), statuses.map { it.typeMismatch })
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
            // Unknown version: nothing.
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
        assertEquals("download_failed", downloadFailureMessage(99999)) // unknown
    }

    @Test
    fun `paused message maps wifi and retry reasons`() {
        assertEquals("download_paused_wifi", downloadPausedMessage(DownloadManager.PAUSED_WAITING_FOR_NETWORK))
        assertEquals("download_paused_retry", downloadPausedMessage(DownloadManager.PAUSED_WAITING_TO_RETRY))
        assertEquals("download_paused", downloadPausedMessage(99999))
    }

    private companion object {
        /** The only release-asset URL shape the trusted-URL policy accepts. */
        const val ASSET_BASE = "https://github.com/Aravinth-Earth/Payanam/releases/download"
    }
}
