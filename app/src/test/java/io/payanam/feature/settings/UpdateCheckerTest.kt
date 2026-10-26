//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import io.payanam.presentation.updates.ReleaseVersion
import io.payanam.presentation.updates.artifactNameMatchesBuildType
import io.payanam.presentation.updates.nextPageUrl
import io.payanam.presentation.updates.parseReleaseTag
import io.payanam.shared.updates.ReleaseStatus
import io.payanam.shared.updates.UpdateCheckError
import io.payanam.shared.updates.UpdateCheckResult
import io.payanam.shared.updates.UpdateOutcome
import io.payanam.shared.updates.resolveUpdateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class UpdateCheckerTest {

    // ── Release tag parsing ───────────────────────────────────────────────

    @Test
    fun `parseReleaseTag reads a canonical tag`() {
        assertEquals(ReleaseVersion(name = "1.0.0", code = 1_000_000), parseReleaseTag("v1.0.0"))
    }

    @Test
    fun `parseReleaseTag packs each field into its own thousand`() {
        assertEquals(1_514_000, parseReleaseTag("v1.514.0")?.code)
        assertEquals(2_003_004, parseReleaseTag("v2.3.4")?.code)
        assertEquals(99_999, parseReleaseTag("v0.99.999")?.code)
    }

    @Test
    fun `parseReleaseTag handles single and multi digit fields`() {
        assertEquals(ReleaseVersion("0.0.1", 1), parseReleaseTag("v0.0.1"))
        assertEquals(ReleaseVersion("10.20.30", 10_020_030), parseReleaseTag("v10.20.30"))
    }

    @Test
    fun `parseReleaseTag rebuilds the name from the parsed digits`() {
        // Leading zeros are read and then dropped, so one release has exactly one spelling.
        assertEquals(ReleaseVersion("1.0.0", 1_000_000), parseReleaseTag("v01.000.0"))
    }

    @Test
    fun `legacy and malformed tags are ignored rather than guessed at`() {
        // The build-number scheme this checker used to read is not a version, and a partial version is
        // not upgraded into one.
        assertNull(parseReleaseTag("dev-v1704"))
        assertNull(parseReleaseTag("beta-v1500"))
        assertNull(parseReleaseTag("stable-v200"))
        assertNull(parseReleaseTag("v1704"))
        assertNull(parseReleaseTag("latest"))
        assertNull(parseReleaseTag("latest-nightly"))
        assertNull(parseReleaseTag("v1.2"))
        assertNull(parseReleaseTag("v1.2.3.4"))
        assertNull(parseReleaseTag("v1.2.3-rc1"))
        assertNull(parseReleaseTag("V1.2.3"))
        assertNull(parseReleaseTag(""))
    }

    @Test
    fun `a tag with a digit group beyond any versionCode is refused rather than wrapped`() {
        assertNull(parseReleaseTag("v99999999999999999999.0.0"))
        assertNull(parseReleaseTag("v1.0.99999999999999999999"))
        // Above the 999 segment bound, so refused: no accepted tag can pack past an Int.
        assertNull(parseReleaseTag("v2100.0.1"))
    }

    // ── Version comparison ────────────────────────────────────────────────

    @Test
    fun `update available when the newest release is newer`() {
        val result = resolveUpdateResult(1_514_000, listOf(release("1.515.0")))
        assertEquals(UpdateOutcome.UPDATE_AVAILABLE, result.outcome)
    }

    @Test
    fun `up to date when the newest release is the installed version`() {
        val result = resolveUpdateResult(1_514_000, listOf(release("1.514.0")))
        assertEquals(UpdateOutcome.UP_TO_DATE, result.outcome)
    }

    @Test
    fun `no downgrade when the installed version is newer`() {
        val result = resolveUpdateResult(1_515_000, listOf(release("1.514.0")))
        assertEquals(UpdateOutcome.UP_TO_DATE, result.outcome)
    }

    // ── Download filename → version label ─────────────────────────────────

    @Test
    fun `version extracted from an apk filename, empty when the name declares none`() {
        assertEquals("1.0.0", versionFromFileName("Payanam_android_1.0.0_a1b2c3d_debug_20261026_101500.apk"))
        assertEquals("1.4.12", versionFromFileName("Payanam_windows_1.4.12_nogit_release_20261026_101500.apk"))
        assertEquals("", versionFromFileName("Payanam_Android_1568_debug_20260812_193754.apk")) // legacy build-number name
        assertEquals("", versionFromFileName("Payanam update")) // no version field → empty
        assertEquals("", versionFromFileName("")) // empty input → empty
    }

    @Test
    fun `a completed-download marker only matches a file that declares its version`() {
        assertTrue(isApkOfVersion("Payanam_android_1.0.0_a1b2c3d_debug_20261026_101500.apk", "1.0.0"))
        assertFalse(isApkOfVersion("Payanam_android_1.0.0_a1b2c3d_debug_20261026_101500.apk", "1.0.1"))
        assertFalse(isApkOfVersion("Payanam_android_1.0.0_a1b2c3d_debug_20261026_101500.apk", ""))
        // A marker left by the old build-number scheme names no version, so it matches nothing.
        assertFalse(isApkOfVersion("Payanam_Android_1568_debug_20260812_193754.apk", "1568"))
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
    fun `apk-for-build matcher requires version, type and apk suffix`() {
        assertTrue(isApkForBuild("Payanam_Android_1568_debug_20260812_193754.apk", "1568", "debug"))
        assertFalse(isApkForBuild("Payanam_Android_1568_20260812_193754.apk", "1568", "debug")) // legacy: no type token
        assertFalse(isApkForBuild("Payanam_Android_1568_release_20260812_193754.apk", "1568", "debug")) // wrong type
        assertFalse(isApkForBuild("Payanam_Android_1568_debug_20260812_193754.apk.sha256", "1568", "debug")) // sha sibling must never be returned
        assertFalse(isApkForBuild("Payanam_Android_1569_debug_20260812_193754.apk", "1568", "debug")) // wrong version
    }

    // ── Fail-closed verdict ───────────────────────────────────────────────

    @Test
    fun `type mismatch fails the verdict closed`() {
        val result = resolveUpdateResult(1_000_000, listOf(release("1.2.0", typeMismatch = true)))
        // A newer release exists, but of another type — the VERDICT must fail closed.
        assertEquals(UpdateOutcome.TYPE_MISMATCH, result.outcome)
        // The version is still reported (the download rows label themselves from it).
        assertEquals("1.2.0", result.latestVersionName)
        assertEquals(1_002_000, result.latestVersionCode)
        assertNull(result.apkDownloadUrl)
        assertNull(result.error)
    }

    @Test
    fun `matching type with a newer version is available`() {
        val result = resolveUpdateResult(1_000_000, listOf(release("1.0.1", apk = "url/apk", sha = "url/apk.sha256")))
        assertEquals(UpdateOutcome.UPDATE_AVAILABLE, result.outcome)
        assertEquals("url/apk", result.apkDownloadUrl)
        assertEquals("url/apk.sha256", result.apkSha256Url)
    }

    @Test
    fun `matching type with the same or older version is not available`() {
        assertEquals(UpdateOutcome.UP_TO_DATE, resolveUpdateResult(1_000_000, listOf(release("1.0.0"))).outcome)
        assertEquals(UpdateOutcome.UP_TO_DATE, resolveUpdateResult(1_000_000, listOf(release("0.99.0"))).outcome)
    }

    @Test
    fun `nothing parsable is NO_RELEASE and is distinct from a failed check`() {
        // A scan that reached the end of the list and found no versioned release: an explicit non-success
        // with a reason but no error, because the check itself worked.
        val noRelease = resolveUpdateResult(1_000_000, emptyList())
        assertEquals(UpdateOutcome.NO_RELEASE, noRelease.outcome)
        assertNull(noRelease.latestVersionName)
        assertNull(noRelease.error)

        // The check itself failing is a different outcome, and the only one that carries an error reason.
        val failed =
            UpdateCheckResult(
                outcome = UpdateOutcome.FAILED,
                latestVersionName = null,
                latestVersionCode = null,
                releaseUrl = null,
                error = UpdateCheckError.NO_INTERNET,
            )
        assertNotEquals(noRelease.outcome, failed.outcome)
        assertEquals(UpdateCheckError.NO_INTERNET, failed.error)
    }

    @Test
    fun `an exhausted scan is undetermined, never up to date`() {
        val result = resolveUpdateResult(installedVersionCode = 1_000_000, releases = emptyList(), scanComplete = false)
        assertEquals(UpdateOutcome.INDETERMINATE, result.outcome)
        assertNotEquals(UpdateOutcome.UP_TO_DATE, result.outcome)
    }

    @Test
    fun `the newest release wins regardless of list order`() {
        val newestLast = resolveUpdateResult(1_000_000, listOf(release("1.0.1"), release("1.0.3"), release("1.0.2")))
        assertEquals("1.0.3", newestLast.latestVersionName)
        assertEquals(UpdateOutcome.UPDATE_AVAILABLE, newestLast.outcome)

        val newestFirst = resolveUpdateResult(1_000_000, listOf(release("1.0.3"), release("1.0.1"), release("1.0.2")))
        assertEquals("1.0.3", newestFirst.latestVersionName)
    }

    @Test
    fun `a newer release with no artifact does not fall back to an older installable one`() {
        // 1.2.0 ships nothing this build can install while 1.1.0 does: the verdict is the MISMATCH, so
        // the older installable release is never offered as a downgrade.
        val result =
            resolveUpdateResult(
                1_000_000,
                listOf(release("1.1.0", apk = "url/old.apk"), release("1.2.0", typeMismatch = true)),
            )
        assertEquals(UpdateOutcome.TYPE_MISMATCH, result.outcome)
        assertEquals("1.2.0", result.latestVersionName)
        assertNull(result.apkDownloadUrl)
    }

    @Test
    fun `an older release's mismatch does not mark the newest release as one`() {
        val result =
            resolveUpdateResult(
                1_000_000,
                listOf(release("1.1.0", typeMismatch = true), release("1.2.0", apk = "url/new.apk")),
            )
        assertEquals(UpdateOutcome.UPDATE_AVAILABLE, result.outcome)
        assertEquals("1.2.0", result.latestVersionName)
        assertFalse(result.typeMismatch)
    }

    @Test
    fun `a newer release that ships nothing is a mismatch only against a newer release`() {
        // The mismatch is a verdict about being NEWER; an install at or above the mismatched release has
        // nothing to be told about it.
        val mismatch = release("1.2.0", typeMismatch = true)
        assertEquals(UpdateOutcome.TYPE_MISMATCH, resolveUpdateResult(1_000_000, listOf(mismatch)).outcome)
        assertEquals(UpdateOutcome.UP_TO_DATE, resolveUpdateResult(1_002_000, listOf(mismatch)).outcome)
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

    // ── UpdateCheckResult construction ────────────────────────────────────

    @Test
    fun `result with update available`() {
        val result =
            UpdateCheckResult(
                outcome = UpdateOutcome.UPDATE_AVAILABLE,
                latestVersionName = "1.515.0",
                latestVersionCode = 1_515_000,
                releaseUrl = "https://github.com/Aravinth-Earth/Payanam/releases/tag/v1.515.0",
                error = null,
            )
        assertEquals(UpdateOutcome.UPDATE_AVAILABLE, result.outcome)
        assertEquals("1.515.0", result.latestVersionName)
        assertEquals(1_515_000, result.latestVersionCode)
        assertNull(result.error)
    }

    @Test
    fun `result with error`() {
        val result =
            UpdateCheckResult(
                outcome = UpdateOutcome.FAILED,
                latestVersionName = null,
                latestVersionCode = null,
                releaseUrl = null,
                error = UpdateCheckError.NO_INTERNET,
            )
        assertEquals(UpdateOutcome.FAILED, result.outcome)
        assertNull(result.latestVersionName)
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
        assertEquals(6, UpdateOutcome.entries.size)
    }

    /** `major.minor.patch` packed the way the build packs it, so a fixture cannot name one version and compare another. */
    private fun packed(version: String): Int {
        val (major, minor, patch) = version.split('.').map { it.toInt() }
        return major * 1_000_000 + minor * 1_000 + patch
    }

    /** One release as the parser reports it: the version from its tag plus whatever its assets selected. */
    private fun release(
        version: String,
        apk: String? = null,
        sha: String? = null,
        typeMismatch: Boolean = false,
    ) = ReleaseStatus(
        versionName = version,
        versionCode = packed(version),
        releaseUrl = "https://example.invalid/releases/v$version",
        apkDownloadUrl = apk,
        apkSha256Url = sha,
        typeMismatch = typeMismatch,
    )
}
