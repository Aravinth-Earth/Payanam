//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.shared.journal.JournalReflectionContracts
import org.junit.Test

/**
 * The desktop's Journal journey, walked in-process the way Android's `JournalPerDimensionTest` walks it on a
 * device.
 *
 * **The gap this closes.** Nothing on the desktop ever typed into a reflection prompt or expanded a
 * per-dimension card through the UI. The Journal screen renders, but the write path - the overall prompt
 * field accepting text and the app's own "saved" indicator appearing, and a dimension card expanding to
 * reveal its prompts - was unexercised.
 *
 * **The journey.** Navigate to the Journal tab, assert the daily-reflection header, type into the first
 * overall prompt (addressed by its `testTag`), require the app's own saved indicator by its prefix, then
 * expand the first per-dimension card and require its prompt to appear.
 *
 * The dimension prompt asserted is read from the app's own [JournalReflectionContracts], not a literal, so a
 * prompt-text change breaks this rather than silently passing. The typed reflection is data the test chose.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopJournalJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `a reflection is typed, saved, and a dimension card expands`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // ---- Navigate to Journal ---------------------------------------------------------------
            onAllNodesWithText(PayanamTab.Journal.title(strings)).onFirst().performClick()
            waitForIdle()

            // ---- Assert the daily-reflection header --------------------------------------------------
            awaitNodeWithText(strings.locDailyReflection)
            frame("00-journal-header", "the Journal screen with the daily-reflection header", copy = strings.locDailyReflection)

            // ---- Type into the first overall prompt and require the saved indicator ------------------
            onAllNodesWithTag(OVERALL_PROMPT_0_TAG)
                .onFirst()
                .performScrollTo()
                .performTextInput(REFLECTION)

            val savedPrefix = strings.locJournalSavedForDate.substringBefore('%')
            waitUntil(timeoutMillis = 10_000L) {
                onAllNodesWithText(savedPrefix, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertLogged("DayViewModel.saveJournalResponse", "Created new journal entry")
            frame("01-journal-saved", "the Journal screen showing the saved indicator", copy = savedPrefix)

            // ---- Expand the first per-dimension card and require its prompt ---------------------------
            onAllNodesWithTag(DIMENSION_CARD_TAG)
                .onFirst()
                .performScrollTo()
                .performClick()

            val dimensionPrompt = JournalReflectionContracts.dimensionPrompts.first().prompt
            awaitNodeWithText(dimensionPrompt)
            frame("02-journal-dimension-expanded", "the Journal screen with a dimension card expanded", copy = dimensionPrompt)
        }

    private companion object {
        /** The first overall prompt field's testTag (`DayScreenSummary`). */
        const val OVERALL_PROMPT_0_TAG = "journal_overall_prompt_0"

        /** A per-dimension card's testTag (`DayScreenSummary`). */
        const val DIMENSION_CARD_TAG = "journal_dimension_card"

        /** Data the test types in, not app copy. */
        const val REFLECTION = "A quiet, focused day."
    }
}
