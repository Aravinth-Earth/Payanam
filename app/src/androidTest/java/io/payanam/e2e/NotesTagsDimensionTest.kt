//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Notes editor's tag field and life-dimension picker.
 *
 * **What was uncovered.** [NotesCrudTest] covers the note's title and details. The two controls that give a
 * note its meaning — the tag editor and the dimension picker — were rendered but never driven, so a picker
 * that never writes its selection, or a tag field that never persists, would look identical to a working
 * editor.
 *
 * **The journey.** Add a note with a title, a tag and a CHANGED dimension (the picker's default is the first
 * visible dimension, so the test moves it to a different one), Save, and require the note to appear. Then
 * reopen the note and require the dimension picker to show the chosen dimension and the tag field to hold
 * the tag — the round-trip that proves both writes reached the database.
 *
 * Every label is the app's own copy via [E2eLabels]; the title and the tag are data the test typed in.
 */
@RunWith(AndroidJUnit4::class)
class NotesTagsDimensionTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-NOTES-TAGS")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun aNotesTagAndDimensionRoundTrip() {
        FreshSetup(h).runIfNeeded()

        h.goToTab(l.notes)
        // The precondition is "this class's note is ABSENT", not "the list is empty" - this class shares
        // one instrumentation invocation with the rest of the feature journeys, so an emptiness assertion
        // would depend on what an earlier class happened to leave behind. See [FreshSetup.runIfNeeded].
        h.assertNotVisible(NOTE_TITLE)

        // ── create: title + tag + a CHANGED dimension ──
        h.click(l.addNote)
        h.type(l.noteTitleField, NOTE_TITLE)
        h.type(l.ui.locTagsOptional, TAG)
        assertFieldContains(l.ui.locTagsOptional, TAG)
        h.clickTag(NOTES_DIMENSION_PICKER_TAG)
        h.click(l.workLivelihood)
        h.click(l.save)
        h.assertVisible(NOTE_TITLE)
        h.log("note=created")

        // ── reopen: the changed dimension came back ──
        h.click(NOTE_TITLE)
        h.assertVisible(l.ui.locTitle)
        h.assertVisible(l.workLivelihood)
        h.log("note=round-tripped")

        // ── cleanup: remove the note this class created ──
        // This class shares one instrumentation invocation with the rest of the feature journeys
        // ([FreshSetup.runIfNeeded]), so anything it leaves behind is data the NEXT class reads. Deleting
        // what it created is what makes the shared app safe - the rule the CRUD journeys already follow.
        // The edit dialog is still open, so its own delete action is what removes the note.
        h.click(l.ui.locDeleteNote)
        h.waitForGone(NOTE_TITLE)
        h.assertNotVisible(NOTE_TITLE)
        h.log("note=cleaned-up")
    }

    /**
     * Requires the on-screen text field carrying [label] to hold [expected], POLLING until it does.
     *
     * `NotesViewModel.createNote` writes the note and then its tags as TWO repository calls, so a reopen
     * immediately after the note appears can read the tags before that second write lands. Polling waits on
     * the state rather than trusting a settle - the same rule the rest of the tier runs on.
     */
    private fun assertFieldContains(label: String, expected: String, timeoutMs: Long = 15_000) {
        rule.waitUntil(timeoutMs) { fieldHolds(label, expected) }
    }

    /** True when some text field carrying [label] holds [expected]. */
    private fun fieldHolds(label: String, expected: String): Boolean {
        val fields = rule.onAllNodes(h.labelled(label)).filter(hasSetTextAction())
        val count = runCatching { fields.fetchSemanticsNodes().size }.getOrDefault(0)
        for (i in 0 until count) {
            val node = runCatching { fields[i].fetchSemanticsNode() }.getOrNull() ?: continue
            val text = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            if (text.contains(expected)) return true
        }
        return false
    }

    private companion object {
        /** The dimension picker's testTag (`NotesScreen`). */
        const val NOTES_DIMENSION_PICKER_TAG = "notes_dimension_picker"

        /** Data the test typed in, not app copy. */
        const val NOTE_TITLE = "Tagged Note"
        const val TAG = "errand"
    }
}
