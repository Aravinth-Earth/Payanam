//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.DesktopBuildInfo
import io.payanam.shared.platform.ApkUpdateSource
import io.payanam.shared.platform.InstallHandoff
import io.payanam.shared.platform.InstallRefusal
import io.payanam.shared.platform.SupportState
import io.payanam.shared.platform.UpdateAvailability
import io.payanam.shared.platform.UpdateDownload
import io.payanam.shared.updates.ReleaseStatus
import io.payanam.shared.updates.UpdateCheckError
import io.payanam.shared.updates.UpdateCheckResult
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
 *
 * The release read is driven through `apkUpdateSource`'s own check seam rather than left to the default,
 * which reads the network: a verdict that depends on whether the machine running the tests is online is
 * not a verdict, and the mapping under test is the same one either way.
 */
class DesktopApkUpdateSourceTest {
    /** The production instance: no network is reached by anything asserted through this one. */
    private val source: ApkUpdateSource = DesktopUnsupportedPorts.apkUpdateSource

    /** The same mapping over a scripted release read. */
    private fun sourceReporting(result: UpdateCheckResult): ApkUpdateSource =
        DesktopUnsupportedPorts.apkUpdateSource(checkForUpdates = { _ -> result })

    @Test
    fun reportsNotApplicableRatherThanDeclining() {
        // DECLINES would claim a platform that once said yes and changed its mind. Nothing was ever
        // offered here, so NOT_APPLICABLE is the truthful answer.
        assertThat(source.state).isEqualTo(SupportState.NOT_APPLICABLE)
    }

    @Test
    fun neverClaimsToBeUpToDate() {
        // THE property. `UpToDate` is a claim about the release list; only a release list that was read to
        // a conclusion may make it, and every outcome below means it was not.
        runTest {
            nonVerdictOutcomes().forEach { outcome ->
                val availability = sourceReporting(couldNotConclude(outcome)).isNewerBuildAvailable()
                assertThat(availability).isNotEqualTo(UpdateAvailability.UpToDate)
            }
        }
    }

    @Test
    fun answersInconclusiveSoTheScreenCanExplainRatherThanOfferADeadButton() {
        runTest {
            nonVerdictOutcomes().forEach { outcome ->
                assertThat(sourceReporting(couldNotConclude(outcome)).isNewerBuildAvailable())
                    .isEqualTo(UpdateAvailability.Inconclusive)
            }
        }
    }

    @Test
    fun passesTheOneProvenNeutralAnswerThrough() {
        // The other half of the fail-closed rule: UP_TO_DATE really was read, so it is the one outcome that
        // may reach shared UI as "you have the latest version".
        runTest {
            assertThat(sourceReporting(upToDate()).isNewerBuildAvailable()).isEqualTo(UpdateAvailability.UpToDate)
        }
    }

    @Test
    fun reportsANewerReleaseAsNotInstallable() {
        // A newer release is reported, because the check really establishes it; `installable = false` is
        // what keeps shared UI from offering an install this platform has no installer for.
        runTest {
            val answer = sourceReporting(availableUpdate()).isNewerBuildAvailable()

            assertThat(answer).isEqualTo(UpdateAvailability.Newer(buildNumber = NEWEST_VERSION_CODE, installable = false))
        }
    }

    @Test
    fun asksTheCheckWithThisBuildsOwnVersionCode() {
        // The input both platforms share: the number the packager stamped, never a constant in the port.
        val asked = mutableListOf<Int>()
        val scripted =
            DesktopUnsupportedPorts.apkUpdateSource(
                checkForUpdates = { installed ->
                    asked += installed
                    upToDate()
                },
            )

        runTest {
            scripted.isNewerBuildAvailable()
        }

        assertThat(asked).containsExactly(DesktopBuildInfo.VERSION_CODE)
    }

    @Test
    fun fetchesNothingAndSaysWhy() {
        runTest {
            val download = source.downloadNewerBuild(UpdateAvailability.Newer(buildNumber = NEWEST_VERSION_CODE, installable = false))
            assertThat(download).isNull()
        }
    }

    @Test
    fun refusesToInstallWithThePlatformReasonRatherThanPretending() {
        val handoff = source.startInstall(UpdateDownload(buildNumber = NEWEST_VERSION_CODE, platformHandle = "unused"))

        assertThat(handoff).isEqualTo(InstallHandoff.Refused(InstallRefusal.PLATFORM_NOT_APPLICABLE))
    }

