//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journal day-screen journey on a fresh install: the journal tab renders the day view, a typed
 * response lands in the first overall reflection prompt, and the date picker opens from the date row,
 * confirms, and leaves the day view (with the response) up.
 *
 * One @Test method, one fresh-install invocation (run with `-KeepInstalled:$false`). Every label is
 * read from [E2eLabels] — the app's own copy, in the app's current language — so the flow runs
 * unchanged on a Tamil-locale device. Two kinds of literal appear, both deliberate:
 * - the first overall prompt text, which is DOMAIN CONTENT (see [FIRST_OVERALL_PROMPT]), not copy;
 * - the response the test types in, which is data the test compares against what it wrote.
 *
 * Scope notes (what is asserted, and why):
 * - The response is read back with `assertTextContains` because a text field exposes its content as
 *   `EditableText`, which the harness's text/contentDescription matchers do not match (measured
 *   2026-10-16: an `assertVisible(response)` found nothing while the field plainly held it).
 * - The "Saved for <date>" status line is NOT asserted: its formatted date is locale-fragile.
 * - Per-dimension reflection cards are collapsed on a fresh entry, so their prompt fields are not
 *   composed and this journey does not open them. There is no journal-delete flow (responses
 *   overwrite in place).
 */
@RunWith(AndroidJUnit4::class)
class JournalCrudTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E-JournalCrud")

    /** The app's own copy in the app's current language. */
    private val l get() = h.labels

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun journalCrudJourney() {
        // ═══ FRESH INSTALL → MAIN APP ═══════════════════════════════════════════════════════════════
        FreshSetup(h).runIfNeeded()

        // ═══ 1. JOURNAL TAB — THE DAY VIEW IS UP ═══════════════════════════════════════════════════
        // locDailyReflection ("Daily Reflection") is the day screen's own section heading — unique to
        // this screen, unlike the top-bar title, which collides with the bottom-nav tab.
        h.goToTab(l.journal)
        h.assertVisible(l.ui.locDailyReflection)
        h.capture("journal_screen")
        h.log("phase1=journalTabUp")

        // ═══ 2. FIRST OVERALL PROMPT — WRITE AND ASSERT ═══════════════════════════════════════════════
        // The overall prompt fields carry a per-prompt testTag (`journal_overall_prompt_<index>`)
        // applied in `DayScreenSummary`, so the harness can aim at the first one directly.
        h.scrollTo(FIRST_OVERALL_PROMPT)
        rule.onAllNodesWithTag("journal_overall_prompt_0").onFirst().performClick()
        rule.onAllNodesWithTag("journal_overall_prompt_0").onFirst().performTextInput(GRATITUDE_RESPONSE)
        rule.onAllNodesWithTag("journal_overall_prompt_0").onFirst().assertTextContains(GRATITUDE_RESPONSE)
        h.log("phase2=promptWritten")

        // ═══ 3. DATE PICKER — OPEN FROM THE DATE ROW, CONFIRM, DAY VIEW STILL UP ════════════════════
        // The picker opens on the selected date (today), so confirming with no change keeps today.
        h.clickTag("journal_date_row")
        h.click(l.ui.locOk)
        h.waitForGone(l.ui.locOk) // the dialog dismissed
        h.assertVisible(l.ui.locDailyReflection) // the day view is still up (not the loading spinner)
        h.log("phase3=datePicker")
    }

    private companion object {
        /**
         * The first overall journal prompt, exactly as the app renders it.
         *
         * This one literal is deliberate: the prompt texts are domain content defined in
         * `io.payanam.shared.journal.JournalReflectionContracts` (surfaced through
         * `io.payanam.presentation.day.OVERALL_JOURNAL_PROMPTS`), NOT localized UI copy — they have no
         * entry in strings.xml and no EN/TA parity, so there is no `l.ui` property to read them from.
         * The test uses the literal only to scroll the field into view; the typing itself goes through
         * the position-based field lookup above, not through this text.
         */
        const val FIRST_OVERALL_PROMPT = "What am I grateful for today?"

        /** The response the test types into the first overall prompt and reads back. */
        const val GRATITUDE_RESPONSE = "Grateful for a quiet morning walk."
    }
}
