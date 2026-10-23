//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith


/**
 * Notes CRUD journey on a fresh install: empty state, add two notes, edit one,
 * search, delete one.
 *
 * Every label comes from [E2eLabels] via `l.ui.<property>`, so a ta-IN device
 * resolves it too. Note titles and details the test types are data, not copy,
 * and stay literals.
 *
 * SHARED ROUTE: it calls [FreshSetup.runIfNeeded], so it provisions on a clean install and REUSES the
 * app an earlier class in the same invocation provisioned - one install and one setup for the whole
 * route. Every class that creates list data deletes it, which is what makes sharing the app safe.
 */
@RunWith(AndroidJUnit4::class)
class NotesCrudTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-NOTES-CRUD")

    private val l: E2eLabels.Labels get() = h.labels

    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun notesCrud() {
        FreshSetup(h).runIfNeeded()

        // ── 1. Notes tab, empty state ──────────────────────────────────────────
        h.goToTab(l.ui.settingsDatabaseNotes)
        h.assertVisible(l.ui.locNoNotesYet)
        h.assertVisible(l.ui.locTapAddFirstNote)

        // ── 2. Add two notes ─────────────────────────────────────────────────
        addNote("Alpha Note", "Details Alpha")
        addNote("Beta Note", "Details Beta")
        h.assertVisible("Alpha Note")
        h.assertVisible("Beta Note")
        // The LOG half of parity: the same marker the desktop Notes journey asserts.
        h.assertLogged("NotesViewModel.createNote", "Note created")

        // ── 3. Edit the first card's title ──────────────────────────────────
        // First card is the most recently created note (Beta Note), per the DAO's
        // ORDER BY updatedAt DESC.
        rule.onAllNodesWithTag("notes_note_card").onFirst().performClick()
        h.assertVisible(l.ui.locEditNote)
        val titleField = rule.onAllNodes(
            h.labelled(l.ui.locTitle) and hasSetTextAction(),
        ).onFirst()
        titleField.performTextClearance()
        titleField.performTextInput("Beta Note Edited")
        h.click(l.ui.locSave)
        h.waitForGone(l.ui.locEditNote)
        h.assertVisible("Beta Note Edited")

        // ── 4. Search ────────────────────────────────────────────────────────
        h.type(l.ui.locSearchNotes, "Alpha")
        h.assertVisible("Alpha Note")
        h.assertNotVisible("Beta Note Edited")
        h.click(l.ui.locClearSearch)
        h.assertVisible("Beta Note Edited")

        // ── 5. Delete the second card ───────────────────────────────────────
        rule.onAllNodesWithTag("notes_note_card")[1].performClick()
        h.assertVisible(l.ui.locDeleteNote)
        h.click(l.ui.locDeleteNote)
        h.waitForGone("Alpha Note")
        h.assertNotVisible("Alpha Note")
    }

    /** Adds a note with [title] and [details] through the Add Note dialog. */
    private fun addNote(title: String, details: String) {
        h.click(l.ui.locAddNote)
        h.type(l.ui.locTitle, title)
        h.type(l.ui.locDetailsOptional, details)
        h.click(l.ui.locSave)
        h.waitFor(title)
    }
}
