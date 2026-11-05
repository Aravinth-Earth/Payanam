//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.updates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The shared release CHECK's three-way answer, and why it is one rule rather than one per platform.
 *
 * WHY THIS FILE EXISTS: the comparison used to live in the Android settings feature, where no desktop
 * code could reach it - a second copy was the only alternative, and two copies of "is this newer" are
 * two answers waiting to disagree. The check now resolves here, and this test pins the vocabulary both
 * platforms route on: a verdict is positive, proven-neutral, or explicitly non-success, and an absent
 * or truncated scan can NEVER come out as "up to date".
 *
 * The three states the gate names are all here, driven through the SAME function the Android source
 * calls (via its checker) and the same vocabulary the desktop's honest-decline answer uses
 * (`UpdateAvailability.Inconclusive` mirrors `INDETERMINATE`): up to date, update available, and
 * cannot-tell.
 */
class SharedUpdateCheckTest {
    private fun release(
        version: String = "1.2.0",
        typeMismatch: Boolean = false,
        apk: String? = null,
        sha: String? = null,
        releaseUrl: String = "https://example.invalid/release",
    ) = ReleaseStatus(
        versionName = version,
        versionCode = packed(version),
        releaseUrl = releaseUrl,
        apkDownloadUrl = apk,
        apkSha256Url = sha,
        typeMismatch = typeMismatch,
    )

    @Test
    fun `one release state, one three-way answer - up to date`() {
        val result = resolveUpdateResult(installedVersionCode = 1_002_000, releases = listOf(release("1.2.0")))

        assertThat(result.outcome).isEqualTo(UpdateOutcome.UP_TO_DATE)
        assertThat(result.latestVersionName).isEqualTo("1.2.0")
        assertThat(result.latestVersionCode).isEqualTo(1_002_000)
    }

    @Test
    fun `one release state, one three-way answer - update available`() {
        val result = resolveUpdateResult(installedVersionCode = 1_002_000, releases = listOf(release("1.2.1")))

        assertThat(result.outcome).isEqualTo(UpdateOutcome.UPDATE_AVAILABLE)
        assertThat(result.latestVersionName).isEqualTo("1.2.1")
        assertThat(result.latestVersionCode).isEqualTo(1_002_001)
    }

    @Test
    fun `one release state, one three-way answer - cannot tell`() {
        // The page cap was hit before any release was seen: the answer must be the explicit non-success,
        // which is the vocabulary's "Inconclusive" - never a verdict by absence.
        val result = resolveUpdateResult(installedVersionCode = 1_002_000, releases = emptyList(), scanComplete = false)

        assertThat(result.outcome).isEqualTo(UpdateOutcome.INDETERMINATE)
    }

    @Test
    fun `the fail-closed rule holds for every absence - never up to date by accident`() {
        // A completed scan that saw no release carrying a version.
        val noRelease = resolveUpdateResult(1_002_000, emptyList(), scanComplete = true)
        assertThat(noRelease.outcome).isEqualTo(UpdateOutcome.NO_RELEASE)

        // A newer release that ships nothing this install's type can use.
        val mismatch = resolveUpdateResult(1_002_000, listOf(release("1.3.0", typeMismatch = true)))
        assertThat(mismatch.outcome).isEqualTo(UpdateOutcome.TYPE_MISMATCH)

        // A truncated scan.
        val indeterminate = resolveUpdateResult(1_002_000, emptyList(), scanComplete = false)
        assertThat(indeterminate.outcome).isEqualTo(UpdateOutcome.INDETERMINATE)

        // None of the three may ever be reported as UP_TO_DATE: the whole point of the split.
        listOf(noRelease, mismatch, indeterminate).forEach { result ->
            assertThat(result.outcome).isNotEqualTo(UpdateOutcome.UP_TO_DATE)
        }
    }

    @Test
    fun `an install at or above the newest release is up to date, however new that release is`() {
        // One-directional: a build ahead of the newest published release has nothing to install, so a
        // downgrade is never reported as an update.
        assertThat(resolveUpdateResult(1_002_001, listOf(release("1.2.0"))).outcome).isEqualTo(UpdateOutcome.UP_TO_DATE)
        assertThat(resolveUpdateResult(2_000_000, listOf(release("1.2.0", typeMismatch = true))).outcome)
            .isEqualTo(UpdateOutcome.UP_TO_DATE)
    }

