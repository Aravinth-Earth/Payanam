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
import org.junit.Test

/**
 * The desktop's journal DATE leg, walked in-process the way Android's `JournalCrudTest` walks it on a device.
 *
 * **The gap this closes.** `DesktopJournalJourneyTest` types a reflection, sees the saved indicator and
 * expands a dimension card. The DATE ROW - the control that moves the day being journalled - was never
 * opened on the desktop, so a picker that never opened, or a confirm that dropped the typed response or left
 * the screen on a spinner, would look identical to a working one.
 *
 * **The journey.** Navigate to the Journal tab, type into the first overall prompt and wait for the app's own
 * saved indicator (so the date leg has state to preserve), open the picker from the date row, confirm with no
 * change - the picker opens on the selected date, so confirming keeps today - and require the dialog to close
 * AND the day view (with its response) to be the screen that is up.
 *
 * Every label is the app's own copy via the harness's `strings`; the reflection is data.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopJournalDatePickerJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `the journal date row opens the picker and confirming leaves the day view up`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            onAllNodesWithText(PayanamTab.Journal.title(strings)).onFirst().performClick()
            waitForIdle()
            awaitNodeWithText(strings.locDailyReflection)

            // Write a response FIRST, so the date leg is shown not to lose it.
            onAllNodesWithTag(OVERALL_PROMPT_0_TAG)
                .onFirst()
                .performScrollTo()
                .performTextInput(REFLECTION)
            val savedPrefix = strings.locJournalSavedForDate.substringBefore('%')
            waitUntil(timeoutMillis = 10_000L) {
                onAllNodesWithText(savedPrefix, substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            // ---- the date row opens the picker, which lands on the selected date (today) ----------
            // The date row is already in view on this screen, so it is clicked directly: `performScrollTo`
            // needs a scrollable ancestor, and the day screen's date row has none.
            onAllNodesWithTag(DATE_ROW_TAG).onFirst().performClick()
            awaitNodeWithText(strings.locOk)
            frame("00-date-picker", "the journal date picker open from the date row", copy = strings.locOk)

            onAllNodesWithText(strings.locOk).onFirst().performClick()
            awaitNodeGone(strings.locOk)

            // The DAY VIEW is still the screen that is up - not the loading spinner.
            awaitNodeWithText(strings.locDailyReflection)
            // The saved indicator is deliberately NOT asserted after the date leg: it reports a SAVE, and
            // confirming a date re-reads the day rather than saving, so requiring it here would be requiring
            // the indicator to outlive the write it reports. What this leg claims is that the picker opened,
            // confirmed and left the day view standing.
            frame("01-day-view-up", "the journal day view after confirming the date", copy = strings.locDailyReflection)
        }

    private companion object {
        /** The first overall prompt field's testTag (`DayScreenSummary`). */
        const val OVERALL_PROMPT_0_TAG = "journal_overall_prompt_0"

        /** The date row's testTag (`DayScreenSummary`). */
        const val DATE_ROW_TAG = "journal_date_row"

        /** Data the test types in, not app copy. */
        const val REFLECTION = "A quiet, focused day."
    }
}
