//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.updates

import io.payanam.shared.updates.ChannelStatus
import io.payanam.shared.updates.UpdateChannel
import io.payanam.shared.updates.UpdateCheckResult
import io.payanam.shared.updates.UpdateOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The update channel and the result shape, now shared with every platform that shows updates.
 *
 * The parsing rule is the load-bearing part: an unknown or absent stored value falls back to DEV, and
 * the stored value is the enum NAME. Both facts are wire-compatible with what the app wrote before the
 * move, so a row written by the Android build parses the same way here.
 */
class UpdateChannelContractTest {
    @Test
    fun `each channel's tag suffix is the token the release tags use`() {
        assertEquals("dev", UpdateChannel.DEV.tagSuffix)
        assertEquals("beta", UpdateChannel.BETA.tagSuffix)
        assertEquals("stable", UpdateChannel.STABLE.tagSuffix)
    }

    @Test
    fun `fromStorage reads back every channel by name`() {
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage("DEV"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromStorage("BETA"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromStorage("STABLE"))
    }

    @Test
    fun `fromStorage falls back to DEV for absent, unknown and tag-style values`() {
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage(null))
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage(""))
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage("nightly"))
        // The tag suffix is not the stored value: the row holds the enum name.
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStorage("stable"))
    }

    @Test
    fun `a result defaults to no channel statuses and to the moment it was built`() {
        val before = System.currentTimeMillis()
        val result =
            UpdateCheckResult(
                outcome = UpdateOutcome.UP_TO_DATE,
                latestBuildNumber = 1,
                releaseUrl = null,
                error = null,
            )
        assertEquals(emptyList<ChannelStatus>(), result.channelStatuses)
        assertNull(result.error)
        assertEquals(true, result.checkedAtMs >= before)
    }

    @Test
    fun `a channel status fails closed by defaulting its asset urls to absent`() {
        val status = ChannelStatus(channel = UpdateChannel.STABLE, buildNumber = 7, releaseUrl = "u")
        assertNull(status.apkDownloadUrl)
        assertNull(status.apkSha256Url)
        assertEquals(false, status.typeMismatch)
    }
}
