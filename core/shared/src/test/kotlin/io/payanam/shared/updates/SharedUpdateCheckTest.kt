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
    private fun status(
        channel: UpdateChannel = UpdateChannel.DEV,
        buildNumber: Int? = 120,
        typeMismatch: Boolean = false,
    ) = ChannelStatus(
        channel = channel,
        buildNumber = buildNumber,
        releaseUrl = "https://example.invalid/release",
        typeMismatch = typeMismatch,
    )

    @Test
    fun `one channel state, one three-way answer - up to date`() {
        val result =
            resolveUpdateResult(
                currentBuildNumber = 120,
                channel = UpdateChannel.DEV,
                statuses = listOf(status(buildNumber = 120)),
            )

        assertThat(result.outcome).isEqualTo(UpdateOutcome.UP_TO_DATE)
        assertThat(result.latestBuildNumber).isEqualTo(120)
    }

    @Test
    fun `one channel state, one three-way answer - update available`() {
        val result =
            resolveUpdateResult(
                currentBuildNumber = 120,
                channel = UpdateChannel.DEV,
                statuses = listOf(status(buildNumber = 121)),
            )

        assertThat(result.outcome).isEqualTo(UpdateOutcome.UPDATE_AVAILABLE)
        assertThat(result.latestBuildNumber).isEqualTo(121)
    }

    @Test
    fun `one channel state, one three-way answer - cannot tell`() {
        // The page cap was hit before the channel was seen: the answer must be the explicit
        // non-success, which is the vocabulary's "Inconclusive" - never a verdict by absence.
        val result =
            resolveUpdateResult(
                currentBuildNumber = 120,
                channel = UpdateChannel.DEV,
                statuses = emptyList(),
                scanComplete = false,
            )

        assertThat(result.outcome).isEqualTo(UpdateOutcome.INDETERMINATE)
    }

    @Test
    fun `the fail-closed rule holds for every absence - never up to date by accident`() {
        // A completed scan that did not see the channel.
        assertThat(
            resolveUpdateResult(120, UpdateChannel.DEV, emptyList(), scanComplete = true).outcome,
        ).isEqualTo(UpdateOutcome.NO_RELEASE_ON_CHANNEL)

        // A found release with no parseable build number.
        assertThat(
            resolveUpdateResult(120, UpdateChannel.DEV, listOf(status(buildNumber = null))).outcome,
        ).isEqualTo(UpdateOutcome.RELEASE_UNREADABLE)

        // A release that ships nothing this install's type can use.
        assertThat(
            resolveUpdateResult(120, UpdateChannel.DEV, listOf(status(typeMismatch = true))).outcome,
        ).isEqualTo(UpdateOutcome.TYPE_MISMATCH)

        // None of the three may ever be reported as UP_TO_DATE: the whole point of the split.
        assertThat(
            resolveUpdateResult(120, UpdateChannel.DEV, emptyList(), scanComplete = true).outcome,
        ).isNotEqualTo(UpdateOutcome.UP_TO_DATE)
    }

    @Test
    fun `the selected channel is the one compared, not whichever was seen first`() {
        val result =
            resolveUpdateResult(
                currentBuildNumber = 120,
                channel = UpdateChannel.STABLE,
                statuses =
                    listOf(
                        status(channel = UpdateChannel.DEV, buildNumber = 500),
                        status(channel = UpdateChannel.STABLE, buildNumber = 119),
                    ),
            )

        assertThat(result.outcome).isEqualTo(UpdateOutcome.UP_TO_DATE)
        assertThat(result.latestBuildNumber).isEqualTo(119)
    }

    @Test
    fun `FAILED is a distinct outcome, not a synonym for any other`() {
        // FAILED is the only outcome that carries an error reason; conflating it with
        // INDETERMINATE or NO_RELEASE_ON_CHANNEL would let a failed check render as
        // "could not tell" or "no release", hiding the real problem from the user.
        val failed = UpdateOutcome.FAILED
        assertThat(failed).isNotEqualTo(UpdateOutcome.UPDATE_AVAILABLE)
        assertThat(failed).isNotEqualTo(UpdateOutcome.UP_TO_DATE)
        assertThat(failed).isNotEqualTo(UpdateOutcome.TYPE_MISMATCH)
        assertThat(failed).isNotEqualTo(UpdateOutcome.NO_RELEASE_ON_CHANNEL)
        assertThat(failed).isNotEqualTo(UpdateOutcome.RELEASE_UNREADABLE)
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
    fun `ChannelStatus carries the APK download and checksum URLs`() {
        // The download URL is what the installer fetches; the checksum URL is what verifies it.
        // A status that loses either would ship an unverifiable update.
        val status =
            ChannelStatus(
                channel = UpdateChannel.DEV,
                buildNumber = 121,
                releaseUrl = "https://example.invalid/release",
                apkDownloadUrl = "https://example.invalid/app.apk",
                apkSha256Url = "https://example.invalid/app.apk.sha256",
            )
        assertThat(status.apkDownloadUrl).isEqualTo("https://example.invalid/app.apk")
        assertThat(status.apkSha256Url).isEqualTo("https://example.invalid/app.apk.sha256")
    }

    @Test
    fun `ChannelStatus defaults APK URLs to null when the release ships no APK`() {
        val status =
            ChannelStatus(
                channel = UpdateChannel.DEV,
                buildNumber = 121,
                releaseUrl = "https://example.invalid/release",
            )
        assertThat(status.apkDownloadUrl).isNull()
        assertThat(status.apkSha256Url).isNull()
    }

    @Test
    fun `UpdateCheckResult carries the error reason when the outcome is FAILED`() {
        val result =
            UpdateCheckResult(
                outcome = UpdateOutcome.FAILED,
                latestBuildNumber = null,
                releaseUrl = null,
                error = UpdateCheckError.NO_INTERNET,
            )
        assertThat(result.error).isEqualTo(UpdateCheckError.NO_INTERNET)
        assertThat(result.outcome).isEqualTo(UpdateOutcome.FAILED)
    }

    @Test
    fun `UpdateCheckResult carries the per-channel statuses it was built from`() {
        val statuses =
            listOf(
                ChannelStatus(
                    channel = UpdateChannel.DEV,
                    buildNumber = 121,
                    releaseUrl = "https://example.invalid/dev",
                ),
                ChannelStatus(
                    channel = UpdateChannel.STABLE,
                    buildNumber = 120,
                    releaseUrl = "https://example.invalid/stable",
                ),
            )
        val result =
            UpdateCheckResult(
                outcome = UpdateOutcome.UPDATE_AVAILABLE,
                latestBuildNumber = 121,
                releaseUrl = "https://example.invalid/dev",
                error = null,
                channelStatuses = statuses,
            )
        assertThat(result.channelStatuses).hasSize(2)
        assertThat(result.channelStatuses[0].channel).isEqualTo(UpdateChannel.DEV)
        assertThat(result.channelStatuses[1].channel).isEqualTo(UpdateChannel.STABLE)
    }

    @Test
    fun `UpdateCheckResult carries the timestamp it was produced at`() {
        val before = System.currentTimeMillis()
        val result =
            UpdateCheckResult(
                outcome = UpdateOutcome.UP_TO_DATE,
                latestBuildNumber = 120,
                releaseUrl = null,
                error = null,
            )
        val after = System.currentTimeMillis()
        assertThat(result.checkedAtMs).isAtLeast(before)
        assertThat(result.checkedAtMs).isAtMost(after)
    }

    @Test
    fun `resolveUpdateResult propagates the release URL from the selected channel`() {
        val result =
            resolveUpdateResult(
                currentBuildNumber = 120,
                channel = UpdateChannel.DEV,
                statuses =
                    listOf(
                        status(buildNumber = 121).copy(releaseUrl = "https://example.invalid/r/121"),
                    ),
            )
        assertThat(result.releaseUrl).isEqualTo("https://example.invalid/r/121")
    }

    @Test
    fun `resolveUpdateResult propagates all channel statuses, not just the selected one`() {
        val statuses =
            listOf(
                status(channel = UpdateChannel.DEV, buildNumber = 121),
                status(channel = UpdateChannel.STABLE, buildNumber = 120),
            )
        val result =
            resolveUpdateResult(
                currentBuildNumber = 120,
                channel = UpdateChannel.DEV,
                statuses = statuses,
            )
        assertThat(result.channelStatuses).hasSize(2)
        assertThat(result.channelStatuses.map { it.channel })
            .containsExactly(UpdateChannel.DEV, UpdateChannel.STABLE)
    }

    @Test
    fun `resolveUpdateResult sets latestBuildNumber to null when the release has no parseable build`() {
        val result =
            resolveUpdateResult(
                currentBuildNumber = 120,
                channel = UpdateChannel.DEV,
                statuses = listOf(status(buildNumber = null)),
            )
        assertThat(result.latestBuildNumber).isNull()
        assertThat(result.outcome).isEqualTo(UpdateOutcome.RELEASE_UNREADABLE)
    }

    @Test
    fun `UpdateChannel fromStorage parses every known channel name`() {
        assertThat(UpdateChannel.fromStorage("DEV")).isEqualTo(UpdateChannel.DEV)
        assertThat(UpdateChannel.fromStorage("BETA")).isEqualTo(UpdateChannel.BETA)
        assertThat(UpdateChannel.fromStorage("STABLE")).isEqualTo(UpdateChannel.STABLE)
    }

    @Test
    fun `UpdateChannel fromStorage falls back to DEV for unknown or null values`() {
        // A stored value from a removed channel or a corrupted preference must not crash the parser;
        // it falls back to the most conservative channel (dev) rather than crashing or returning null.
        assertThat(UpdateChannel.fromStorage("GAMMA")).isEqualTo(UpdateChannel.DEV)
        assertThat(UpdateChannel.fromStorage("")).isEqualTo(UpdateChannel.DEV)
        assertThat(UpdateChannel.fromStorage(null)).isEqualTo(UpdateChannel.DEV)
    }

    @Test
    fun `UpdateChannel carries the tag suffix the release publisher writes`() {
        // The tag suffix is what the GitHub release tag is built from: "{tagSuffix}-v{buildNumber}".
        // A wrong suffix would make the parser look for tags that do not exist.
        assertThat(UpdateChannel.DEV.tagSuffix).isEqualTo("dev")
        assertThat(UpdateChannel.BETA.tagSuffix).isEqualTo("beta")
        assertThat(UpdateChannel.STABLE.tagSuffix).isEqualTo("stable")
    }
}
