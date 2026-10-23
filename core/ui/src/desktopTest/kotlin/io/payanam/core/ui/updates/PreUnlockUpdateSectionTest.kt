//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.updates

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.presentation.download.DownloadUiState
import io.payanam.shared.platform.SupportState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The shared pre-unlock update hatch, rendered for real on the JVM target.
 *
 * WHY THIS FILE EXISTS: the hatch is the one update control drawn before the database is even unlocked,
 * and what it promises is a narrow, specific thing - it either offers a working action or it offers a
 * note explaining why it cannot. Every case below pins one half of that promise: WHICH branch rendered,
 * and WHICH of the three actions a tap reaches. A hatch that drew a button no platform can honour would
 * fail on a user's phone with no explanation, so the early returns are asserted as absences of control,
 * not as a different colour.
 *
 * WHY THE PLATFORM ANSWER IS MATCHED FIRST: `platformSupport` is the one input that can remove the
 * control entirely, so a state that says "this platform cannot update itself" must produce NO tap target
 * of any label. Those cases assert zero hatch labels and zero version rows, because the early return
 * happens before the card is composed at all.
 *
 * WHY THE DISABLED BRANCHES ARE NOT CLICKED: `performClick` asserts on the semantics action it dispatches
 * and would fail on a disabled node instead of testing anything about it. Enablement IS the mechanism
 * that stops the tap, so each of those cases asserts the node is off AND that the section holds no
 * enabled click target at all AND that nothing reached the actions - the same recorder the positive cases
 * use, so an empty list here means the same thing it means there.
 *
 * WHY THE INTERPOLATED LABELS ARE ASSERTED AGAINST THE RENDERED COPY: both keyed labels this section
 * builds through `formatArgs` (`#%1$d (dev)`, `Downloading %1$d%%`) would satisfy a check written against
 * the raw table value while showing a literal `%1$d` on screen. The percentage is read back off the state
 * object the section reads it from, so the two cannot drift apart.
 */
class PreUnlockUpdateSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    @Test
    fun `a supported platform draws the check button and routes its tap to the check action`() {
        val recorder = Recorder()
        render(hatchState(), recorder)

        composeRule.onNodeWithText(strings.settingsUpdateCheckButton).assertIsEnabled()
        composeRule.onNodeWithText(strings.settingsAboutVersionLabel).assertExists()
        composeRule.onNodeWithText(strings.preUnlockUpdateBuildValue.formatArgs(BUILD_NUMBER)).assertExists()
        composeRule.onNodeWithText(strings.settingsUpdateCheckButton).performClick()

        assertEquals(listOf("check"), recorder.fired)
    }

    @Test
    fun `a packager-managed install returns early with the F-Droid note and no button`() {
        val recorder = Recorder()
        render(hatchState(platformSupport = SupportState.DECLINES), recorder)

        // Its OWN note, not the generic one: this platform has an updater and refuses to act on this
        // install, which is a different answer from having no updater at all.
        assertStaticNoteOnly(strings.settingsUpdateFdroidManaged)
        composeRule.onAllNodesWithText(strings.settingsUpdateNotSupportedHere).assertCountEquals(0)
        assertEquals(emptyList<String>(), recorder.fired)
    }

    @Test
    fun `a platform with no in-app updater returns early with the not-supported note and no button`() {
        val recorder = Recorder()
        render(hatchState(platformSupport = SupportState.NOT_APPLICABLE), recorder)

        assertStaticNoteOnly(strings.settingsUpdateNotSupportedHere)
        composeRule.onAllNodesWithText(strings.settingsUpdateFdroidManaged).assertCountEquals(0)
        assertEquals(emptyList<String>(), recorder.fired)
    }

    @Test
    fun `a state that is accepted but not surfaced yet renders the same note and no button`() {
        val recorder = Recorder()
        render(hatchState(platformSupport = SupportState.NOT_SURFACED), recorder)

        // Listed rather than folded into an `else` so a future [SupportState] value is a compile error
        // here; today this one shares NOT_APPLICABLE's note because both mean "no action for you now".
        assertStaticNoteOnly(strings.settingsUpdateNotSupportedHere)
        composeRule.onAllNodesWithText(strings.settingsUpdateFdroidManaged).assertCountEquals(0)
        assertEquals(emptyList<String>(), recorder.fired)
    }

    @Test
    fun `a capability that only works while the app is open renders the same note and no button`() {
        val recorder = Recorder()
        render(hatchState(platformSupport = SupportState.IN_PROCESS_ONLY), recorder)

        // The same shape as NOT_SURFACED, and for the same reason: the hatch cannot hand an APK to the
        // user from a state that is only alive while the process is.
        assertStaticNoteOnly(strings.settingsUpdateNotSupportedHere)
        composeRule.onAllNodesWithText(strings.settingsUpdateFdroidManaged).assertCountEquals(0)
        assertEquals(emptyList<String>(), recorder.fired)
    }

    @Test
    fun `a check in flight draws the checking label disabled and dispatches nothing`() {
        val recorder = Recorder()
        render(hatchState(isChecking = true), recorder)

        composeRule.onNodeWithText(strings.settingsUpdateChecking).assertIsNotEnabled()
        // The checking branch REPLACES the check label rather than sitting beside it, so both the wrong
        // caption and a second, still-live tap target are ruled out here.
        composeRule.onAllNodesWithText(strings.settingsUpdateCheckButton).assertCountEquals(0)
        assertNothingCanBeTapped()
        assertEquals(emptyList<String>(), recorder.fired)
    }

    @Test
    fun `a download in flight draws the percentage disabled and dispatches nothing`() {
        val recorder = Recorder()
        render(hatchState(downloadState = downloadingState()), recorder)

        composeRule.onNodeWithText(downloadingLabel()).assertIsNotEnabled()
        composeRule.onAllNodesWithText(strings.settingsUpdateCheckButton).assertCountEquals(0)
        assertNothingCanBeTapped()
        assertEquals(emptyList<String>(), recorder.fired)
    }

    @Test
    fun `a finished download offers the install button and routes its tap to the install action`() {
        val recorder = Recorder()
        render(hatchState(downloadState = DownloadUiState.Downloaded(fileName = APK_FILE_NAME)), recorder)

        // Install and download are separate actions on purpose: the hatch never installs behind the
        // user's back, so a tap here must reach install and nothing else.
        composeRule.onNodeWithText(strings.settingsUpdateInstallNowButton).assertIsEnabled()
        composeRule.onNodeWithText(strings.settingsUpdateInstallNowButton).performClick()

        assertEquals(listOf("install"), recorder.fired)
    }

    @Test
    fun `a failed download offers the retry button with its message and routes the tap to download`() {
        val recorder = Recorder()
        render(hatchState(downloadState = DownloadUiState.Failed(message = "network")), recorder)

        composeRule.onNodeWithText(strings.preUnlockUpdateDownloadFailed).assertExists()
        composeRule.onNodeWithText(strings.settingsUpdateRetryButton).assertIsEnabled()
        composeRule.onNodeWithText(strings.settingsUpdateRetryButton).performClick()

        // Retry re-requests the DOWNLOAD; it never jumps to install, because the file is not there.
        assertEquals(listOf("download"), recorder.fired)
    }

    @Test
    fun `a check that found a build offers download and install and routes the tap to download`() {
        val recorder = Recorder()
        render(hatchState(checkResultMessage = "update_available_$BUILD_NUMBER"), recorder)

        composeRule.onNodeWithText(strings.preUnlockUpdateDownloadInstall).assertIsEnabled()
        composeRule.onAllNodesWithText(strings.settingsUpdateCheckButton).assertCountEquals(0)
        composeRule.onNodeWithText(strings.preUnlockUpdateDownloadInstall).performClick()

        assertEquals(listOf("download"), recorder.fired)
    }

    @Test
    fun `every check outcome with no build to offer renders its own message and keeps the check button`() {
        val outcomes =
            listOf(
                "up_to_date" to strings.preUnlockUpdateUpToDate,
                "type_mismatch" to strings.settingsUpdateTypeMismatch,
                "no_release" to strings.settingsUpdateNoReleases,
                // A suffixed reason key, because the check failure carries its reason after the prefix.
                CHECK_FAILED_KEY to strings.preUnlockUpdateCheckFailed,
            )

        outcomes.forEach { (reasonKey, message) ->
            render(hatchState(checkResultMessage = reasonKey), Recorder())

            composeRule.onNodeWithText(message).assertExists()
            // None of these four is an offer, so the hatch stays on the check button - and stays LIVE,
            // because the user's next move after "no update for you" is to ask again.
            composeRule.onNodeWithText(strings.settingsUpdateCheckButton).assertIsEnabled()
        }
    }

    @Test
    fun `a paused download has no branch of its own and falls through to the check button`() {
        val recorder = Recorder()
        render(hatchState(downloadState = DownloadUiState.Paused(message = "waiting for wifi")), recorder)

        // DELIBERATE, and pinned so it stays deliberate: `Paused` is matched by neither the downloading,
        // downloaded nor failed arm, so it lands on the check arm. A system pause is not an error and
        // not a finished file - it must not claim a percentage, must not offer to install, and must not
        // read as a failure the user has to retry.
        composeRule.onNodeWithText(strings.settingsUpdateCheckButton).assertIsEnabled()
        composeRule.onAllNodesWithText(downloadingLabel()).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsUpdateInstallNowButton).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsUpdateRetryButton).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.preUnlockUpdateDownloadInstall).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.preUnlockUpdateDownloadFailed).assertCountEquals(0)

        composeRule.onNodeWithText(strings.settingsUpdateCheckButton).performClick()
        assertEquals(listOf("check"), recorder.fired)
    }

    /**
     * The whole claim of an early-return branch: the note renders, and nothing that could be pressed
     * exists - including the version row, which lives below the return.
     */
    private fun assertStaticNoteOnly(note: String) {
        composeRule.onAllNodesWithText(note).assertCountEquals(1)
        hatchLabels().forEach { label -> composeRule.onAllNodesWithText(label).assertCountEquals(0) }
        composeRule.onAllNodesWithText(strings.settingsAboutVersionLabel).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.preUnlockUpdateHint).assertCountEquals(0)
        assertNothingCanBeTapped()
    }

    /** Every caption the single state-driven button can wear, so one check covers all five branches. */
    private fun hatchLabels(): List<String> =
        listOf(
            strings.settingsUpdateCheckButton,
            strings.settingsUpdateChecking,
            strings.settingsUpdateInstallNowButton,
            strings.settingsUpdateRetryButton,
            strings.preUnlockUpdateDownloadInstall,
        )

    /** No enabled click target anywhere in the section: a tap has nothing left to reach. */
    private fun assertNothingCanBeTapped() {
        composeRule.onAllNodes(isEnabled() and hasClickAction()).assertCountEquals(0)
    }

    private fun downloadingState(): DownloadUiState.Downloading =
        DownloadUiState.Downloading(
            fileName = APK_FILE_NAME,
            bytesDownloaded = BYTES_DOWNLOADED,
            totalBytes = TOTAL_BYTES,
        )

    /** The label the section builds, with the percentage the section itself derives. */
    private fun downloadingLabel(): String =
        strings.preUnlockUpdateDownloading.formatArgs(downloadingState().progressPercent)

    private fun hatchState(
        platformSupport: SupportState = SupportState.SUPPORTED,
        downloadState: DownloadUiState = DownloadUiState.Idle,
        isChecking: Boolean = false,
        checkResultMessage: String? = null,
    ): PreUnlockUpdateUiState =
        PreUnlockUpdateUiState(
            buildNumber = BUILD_NUMBER,
            platformSupport = platformSupport,
            downloadState = downloadState,
            isChecking = isChecking,
            checkResultMessage = checkResultMessage,
        )

    private fun render(
        uiState: PreUnlockUpdateUiState,
        recorder: Recorder,
        strings: UiStrings = this.strings,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                PreUnlockUpdateSection(uiState = uiState, actions = recorder)
            }
        }
    }

    /**
     * The three actions the section may report, and nothing else - the shared contract is that the
     * section says which of them the user asked for and leaves the download and the installer alone.
     */
    private class Recorder : PreUnlockUpdateActions {
        val fired = mutableListOf<String>()

        override fun onCheckRequested() {
            fired += "check"
        }

        override fun onDownloadRequested() {
            fired += "download"
        }

        override fun onInstallRequested() {
            fired += "install"
        }
    }

    private companion object {
        const val BUILD_NUMBER = 1568
        const val APK_FILE_NAME = "Payanam_Android_1568_debug_20260812_193754.apk"
        const val BYTES_DOWNLOADED = 420L
        const val TOTAL_BYTES = 1000L

        /** A suffixed reason key: the section matches the `check_failed` prefix, not an exact string. */
        const val CHECK_FAILED_KEY = "check_failed_http_503"
    }
}
