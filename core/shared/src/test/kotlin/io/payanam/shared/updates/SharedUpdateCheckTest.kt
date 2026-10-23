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
}
