//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-process assertion for the updater's always-visible channel/type block in
 * the Settings About section. A debug install must see what each channel ships
 * and a distinct mismatch message when the selected channel cannot feed it —
 * statically, with NO network check involved.
 *
 * REQUIRES A FRESH INSTALL — run with '-KeepInstalled:$false', like the full
 * tier. See [FreshSetup] for why the app's own Delete All Data cannot stand in
 * for a wipe here. The test switches the channel back to Dev before finishing
 * so a later run starts from the default state.
 */
@RunWith(AndroidJUnit4::class)
class UpdateChannelTypeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-CHANNEL-TYPE")

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun channelTypeBlockReflectsSelectedChannel() {
        FreshSetup(h).run()
        h.log("channel-type=setup")

        h.scrollTo("Verify and proceed")
        h.click("Verify and proceed")
        h.assertVisible("Per Dimension")

        // ── Settings → About (the update block lives in the About card) ──
        h.clickNav("Settings")
        h.assertVisible("Settings")
        h.scrollTo("About")
        h.click("About")

        // ── default channel = Dev: it ships debug APKs, and this run is a debug build ──
        // The Settings screen is a plain scrolling Column: its content EXISTS in the
        // tree even off-screen, so assertVisible alone can wait forever without
        // scrolling. scrollTo both scrolls AND asserts visibility (its final check).
        h.scrollTo(DEV_SHIPS)
        h.scrollTo(RUNNING_DEBUG)
        h.assertNotVisible(MISMATCH)
        h.log("channel-type=dev-block-ok")

        // ── switch to Beta: release-type channel vs debug install ⇒ mismatch shows ──
        h.click("Dev (daily)")
        h.click("Beta (weekly)")
        h.waitFor(BETA_SHIPS)
        h.scrollTo(BETA_SHIPS)
        h.waitFor(MISMATCH)
        h.scrollTo(MISMATCH)
        h.log("channel-type=beta-mismatch-shown")

        // ── switch back to Dev: the mismatch clears (state restored for re-runs) ──
        h.click("Beta (weekly)")
        h.click("Dev (daily)")
        h.waitFor(DEV_SHIPS)
        h.waitForGone(MISMATCH)
        h.scrollTo(DEV_SHIPS)
        h.log("channel-type=done")
    }

    private companion object {
        const val DEV_SHIPS = "Dev (daily) channel ships: debug APK"
        const val BETA_SHIPS = "Beta (weekly) channel ships: release APK"
        const val RUNNING_DEBUG = "You are on: debug build"
        const val MISMATCH = "Build type mismatch — no compatible update is available for this install."
    }
}
