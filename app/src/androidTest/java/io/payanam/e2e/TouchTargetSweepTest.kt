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
 * Touch-target sweep: every DISPLAYED pressable control on a few settled screens must be big enough to
 * hit.
 *
 * **The gap this closes.** Every other class in the tier presses controls by their label and never asks
 * how big they are. A control rendered at a few dp - a `Modifier.clickable` on a bare icon, a `Box` whose
 * padding was dropped, a zero-area surface - is invisible to those assertions: a tap can still land on it
 * by luck, and the screen's copy is still present. On a real phone that is a control a user cannot
 * reliably hit, which is exactly the class of defect an accessibility/ergonomics sweep exists to catch.
 *
 * **The rule.** Each measured control must be at least [MIN_TOUCH_TARGET_DP] in BOTH dimensions. Two kinds
 * of node are deliberately excluded, and the exclusion lives in [E2eHarness.pressableTargets] where it is
 * stated: `Selected` (filter chips, tabs, segmented buttons, nav items) and `ToggleableState` (switches,
 * checkboxes). Material sizes those below the comfort target on purpose - the row around a switch is the
 * touch surface, and a chip is not a control a user struggles to hit - so including them would fail every
 * screen with a chip row for a non-defect.
 *
 * **What it walks.** The four top-level surfaces a fresh install can reach with no data: Lenses (the
 * landing screen after setup), Tasks, Time and Settings. Each is asserted by its OWN copy before it is
 * measured, so a sweep cannot silently run against a half-composed screen. It is one fresh-install
 * invocation, on the same terms as the rest of the tier - and it must STAY one. It is a first-run-gate
 * route class, not a shared-route one: its premise is a screen with NO DATA, and in a shared invocation an
 * earlier class's habits are on the Lenses screen, where their rows measure a few dp tall and the sweep
 * then fails on data rather than on a defect (measured 2026-10-17).
 *
 * **What it does NOT claim.** It measures the controls in the current viewport, not every row a lazy list
 * could compose; and it does not measure dialogs, which are reached by their own journeys. Its floor is a
 * minimum, not a target - 48dp remains the comfort size.
 */
@RunWith(AndroidJUnit4::class)
class TouchTargetSweepTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-TOUCH")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-run; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenOn() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun displayedControlsAreBigEnoughToTap() {
        FreshSetup(h).run()

        // run() ends on the mandatory dimension step, so the sweep needs it cleared first: the bottom
        // navigation it walks is not composed there, and the first nav tap would time out.
        h.scrollTo(l.verifyAndProceed)
        h.click(l.verifyAndProceed)
        h.assertVisible(l.perDimension)

        h.assertTouchTargets("Lenses", MIN_TOUCH_TARGET_DP)

        h.goToTab(l.tasks)
        h.assertVisible(l.ui.locAddTask)
        h.assertTouchTargets("Tasks", MIN_TOUCH_TARGET_DP)

        h.goToTab(l.time)
        h.assertVisible(l.ui.locAddTimeEntry)
        h.assertTouchTargets("Time", MIN_TOUCH_TARGET_DP)

        h.goToTab(l.settings)
        h.assertVisible(l.ui.settingsAppearanceTitle)
        h.assertTouchTargets("Settings", MIN_TOUCH_TARGET_DP)

        h.log("touch-target-sweep=done")
    }

    private companion object {
        /**
         * The floor, in dp.
         *
         * 24dp, not Material's 48dp comfort size: the sweep's job is to catch a control that is
         * effectively UNHITTABLE, not to fail the suite on a control that is merely tighter than the
         * guideline. Android's own minimum is 48dp; 24dp is the point below which a thumb cannot land
         * reliably at all, so a control under it is a defect rather than a preference.
         */
        const val MIN_TOUCH_TARGET_DP = 24f
    }
}
