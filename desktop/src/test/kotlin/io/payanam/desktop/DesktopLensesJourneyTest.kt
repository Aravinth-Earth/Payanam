//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.time.EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG
import org.junit.Test

/**
 * The desktop's Lenses journey, walked in-process the way Android's `LensesChartsTest` walks it on a
 * device.
 *
 * **The gap this closes.** Nothing on the desktop ever opened Lenses over DATA. The screen renders
 * (`DesktopNavHostDestinationRenderTest`) and the section components carry their own unit tests, but
 * the journey Android proves - seed a tracked time entry through the real UI, open Lenses, and see the
 * Time module's average-daily section and the habit-score matrix reach the real encrypted database - had
 * no desktop twin. A Lenses screen whose sections rendered empty, or whose matrix trigger was
 * unreachable, would look identical to a working one without this drive.
 *
 * **The journey.** One manual time entry is seeded through the Time screen's Add FAB and modal (the
 * same path `DesktopTimeEntryJourneyTest` drives), then Lenses is opened and asserted in order: the Time
 * module's average-daily section (the seeded entry is what makes it a real claim), and - only when the
 * Habits chart module is on - the matrix's metric dropdown trigger.
 *
 * **Why the habit-score matrix is allowed to be absent.** `chartHabitModuleEnabled` defaults to
 * FALSE, so on a freshly seeded fixture the matrix legitimately does not render and this step frames
 * that absence instead of failing. When the module is on and a habit exists, the row tag
 * `lenses_matrix_row` is present and the trigger must be reachable. Either way the step is evidence,
 * not a flaky gate.
 *
 * **Why the production fixture.** The Time module reads `time_entries` through the shared data layer
 * over the real encrypted database, exactly as it does after unlock, and the chart-visibility flags
 * come from the real preference state ([loadedPreferences]) - so the defaults under test are the
 * shipped ones, not a fixture's invention.
 *
 * **What it does NOT claim.** It does not seed a habit, open the dropdown to choose a different
 * metric, or assert any chart's visual output - the matrix leg is a reachability claim, and the
 * frames are the visual evidence a person reads.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopLensesJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `the Lenses screen renders its sections over a seeded time entry`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // ---- SEED: one manual time entry, so the Time module has a data point --------------
            onAllNodesWithText(PayanamTab.Time.title(strings)).onFirst().performClick()
            waitForIdle()
            awaitNodeWithContentDescription(strings.locAddTimeEntry)
            frame("00-time-empty", "the Time screen before the entry is seeded", copy = strings.locAddTimeEntry)

            onNodeWithContentDescription(strings.locAddTimeEntry).performClick()
            awaitNodeWithTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG)
            onAllNodesWithTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG).onFirst().performClick()

            // The modal closing is the proof the entry was accepted (the FAB itself never leaves).
            waitUntil(timeoutMillis = UI_STATE_TIMEOUT_MS) {
                onAllNodesWithTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG).fetchSemanticsNodes().isEmpty()
            }

            // The entry's block announces the dimension it was logged against (the first visible
            // dimension - Physical Health), so its own name is the evidence the write landed.
            awaitContentDescriptionContaining(FIRST_DIMENSION)
            frame("01-time-entry-seeded", "the timeline carrying the seeded entry", copy = FIRST_DIMENSION)

            // ---- LENSES ------------------------------------------------------------------------
            onAllNodesWithText(PayanamTab.Lenses.title(strings)).onFirst().performClick()
            waitForIdle()

            // The Time module's average-daily section: the module card is expanded by default, but the
            // screen is a long scroll, so the section is scrolled into view before it is asserted. The
            // wait below is the settle point the screen's asynchronous state read needs - `LensesScreen`
            // draws a spinner until that read completes, and the assertion on the section's copy is what
            // proves the body composed over the seeded entry.
            waitUntil(timeoutMillis = UI_STATE_TIMEOUT_MS) {
                onAllNodesWithText(strings.locLensTimeAverageDailyTitle).fetchSemanticsNodes().isNotEmpty()
            }
            onAllNodesWithText(strings.locLensTimeAverageDailyTitle).onFirst().performScrollTo()
            awaitNodeWithText(strings.locLensTimeAverageDailyTitle)
            frame(
                "02-lenses-average-daily",
                "the Time module's average-daily section with data",
                copy = strings.locLensTimeAverageDailyTitle,
            )

            // ---- The habit-score matrix: present only when the Habits chart module is on ----------
            // The trigger is named by the shared table's format string, matched the way Android's
            // `LensesChartsTest` matches it: by the prefix before the placeholder.
            val metricTriggerPrefix = strings.locLensMetricLabel.substringBefore('%').trim()
            if (onAllNodesWithTag(LENSES_MATRIX_ROW_TAG).fetchSemanticsNodes().isNotEmpty()) {
                // A collapsed module card is expanded the way a user does - by its Expand button. On
                // the default state the other module cards are still collapsed, so there is one; if a
                // state had every card expanded there would be nothing to expand and the trigger is
                // already on screen.
                val expandButtons = onAllNodesWithText(strings.settingsActionExpand).fetchSemanticsNodes()
                if (expandButtons.isNotEmpty()) {
                    onAllNodesWithText(strings.settingsActionExpand).onFirst().performClick()
                    waitForIdle()
                }
                onAllNodesWithText(metricTriggerPrefix, substring = true).onFirst().performScrollTo()
                waitUntil(timeoutMillis = UI_STATE_TIMEOUT_MS) {
                    onAllNodesWithText(metricTriggerPrefix, substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                frame("03-lenses-matrix-trigger", "the habit-score matrix's metric dropdown trigger", copy = metricTriggerPrefix)
            } else {
                frame(
                    "03-lenses-matrix-absent",
                    "the Lenses screen without the habit-score matrix (the Habits chart module is off by default)",
                    copy = "lenses_matrix_row absent - the Habits chart module is off by default",
                )
            }
        }

    private companion object {
        /** How long a UI state produced by a real database write may take to appear. */
        private const val UI_STATE_TIMEOUT_MS = 10_000L

        /** The first visible dimension, by the shared catalog's sort order: the add modal's default. */
        const val FIRST_DIMENSION = "Physical Health"

        /** The habit-score matrix row's testTag, mirrored from the section that renders it. */
        const val LENSES_MATRIX_ROW_TAG = "lenses_matrix_row"
    }
}
