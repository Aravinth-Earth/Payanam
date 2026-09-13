//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.payanam.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-process port of `maestro/e2e/payanam_e2e.yaml` — one journey, one execution cycle.
 *
 * Same rules as the Maestro tier: behaviour-driven only (no mocks, no internal state, no DB
 * manipulation), zero coordinate taps, every action once, sequential navigation.
 *
 * REQUIRES A FRESH INSTALL. Phase 0 exercises the first-run journey, which Maestro reaches with
 * `clearState: true`. In-process the equivalent is a fresh install, so run this class through
 * `build-tools/scripts/run-inprocess-tests.ps1` with `-KeepInstalled $false`.
 *
 * Phase 9 (unlock after a process restart) cannot live here — an instrumentation run hosts every
 * test method in one app process, so the session never closes. It lives in [UnlockTest], which the
 * runner starts as a second invocation.
 */
@RunWith(AndroidJUnit4::class)
class FullJourneyTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E")

    /** The device sleeps on a short timeout mid-build; a stopped Activity has no Compose tree. */
    @Before
    fun keepScreenAwake() {
        keepScreenOn(rule.activity)
    }

    @Test
    fun fullJourney() {
        val setup = FreshSetup(h)
        setup.run()

        // ═══ PHASE 0 (cont.) — LIFE DIMENSION SETUP ═══════════════════════════════════════════════
        dimensionSetup()

        // ═══ MAIN SCREEN REACHED ══════════════════════════════════════════════════════════════════
        h.assertVisible("Per Dimension")
        h.log("phase0=done")

        // ═══ PHASE 1 — TASKS ══════════════════════════════════════════════════════════════════════
        tasks()
        h.log("phase1=done")

        // ═══ PHASE 2 — HABITS ═════════════════════════════════════════════════════════════════════
        habits()
        h.log("phase2=done")

        // ═══ PHASE 3 — TIME ═══════════════════════════════════════════════════════════════════════
        timeTracking()
        h.log("phase3=done")

        // ═══ PHASE 4 — JOURNAL ════════════════════════════════════════════════════════════════════
        h.goToTab("Journal")
        h.capture("journal_screen")
        h.log("phase4=done")

        // ═══ PHASE 5 — NOTES ══════════════════════════════════════════════════════════════════════
        notes()
        h.log("phase5=done")

        // ═══ PHASE 6 — LENSES ═════════════════════════════════════════════════════════════════════
        h.goToTab("Lenses")
        h.assertVisible("Lenses")
        h.capture("lenses_main")
        h.log("phase6=done")

        // ═══ PHASE 7 — SETTINGS ═══════════════════════════════════════════════════════════════════
        settings()
        h.log("phase7=done")

        // ═══ PHASE 8 — PASSPHRASE CHANGE ══════════════════════════════════════════════════════════
        passphraseChange()
        h.log("phase8=done")

        // ═══ PHASE 10 — DATA PERSISTENCE ══════════════════════════════════════════════════════════
        // Everything created earlier in this run must still be there after all the navigating.
        h.clickNav("Tasks")
        h.assertVisible("Quick Task")
        h.assertVisible("Full Detail Task")
        h.clickNav("Lenses")
        h.assertVisible("Lenses")
        h.log("phase10=done")

        // ═══ PHASE 11 — FINAL LOG EXPORT ══════════════════════════════════════════════════════════
        // Exported a second time because the app's session log is per process: the export in phase 7
        // and this one are different files, and together they cover the whole run.
        h.clickNav("Settings")
        h.assertVisible("Settings")
        h.scrollTo("Debug")
        h.click("Debug")
        h.scrollTo("Export Current Session Log")
        h.click("Export Current Session Log")
        h.log("phase11=done")
    }

    /**
     * Port of Phase 8 — opens the passphrase dialog and backs out.
     *
     * The flow deliberately stops at opening it: changing the passphrase here would invalidate the
     * one [UnlockTest] unlocks with. Coverage is "the dialog opens from Data Management".
     */
    private fun passphraseChange() {
        h.clickNav("Settings")
        h.scrollTo("Data Management")
        h.click("Data Management")
        h.scrollTo("Update Passphrase")
        h.click("Update Passphrase")
        h.capture("passphrase_change")
        h.back()
    }

    /**
     * Port of Phase 7 — the settings surfaces, exercised one at a time.
     *
     * Every value is set twice on purpose (there and back) so the run leaves no changed state behind
     * and the toggling itself is what gets exercised. Every selection is required: the option sheet
     * is opened by the row tap directly above it and the waiting assert fails the journey when the
     * option is absent, instead of silently skipping the feature.
     */
    private fun settings() {
        h.clickNav("Settings")
        h.assertVisible("Settings")

        // ── appearance: theme mode → dark → back to system ──
        h.scrollTo("Appearance")
        h.click("Appearance")
        h.scrollTo("Theme Mode")
        h.click("Theme Mode")
        pick("Dark Theme")
        h.click("Theme Mode")
        pick("System Theme")

        // ── font: the spinner VALUE is the control, not the "Font Family" label ──
        h.click("Font: Sans Serif")
        h.click("Font: Monospace")
        h.assertVisible("Font: Monospace")
        h.click("Font: Monospace")
        h.click("Font: Sans Serif")
        h.assertVisible("Font: Sans Serif")

        // ── time format: 12 → 24 → 24h confirmed ──
        h.click("Time Format")
        pick("24-hour clock")
        h.click("Time Format")
        pick("12-hour clock")
        h.click("Time Format")
        pick("24-hour clock")

        // ── language ──
        h.scrollTo("App Language")
        h.click("App Language")
        pick("English")

        // ── default landing screen, then straight back to Settings ──
        h.scrollTo("Default landing screen")
        h.click("Default landing screen")
        h.clickNav("Settings")

        // ── life dimensions (read-only visit) ──
        h.scrollTo("Life Dimensions")
        h.click("Life Dimensions")
        h.back()
        h.clickNav("Settings")

        // ── scoring configuration ──
        h.scrollTo("Scoring Configuration")
        h.click("Scoring Configuration")
        h.click("Configure Scoring Weights")
        h.assertVisible("Scoring Configuration")
        h.back()

        // ── data management (Export opens the system file picker — deliberately out of reach) ──
        h.scrollTo("Data Management")
        h.click("Data Management")

        // ── debug: the app's own session-log export ──
        h.scrollTo("Debug")
        h.click("Debug")
        h.scrollTo("Export Current Session Log")
        h.click("Export Current Session Log")

        // ── about ──
        h.scrollTo("About")
        h.click("About")
        h.capture("settings_about")
    }

    /**
     * Required sheet selection: the assert waits for the option sheet (opened by the row tap just
     * above it) and fails the journey when the option is absent — a required toggle must never be
     * silently skipped, or the run is green without exercising the feature.
     */
    private fun pick(option: String) {
        h.assertVisible(option)
        h.click(option)
    }

    /**
     * Port of Phase 2. The Maestro flow notes that the save may not complete because a habit wants
     * a recurrence type, so the phase asserts nothing after Save — the port keeps the same reach.
     */
    private fun habits() {
        h.goToTab("Habits")
        h.assertVisible("No Habits Yet")

        h.click("Add Task")
        h.type(TASK_TITLE_FIELD, "Daily Meditation")
        h.scrollTo("Save Task")
        h.click("Save Task")
    }

    /** Port of Phase 3: start the timer, confirm it runs, stop it, dismiss the edit dialog. */
    private fun timeTracking() {
        h.goToTab("Time")

        h.click("Start Tracking")
        h.click("Start")
        h.assertVisible("Stop Tracking")

        h.click("Stop Tracking")
        h.click("Cancel")
        // The modal dismisses asynchronously. Tapping the bottom nav while its scrim is still up
        // swallows the tap and leaves the app on this screen, so wait for the dialog to actually
        // leave before moving on. Maestro's implicit settle did this for free; in-process it has to
        // be explicit. (A condition wait on the app's own state — not a sleep.)
        h.waitForGone("Cancel")
    }

    /** Port of Phase 5: the empty state, then a note with a title. */
    private fun notes() {
        h.goToTab("Notes")
        h.assertVisible("No Notes Yet")

        h.click("Add Note")
        h.capture("add_note_dialog")
        h.type("Title", "Test Note")
        h.click("Save")
        // The dialog may stay open (the flow's own guard); Back is harmless either way.
        h.back()
    }

    /**
     * Port of Phase 1: the empty state, two creates (one minimal, one with a description), then the
     * detail screen. The two shapes matter — the second exercises the optional-field path.
     */
    private fun tasks() {
        h.goToTab("Tasks")
        h.assertVisible(NO_TASKS)

        // ── create 1: title only ──
        h.click("Add Task")
        h.assertVisible(TASK_TITLE_FIELD)
        h.type(TASK_TITLE_FIELD, "Quick Task")
        h.scrollTo("Save Task")
        h.click("Save Task")
        h.assertVisible("Quick Task")

        // ── create 2: title + description ──
        h.click("Add Task")
        h.type(TASK_TITLE_FIELD, "Full Detail Task")
        h.type("Description (optional)", "Important task")
        h.scrollTo("Save Task")
        h.click("Save Task")
        h.assertVisible("Full Detail Task")

        // ── detail screen ──
        h.click("Full Detail Task")
        h.assertVisible("Task Details")
        h.back()
    }

    /**
     * Port of the Phase 0 dimension block: edit a dimension (colour picker + icon picker), discard,
     * then exercise the empty-name validation on a new dimension, then proceed.
     */
    private fun dimensionSetup() {
        // The list is taller than the screen; the flow asserts both ends of it.
        h.assertVisible("Physical Health")

        h.scrollTo("Work & Livelihood")
        h.assertVisible("Work & Livelihood")

        // ── edit dialog opens ──
        h.click("Edit")
        h.assertVisible(EDIT_DIALOG)

        // ── colour picker (localized swatch labels) ──
        // "Deep Purple 500" is free (no default dimension uses it) and unique.
        // testTag note: the icon picker below uses a testTag, the colour palette uses
        // contentDescription — which is exactly why the harness matches both.
        h.click("Choose color")
        h.assertVisible("Deep Purple 500")
        h.click("Deep Purple 500")
        h.assertVisible(EDIT_DIALOG)

        // ── icon picker (testTag surfaced as resource-id in Maestro) ──
        h.click("Choose icon")
        h.clickTag("dimension_icon_favorite")
        h.assertVisible(EDIT_DIALOG)

        // Discard — keep the default colours/icons intact for the later phases.
        h.click("Cancel")
        h.assertNotVisible(EDIT_DIALOG)

        // ── validation: an active dimension must have a name ──
        h.scrollTo("Add New")
        h.click("Add New")
        h.assertVisible(ADD_DIALOG)
        h.click("Save")
        h.assertVisible("Every active dimension must have a name.")
        h.click("Cancel")

        // ── proceed to the main app ──
        h.click("Verify and proceed")
    }

    private companion object {
        const val EDIT_DIALOG = "Edit life dimension"
        const val ADD_DIALOG = "Add life dimension"
        const val NO_TASKS = "No Tasks Due Today"
        const val TASK_TITLE_FIELD = "Task Title"
    }
}
