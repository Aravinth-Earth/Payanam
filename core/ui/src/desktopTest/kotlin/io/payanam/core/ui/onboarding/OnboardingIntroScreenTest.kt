//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Rule
import org.junit.Test

/**
 * The shared onboarding tour, rendered for real on the JVM target.
 *
 * This is the FIRST screen both platforms draw, and it has no ViewModel: every decision it makes is
 * made inside the composable, straight from the pager's current page. That is exactly what no
 * ViewModel test can reach, so the two things pinned here are the two ways this screen can strand a
 * user on a tour that never ends:
 *
 *  - the action button's label is a function of the page (`onboardingActionNext` until the last page,
 *    `onboardingActionStart` on it). A label that stops swapping promises an ending that never comes.
 *  - the finish callback is fired by the last page's activation and by nothing else. A page advance
 *    that also finished the tour would be the same user-visible bug as a "Start" that never fires.
 *
 * Every expected string is read from the shipped table rather than typed in, so a copy change breaks
 * this test instead of quietly making it assert nothing: the labels come from the SAME source the
 * screen reads, so screen and table cannot drift apart into a vacuous pass.
 */
class OnboardingIntroScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `first page shows the first slide and a next action`() {
        render()

        // The title is the first page's own, from the shared copy table.
        composeRule.onNodeWithText(strings.onboardingSlide1Title).assertIsDisplayed()
        // ...and the action is the mid-tour label, with the end-of-tour label not on the tree at all.
        composeRule.onNodeWithText(strings.onboardingActionNext).assertIsDisplayed()
        composeRule.onNodeWithText(strings.onboardingActionStart).assertDoesNotExist()
    }

    @Test
    fun `the action becomes the start action on the last page`() {
        render()

        advanceToLastPage()

        // The swap is the point: the start label where the next label used to be, and no next left.
        composeRule.onNodeWithText(strings.onboardingActionStart).assertIsDisplayed()
        composeRule.onNodeWithText(strings.onboardingActionNext).assertDoesNotExist()
        // The pager reached the last slide, so the label changed because the page did. Existence rather
        // than display: a page's content sits inside the pager's own bounds, and what the harness calls
        // "displayed" there depends on the render surface rather than on the screen's own logic.
        composeRule.onNodeWithText(strings.onboardingSlide3Title).assertExists()
    }

    @Test
    fun `advancing a page does not finish the tour`() {
        val finishes = mutableListOf<Unit>()

        render(finishes)
        advanceToLastPage()

        // Both page advances went through the same button that calls `onFinished` on the last page,
        // so neither may have finished. A Next that ends the tour early is the same bug as a Start
        // that never does.
        composeRule.runOnIdle { check(finishes.isEmpty()) { "a page advance fired onFinished" } }
    }

    @Test
    fun `the start action on the last page finishes the tour exactly once`() {
        val finishes = mutableListOf<Unit>()

        render(finishes)
        advanceToLastPage()

        composeRule.onNodeWithText(strings.onboardingActionStart).performClick()

        composeRule.runOnIdle { check(finishes.size == 1) { "expected one onFinished, got ${finishes.size}" } }
    }

    /**
     * Renders the screen with nothing between it and the test: it resolves its own copy from the
     * language and needs no theme or CompositionLocal installed to draw, so the harness measures the
     * shared composable rather than a wrapper.
     */
    private fun render(finishes: MutableList<Unit> = mutableListOf()) {
        composeRule.setContent {
            OnboardingIntroScreen(
                language = UiLanguage.ENGLISH,
                onFinished = { finishes += Unit },
            )
        }
    }

    /**
     * Clicks the mid-tour action until the screen has swapped it for the end-of-tour one.
     *
     * The screen advances with `animateScrollToPage`, so each click is followed by an idle wait rather
     * than a blind retry. The loop stops on the observable state change - the next label is gone - so
     * it needs no copy of the page count the screen already owns.
     */
    private fun advanceToLastPage() {
        var advances = 0
        while (composeRule.onAllNodesWithText(strings.onboardingActionNext).fetchSemanticsNodes().isNotEmpty()) {
            // Bounded, so a screen that never swaps the label fails with a readable message instead of
            // hanging the gate: the bound is above the page count the screen draws, not a copy of it.
            check(advances < MAX_ADVANCES) { "the next action never became the start action" }
            composeRule.onNodeWithText(strings.onboardingActionNext).performClick()
            composeRule.waitForIdle()
            advances++
        }
    }

    private companion object {
        /** The shipped English table - the same instance the screen builds from [UiLanguage.ENGLISH]. */
        val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

        /** Generously above the tour's real page count, so only a stuck pager can reach it. */
        const val MAX_ADVANCES = 10
    }
}
