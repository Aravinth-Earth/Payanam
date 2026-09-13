//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertEquals("1568", buildNumberFromFileName("Payanam_Android_1568_debug_20260812_193754.apk"))
        assertEquals("1568", buildNumberFromFileName("Payanam_Android_1568_20260812_193754.apk"))   // legacy untyped
        assertEquals("", buildNumberFromFileName("Payanam update"))   // no build number → empty
        assertEquals("", buildNumberFromFileName(""))                  // empty input → empty
    }

    // ── Type-aware artifact matching (artifact-naming standard) ───────────

    @Test
    fun `artifact name matches only its own build type`() {
        assertTrue(artifactNameMatchesBuildType("Payanam_Android_1568_debug_20260812_193754.apk", "debug"))
        assertTrue(artifactNameMatchesBuildType("Payanam_Android_1568_release_20260812_193754.apk", "release"))
        assertFalse(artifactNameMatchesBuildType("Payanam_Android_1568_debug_20260812_193754.apk", "release"))
        assertFalse(artifactNameMatchesBuildType("Payanam_Android_1568_release_20260812_193754.apk", "debug"))
    }

    @Test
    fun `artifact name predicate fails closed on legacy names and sha siblings`() {
        // Legacy untyped name: no fallback, never matches.
        assertFalse(artifactNameMatchesBuildType("Payanam_Android_1568_20260812_193754.apk", "debug"))
        // `.sha256` sibling: carries the type token but is not an APK.
        assertFalse(artifactNameMatchesBuildType("Payanam_Android_1568_debug_20260812_193754.apk.sha256", "debug"))
        assertFalse(artifactNameMatchesBuildType("", "debug"))
    }

    @Test
    fun `apk-for-build matcher requires build, type and apk suffix`() {
        assertTrue(isApkForBuild("Payanam_Android_1568_debug_20260812_193754.apk", "1568", "debug"))
        assertFalse(isApkForBuild("Payanam_Android_1568_20260812_193754.apk", "1568", "debug"))          // legacy: no type token
        assertFalse(isApkForBuild("Payanam_Android_1568_release_20260812_193754.apk", "1568", "debug"))  // wrong type
        assertFalse(isApkForBuild("Payanam_Android_1568_debug_20260812_193754.apk.sha256", "1568", "debug")) // sha sibling must never be returned
        assertFalse(isApkForBuild("Payanam_Android_1569_debug_20260812_193754.apk", "1568", "debug"))    // wrong build
    }

    @Test
    fun `channel ships the declared build type`() {
        assertEquals("debug", UpdateChannel.DEV.shippedApkType())
        assertEquals("release", UpdateChannel.BETA.shippedApkType())
        assertEquals("release", UpdateChannel.STABLE.shippedApkType())
    }

    // ── Fail-closed channel verdict ───────────────────────────────────────

    @Test
    fun `type mismatch fails the verdict closed`() {
        val statuses = listOf(
            ChannelStatus(
                channel = UpdateChannel.DEV,
                buildNumber = 9999,
                releaseUrl = "url",
                apkDownloadUrl = null,
                typeMismatch = true,
            ),
        )
        val result = resolveUpdateResult(currentBuildNumber = 100, channel = UpdateChannel.DEV, statuses = statuses)
        // Newer build exists, but of another type — the VERDICT must fail closed.
        assertEquals(UpdateOutcome.TYPE_MISMATCH, result.outcome)
        // The build number is still reported (channel status rows use it).
        assertEquals(9999, result.latestBuildNumber)
    }

    @Test
    fun `matching type with a newer build is available`() {
        val statuses = listOf(
            ChannelStatus(
                channel = UpdateChannel.DEV,
                buildNumber = 101,
                releaseUrl = "url",
                apkDownloadUrl = "url/apk",
                apkSha256Url = "url/apk.sha256",
                typeMismatch = false,
            ),
        )
        val result = resolveUpdateResult(currentBuildNumber = 100, channel = UpdateChannel.DEV, statuses = statuses)
        assertEquals(UpdateOutcome.UPDATE_AVAILABLE, result.outcome)
    }

    @Test
    fun `matching type with same or older build is not available`() {
        val sameBuild = listOf(
            ChannelStatus(channel = UpdateChannel.DEV, buildNumber = 100, releaseUrl = "url", apkDownloadUrl = "url/apk"),
        )
        assertEquals(UpdateOutcome.UP_TO_DATE, resolveUpdateResult(100, UpdateChannel.DEV, sameBuild).outcome)

        val olderBuild = listOf(
            ChannelStatus(channel = UpdateChannel.DEV, buildNumber = 99, releaseUrl = "url", apkDownloadUrl = "url/apk"),
        )
        assertEquals(UpdateOutcome.UP_TO_DATE, resolveUpdateResult(100, UpdateChannel.DEV, olderBuild).outcome)
    }

    @Test
    fun `no release on the channel is an explicit non-success outcome`() {
        val result = resolveUpdateResult(currentBuildNumber = 100, channel = UpdateChannel.DEV, statuses = emptyList())
        assertEquals(UpdateOutcome.NO_RELEASE_ON_CHANNEL, result.outcome)
        assertNull(result.latestBuildNumber)
        assertNull(result.error)
    }

    @Test
    fun `an exhausted scan is undetermined, never up to date`() {
        val result = resolveUpdateResult(
            currentBuildNumber = 100,
            channel = UpdateChannel.DEV,
            statuses = emptyList(),
            scanComplete = false,
        )
        assertEquals(UpdateOutcome.INDETERMINATE, result.outcome)
    }

    @Test
    fun `a release without a parseable build number is unreadable, not up to date`() {
        val statuses = listOf(
            ChannelStatus(channel = UpdateChannel.DEV, buildNumber = null, releaseUrl = "url", apkDownloadUrl = "url/apk"),
        )
        val result = resolveUpdateResult(currentBuildNumber = 100, channel = UpdateChannel.DEV, statuses = statuses)
        assertEquals(UpdateOutcome.RELEASE_UNREADABLE, result.outcome)
    }

    @Test
    fun `a mismatch on another channel does not affect the selected channel`() {
        val statuses = listOf(
            ChannelStatus(channel = UpdateChannel.BETA, buildNumber = 9999, releaseUrl = "beta", typeMismatch = true),
            ChannelStatus(channel = UpdateChannel.DEV, buildNumber = 101, releaseUrl = "dev", apkDownloadUrl = "dev/apk"),
        )
        val result = resolveUpdateResult(currentBuildNumber = 100, channel = UpdateChannel.DEV, statuses = statuses)
        assertEquals(UpdateOutcome.UPDATE_AVAILABLE, result.outcome)
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
    fun `channel from tag ignores non-channel tags`() {
        assertNull(channelFromTag("v1.2.3"))
        assertNull(channelFromTag("latest"))
        assertNull(channelFromTag(""))
        assertNull(channelFromTag("latest-nightly"))
        assertNull(channelFromTag("latest-dev"))       // former rolling tag
        assertNull(channelFromTag("latest-stable"))     // former rolling tag
    }

    @Test
    fun `channel from tag maps persistent tags correctly`() {
        assertEquals(UpdateChannel.DEV, channelFromTag("dev-v1704"))
        assertEquals(UpdateChannel.BETA, channelFromTag("beta-v1500"))
        assertEquals(UpdateChannel.STABLE, channelFromTag("stable-v200"))
    }

    @Test
    fun `channel from tag maps plain v tags to stable`() {
        assertEquals(UpdateChannel.STABLE, channelFromTag("v1704"))
        assertEquals(UpdateChannel.STABLE, channelFromTag("v200"))
    }

    @Test
    fun `channel from tag rejects malformed persistent tags`() {
        assertNull(channelFromTag("dev-v"))            // no build number
        assertNull(channelFromTag("dev-vabc"))         // non-numeric build
        assertNull(channelFromTag("nightly-v1704"))    // unknown channel
        assertNull(channelFromTag("dev-v1704-extra"))  // trailing content
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
            outcome = UpdateOutcome.UPDATE_AVAILABLE,
            latestBuildNumber = 1515,
            releaseUrl = "https://github.com/Aravinth-Earth/Payanam/releases/tag/latest-dev",
            error = null,
        )
        assertEquals(UpdateOutcome.UPDATE_AVAILABLE, result.outcome)
        assertEquals(1515, result.latestBuildNumber)
        assertNull(result.error)
    }

    @Test
    fun `result with error`() {
        val result = UpdateCheckResult(
            outcome = UpdateOutcome.FAILED,
            latestBuildNumber = null,
            releaseUrl = null,
            error = UpdateCheckError.NO_INTERNET,
        )
        assertEquals(UpdateOutcome.FAILED, result.outcome)
        assertNull(result.latestBuildNumber)
        assertEquals(UpdateCheckError.NO_INTERNET, result.error)
    }

    // ── Pagination + outcome completeness ─────────────────────────────────

    @Test
    fun `next page url is extracted from a Link header`() {
        val header = "<https://api.github.com/repos/x/releases?per_page=100&page=2>; rel=\"next\", " +
            "<https://api.github.com/repos/x/releases?per_page=100&page=9>; rel=\"last\""
        assertEquals("https://api.github.com/repos/x/releases?per_page=100&page=2", nextPageUrl(header))
        assertNull(nextPageUrl(""))
        assertNull(nextPageUrl(null))
        assertNull(nextPageUrl("<https://api.github.com/repos/x/releases?page=9>; rel=\"last\""))
    }

    @Test
    fun `the outcome enum is closed and deliberately sized`() {
        // Consumers switch exhaustively over UpdateOutcome — adding a state is
        // a deliberate act that must update this count AND every consumer.
        assertEquals(7, UpdateOutcome.entries.size)
    }
}