    @Test
    fun reportsTheBuildNumberThePackagerStamped() {
        // Real, not fabricated: a sentinel 0 would read as "always out of date" if anything ever compared
        // it. Null would be wrong too, because this build genuinely knows its own number.
        assertThat(source.currentBuildNumber ?: 0).isEqualTo(DesktopBuildInfo.VERSION_CODE)
    }

    @Test
    fun `the desktop declines where the shared check reads a verdict`() {
        // This platform has no installer to hand a file to, so its answer for a check that concluded
        // without a release is the explicit non-success - never UpToDate and never a Newer verdict.
        runTest {
            val desktopAnswer = sourceReporting(couldNotConclude(UpdateOutcome.NO_RELEASE)).isNewerBuildAvailable()
            assertThat(desktopAnswer).isEqualTo(UpdateAvailability.Inconclusive)
            assertThat(desktopAnswer).isNotEqualTo(UpdateAvailability.UpToDate)
            assertThat(desktopAnswer).isNotInstanceOf(UpdateAvailability.Newer::class.java)
        }

        // The comparison itself is one function, not one per platform: the two release lists below resolve
        // the way Android reads them, up to date then newer.
        val releases = listOf(release("1.2.0"))
        assertThat(resolveUpdateResult(1_002_000, releases).outcome).isEqualTo(UpdateOutcome.UP_TO_DATE)
        assertThat(
            resolveUpdateResult(1_002_000, listOf(release("1.2.1"))).outcome,
        ).isEqualTo(UpdateOutcome.UPDATE_AVAILABLE)
    }

    /** Every outcome that means the release list was not read to a verdict. */
    private fun nonVerdictOutcomes() =
        listOf(
            UpdateOutcome.TYPE_MISMATCH,
            UpdateOutcome.NO_RELEASE,
            UpdateOutcome.INDETERMINATE,
            UpdateOutcome.FAILED,
        )

    /** The result a genuine check produces for [outcome], rather than a bare enum. */
    private fun couldNotConclude(outcome: UpdateOutcome): UpdateCheckResult =
        when (outcome) {
            UpdateOutcome.TYPE_MISMATCH -> resolveUpdateResult(INSTALLED_VERSION_CODE, listOf(release("1.3.0", typeMismatch = true)))
            UpdateOutcome.NO_RELEASE -> resolveUpdateResult(INSTALLED_VERSION_CODE, emptyList())
            UpdateOutcome.INDETERMINATE -> resolveUpdateResult(INSTALLED_VERSION_CODE, emptyList(), scanComplete = false)
            UpdateOutcome.FAILED ->
                UpdateCheckResult(
                    outcome = UpdateOutcome.FAILED,
                    latestVersionName = null,
                    latestVersionCode = null,
                    releaseUrl = null,
                    error = UpdateCheckError.NO_INTERNET,
                )

            else -> error("$outcome is a verdict, not a could-not-conclude outcome")
        }

    private fun upToDate(): UpdateCheckResult = resolveUpdateResult(INSTALLED_VERSION_CODE, listOf(release(INSTALLED_VERSION_NAME)))

    private fun availableUpdate(): UpdateCheckResult =
        resolveUpdateResult(INSTALLED_VERSION_CODE, listOf(release(NEWEST_VERSION_NAME, apk = NEWEST_APK_URL)))

    /** One release as the shared parser reports it. */
    private fun release(
        version: String,
        apk: String? = null,
        typeMismatch: Boolean = false,
    ) = ReleaseStatus(
        versionName = version,
        versionCode = packed(version),
        releaseUrl = "https://example.invalid/releases/v$version",
        apkDownloadUrl = apk,
        typeMismatch = typeMismatch,
    )

    /** `major.minor.patch` packed the way the build packs it. */
    private fun packed(version: String): Int {
        val (major, minor, patch) = version.split('.').map { it.toInt() }
        return major * 1_000_000 + minor * 1_000 + patch
    }

    private companion object {
        const val INSTALLED_VERSION_NAME = "1.2.0"
        const val INSTALLED_VERSION_CODE = 1_002_000

        const val NEWEST_VERSION_NAME = "1.3.0"
        const val NEWEST_VERSION_CODE = 1_003_000

        const val NEWEST_APK_URL = "https://example.invalid/releases/v1.3.0/Payanam_Android_1.3.0_a1b2c3d_debug_20261026_101500.apk"
    }
}
