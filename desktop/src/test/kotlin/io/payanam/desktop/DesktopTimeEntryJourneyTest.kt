//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.payanam.core.ui.navigation.PayanamTab
import io.payanam.core.ui.time.EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG
import org.junit.Test

/**
 * The desktop's time-entry journey, walked in-process the way Android's `TimeEntryEditTest` walks it on a
 * device.
 *
 * **The gap this closes.** Nothing on the desktop ever logged a time entry through the UI. The Time screen
 * renders (`DesktopNavHostSharedScreenRenderTest`) and the timeline's semantics are pinned
 * (`TimeEntryBlockSemanticsTest`), but the write path - the Add FAB opening the real `TimeBlockModalDialog`,
 * the modal's confirm reaching `TimeViewModel.createManualEntry`, and the entry then being editable and
 * deletable - was unexercised. This drives all three: add, edit (the dimension is changed and the block's
 * own announcement is asserted to follow), and delete.
 *
 * **Why the production fixture.** A time-entry write touches `time_entries` and its `life_dimensions`
 * foreign key, and the modal reads its dimension options from `LocalAppPreferences`; so this mounts over
 * [installProductionFixtureLayer] with the REAL preference state ([loadedPreferences]), exactly as the Tasks
 * journey does.
 *
 * **Why the assertions are on the block's contentDescription.** The timeline's entry block has no visible
 * title of its own - its name is the computed `contentDescription` (dimension, range, duration), which is
 * what a screen reader reads. Asserting the block by that name is asserting what the user can reach, and it
 * is the only thing that CHANGES when the entry is edited, which is what makes the edit leg a real claim.
 *
 * **What it does NOT claim.** It does not walk the start/stop timer, the scale presets, the gap-create path
 * or the day-plan template; those are other items' claims. The dimension labels are the shipped catalog's,
 * not data literals.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopTimeEntryJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `a time entry is added, its dimension edited, and deleted`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // The Time destination loads its day on a background dispatcher, so the add FAB is the wait target.
            goToTab(PayanamTab.Time)
            awaitNodeWithContentDescription(strings.locAddTimeEntry)
            frame("00-time-empty", "the Time screen with no entries yet", copy = strings.locAddTimeEntry)

            // ---- ADD: the Add FAB opens the modal, whose confirm proposes 09:00-10:00 on today --------
            onNodeWithContentDescription(strings.locAddTimeEntry).performClick()
            awaitNodeWithTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG)
            onNodeWithTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG).performClick()

            // The new entry's block is named after the dimension it was logged against (the first visible
            // dimension - Physical Health), so its own announcement is the evidence it exists.
            awaitContentDescriptionContaining(FIRST_DIMENSION)
            frame("01-time-with-entry", "the timeline carrying the new entry", copy = FIRST_DIMENSION)

            // ---- EDIT: open the block, change its dimension, save ---------------------------------
            onAllNodesWithContentDescription(FIRST_DIMENSION, substring = true)
                .onFirst()
                .performScrollTo()
                .performClick()
            // The CONTEXT section is collapsed on an existing entry (the modal opens on TIME), so the
            // dimension field is reached by expanding the section whose header carries the dimension label.
            awaitNodeWithText(strings.locLifeDimension)
            onAllNodesWithText(strings.locLifeDimension).onFirst().performClick()
            awaitNodeWithTag("time_entry_dimension_field")
            onNodeWithTag("time_entry_dimension_field").performClick()
            onNodeWithText(SECOND_DIMENSION).performClick()
            onNodeWithTag(EDIT_TIME_ENTRY_CONFIRM_BUTTON_TAG).performClick()

            // The block now announces the NEW dimension - the edit reached the database and came back.
            awaitContentDescriptionContaining(SECOND_DIMENSION)
            frame("02-time-entry-edited", "the timeline with the entry's dimension changed", copy = SECOND_DIMENSION)

            // ---- DELETE: reopen the block and delete it ------------------------------------------
            onAllNodesWithContentDescription(SECOND_DIMENSION, substring = true)
                .onFirst()
                .performScrollTo()
                .performClick()
            awaitNodeWithText(strings.locDelete)
            onNodeWithText(strings.locDelete).performClick()

            awaitContentDescriptionContainingGone(SECOND_DIMENSION)
            assertLogged("TimeViewModel.deleteTimeEntry", "Time entry deleted")
            frame("03-time-after-delete", "the timeline after the entry was deleted", copy = strings.locAddTimeEntry)
        }

    private companion object {
        /** The first visible dimension, by the shared catalog's sort order: the add modal's default. */
        const val FIRST_DIMENSION = "Physical Health"

        /** The second visible dimension: the edit target, so the block's name provably changed. */
        const val SECOND_DIMENSION = "Mental Health"
    }
}
