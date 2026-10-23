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
 * SHARED ROUTE: it calls [FreshSetup.runIfNeeded], so it provisions on a clean install and REUSES the
 * app an earlier class in the same invocation provisioned - one install and one setup for the whole
 * route. Every class that creates list data deletes it, which is what makes sharing the app safe.
 */
@RunWith(AndroidJUnit4::class)
class UpdateChannelTypeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-CHANNEL-TYPE")

    /** Every label below comes from [E2eLabels], so a ta-IN device resolves it too. */
    private val l: E2eLabels.Labels get() = h.labels

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun channelTypeBlockReflectsSelectedChannel() {
        FreshSetup(h).runIfNeeded()
        h.log("channel-type=setup")

        // ── Settings → About (the update block lives in the About card) ──
        h.clickNav(l.settings)
        h.assertVisible(l.settings)
        h.scrollTo(l.about)
        h.click(l.about)

        // ── default channel = Dev: it ships debug APKs, and this run is a debug build ──
        // The Settings screen is a plain scrolling Column: its content EXISTS in the
        // tree even off-screen, so assertVisible alone can wait forever without
        // scrolling. scrollTo both scrolls AND asserts visibility (its final check).
        h.scrollTo(l.updateChannelShips(l.updateChannelDev, "debug"))
        h.scrollTo(l.runningBuildType("debug"))
        h.assertNotVisible(l.updateTypeMismatch)
        h.log("channel-type=dev-block-ok")

        // ── switch to Beta: release-type channel vs debug install ⇒ mismatch shows ──
        h.click(l.updateChannelDev)
        h.click(l.updateChannelBeta)
        h.waitFor(l.updateChannelShips(l.updateChannelBeta, "release"))
        h.scrollTo(l.updateChannelShips(l.updateChannelBeta, "release"))
        h.waitFor(l.updateTypeMismatch)
        h.scrollTo(l.updateTypeMismatch)
        h.log("channel-type=beta-mismatch-shown")

        // ── switch back to Dev: the mismatch clears (state restored for re-runs) ──
        h.click(l.updateChannelBeta)
        h.click(l.updateChannelDev)
        h.waitFor(l.updateChannelShips(l.updateChannelDev, "debug"))
        h.waitForGone(l.updateTypeMismatch)
        h.scrollTo(l.updateChannelShips(l.updateChannelDev, "debug"))
        h.log("channel-type=done")
    }
}
