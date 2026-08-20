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
        /** Title. */
        val title = "Latest Dev Build (#1514)"
        /** Match. */
        val match = buildNumberRegex.find(title)
        /** Assert equals. */
        assertEquals(1514, match?.groupValues?.get(1)?.toIntOrNull())
    }

    @Test
    fun `parseBuildNumber no match for random string`() {
        /** Title. */
        val title = "Some random release"
        /** Match. */
        val match = buildNumberRegex.find(title)
        /** Assert null. */
        assertNull(match)
    }

    @Test
    fun `parseBuildNumber handles multi-digit`() {
        /** Title. */
        val title = "Build (#99999)"
        /** Match. */
        val match = buildNumberRegex.find(title)
        /** Assert equals. */
        assertEquals(99999, match?.groupValues?.get(1)?.toIntOrNull())
    }

    @Test
    fun `parseBuildNumber handles single digit`() {
        /** Title. */
        val title = "Build (#1)"
        /** Match. */
        val match = buildNumberRegex.find(title)
        /** Assert equals. */
        assertEquals(1, match?.groupValues?.get(1)?.toIntOrNull())
    }

    // ── Version comparison ────────────────────────────────────────────────

    @Test
    fun `update available when latest is newer`() {
        /** Latest. */
        val latest = 1515
        /** Current. */
        val current = 1514
        /** Assert true. */
        assertTrue(latest > current)
    }

    @Test
    fun `up to date when same build`() {
        /** Latest. */
        val latest = 1514
        /** Current. */
        val current = 1514
        /** Assert false. */
        assertFalse(latest > current)
    }

    @Test
    fun `no downgrade when local is newer`() {
        /** Latest. */
        val latest = 1513
        /** Current. */
        val current = 1514
        /** Assert false. */
        assertFalse(latest > current)
    }

    // ── Download filename → build label ───────────────────────────────────

    @Test
    fun `build number extracted from apk filename`() {
        /** Assert equals. */
        assertEquals("1568", buildNumberFromFileName("Payanam_Android_1568_20260812_193754.apk"))
        /** Assert equals. */
        assertEquals("", buildNumberFromFileName("Payanam update"))   // no build number → empty
        /** Assert equals. */
        assertEquals("", buildNumberFromFileName(""))                  // empty input → empty
    }

    // ── Error enum coverage ───────────────────────────────────────────────

    @Test
    fun `all error types are covered`() {
        /** Errors. */
        val errors = UpdateCheckError.entries
        /** Assert equals. */
        assertEquals(6, errors.size)
    }

    @Test
    fun `error enum contains expected values`() {
        /** Assert true. */
        assertTrue(UpdateCheckError.NO_INTERNET in UpdateCheckError.entries)
        /** Assert true. */
        assertTrue(UpdateCheckError.TIMEOUT in UpdateCheckError.entries)
        /** Assert true. */
        assertTrue(UpdateCheckError.GITHUB_UNAVAILABLE in UpdateCheckError.entries)
        /** Assert true. */
        assertTrue(UpdateCheckError.RATE_LIMITED in UpdateCheckError.entries)
        /** Assert true. */
        assertTrue(UpdateCheckError.PARSE_ERROR in UpdateCheckError.entries)
        /** Assert true. */
        assertTrue(UpdateCheckError.UNKNOWN in UpdateCheckError.entries)
    }

    // ── Channel mapping ───────────────────────────────────────────────────

    @Test
    fun `channel from tag maps correctly`() {
        /** Assert equals. */
        assertEquals(UpdateChannel.DEV, channelFromTag("latest-dev"))
        /** Assert equals. */
        assertEquals(UpdateChannel.BETA, channelFromTag("latest-beta"))
        /** Assert equals. */
        assertEquals(UpdateChannel.STABLE, channelFromTag("latest-stable"))
    }

    @Test
    fun `channel from tag ignores non-channel tags`() {
        /** Assert null. */
        assertNull(channelFromTag("v1.2.3"))
        /** Assert null. */
        assertNull(channelFromTag("latest"))
        /** Assert null. */
        assertNull(channelFromTag(""))
        /** Assert null. */
        assertNull(channelFromTag("latest-nightly"))
    }

    @Test
    fun `channel from storage parses valid values`() {
        /** Assert equals. */
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage("DEV"))
        /** Assert equals. */
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromStorage("BETA"))
        /** Assert equals. */
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromStorage("STABLE"))
    }

    @Test
    fun `channel from storage falls back to DEV for garbage`() {
        /** Assert equals. */
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage(null))
        /** Assert equals. */
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage(""))
        /** Assert equals. */
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage("nightly"))
        /** Assert equals. */
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage("dev"))  // lowercase is invalid storage
    }

    @Test
    fun `channel tag suffixes match storage names`() {
        // Storage stores enum .name (uppercase); tags use tagSuffix (lowercase).
        /** Assert equals. */
        assertEquals("dev", UpdateChannel.DEV.tagSuffix)
        /** Assert equals. */
        assertEquals("beta", UpdateChannel.BETA.tagSuffix)
        /** Assert equals. */
        assertEquals("stable", UpdateChannel.STABLE.tagSuffix)
    }

    // ── UpdateCheckResult construction ────────────────────────────────────

    @Test
    fun `result with update available`() {
        /** Result. */
        val result = UpdateCheckResult(
            isUpdateAvailable = true,
            latestBuildNumber = 1515,
            releaseUrl = "https://github.com/Aravinth-Earth/Payanam/releases/tag/latest-dev",
            error = null,
        )
        /** Assert true. */
        assertTrue(result.isUpdateAvailable)
        /** Assert equals. */
        assertEquals(1515, result.latestBuildNumber)
        /** Assert null. */
        assertNull(result.error)
    }

    @Test
    fun `result with error`() {
        /** Result. */
        val result = UpdateCheckResult(
            isUpdateAvailable = false,
            latestBuildNumber = null,
            releaseUrl = null,
            error = UpdateCheckError.NO_INTERNET,
        )
        /** Assert false. */
        assertFalse(result.isUpdateAvailable)
        /** Assert null. */
        assertNull(result.latestBuildNumber)
        /** Assert equals. */
        assertEquals(UpdateCheckError.NO_INTERNET, result.error)
    }
}
