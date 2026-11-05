/*
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Payanam - the day-boundary hour control, rendered for real on the JVM target.
 *
 * The control existed in neither platform's UI before this section: every platform stored the hour,
 * read it back and clamped it, and neither offered a way to change it. These cases pin the three
 * things that make it a control rather than a readout - the copy around it, the slider's own range,
 * and the single write it reports when the value moves.
 *
 * The slider carries the section title as its merged description: its raw value is a bare number
 * with nothing naming what it governs, so a case asserts the description rather than the number
 * alone. The write case moves the slider through its semantics action and asserts the callback
 * receives the new hour - one write, not a render-time echo.
 */
package io.payanam.core.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The shared day-boundary settings section, rendered for real on the JVM target. */
class SettingsDayBoundarySectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings = UiStrings.of(UiLanguage.ENGLISH)

    private fun render(
        currentHour: Int,
        onDayBoundaryHourChange: (Int) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                SettingsDayBoundarySection(
                    currentHour = currentHour,
                    onDayBoundaryHourChange = onDayBoundaryHourChange,
                )
            }
        }
    }

    @Test
    fun `the section draws its title, hint and both end labels`() {
        render(currentHour = 3)

        composeRule.onAllNodesWithText(strings.settingsDayBoundaryTitle).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDayBoundaryHint).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDayBoundaryMidnight).assertCountEquals(1)
        composeRule.onAllNodesWithText(strings.settingsDayBoundaryFiveAm).assertCountEquals(1)
    }

    @Test
    fun `the slider carries the title as its description and the stored hour as its value`() {
        render(currentHour = 3)

        composeRule
            .onNodeWithContentDescription(strings.settingsDayBoundaryTitle)
            .assertRangeInfoEquals(ProgressBarRangeInfo(current = 3f, range = 0f..5f, steps = 4))
    }

    @Test
    fun `moving the slider reports the new hour and nothing else`() {
        val writes = mutableListOf<Int>()

        render(currentHour = 3, onDayBoundaryHourChange = { writes.add(it) })

        composeRule
            .onNodeWithContentDescription(strings.settingsDayBoundaryTitle)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }

        assertEquals(listOf(5), writes)
    }
}
