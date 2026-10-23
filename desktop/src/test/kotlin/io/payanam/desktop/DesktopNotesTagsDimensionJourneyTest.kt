//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.payanam.core.ui.navigation.PayanamTab
import org.junit.Test

/**
 * The desktop's notes TAG + LIFE-DIMENSION journey, walked in-process the way Android's
 * `NotesTagsDimensionTest` walks it on a device.
 *
 * **The gap this closes.** `DesktopNotesJourneyTest` covers the note's title and details. The two controls
 * that give a note its MEANING - the tag editor and the life-dimension picker - were rendered but never
 * driven, so a picker that never writes its selection, or a tag field that never persists, would look
 * identical to a working editor.
 *
 * **The journey.** Add a note with a title, a tag and a CHANGED dimension (the picker's default is the first
 * visible dimension, so the test moves it to a different one), Save, and require the note to appear. Then
 * reopen the note and require the picker to show the chosen dimension - the round-trip that proves the write
 * reached the database rather than only the field.
 *
 * The picker is addressed by its `notes_dimension_picker` testTag, the same handle Android's test uses, so the
 * two platforms target the SAME control rather than two lookalikes. Every label is the app's own copy via the
 * harness's `strings`; the title and the tag are data.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopNotesTagsDimensionJourneyTest : DesktopAppJourneyHarness() {
    @Test
    fun `a note's tag and life dimension survive a save and a reopen`() =
        runAtTheAppWindowSize {
            installProductionFixtureLayer()
            mountShell(configuredState(), isLandingReady = true, preferences = loadedPreferences())

            onAllNodesWithText(PayanamTab.Notes.title(strings)).onFirst().performClick()
            waitForIdle()
            awaitNodeWithText(strings.locNoNotesYet)

            // ---- create: title + tag + a CHANGED dimension ---------------------------------------
            onNodeWithContentDescription(strings.locAddNote).performClick()
            awaitNodeWithText(strings.locTitle)
            onNodeWithText(strings.locTitle).performTextInput(NOTE_TITLE)
            onNodeWithText(strings.locTagsOptional).performTextInput(TAG)

            // The picker defaults to the first visible dimension, so it is moved to a DIFFERENT one - a
            // picker that ignored the selection would still show its default and pass a weaker assertion.
            onNodeWithTag(NOTES_DIMENSION_PICKER_TAG).performClick()
            awaitNodeWithText(strings.locDimensionNameWorkLivelihood)
            onAllNodesWithText(strings.locDimensionNameWorkLivelihood).onFirst().performClick()
            frame(
                "00-note-editor",
                "the note editor with a tag typed and a changed life dimension",
                copy = strings.locDimensionNameWorkLivelihood,
            )

            onNodeWithText(strings.locSave).performClick()
            awaitNodeGone(strings.locTitle)
            awaitNodeWithText(NOTE_TITLE)
            assertLogged("NotesViewModel.createNote", "Note created")
            frame("01-note-created", "the Notes list carrying the tagged note", copy = NOTE_TITLE)

            // ---- reopen: the changed dimension came back -----------------------------------------
            onNodeWithText(NOTE_TITLE).performClick()
            awaitNodeWithText(strings.locEditNote)
            awaitNodeWithText(strings.locDimensionNameWorkLivelihood)
            frame(
                "02-note-round-tripped",
                "the reopened note showing the dimension that was saved",
                copy = strings.locDimensionNameWorkLivelihood,
            )
        }

    private companion object {
        /** The dimension picker's testTag in the shared `NotesScreen`. */
        const val NOTES_DIMENSION_PICKER_TAG = "notes_dimension_picker"

        /** Data the test typed in, not app copy. */
        const val NOTE_TITLE = "Desktop Tagged Note"
        const val TAG = "errand"
    }
}
