//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateCheckerTest.
 */
class UpdateCheckerTest {

    private val buildNumberRegex = Regex("""#(\d+)""")

    // ── Build number parsing ──────────────────────────────────────────────

    @Test
    fun `parseBuildNumber from release title`() {
        val title = "Latest Dev Build (#1514)"
        val match = buildNumberRegex.find(title)
        assertEquals(1514, match?.groupValues?.get(1)?.toIntOrNull())
    }

    @Test
    fun `parseBuildNumber no match for random string`() {
        val title = "Some random release"
        val match = buildNumberRegex.find(title)
        assertNull(match)
    }

    @Test
    fun `parseBuildNumber handles multi-digit`() {
        val title = "Build (#99999)"
        val match = buildNumberRegex.find(title)
        assertEquals(99999, match?.groupValues?.get(1)?.toIntOrNull())
    }

    @Test
    fun `parseBuildNumber handles single digit`() {
        val title = "Build (#1)"
        val match = buildNumberRegex.find(title)
        assertEquals(1, match?.groupValues?.get(1)?.toIntOrNull())
    }

    // ── Version comparison ────────────────────────────────────────────────

    @Test
    fun `update available when latest is newer`() {
        val latest = 1515
        val current = 1514
        assertTrue(latest > current)
    }

    @Test
    fun `up to date when same build`() {
        val latest = 1514
        val current = 1514
        assertFalse(latest > current)
    }

    @Test
    fun `no downgrade when local is newer`() {
        val latest = 1513
        val current = 1514
        assertFalse(latest > current)
    }

    // ── Download filename → build label ───────────────────────────────────

    @Test
    fun `build number extracted from apk filename`() {
        assertEquals("1568", buildNumberFromFileName("Payanam_Android_1568_20260812_193754.apk"))
        assertEquals("", buildNumberFromFileName("Payanam update"))   // no build number → empty
        assertEquals("", buildNumberFromFileName(""))                  // empty input → empty
    }

    // ── Error enum coverage ───────────────────────────────────────────────

    @Test
    fun `all error types are covered`() {
        val errors = UpdateCheckError.entries
        assertEquals(6, errors.size)
    }

    @Test
    fun `error enum contains expected values`() {
        assertTrue(UpdateCheckError.NO_INTERNET in UpdateCheckError.entries)
        assertTrue(UpdateCheckError.TIMEOUT in UpdateCheckError.entries)
        assertTrue(UpdateCheckError.GITHUB_UNAVAILABLE in UpdateCheckError.entries)
        assertTrue(UpdateCheckError.RATE_LIMITED in UpdateCheckError.entries)
        assertTrue(UpdateCheckError.PARSE_ERROR in UpdateCheckError.entries)
        assertTrue(UpdateCheckError.UNKNOWN in UpdateCheckError.entries)
    }

    // ── Channel mapping ───────────────────────────────────────────────────

    @Test
    fun `channel from tag maps correctly`() {
        assertEquals(UpdateChannel.DEV, channelFromTag("latest-dev"))
        assertEquals(UpdateChannel.BETA, channelFromTag("latest-beta"))
        assertEquals(UpdateChannel.STABLE, channelFromTag("latest-stable"))
    }

    @Test
    fun `channel from tag ignores non-channel tags`() {
        assertNull(channelFromTag("v1.2.3"))
        assertNull(channelFromTag("latest"))
        assertNull(channelFromTag(""))
        assertNull(channelFromTag("latest-nightly"))
    }

    @Test
    fun `channel from storage parses valid values`() {
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage("DEV"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromStorage("BETA"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromStorage("STABLE"))
    }

    @Test
    fun `channel from storage falls back to DEV for garbage`() {
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage(null))
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage(""))
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage("nightly"))
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage("dev"))  // lowercase is invalid storage
    }

    @Test
    fun `channel tag suffixes match storage names`() {
        // Storage stores enum .name (uppercase); tags use tagSuffix (lowercase).
        assertEquals("dev", UpdateChannel.DEV.tagSuffix)
        assertEquals("beta", UpdateChannel.BETA.tagSuffix)
        assertEquals("stable", UpdateChannel.STABLE.tagSuffix)
    }

    // ── UpdateCheckResult construction ────────────────────────────────────

    @Test
    fun `result with update available`() {
        val result = UpdateCheckResult(
            isUpdateAvailable = true,
            latestBuildNumber = 1515,
            releaseUrl = "https://github.com/Aravinth-Earth/Payanam/releases/tag/latest-dev",
            error = null,
        )
        assertTrue(result.isUpdateAvailable)
        assertEquals(1515, result.latestBuildNumber)
        assertNull(result.error)
    }

    @Test
    fun `result with error`() {
        val result = UpdateCheckResult(
            isUpdateAvailable = false,
            latestBuildNumber = null,
            releaseUrl = null,
            error = UpdateCheckError.NO_INTERNET,
        )
        assertFalse(result.isUpdateAvailable)
        assertNull(result.latestBuildNumber)
        assertEquals(UpdateCheckError.NO_INTERNET, result.error)
    }
}
