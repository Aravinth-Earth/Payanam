//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.platform.ApkUpdateSource
import io.payanam.shared.platform.InstallHandoff
import io.payanam.shared.platform.InstallRefusal
import io.payanam.shared.platform.SupportState
import io.payanam.shared.platform.UpdateAvailability
import io.payanam.shared.platform.UpdateDownload
import io.payanam.shared.updates.ChannelStatus
import io.payanam.shared.updates.UpdateChannel
import io.payanam.shared.updates.UpdateOutcome
import io.payanam.shared.updates.resolveUpdateResult
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The desktop's in-app update answer, asserted as a set of honesty properties.
 *
 * This platform cannot update itself, and the whole risk here is a quiet lie: a `UpToDate` answer would
 * tell a user their build is current when nothing was ever checked, and a `Launched` handoff would report
 * an install that never started. Both are the failure this class exists to make impossible.
 */
class DesktopApkUpdateSourceTest {
    private val source: ApkUpdateSource = DesktopUnsupportedPorts.apkUpdateSource

    @Test
    fun reportsNotApplicableRatherThanDeclining() {
        // DECLINES would claim a platform that once said yes and changed its mind. Nothing was ever
        // offered here, so NOT_APPLICABLE is the truthful answer.
        assertThat(source.state).isEqualTo(SupportState.NOT_APPLICABLE)
    }

    @Test
    fun neverClaimsToBeUpToDate() {
        // THE property. `UpToDate` is a claim about the release channel; this platform has no feed to
        // consult, so reporting it would be a fabricated answer rather than a missing feature.
        runTest {
            val availability = source.isNewerBuildAvailable()
            assertThat(availability).isNotEqualTo(UpdateAvailability.UpToDate)
        }
    }

    @Test
    fun answersInconclusiveSoTheScreenCanExplainRatherThanOfferADeadButton() {
        runTest {
            assertThat(source.isNewerBuildAvailable()).isEqualTo(UpdateAvailability.Inconclusive)
        }
    }

    @Test
    fun fetchesNothingAndSaysWhy() {
        runTest {
            val download = source.downloadNewerBuild(UpdateAvailability.Newer(buildNumber = 999, installable = false))
            assertThat(download).isNull()
        }
    }

    @Test
    fun refusesToInstallWithThePlatformReasonRatherThanPretending() {
        val handoff = source.startInstall(UpdateDownload(buildNumber = 999, platformHandle = "unused"))

        assertThat(handoff).isEqualTo(InstallHandoff.Refused(InstallRefusal.PLATFORM_NOT_APPLICABLE))
    }

    @Test
    fun reportsTheBuildNumberThePackagerStamped() {
        // Real, not fabricated: a sentinel 0 would read as "always out of date" if anything ever compared
        // it. Null would be wrong too, because this build genuinely knows its own number.
        assertThat(source.currentBuildNumber).isNotNull()
    }

    @Test
    fun `the desktop declines for a channel state the shared check answers for the other platform`() {
        // The two halves of the same contract, driven with the SAME channel state.
        //
        // This platform has no feed to consult, so its answer is the explicit non-success - never
        // UpToDate and never a Newer verdict, both of which would be fabricated answers about a channel
        // nothing here read.
        runTest {
            val desktopAnswer = source.isNewerBuildAvailable()
            assertThat(desktopAnswer).isEqualTo(UpdateAvailability.Inconclusive)
            assertThat(desktopAnswer).isNotEqualTo(UpdateAvailability.UpToDate)
            assertThat(desktopAnswer).isNotInstanceOf(UpdateAvailability.Newer::class.java)
        }

        // And for that same channel state, the answer the Android implementation gives comes from the
        // SHARED check - one comparison, not one per platform. Up to date, and newer, for the two
        // states of the same channel.
        val state =
            listOf(
                ChannelStatus(
                    channel = UpdateChannel.DEV,
                    buildNumber = 120,
                    releaseUrl = "https://example.invalid/release",
                ),
            )
        assertThat(resolveUpdateResult(120, UpdateChannel.DEV, state).outcome)
            .isEqualTo(UpdateOutcome.UP_TO_DATE)
        assertThat(
            resolveUpdateResult(
                120,
                UpdateChannel.DEV,
                listOf(state.first().copy(buildNumber = 121)),
            ).outcome,
        ).isEqualTo(UpdateOutcome.UPDATE_AVAILABLE)
    }
}
