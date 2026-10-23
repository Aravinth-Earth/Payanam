//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

/**
 * The font-scale readability sweep for the shared screens.
 *
 * WHY THIS FILE EXISTS. A screen can be fully named at the default font scale and still become unusable
 * when the user enlarges text: the OS font scale flows into the density, every `Text` node grows, a
 * fixed-height row wraps into its neighbour, and two controls that sat side by side end up on top of each
 * other. None of that is visible in the naming sweep, which renders at 1x, and none of it is visible to a
 * source guard. This sweep renders the SAME shared screens at a deliberately large scale and asks the two
 * geometry questions that a11y tooling and manual device testing would otherwise catch: is every control
 * still drawn with an area, and do two controls the user is asked to press share any pixels.
 *
 * WHY IT REUSES THE NAMING SWEEP'S FIXTURES. Every screen, fake, state value and reach step lives in
 * `SharedScreenFixtures.kt`, so this sweep measures the same rendered screens the naming sweep does rather
 * than a second, drifting copy. The fixture's own `LocalUiStrings` provider is left in place and
 * [renderScreen] only rebuilds the density around it, so the copy and the controls are identical at both
 * scales - only their size changes.
 *
 * WHY 2x. Font scale is the one display setting that scales every text node without the app opting in, and
 * 2x is far enough past the common steps that a screen which is merely cramped at 1x will show it. The
 * sweep deliberately asserts NO control count at this scale: a lazy grid composes only what fits, so a
 * larger tile legitimately reduces the number of swatches on screen, and a floor that holds at 1x could
 * hold at 2x for the wrong reason. The naming sweep owns the render floor at 1x; this sweep measures
 * geometry only.
 *
 * WHAT IT DOES NOT ASSERT, and why:
 *  - Text clipping, line wrapping, or content overflowing its container. Those are per-node shape
 *    questions, and a control can still be fully pressable while its label is truncated; they belong to a
 *    dedicated overflow check rather than being folded into a readability sweep.
 *  - That a control is reachable by scrolling. A screen that grows past the window is expected to scroll,
 *    and `assertControlsDoNotOverlap` already tolerates controls outside the viewport.
 *
 * The overlap assertion is the library's, so the deliberate exclusions there - toggles, which Material
 * gives a minimum interactive size larger than their row - hold here too.
 */
class UiReadabilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Renders every shared screen at 2x and asserts the geometry of its interactive controls.
     *
     * ONE loop over `sharedScreenFixtures`, the same shape the naming sweep uses, so the two sweeps stay
     * visibly parallel: same fixtures, same render helper, different scale and different assertions.
     */
    @Test
    fun `controlsStayLaidOutAtDoubleFontScale`() {
        sharedScreenFixtures.forEach { fixture ->
            composeRule.renderScreen(fixture, fontScale = 2f)
            composeRule.waitForIdle()
            val interactive = composeRule.interactiveNodes()
            UiQualityAssertions.assertControlsAreLaidOut(fixture.name, interactive)
            UiQualityAssertions.assertControlsDoNotOverlap(fixture.name, interactive)
        }
    }
}
