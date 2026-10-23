//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.payanam.core.ui.navigation.PayanamTab
import org.junit.Test

/**
 * The desktop's notes journey, walked in-process the way Android's `NotesCrudTest` walks it on a device.
 *
 * **The gap this closes.** The desktop proved the Notes SCREEN renders (`DesktopNavHostSharedScreenRenderTest`
 * pins the FAB and the empty state) and the search field types (`DesktopNotesScreenTest`), but nothing on the
 * desktop ever WROTE a note through the UI: the Add FAB opening the real `NoteDialog`, the typed title reaching
 * `NotesViewModel.createNote` and the encrypted database, the card reopening the edit dialog, the retitle and
 * the delete. Those are the write paths a user takes, and on Android an on-device class covers them - so the
 * desktop's parity claim for Notes rested on render assertions. This drives the same spine through the real
 * graph over the real database: add, edit, delete.
 *
 * **Why the production fixture.** A note write touches `notes` (and its `life_dimensions` foreign key), and the
 * dialog reads its dimension options from `LocalAppPreferences`; so this mounts over
 * [installProductionFixtureLayer] with the REAL preference state ([loadedPreferences]), exactly as the Tasks and
 * Time journeys do.
 *
 * **Why the tab is reached by the first-match form.** The harness's `goToTab` resolves its tab by
 * `onNodeWithText(tab.title(strings))`, and the Notes screen's own top-bar title carries the SAME copy
 * (`settingsDatabaseNotes`) - so after the navigation the label matches twice and the single-node lookup throws.
 * This clicks the first match (the tab control) and waits for idle, the form Android's own click makes.
 *
 * **What it does NOT claim.** It does not walk the search leg, the tags field, the dimension picker or the
 * multi-note ordering; those are other items' claims. The note titles and details are data literals, never app
 * copy.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopNotesJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `a note is added, its title edited, and the note deleted`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            // ---- 1. The Notes tab and its empty state ------------------------------------------
            onAllNodesWithText(PayanamTab.Notes.title(strings)).onFirst().performClick()
            waitForIdle()
            awaitNodeWithText(strings.locNoNotesYet)
            awaitNodeWithText(strings.locTapAddFirstNote)
            frame("00-notes-empty", "the Notes screen with no notes yet", copy = strings.locNoNotesYet)

            // ---- 2. ADD: the Add FAB opens the real dialog, Save reaches createNote --------------
            onNodeWithContentDescription(strings.locAddNote).performClick()
            awaitNodeWithText(strings.locTitle)
            onNodeWithText(strings.locTitle).performTextInput(NOTE_TITLE)
            onNodeWithText(strings.locDetailsOptional).performTextInput(NOTE_DETAILS)
            onNodeWithText(strings.locSave).performClick()

            // Wait for the dialog to close before awaiting the card, so the title is matched on the
            // LIST (the field behind the still-open dialog carries the same text as its value).
            awaitNodeGone(strings.locTitle)
            awaitNodeWithText(NOTE_TITLE)
            // The LOG half of parity: the same marker Android's `NotesCrudTest` emits for the same
            // interaction. A create that worked on one platform and silently failed on the other shows up
            // here as a missing marker.
            assertLogged("NotesViewModel.createNote", "Note created")
            frame("01-notes-with-note", "the Notes list carrying the new note", copy = NOTE_TITLE)

            // ---- 3. EDIT: open the card, retitle it, save ----------------------------------------
            onNodeWithText(NOTE_TITLE).performClick()
            awaitNodeWithText(strings.locEditNote)
            onNodeWithText(strings.locTitle).performTextClearance()
            onNodeWithText(strings.locTitle).performTextInput(EDITED_TITLE)
            onNodeWithText(strings.locSave).performClick()

            // The dialog's title field holds the new text while it is open; its disappearance is what
            // proves the dialog closed, so the card behind it is what the assertions then read.
            awaitNodeGone(strings.locEditNote)
            awaitNodeWithText(EDITED_TITLE)
            awaitNodeGone(NOTE_TITLE)
            frame("02-notes-after-edit", "the list carrying the note under its new title", copy = EDITED_TITLE)

            // ---- 4. DELETE: reopen the note and delete it ----------------------------------------
            onNodeWithText(EDITED_TITLE).performClick()
            awaitNodeWithText(strings.locDeleteNote)
            onNodeWithText(strings.locDeleteNote).performClick()

            awaitNodeGone(EDITED_TITLE)
            awaitNodeWithText(strings.locNoNotesYet)
            frame("03-notes-after-delete", "the Notes screen after the note was deleted", copy = strings.locNoNotesYet)
        }

    private companion object {
        /** A data literal, never app copy: the row is asserted by what the user typed. */
        const val NOTE_TITLE = "Alpha Note"

        /** The note's details - data, not copy, so it is a literal. */
        const val NOTE_DETAILS = "Details Alpha"

        /** The edit target, so the card's text provably changed rather than merely re-rendered. */
        const val EDITED_TITLE = "Beta Note Edited"
    }
}
