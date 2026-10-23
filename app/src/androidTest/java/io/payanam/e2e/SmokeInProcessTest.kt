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
 * In-process port of the retired Maestro tier's smoke flow — the fast inner loop.
 *
 * Same relationship to [FullJourneyTest] as the smoke YAML has to the full one: an independent step
 * list sharing the same setup, not a replacement. Scope is boot → every module renders → one write
 * lands. The restart phase (unlock + persistence) is shared with the full tier through [UnlockTest],
 * which is why both tiers create a task titled "Quick Task".
 *
 * REQUIRES A FRESH INSTALL — run with '-KeepInstalled:$false', like the full tier. See [FreshSetup]
 * for why the app's own Delete All Data cannot stand in for a wipe here.
 *
 * LOCALE: labels come from [E2eLabels], so this runs on a device in any language. The one title the
 * suite types in is data, not copy, and stays a literal.
 */
@RunWith(AndroidJUnit4::class)
class SmokeInProcessTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-SMOKE")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

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
        h.scrollTo(l.verifyAndProceed)
        h.click(l.verifyAndProceed)
        h.assertVisible(l.perDimension)

        // ── TASKS: one write ──
        h.goToTab(l.tasks)
        h.assertVisible(l.noTasksDueToday)
        h.click(l.addTask)
        h.type(l.taskTitleField, QUICK_TASK)
        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.assertVisible(QUICK_TASK)

        // ── HABITS: renders ──
        h.goToTab(l.habits)
        h.assertVisible(l.noHabitsYet)

        // ── TIME: start/stop cycle ──
        h.goToTab(l.time)
        h.click(l.startTracking)
        h.click(l.start)
        h.assertVisible(l.stopTracking)
        h.click(l.stopTracking)
        h.click(l.cancel)
        // The modal dismisses asynchronously; a nav tap while the scrim is up is swallowed.
        h.waitForGone(l.cancel)

        // ── JOURNAL: renders ──
        h.goToTab(l.journal)
        h.capture("smoke_journal")

        // ── NOTES: one write ──
        h.goToTab(l.notes)
        h.assertVisible(l.noNotesYet)
        h.click(l.addNote)
        h.type(l.noteTitleField, "Smoke Note")
        h.click(l.save)
        h.back()

        // ── LENSES + SETTINGS: render ──
        h.goToTab(l.lenses)
        h.assertVisible(l.lenses)
        h.clickNav(l.settings)
        h.assertVisible(l.settings)

        h.log("smoke=done")
    }

    private companion object {
        /** Shared with [UnlockTest], so one restart phase serves both tiers. Suite-authored data. */
        const val QUICK_TASK = "Quick Task"
    }
}
