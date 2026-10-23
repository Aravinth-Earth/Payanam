//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The shared auto-backup failure card, rendered for real on the JVM target.
 *
 * WHY THIS FILE EXISTS: it is the one settings file in the moved set whose test was missing - the card is
 * mounted from the section, but the section's own cases never render it, so its behaviours were unpinned:
 * the early return, the two message shapes (with and without a recorded time), and the dismiss callback.
 * The card is the only place a failed auto-backup can say so - a card that rendered the wrong shape, or a
 * dismiss that did nothing, would leave the failure silent.
 */
class AutoBackupFailureMessageCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    private fun render(
        errorMessage: String?,
        errorAt: String? = null,
        dismissals: MutableList<String> = mutableListOf(),
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                autoBackupFailureMessageCard(
                    errorMessage = errorMessage,
                    errorAt = errorAt,
                    onDismiss = { dismissals.add("dismiss") },
                )
            }
        }
    }

    @Test
    fun `no message renders nothing - the card is absent, not empty`() {
        render(errorMessage = null)

        composeRule.onAllNodesWithText(strings.backupFailureDialogTitle).assertCountEquals(0)
        composeRule.onAllNodesWithText(strings.settingsActionDismissErrorMessage).assertCountEquals(0)
    }

    @Test
    fun `a blank message renders nothing too - the guard is isNullOrBlank, not a null check`() {
        render(errorMessage = "   ")

        composeRule.onAllNodesWithText(strings.backupFailureDialogTitle).assertCountEquals(0)
    }

    @Test
    fun `a message without a time renders the without-time shape and dismiss reports`() {
        val dismissals = mutableListOf<String>()
        render(errorMessage = "disk full", dismissals = dismissals)

        composeRule.onNodeWithText(strings.backupFailureDialogTitle).assertExists()
        composeRule
            .onNodeWithText(strings.backupFailureDialogMessageWithoutTime.format("disk full"))
            .assertExists()
        composeRule.onNodeWithText(strings.settingsActionDismissErrorMessage).performClick()
        assertEquals(listOf("dismiss"), dismissals)
    }

    @Test
    fun `a message with a time renders the with-time shape`() {
        render(errorMessage = "disk full", errorAt = "2026-10-04 16:00")

        composeRule
            .onNodeWithText(
                strings.backupFailureDialogMessageWithTime.format("2026-10-04 16:00", "disk full"),
            ).assertExists()
    }
}