    @Test
    fun `the newest release is the one compared, not whichever was seen first`() {
        val result =
            resolveUpdateResult(
                installedVersionCode = 1_002_000,
                releases = listOf(release("0.5.0"), release("0.4.0")),
            )

        assertThat(result.outcome).isEqualTo(UpdateOutcome.UP_TO_DATE)
        assertThat(result.latestVersionCode).isEqualTo(5_000)
    }

    @Test
    fun `order does not decide the newest release`() {
        val versions = listOf("1.0.1", "1.0.3", "1.0.2")

        val forwards = resolveUpdateResult(1_000_000, versions.map { release(it) })
        val backwards = resolveUpdateResult(1_000_000, versions.reversed().map { release(it) })

        assertThat(forwards.latestVersionName).isEqualTo("1.0.3")
        assertThat(backwards.latestVersionName).isEqualTo("1.0.3")
        assertThat(backwards.outcome).isEqualTo(forwards.outcome)
    }

    @Test
    fun `the comparison reads the packed code, not the version name`() {
        // Ordered as text, "0.9.0" sorts above "0.10.0"; ordered as packed codes it does not. The verdict must
        // follow the code, because that is what the platform installer compares.
        val result = resolveUpdateResult(9_000, listOf(release("0.9.0"), release("0.10.0")))

        assertThat(result.latestVersionName).isEqualTo("0.10.0")
        assertThat(result.outcome).isEqualTo(UpdateOutcome.UPDATE_AVAILABLE)
    }

    @Test
    fun `a newer release with no artifact does not fall back to an older installable one`() {
        // 1.3.0 ships nothing this install can use while 1.2.1 does. The verdict stays the mismatch: offering
        // the older release would install a version the project has already superseded.
        val result =
            resolveUpdateResult(
                1_002_000,
                listOf(release("1.2.1", apk = "https://example.invalid/old.apk"), release("1.3.0", typeMismatch = true)),
            )

        assertThat(result.outcome).isEqualTo(UpdateOutcome.TYPE_MISMATCH)
        assertThat(result.latestVersionName).isEqualTo("1.3.0")
        assertThat(result.apkDownloadUrl).isNull()
    }

    @Test
    fun `a mismatch on an older release does not affect the newest one`() {
        val result =
            resolveUpdateResult(
                1_002_000,
                listOf(release("1.2.1", typeMismatch = true), release("1.3.0", apk = "https://example.invalid/new.apk")),
            )

        assertThat(result.outcome).isEqualTo(UpdateOutcome.UPDATE_AVAILABLE)
        assertThat(result.latestVersionName).isEqualTo("1.3.0")
        assertThat(result.apkDownloadUrl).isEqualTo("https://example.invalid/new.apk")
        assertThat(result.typeMismatch).isFalse()
    }

    @Test
    fun `FAILED is a distinct outcome, not a synonym for any other`() {
        // FAILED is the only outcome that carries an error reason; conflating it with
        // INDETERMINATE or NO_RELEASE would let a failed check render as
        // "could not tell" or "no release", hiding the real problem from the user.
        val failed = UpdateOutcome.FAILED
        assertThat(failed).isNotEqualTo(UpdateOutcome.UPDATE_AVAILABLE)
        assertThat(failed).isNotEqualTo(UpdateOutcome.UP_TO_DATE)
        assertThat(failed).isNotEqualTo(UpdateOutcome.TYPE_MISMATCH)
        assertThat(failed).isNotEqualTo(UpdateOutcome.NO_RELEASE)
        assertThat(failed).isNotEqualTo(UpdateOutcome.INDETERMINATE)
    }

    @Test
    fun `every UpdateOutcome entry is distinct`() {
        assertThat(UpdateOutcome.entries.toSet()).hasSize(UpdateOutcome.entries.size)
    }

