//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.BuildConfig
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.platform.ApkUpdateSource
import io.payanam.shared.platform.InstallHandoff
import io.payanam.shared.platform.InstallRefusal
import io.payanam.shared.platform.SupportState
import io.payanam.shared.platform.UpdateAvailability
import io.payanam.shared.platform.UpdateDownload
import io.payanam.shared.updates.ChannelStatus
import io.payanam.shared.updates.UpdateChannel
import io.payanam.shared.updates.UpdateCheckError
import io.payanam.shared.updates.UpdateCheckResult
import io.payanam.shared.updates.UpdateOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Android's answer to the update-source port, asserted as a set of honesty properties.
 *
 * This platform CAN update itself, so the risk runs the other way from a platform that cannot: the two
 * quiet lies available here are telling a store-managed build that it may replace itself, and turning any
 * check that failed to conclude into "you have the latest build". Both are asserted here, and the second
 * one is asserted per-outcome rather than as a single case, because five different reasons a verdict is
 * unavailable must all stay off "up to date" for the same reason.
 *
 * Every answer here is produced by the class under test through its own collaborators. The channel read is
 * scripted through the constructor's own check function, so no network is involved and the mapping under
 * test is the mapping that runs in production. The store-managed build is reached through the constructor's
 * store flag rather than through [FdroidBuildCheck], which reports a fact about the build itself and cannot
 * be provoked from a test; everything downstream of that one fact is still this class's own answer.
 *
 * The install refusals are reached by the real handoff, not by asserting on the source's internals: a path
 * that does not exist is refused as a missing artifact before any URI is built, and a real file this source
 * never verified is refused as an unverified checksum. Nothing is stubbed to fail.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidApkUpdateSourceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A newer build this app could genuinely fetch: a real build number and a real asset URL. */
    private val fetchableStatuses =
        listOf(
            ChannelStatus(
                channel = UpdateChannel.DEV,
                buildNumber = NEWER_BUILD,
                releaseUrl = "https://example.invalid/releases/next",
                apkDownloadUrl = "https://example.invalid/releases/next/Payanam.apk",
            ),
        )

    /** Builds this port with a scripted channel read, so no network is involved. */
    private fun sourceReporting(
        result: UpdateCheckResult,
        storeManaged: Boolean = false,
        channel: UpdateChannel = UpdateChannel.DEV,
    ): ApkUpdateSource = AndroidApkUpdateSource(
        context = context,
        channel = channel,
        isStoreManaged = storeManaged,
        checkForUpdates = { _, _ -> result },
    )

    @Before
    fun setUp() {
        // The source and the download manager both read the process logger when they are constructed; the
        // tests install it exactly as the app's startup path does.
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    fun `a store-managed build declines rather than claiming it can update itself`() {
        // DECLINES, not NOT_APPLICABLE: the self-install concept exists perfectly well on this platform and
        // this build is simply not allowed to use it. NOT_APPLICABLE would also bury the real reason,
        // sending a reader to look for a missing feature instead of the client that installed the app.
        assertThat(sourceReporting(upToDate(), storeManaged = true).state)
            .isEqualTo(SupportState.DECLINES)
    }

    @Test
    fun `a normal build reports that it can install a newer build itself`() {
        assertThat(sourceReporting(upToDate()).state).isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `the reported build number is the version code this build was stamped with`() {
        // The number release tags are compared against, read from the generated build config rather than
        // restated as a constant: a hand-copied number here would keep passing after a version bump.
        assertThat(sourceReporting(upToDate()).currentBuildNumber).isEqualTo(BuildConfig.VERSION_CODE)
    }

    @Test
    fun `a channel read that establishes nothing is newer answers up to date`() {
        runTest {
            val source = sourceReporting(upToDate())

            assertThat(source.isNewerBuildAvailable()).isEqualTo(UpdateAvailability.UpToDate)
        }
    }

    @Test
    fun `a newer release with a fetchable artifact answers newer and installable`() {
        runTest {
            val source = sourceReporting(availableUpdate())

            assertThat(source.isNewerBuildAvailable())
                .isEqualTo(UpdateAvailability.Newer(buildNumber = NEWER_BUILD, installable = true))
        }
    }

    @Test
    fun `every outcome that could not conclude answers inconclusive and never up to date`() {
        // THE property this platform owns. Each entry is a distinct reason no verdict was reached, and
        // rendering any of them as "up to date" tells a user their build is current when nothing established
        // it. UP_TO_DATE is deliberately absent from this list - it is asserted separately above, as the one
        // outcome that genuinely was read.
        val unavailable =
            listOf(
                UpdateOutcome.TYPE_MISMATCH,
                UpdateOutcome.NO_RELEASE_ON_CHANNEL,
                UpdateOutcome.RELEASE_UNREADABLE,
                UpdateOutcome.INDETERMINATE,
                UpdateOutcome.FAILED,
            )

        runTest {
            unavailable.forEach { outcome ->
                val source = sourceReporting(couldNotConclude(outcome))

                val answer = source.isNewerBuildAvailable()

                assertThat(answer).isEqualTo(UpdateAvailability.Inconclusive)
                assertThat(answer).isNotEqualTo(UpdateAvailability.UpToDate)
            }
        }
    }

    @Test
    fun `a verdict with nothing to fetch behind it is not reported as an available update`() {
        // An available-update verdict that carries no artifact would hand shared code an installable build
        // this platform cannot produce a file for, so it falls back with the check.
        val source = sourceReporting(verdictWithNoArtifact())

        runTest {
            assertThat(source.isNewerBuildAvailable()).isEqualTo(UpdateAvailability.Inconclusive)
        }
    }

    @Test
    fun `a check that throws is treated as could-not-conclude rather than a verdict`() {
        // An exception is not an answer. A thrown check must leave the caller with the honest third answer
        // rather than an error it has no vocabulary for.
        val source =
            AndroidApkUpdateSource(
                context = context,
                isStoreManaged = false,
                checkForUpdates = { _, _ -> throw IllegalStateException("channel unreachable") },
            )

        runTest {
            assertThat(source.isNewerBuildAvailable()).isEqualTo(UpdateAvailability.Inconclusive)
        }
    }

    @Test
    fun `a store-managed build still learns a newer release exists but never calls it installable`() {
        // The two answers must agree. `state` says this build may not replace itself, so reporting the
        // release as installable would have shared UI offer a button this source then refuses - the two
        // would contradict each other on the same question.
        val source = sourceReporting(availableUpdate(), storeManaged = true)

        runTest {
            val answer = source.isNewerBuildAvailable()

            assertThat(answer)
                .isEqualTo(UpdateAvailability.Newer(buildNumber = NEWER_BUILD, installable = false))
            assertThat(source.state).isEqualTo(SupportState.DECLINES)
        }
    }

    @Test
    fun `a store-managed build fetches no artifact even when one is available`() {
        // The client that installed this app answers for its updates; an app that fetched one could only use
        // it illegitimately. The answer is no artifact, not a fetched one the caller may not install.
        runTest {
            val source = sourceReporting(availableUpdate(), storeManaged = true)
            source.isNewerBuildAvailable()

            val download = source.downloadNewerBuild(UpdateAvailability.Newer(NEWER_BUILD, installable = true))

            assertThat(download).isNull()
        }
    }

    @Test
    fun `an availability answer that names nothing to fetch is refused rather than acted on`() {
        runTest {
            val source = sourceReporting(availableUpdate())

            assertThat(source.downloadNewerBuild(UpdateAvailability.Inconclusive)).isNull()
        }
    }

    @Test
    fun `an artifact that is gone is refused as the file being missing`() {
        // FILE_MISSING and not NO_INSTALLER or a generic failure: the artifact IS gone, and that is the
        // precise reason, so a user whose file disappeared between the fetch and the tap is told exactly
        // that rather than being sent to an installer that could never have opened it.
        val source = sourceReporting(upToDate())
        val vanished = File(context.getExternalFilesDir(null), "never-downloaded.apk")
        assertThat(vanished.exists()).isFalse()

        val handoff =
            source.startInstall(UpdateDownload(buildNumber = NEWER_BUILD, platformHandle = vanished.absolutePath))

        assertThat(handoff).isEqualTo(InstallHandoff.Refused(InstallRefusal.FILE_MISSING))
    }

    @Test
    fun `a real file this source never verified is refused as an unverified checksum`() {
        // The file really is present, so the missing-file branch is not what answers here. What refuses it
        // is provenance: this source hands over only an artifact it verified when it fetched it, and a
        // `UpdateDownload` built by hand names no such artifact. Refusing with its own reason keeps this
        // apart from NO_INSTALLER, which would claim the device has nothing that could open the file.
        val source = sourceReporting(upToDate())
        val unpublished = context.filesDir.resolve("Payanam_Android_${NEWER_BUILD}_debug_test.apk")
        unpublished.writeBytes(ByteArray(1))
        assertThat(unpublished.exists()).isTrue()

        val handoff =
            source.startInstall(UpdateDownload(buildNumber = NEWER_BUILD, platformHandle = unpublished.absolutePath))

        assertThat(handoff).isEqualTo(InstallHandoff.Refused(InstallRefusal.CHECKSUM_UNVERIFIED))
    }

    @Test
    fun `a store-managed build refuses the self-install handoff as not applicable to it`() {
        val source = sourceReporting(upToDate(), storeManaged = true)

        val handoff =
            source.startInstall(
                UpdateDownload(buildNumber = NEWER_BUILD, platformHandle = presentApk().absolutePath),
            )

        assertThat(handoff).isEqualTo(InstallHandoff.Refused(InstallRefusal.PLATFORM_NOT_APPLICABLE))
    }

    @Test
    fun `a handoff for an artifact that was never fetched is refused as no download`() {
        val source = sourceReporting(upToDate())

        val handoff = source.startInstall(UpdateDownload(buildNumber = NEWER_BUILD, platformHandle = ""))

        assertThat(handoff).isEqualTo(InstallHandoff.Refused(InstallRefusal.NO_DOWNLOAD))
    }

    /**
     * A channel read whose verdict was actually established: the release was found and is not newer.
     *
     * Built from the real per-channel status shape rather than a bare enum, so the mapping is exercised with
     * the same data a genuine check produces.
     */
    private fun upToDate(): UpdateCheckResult = UpdateCheckResult(
        outcome = UpdateOutcome.UP_TO_DATE,
        latestBuildNumber = BuildConfig.VERSION_CODE,
        releaseUrl = "https://example.invalid/releases/current",
        error = null,
        channelStatuses = fetchableStatuses,
    )

    /** A channel read that found a newer release carrying a downloadable artifact. */
    private fun availableUpdate(): UpdateCheckResult = UpdateCheckResult(
        outcome = UpdateOutcome.UPDATE_AVAILABLE,
        latestBuildNumber = NEWER_BUILD,
        releaseUrl = "https://example.invalid/releases/next",
        error = null,
        channelStatuses = fetchableStatuses,
    )

    /**
     * A channel read that ended on [outcome] without reaching a verdict.
     *
     * No statuses and no build number: the shape a check produces when the channel could not be read to a
     * conclusion, which is precisely the case that must never be reported as an available update.
     */
    private fun couldNotConclude(outcome: UpdateOutcome): UpdateCheckResult = UpdateCheckResult(
        outcome = outcome,
        latestBuildNumber = null,
        releaseUrl = null,
        error = if (outcome == UpdateOutcome.FAILED) UpdateCheckError.NO_INTERNET else null,
    )

    /**
     * An available-update verdict whose release ships no artifact.
     *
     * The gap this covers is real: a release can be newer and carry no APK of this install's build type, and
     * the checker reports the verdict anyway with the asset selection held closed.
     */
    private fun verdictWithNoArtifact(): UpdateCheckResult = UpdateCheckResult(
        outcome = UpdateOutcome.UPDATE_AVAILABLE,
        latestBuildNumber = NEWER_BUILD,
        releaseUrl = "https://example.invalid/releases/next",
        error = null,
        channelStatuses =
        listOf(
            ChannelStatus(
                channel = UpdateChannel.DEV,
                buildNumber = NEWER_BUILD,
                releaseUrl = "https://example.invalid/releases/next",
                apkDownloadUrl = null,
                typeMismatch = true,
            ),
        ),
    )

    /**
     * A real, non-empty file inside the directory this app publishes to the installer.
     *
     * It exists so a present-artifact branch is genuinely reached: an absent path would be refused earlier
     * as [InstallRefusal.FILE_MISSING] and never get as far as the refusal under test.
     */
    private fun presentApk(): File {
        val directory = context.getExternalFilesDir(null)?.resolve(AutoDownloadManager.SUBDIR)
        requireNotNull(directory) { "the app-private external files dir is unavailable under Robolectric" }
        directory.mkdirs()
        return File(directory, "Payanam_Android_${NEWER_BUILD}_debug_test.apk").apply {
            writeBytes(ByteArray(1))
        }
    }

    private companion object {
        /** A build strictly newer than this one, so it can never be confused with the installed number. */
        const val NEWER_BUILD = 9_999
    }
}
