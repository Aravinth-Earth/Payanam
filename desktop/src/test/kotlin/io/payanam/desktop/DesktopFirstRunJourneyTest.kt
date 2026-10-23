//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.desktop.database.DesktopBootstrapDataLayer
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.shared.startup.startupGateInputs
import org.junit.Test

/**
 * The desktop's first-run journey, walked in-process - the same walk Android's `FullJourneyTest` makes.
 *
 * **Why this exists.** The desktop's journey-level proof ran only as a HEADED run: `desktop-loop.sh`
 * launches the built app under Xvfb, drives real clicks and photographs each step. A photographed frame
 * plus a grepped log line cannot be compared against an assertion, so the parity claim mixed two
 * instruments. This walks the same journey the same way Android does - in-process, asserting each screen
 * by the copy it claims - and writes a frame per step to a run directory on the host.
 *
 * **The run renders at the APP's window size, not the harness default.** `createComposeRule()` renders at
 * `runDesktopComposeUiTest`'s default surface, which is the HARNESS and not the product: measured, it wrote
 * 1024x768 frames of a phone-shaped app, and a frame at the wrong size cannot be compared with the Android
 * reference frames this journey exists to mirror. The surface is therefore sized from the SHIPPED constants
 * ([PHONE_WINDOW_WIDTH_DP] and [PHONE_WINDOW_HEIGHT_DP] - 411x891 dp, the tested phone's viewport), and
 * that resolution is asserted by its own case, so a change to the shipped window shape fails here rather
 * than silently re-sizing the evidence.
 *
 * **The journey, in order.**
 *
 *  1. FIRST RUN - an empty, test-owned data root, nothing configured. The app opens on the onboarding
 *     tour. The premise is measured first (the root holds no files; `startupGateInputs` resolves the
 *     fresh state to the intro), so a green run cannot be the gate quietly skipping.
 *  2. THE THREE SCREENS - with a session open over an encrypted fixture, the shell lands on Tasks; the
 *     journey then walks Notes and then Journal, asserting each screen by the name THAT SCREEN CLAIMS
 *     (`PayanamTab.<Tab>.title(strings)`) rather than by anything the fixture wrote.
 *
 * **What a "frame" is here.** Each step writes a PNG (a real image: `captureToImage()` plus a skiko
 * encode) and a text record of the strings that were on screen at that moment, which is what a later
 * reviewer can diff. They are the app's own window size, so they line up with the Android reference
 * frames point for point.
 *
 * **What it does NOT claim.** It walks three screens, not the whole Android journey: passphrase change,
 * settings editing, the theme/font switches and the on-device dialogs are other items' claims. The
 * headed run still owns "the app works under a real window".
 *
 * The harness - the data root, the encrypted fixture, the shell mount, the tab walk and the frame writer -
 * lives in [DesktopAppJourneyHarness], shared with the Tasks and Time journeys so all three mount the SAME
 * app rather than three copies of it.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopFirstRunJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `the first-run journey reaches Tasks, Notes and Journal, each asserted by name`() =
        runAtTheAppWindowSize {
            // ---- LEG 1: first run, nothing on disk ------------------------------------------------
            // The premise, measured: whatever else is true, this root holds no files, so no gate can have
            // skipped over a database that was there.
            assertThat(dataRoot.walkTopDown().filter { it.isFile }.toList()).isEmpty()
            // The gate is the SHARED database-init screen - the screen that renders the tour as its own first
            // step - and never a tour gate of its own. Android has no separate intro gate either; the desktop
            // used to, and this is the assertion that keeps it from coming back.
            assertThat(startupGateInputs(freshState()).shouldShowDatabaseInit).isTrue()
            assertThat(startupGateInputs(freshState()).shouldShowOnboardingIntro).isFalse()

            // The bootstrap layer is what the shell installs before a session exists (`DesktopMain.kt`), and the
            // shared database-init screen reads the INSTALLED layer rather than building repositories of its
            // own - so the mount needs it for the same reason the app does.
            LocalDataLayer.install(DesktopBootstrapDataLayer)
            mountShell(freshState(), isLandingReady = false)
            frame("00-first-screen", "the shared database-init screen's tour", copy = strings.onboardingSlide1Title)
            assertNodeShowsIt(strings.onboardingSlide1Title, "the first-run screen names itself")

            // Dispose before the state changes: a mount cannot become a different app while it is composed.
            setContent { }
            waitForIdle()
            installEncryptedFixtureLayer()

            // ---- LEG 2: the three screens ---------------------------------------------------------
            mountShell(configuredState(), isLandingReady = true)

            // The shell lands on Tasks. Asserted by the screen's OWN title, read from the shipped table, so
            // a copy change breaks this rather than quietly asserting nothing.
            assertTab(frames = "01-tasks", tab = PayanamTab.Tasks)

            goToTab(PayanamTab.Notes)
            assertTab(frames = "02-notes", tab = PayanamTab.Notes)

            goToTab(PayanamTab.Journal)
            assertTab(frames = "03-journal", tab = PayanamTab.Journal)

            // The run directory is the deliverable alongside the assertion: four frames and the journey's
            // steps, readable on the host without a device.
            assertThat(runDirectory.listFiles()?.map { it.name }?.toList() ?: emptyList<String>())
                .isNotEmpty()
        }

    /**
     * The resolution this journey renders at is the SHIPPED one, asserted rather than assumed.
     *
     * Without this case the surface size is a line in a fixture, and a fixture that drifted back to the
     * harness default would keep writing frames whose size nothing checked - which is how the first cut of
     * this file shipped 1024x768 evidence for a 411x891 app. Both expectations come from the production
     * constants, so changing the shipped window shape fails here instead of passing quietly.
     */
    @Test
    fun `the journey renders at the app's own window size, not the harness default`() =
        runAtTheAppWindowSize {
            // Same mount as the journey's first leg, layer included: this case exists to pin the SURFACE, and a
            // mount that cannot compose would pin nothing.
            LocalDataLayer.install(DesktopBootstrapDataLayer)
            mountShell(freshState(), isLandingReady = false)

            val frame = onRoot().captureToImage()

            assertThat(frame.width).isEqualTo(PHONE_WINDOW_WIDTH_DP)
            assertThat(frame.height).isEqualTo(PHONE_WINDOW_HEIGHT_DP)
        }
}
