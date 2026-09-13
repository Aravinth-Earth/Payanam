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
 * Port of Phase 9 of the Maestro flow — the unlock gate after a process restart.
 *
 * This cannot live in [FullJourneyTest]: a single instrumentation run hosts every test method in ONE
 * app process, so the database session never closes and the lock screen never appears. Running this
 * class as a SECOND invocation gives it a fresh process, which is exactly what `launchApp stopApp:
 * true` achieves for Maestro.
 *
 * It also doubles as the end-to-end proof that the data written by [FullJourneyTest] survived both
 * the process restart and the passphrase. Run it after the journey, against the same install:
 *
 *   run-inprocess-tests.ps1 -Runs 1 -TestClass io.payanam.e2e.FullJourneyTest -KeepInstalled 1
 *   run-inprocess-tests.ps1 -Runs 1 -TestClass io.payanam.e2e.UnlockTest
 *
 * The Maestro flow guards this phase with `runFlow: {when: {visible: "Unlock"}}` because the lock
 * appears only once the session timeout has elapsed; the port keeps the same guard.
 */
@RunWith(AndroidJUnit4::class)
class UnlockTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-UNLOCK")

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun unlockAfterRestart() {
        h.awaitRoot()

        if (h.isOnScreen("Unlock")) {
            h.log("lock=shown")
            h.type("Passphrase", FreshSetup.TEST_DB_PASSPHRASE)
            h.click("Unlock")
        } else {
            h.log("lock=skipped (session still open)")
        }

        // Past the gate the app lands on its default landing screen.
        h.assertVisible("Lenses")

        // And the data written before the restart is still readable. "Quick Task" is the one task
        // both tiers create, so this single check serves the full run and the smoke run alike; the
        // full tier additionally checks its second task from inside its own journey.
        h.clickNav("Tasks")
        h.assertVisible("Quick Task")
        h.log("unlock=verified")
    }
}
