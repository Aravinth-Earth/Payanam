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
 * In-process port of `maestro/e2e/smoke/payanam_smoke.yaml` — the fast inner loop.
 *
 * Same relationship to [FullJourneyTest] as the smoke YAML has to the full one: an independent step
 * list sharing the same setup, not a replacement. Scope is boot → every module renders → one write
 * lands. The restart phase (unlock + persistence) is shared with the full tier through [UnlockTest],
 * which is why both tiers create a task titled "Quick Task".
 *
 * REQUIRES A FRESH INSTALL — run with '-KeepInstalled:$false', like the full tier. See [FreshSetup]
 * for why the app's own Delete All Data cannot stand in for a wipe here.
 */
@RunWith(AndroidJUnit4::class)
class SmokeInProcessTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-SMOKE")

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun smoke() {
        FreshSetup(h).run()
        h.log("smoke=setup")

        // The dimension screen is the last gate before the app itself. The smoke tier takes it
        // as-is — the editors and their validation are the full tier's business.
        h.scrollTo("Verify and proceed")
        h.click("Verify and proceed")
        h.assertVisible("Per Dimension")

        // ── TASKS: one write ──
        h.goToTab("Tasks")
        h.assertVisible("No Tasks Due Today")
        h.click("Add Task")
        h.type("Task Title", QUICK_TASK)
        h.scrollTo("Save Task")
        h.click("Save Task")
        h.assertVisible(QUICK_TASK)

        // ── HABITS: renders ──
        h.goToTab("Habits")
        h.assertVisible("No Habits Yet")

        // ── TIME: start/stop cycle ──
        h.goToTab("Time")
        h.click("Start Tracking")
        h.click("Start")
        h.assertVisible("Stop Tracking")
        h.click("Stop Tracking")
        h.click("Cancel")
        // The modal dismisses asynchronously; a nav tap while the scrim is up is swallowed.
        h.waitForGone("Cancel")

        // ── JOURNAL: renders ──
        h.goToTab("Journal")
        h.capture("smoke_journal")

        // ── NOTES: one write ──
        h.goToTab("Notes")
        h.assertVisible("No Notes Yet")
        h.click("Add Note")
        h.type("Title", "Smoke Note")
        h.click("Save")
        h.back()

        // ── LENSES + SETTINGS: render ──
        h.goToTab("Lenses")
        h.assertVisible("Lenses")
        h.clickNav("Settings")
        h.assertVisible("Settings")

        h.log("smoke=done")
    }

    private companion object {
        /** Shared with [UnlockTest], so one restart phase serves both tiers. */
        const val QUICK_TASK = "Quick Task"
    }
}
