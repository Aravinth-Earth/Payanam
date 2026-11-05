//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.lenses

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.lenses.DimensionSplitState
import io.payanam.presentation.lenses.DimensionSplitWindow
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * The date-range shift arrows on the shared "Time by Dimension" section, rendered for real on the JVM
 * target.
 *
 * Both icons are decorative on their own - they are two chevrons around a date range - so the only thing
 * that tells a screen reader what either control does is its `contentDescription`. When both are `null`,
 * an assistive technology announces two identical unlabelled buttons inside a row whose label says which
 * window is shown, and the user is left with two indistinguishable targets. A grep cannot catch that,
 * because the literal `null` is what the Compose API takes to mean "no label"; only the rendered
 * semantics tree distinguishes a described control from an undescribed one.
 *
 * The descriptions are asserted by VALUE, from the shared copy table, so a control cannot pass by being
 * labelled with the wrong string and a new call site cannot reintroduce the gap by passing a literal.
 * [LocalUiStrings] has no default (a read without a provider fails loudly by design), which is what
 * forces the table to be installed here and keeps the assertion about the SCREEN rather than about
 * whichever default a test happened to inherit.
 */
class DimensionSplitSectionSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `both shift arrows carry their shared description`() {
        render(canShiftLeft = true, canShiftRight = true)

        val strings = UiStrings.of(UiLanguage.ENGLISH)
        composeRule.onNodeWithContentDescription(strings.activityDetailEarlier).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(strings.activityDetailLater).assertIsDisplayed()
    }

    @Test
    fun `a disabled arrow is still described`() {
        // The arrows grey out at the ends of the range. A screen reader must still be able to say what
        // they are, or the boundary of the range is announced as two dead controls with no name.
        render(canShiftLeft = false, canShiftRight = false)

        val strings = UiStrings.of(UiLanguage.ENGLISH)
        composeRule.onNodeWithContentDescription(strings.activityDetailEarlier).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(strings.activityDetailLater).assertIsNotEnabled()
    }

    @Test
    fun `each arrow invokes its own shift callback`() {
        // A single label applied to both would make the second test above pass while both controls did
        // the same thing, so the two callbacks are counted separately.
        var leftShifts = 0
        var rightShifts = 0
        render(
            canShiftLeft = true,
            canShiftRight = true,
            onShiftLeft = { leftShifts++ },
            onShiftRight = { rightShifts++ },
        )

        val strings = UiStrings.of(UiLanguage.ENGLISH)
        composeRule.onNodeWithContentDescription(strings.activityDetailEarlier).performClick()
        composeRule.onNodeWithContentDescription(strings.activityDetailLater).performClick()

        assertEquals(1, leftShifts, "the earlier arrow ran the later arrow's callback, or not at all")
        assertEquals(1, rightShifts, "the later arrow ran the earlier arrow's callback, or not at all")
    }

    @Test
    fun `the section renders the shared window title beside the arrows`() {
        // The arrows carry no text of their own, so the title is the only context they are read in.
        // Asserting it keeps the fix honest: a section that stopped rendering the range would still
        // pass the arrow tests above.
        render(canShiftLeft = true, canShiftRight = true)

        composeRule.onNodeWithText(UiStrings.of(UiLanguage.ENGLISH).locLensDimSplitTitle).assertIsDisplayed()
    }

    private fun render(
        canShiftLeft: Boolean,
        canShiftRight: Boolean,
        onShiftLeft: () -> Unit = {},
        onShiftRight: () -> Unit = {},
    ) {
        val today = LocalDate.now()
        val state =
            DimensionSplitState(
                window = DimensionSplitWindow.W1,
                windowOffset = 0,
                firstEntryDate = today.minusDays(30),
                effectiveStart = today,
                effectiveEnd = today,
                requestedStart = today,
                requestedEnd = today,
                byDimension = mapOf("dim_learning_growth" to 90, null to 10),
                totalMinutes = 100,
                canShiftLeft = canShiftLeft,
                canShiftRight = canShiftRight,
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides UiStrings.of(UiLanguage.ENGLISH)) {
                DimensionSplitSection(
                    state = state,
                    onWindowSelect = {},
                    onShiftLeft = onShiftLeft,
                    onShiftRight = onShiftRight,
                )
            }
        }
    }

    private fun assertEquals(
        expected: Int,
        actual: Int,
        message: String,
    ) {
        org.junit.Assert.assertEquals(message, expected, actual)
    }
}