    @Test
    fun `every UpdateCheckError reason is distinct`() {
        assertThat(UpdateCheckError.entries.toSet()).hasSize(UpdateCheckError.entries.size)
    }

    @Test
    fun `ReleaseStatus carries the APK download and checksum URLs`() {
        // The download URL is what the installer fetches; the checksum URL is what verifies it.
        // A status that loses either would ship an unverifiable update.
        val status =
            release(
                version = "1.2.1",
                apk = "https://example.invalid/app.apk",
                sha = "https://example.invalid/app.apk.sha256",
            )
        assertThat(status.apkDownloadUrl).isEqualTo("https://example.invalid/app.apk")
        assertThat(status.apkSha256Url).isEqualTo("https://example.invalid/app.apk.sha256")
        assertThat(status.typeMismatch).isFalse()
    }

    @Test
    fun `ReleaseStatus defaults the APK URLs to null when the release ships no APK`() {
        val status = release(version = "1.2.1")
        assertThat(status.apkDownloadUrl).isNull()
        assertThat(status.apkSha256Url).isNull()
    }

    @Test
    fun `UpdateCheckResult carries the error reason when the outcome is FAILED`() {
        val result =
            UpdateCheckResult(
                outcome = UpdateOutcome.FAILED,
                latestVersionName = null,
                latestVersionCode = null,
                releaseUrl = null,
                error = UpdateCheckError.NO_INTERNET,
            )
        assertThat(result.error).isEqualTo(UpdateCheckError.NO_INTERNET)
        assertThat(result.outcome).isEqualTo(UpdateOutcome.FAILED)
    }

    @Test
    fun `a resolved verdict carries no error reason`() {
        // The other half of the split: a verdict is not a failure, so the two fields can never both speak.
        assertThat(resolveUpdateResult(1_002_000, listOf(release("1.2.1"))).error).isNull()
        assertThat(resolveUpdateResult(1_002_000, emptyList()).error).isNull()
    }

    @Test
    fun `UpdateCheckResult carries the artifact URLs of the release it names`() {
        val result =
            resolveUpdateResult(
                1_002_000,
                listOf(
                    release("1.2.1", apk = "https://example.invalid/old.apk"),
                    release("1.3.0", apk = "https://example.invalid/new.apk", sha = "https://example.invalid/new.apk.sha256"),
                ),
            )
        assertThat(result.apkDownloadUrl).isEqualTo("https://example.invalid/new.apk")
        assertThat(result.apkSha256Url).isEqualTo("https://example.invalid/new.apk.sha256")
    }

    @Test
    fun `UpdateCheckResult carries the timestamp it was produced at`() {
        val before = System.currentTimeMillis()
        val result =
            UpdateCheckResult(
                outcome = UpdateOutcome.UP_TO_DATE,
                latestVersionName = "1.2.0",
                latestVersionCode = 1_002_000,
                releaseUrl = null,
                error = null,
            )
        val after = System.currentTimeMillis()
        assertThat(result.checkedAtMs).isAtLeast(before)
        assertThat(result.checkedAtMs).isAtMost(after)
    }

    @Test
    fun `resolveUpdateResult propagates the release URL of the newest release`() {
        val result =
            resolveUpdateResult(
                1_002_000,
                listOf(release("1.2.1", releaseUrl = "https://example.invalid/r/1.2.1")),
            )
        assertThat(result.releaseUrl).isEqualTo("https://example.invalid/r/1.2.1")
    }

    @Test
    fun `resolveUpdateResult names no release when the scan found none`() {
        val result = resolveUpdateResult(1_002_000, emptyList())
        assertThat(result.latestVersionName).isNull()
        assertThat(result.latestVersionCode).isNull()
        assertThat(result.releaseUrl).isNull()
        assertThat(result.outcome).isEqualTo(UpdateOutcome.NO_RELEASE)
    }

    /** `major.minor.patch` packed as `major * 1_000_000 + minor * 1_000 + patch`, the rule the build uses. */
    private fun packed(version: String): Int {
        val (major, minor, patch) = version.split('.').map { it.toInt() }
        return major * 1_000_000 + minor * 1_000 + patch
    }
}
