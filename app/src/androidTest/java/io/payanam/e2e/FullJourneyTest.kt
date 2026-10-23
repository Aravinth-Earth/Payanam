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
 * In-process port of the retired Maestro tier's journey flow — one journey, one execution cycle.
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
 *
 * LOCALE: every label is read from [E2eLabels] rather than written here as English copy, so the whole
 * journey runs on a device in whatever language the app is in. Task titles the suite types in
 * ("Quick Task", "Full Detail Task") are data, not copy, and stay literals — see [TASK_DATA].
 */
@RunWith(AndroidJUnit4::class)
class FullJourneyTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val h = E2eHarness(rule, tag = "E2E")

    /** The app's own copy in the app's current language. */
    private val l: E2eLabels.Labels get() = h.labels

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
        h.assertVisible(l.perDimension)
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
        h.goToTab(l.journal)
        h.capture("journal_screen")
        h.log("phase4=done")

        // ═══ PHASE 5 — NOTES ══════════════════════════════════════════════════════════════════════
        notes()
        h.log("phase5=done")

        // ═══ PHASE 6 — LENSES ═════════════════════════════════════════════════════════════════════
        h.goToTab(l.lenses)
        h.assertVisible(l.lenses)
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
        h.clickNav(l.tasks)
        h.assertVisible(TASK_DATA.quickTask)
        h.assertVisible(TASK_DATA.fullDetailTask)
        h.clickNav(l.lenses)
        h.assertVisible(l.lenses)
        h.log("phase10=done")

        // ═══ PHASE 11 — FINAL LOG EXPORT ══════════════════════════════════════════════════════════
        // Exported a second time because the app's session log is per process: the export in phase 7
        // and this one are different files, and together they cover the whole run.
        h.clickNav(l.settings)
        h.assertVisible(l.settings)
        h.scrollTo(l.debug)
        h.click(l.debug)
        h.scrollTo(l.exportCurrentSessionLog)
        h.click(l.exportCurrentSessionLog)
        h.log("phase11=done")
    }

    /**
     * Port of Phase 8 — opens the passphrase dialog and backs out.
     *
     * The flow deliberately stops at opening it: changing the passphrase here would invalidate the
     * one [UnlockTest] unlocks with. Coverage is "the dialog opens from Data Management".
     */
    private fun passphraseChange() {
        h.clickNav(l.settings)
        h.scrollTo(l.dataManagement)
        h.click(l.dataManagement)
        h.scrollTo(l.updatePassphrase)
        h.click(l.updatePassphrase)
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
        h.clickNav(l.settings)
        h.assertVisible(l.settings)

        // ── appearance: theme mode → dark → back to system ──
        h.scrollTo(l.appearance)
        h.click(l.appearance)
        h.scrollTo(l.themeMode)
        h.click(l.themeMode)
        pick(l.themeDark)
        h.click(l.themeMode)
        pick(l.themeSystem)

        // ── font: the spinner VALUE is the control, not the font-family row label ──
        h.click(l.fontSansSerif)
        h.click(l.fontMonospace)
        h.assertVisible(l.fontMonospace)
        h.click(l.fontMonospace)
        h.click(l.fontSansSerif)
        h.assertVisible(l.fontSansSerif)

        // ── time format: 12 → 24 → 24h confirmed ──
        h.click(l.timeFormat)
        pick(l.timeFormat24h)
        h.click(l.timeFormat)
        pick(l.timeFormat12h)
        h.click(l.timeFormat)
        pick(l.timeFormat24h)

        // ── language ──
        // NOTE: this selects the ENGLISH option. On a device already rendering English that is a
        // no-op; on a Tamil device it switches the app's own copy to English for the rest of the run.
        // That is the pre-existing coverage (phase 7 exercises the language picker) and it is left
        // intact — but it means everything after this line is asserted against English labels, which
        // the labels above handle by re-resolving. See the language note in [E2eLabels].
        h.scrollTo(l.appLanguage)
        h.click(l.appLanguage)
        pick(l.languageEnglish)

        // ── default landing screen, then straight back to Settings ──
        h.scrollTo(l.defaultLandingScreen)
        h.click(l.defaultLandingScreen)
        h.clickNav(l.settings)

        // ── life dimensions (read-only visit) ──
        h.scrollTo(l.lifeDimensions)
        h.click(l.lifeDimensions)
        h.back()
        h.clickNav(l.settings)

        // ── scoring configuration ──
        h.scrollTo(l.scoringConfiguration)
        h.click(l.scoringConfiguration)
        h.click(l.configureScoringWeights)
        h.assertVisible(l.scoringConfiguration)
        h.back()

        // ── data management (Export opens the system file picker — deliberately out of reach) ──
        h.scrollTo(l.dataManagement)
        h.click(l.dataManagement)

        // ── debug: the app's own session-log export ──
        h.scrollTo(l.debug)
        h.click(l.debug)
        h.scrollTo(l.exportCurrentSessionLog)
        h.click(l.exportCurrentSessionLog)

        // ── about ──
        h.scrollTo(l.about)
        h.click(l.about)
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
        h.goToTab(l.habits)
        h.assertVisible(l.noHabitsYet)
        h.capture("tasks_screen")

        h.click(l.addTask)
        h.type(l.taskTitleField, "Daily Meditation")
        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
    }

    /** Port of Phase 3: start the timer, confirm it runs, stop it, dismiss the edit dialog. */
    private fun timeTracking() {
        h.goToTab(l.time)

        h.click(l.startTracking)
        h.click(l.start)
        h.assertVisible(l.stopTracking)

        h.click(l.stopTracking)
        h.click(l.cancel)
        // The modal dismisses asynchronously. Tapping the bottom nav while its scrim is still up
        // swallows the tap and leaves the app on this screen, so wait for the dialog to actually
        // leave before moving on. Maestro's implicit settle did this for free; in-process it has to
        // be explicit. (A condition wait on the app's own state — not a sleep.)
        h.waitForGone(l.cancel)
    }

    /** Port of Phase 5: the empty state, then a note with a title. */
    private fun notes() {
        h.goToTab(l.notes)
        h.assertVisible(l.noNotesYet)
        h.capture("notes_screen")

        h.click(l.addNote)
        h.capture("add_note_dialog")
        h.type(l.noteTitleField, "Test Note")
        h.click(l.save)
        // The dialog may stay open (the flow's own guard); Back is harmless either way.
        h.back()
    }

    /**
     * Port of Phase 1: the empty state, two creates (one minimal, one with a description), then the
     * detail screen. The two shapes matter — the second exercises the optional-field path.
     */
    private fun tasks() {
        h.goToTab(l.tasks)
        h.assertVisible(l.noTasksDueToday)

        // ── create 1: title only ──
        h.click(l.addTask)
        h.assertVisible(l.taskTitleField)
        h.type(l.taskTitleField, TASK_DATA.quickTask)
        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.assertVisible(TASK_DATA.quickTask)

        // ── create 2: title + description ──
        h.click(l.addTask)
        h.type(l.taskTitleField, TASK_DATA.fullDetailTask)
        h.type(l.descriptionOptional, "Important task")
        h.scrollTo(l.saveTask)
        h.click(l.saveTask)
        h.assertVisible(TASK_DATA.fullDetailTask)

        // ── detail screen ──
        h.click(TASK_DATA.fullDetailTask)
        h.assertVisible(l.taskDetails)
        h.back()
    }

    /**
     * Port of the Phase 0 dimension block: edit a dimension (colour picker + icon picker), discard,
     * then exercise the empty-name validation on a new dimension, then proceed.
     */
    private fun dimensionSetup() {
        // The list is taller than the screen; the flow asserts both ends of it.
        h.assertVisible(l.physicalHealth)
        // Visual evidence for the header layout (description row + Add New row) — the running
        // font scale at capture time is whatever the device is set to (large-scale sweeps use this).
        h.capture("dimension_setup_header")

        h.scrollTo(l.workLivelihood)
        h.assertVisible(l.workLivelihood)

        // ── edit dialog opens ──
        h.click(l.edit)
        h.assertVisible(l.editDimensionDialog)

        // ── colour picker (localized swatch labels) ──
        // "Deep Purple 500" is free (no default dimension uses it) and unique.
        // testTag note: the icon picker below uses a testTag, the colour palette uses
        // contentDescription — which is exactly why the harness matches both.
        h.click(l.chooseColor)
        h.assertVisible(l.deepPurple500)
        h.click(l.deepPurple500)
        h.assertVisible(l.editDimensionDialog)

        // ── icon picker (testTag — the 352-icon set is too large to localize, per SPEC.md) ──
        h.click(l.chooseIcon)
        h.clickTag(DIMENSION_FAVORITE_ICON_TAG)
        h.assertVisible(l.editDimensionDialog)

        // Discard — keep the default colours/icons intact for the later phases.
        h.click(l.cancel)
        h.assertNotVisible(l.editDimensionDialog)

        // ── validation: an active dimension must have a name ──
        h.scrollTo(l.addNew)
        h.click(l.addNew)
        h.assertVisible(l.addDimensionDialog)
        h.click(l.save)
        h.assertVisible(l.dimensionNameRequired)
        h.click(l.cancel)

        // ── proceed to the main app ──
        h.click(l.verifyAndProceed)
    }

    private companion object {
        /**
         * The app's own testTag on the favourite icon in the 352-icon picker.
         *
         * A testTag rather than a label: SPEC.md reserves testTags for element sets too large to
         * localize, and this is one. It is not localized and must not be.
         */
        const val DIMENSION_FAVORITE_ICON_TAG = "dimension_icon_favorite"

        /** Titles this suite types into the app, so it can find them again. Data, not localized copy. */
        object TASK_DATA {
            const val quickTask = "Quick Task"
            const val fullDetailTask = "Full Detail Task"
        }
    }
}
