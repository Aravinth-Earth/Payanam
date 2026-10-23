//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import io.payanam.shared.journal.JournalReflectionContracts
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Journal screen's reflections: an overall prompt write with its autosave, and a per-dimension card's
 * expand-and-collapse.
 *
 * **What was uncovered.** [JournalCrudTest] walks the day view and the date picker but never types into a
 * reflection or expands a dimension card. Those are the screen's whole purpose — the day view is a shell
 * around these prompts — so "the autosave never fires" or "a dimension card does not expand" would look
 * identical to a working journal.
 *
 * **The journey.** Type into the first overall prompt (addressed by its `testTag`, because the prompt copy
 * is a separate sibling `Text` rather than the field's label) and require the app's own "saved" indicator;
 * then expand the first per-dimension card and require its prompts to appear, and collapse it again.
 *
 * The dimension prompt asserted is read from the app's own [JournalReflectionContracts], not a literal, so a
 * prompt-text change breaks this rather than silently passing. The typed reflection is data the test chose.
 */
@RunWith(AndroidJUnit4::class)
class JournalPerDimensionTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-JOURNAL-REFLECT")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun overallAndDimensionReflectionsAreDriven() {
        FreshSetup(h).runIfNeeded()

        h.goToTab(l.journal)
        h.assertVisible(l.ui.locDailyReflection)
        h.capture("journal-summary")

        overallPromptAutosaves()
        dimensionCardExpands()
        h.log("journal-reflections=done")
    }

    /** Types into the first overall prompt and requires the app's own "saved" indicator. */
    private fun overallPromptAutosaves() {
        rule.onAllNodesWithTag(OVERALL_PROMPT_0_TAG).onFirst()
            .performScrollTo()
            .performTextInput(REFLECTION)
        // The indicator is formatted with the date, which is locale-formatted, so it is matched by its
        // own prefix - everything before the placeholder.
        h.assertVisibleSubstring(l.ui.locJournalSavedForDate.substringBefore('%'))
        h.assertLogged("DayViewModel.saveJournalResponse", "Created new journal entry")
        h.log("overall=saved")
    }

    /** Expands the first per-dimension card and requires its prompts, then collapses it. */
    private fun dimensionCardExpands() {
        rule.onAllNodesWithTag(DIMENSION_CARD_TAG).onFirst().performScrollTo().performClick()
        h.assertVisible(JournalReflectionContracts.dimensionPrompts.first().prompt)
        h.log("dimension=expanded")

        rule.onAllNodesWithTag(DIMENSION_CARD_TAG).onFirst().performClick()
        h.log("dimension=collapsed")
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
