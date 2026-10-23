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
 * Focus Mode settings — the preset selector and the per-tab visibility switches.
 *
 * Exercises the Focus Mode card on the Settings screen: the four preset segmented buttons and the
 * tab-visibility switches, and proves a hidden tab actually leaves the bottom navigation.
 *
 * REQUIRES A FRESH INSTALL (the runner's `-KeepInstalled false`), like every class in this tier.
 *
 * LABEL COLLISION, HONESTLY: each tab-visibility switch carries the SAME contentDescription as the
 * bottom-nav item of the same name ("Tasks", "Habits", ...). So on the Settings screen
 * `h.isOnScreen(label)` is true whether or not the nav item exists — the switch alone satisfies it.
 * To assert the bottom nav really reflects a hidden tab, the check is made from another tab (Notes),
 * where the switch is absent and only the nav item could carry the label.
 */
@RunWith(AndroidJUnit4::class)
class FocusModeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-FOCUS")

    /** App copy in the app's current language. */
    private val l get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun focusMode() {
        FreshSetup(h).run() // onboarding -> passphrase -> dimensions; ends on dimension screen
        h.scrollTo(l.ui.dbInitDimensionSetupContinueAction)
        h.click(l.ui.dbInitDimensionSetupContinueAction) // "Verify and proceed" -> main app
        h.assertVisible(l.perDimension)

        // ── go to Settings ──
        h.clickNav(l.ui.settingsTitle)
        h.assertVisible(l.ui.settingsTitle)

        // ── 1. expand the Focus Mode card ──
        h.scrollTo(l.ui.focusModeTitle)
        h.click(l.ui.focusModeTitle)

        // ── 2. the preset segmented buttons exist; select a non-default preset ──
        h.assertVisible(l.ui.presetSimpleTimeHabits)
        h.assertVisible(l.ui.presetSimpleJournal)
        h.assertVisible(l.ui.presetSimpleTasks)
        h.assertVisible(l.ui.presetFullSuite)
        h.click(l.ui.presetSimpleJournal) // default is FULL_SUITE; this is a non-default preset

        // ── 3. a tab-visibility switch: read, flip, assert the new state ──
        // SIMPLE_JOURNAL leaves the Journal tab visible, so this switch reads ON both before and
        // after the preset selection — the read below cannot race the preset's propagation.
        check(h.isChecked(l.ui.locJournal) == true) {
            "the Journal tab-visibility switch should read ON while the Journal tab is visible"
        }
        h.setChecked(l.ui.locJournal, false)
        check(h.isChecked(l.ui.locJournal) == false) {
            "the Journal tab-visibility switch did not turn OFF after setChecked(false)"
        }

        // ── 4. the bottom nav reflects the hidden tab; then restore it ──
        h.goToTab(l.ui.settingsDatabaseNotes) // Notes: visible under the preset, renders no "Journal"
        check(!h.isOnScreen(l.ui.locJournal)) {
            "the bottom nav still shows the Journal tab after it was hidden in Focus Mode settings"
        }
        h.goToTab(l.ui.settingsTitle)

        // Returned to a fresh composition: the card is collapsed again (expandedSection is a plain
        // remember, not saved state), though the scroll position is restored. Re-expand to reach
        // the switch.
        h.scrollTo(l.ui.focusModeTitle)
        h.click(l.ui.focusModeTitle)
        h.setChecked(l.ui.locJournal, true)
        check(h.isChecked(l.ui.locJournal) == true) {
            "the Journal tab-visibility switch did not turn back ON after setChecked(true)"
        }
    }
}
