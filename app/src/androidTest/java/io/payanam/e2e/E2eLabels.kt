//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import android.content.Context
import io.payanam.R
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.uiStringsFor
import java.util.Locale

/**
 * Locale-agnostic label vocabulary for the in-process E2E tier.
 *
 * WHY THIS EXISTS
 *
 * The suite used to hardcode English UI text (`h.click("Save Task")`, `h.goToTab("Lenses")`). That
 * makes every label a bet that the device is English. A phone whose locale is `ta-IN` renders the
 * app's OWN Tamil copy, so a perfectly correct app failed the tier with
 * `'Lenses' is not on this screen, even after scrolling` while the screen was plainly showing
 * `காட்சிகள்`.
 *
 * The fix is not more literals and not testTags bolted onto production screens. It is to resolve each
 * label from the SAME string tables the app renders from, so the test asks for "whatever the app calls
 * this control in the language it is currently in".
 *
 * Per `docs/e2e/SPEC.md`'s "label & selector policy": a `contentDescription` is the mechanism for a
 * *user-facing* label, a `testTag` for a test-only identifier. The labels below are the former — they
 * are real user-facing copy — so they are read from the localized tables rather than given a testTag.
 * Nothing is added to a composable; this is purely a test-side change.
 *
 * WHERE THE LABELS COME FROM
 *
 *  - [UiStrings] (`core/strings`) carries the copy the shared `commonMain` screens render. It is
 *    generated at build time from `core/strings/src/main/res/values{,-ta}/strings.xml`, with EN/TA
 *    parity enforced by the generator, so a key exists for both languages or the build fails.
 *  - The app module's own `R.string.*` covers the rest, and Android's resource resolution applies the
 *    active per-app locale to it the same way it does to the app's own `stringResource` calls.
 *
 * [localeTag] is read the same way `MainActivity.resolveSystemLanguageTag` / `resolveCurrentAppLanguage`
 * decide what the app is in: a per-app locale override if one is set (API 33+), otherwise the
 * configuration's locale. Both tables are then resolved for that tag, so the two sources cannot drift
 * apart on the screen the user is actually looking at.
 *
 * WHY NOT A testTag EVERYWHERE
 *
 * A testTag would be stable, but adding one to every screen would mean editing production composables
 * for a test-only concern, and it would not prove the app is usable in the user's language — the thing a
 * localized label check actually exercises. `docs/e2e/SPEC.md` reserves testTags for elements too large
 * to localize (the 352-icon picker); those keep their testTag (`dimension_icon_favorite`), and this
 * suite still taps them with `clickTag`.
 *
 * WHY SUBSTRING MATCHING IS STILL SAFE HERE
 *
 * `E2eHarness.labelled` matches substrings, and SPEC.md warns that `"Green 600"` also matches
 * `"Light Green 600"`. The Tamil table has the same shape of collision as the English one it replaces
 * (`locStart` "தொடங்கு" inside `locStartTracking` "கண்காணிப்பைத் தொடங்கவும்"), so this does not make
 * the tier *more* fragile than it already was — it moves every label onto the app's own copy, in the
 * app's own language. Where a call site needs the exact node it uses `labelledExactly`, and where the
 * existing suite already disambiguated (bottom-nav picks the lowest clickable match) that still applies.
 *
 * Values the test itself writes (task titles like `"Quick Task"`) are NOT localized — they are data
 * the test types in, and they are compared against what the test typed. Those stay literals, in
 * [FullJourneyTest]/[SmokeInProcessTest]/[UnlockTest], deliberately.
 */
object E2eLabels {

    /**
     * The tag the app is currently rendering in.
     *
     * Prefers the per-app locale override (what `AppCompat`/`LocaleManager` applies to the Activity's
     * resources) and falls back to the device configuration, which is what a phone with no override set
     * uses. Falls back to English for an unrecognized language, matching
     * `io.payanam.core.ui.text.UiLanguage.of`.
     */
    fun localeTag(context: Context): String {
        val locales = context.resources.configuration.locales
        // LocaleList is 0-based and non-null while it has entries, but an empty one reports size 0 —
        // ask it for the count before indexing rather than assuming a locale exists.
        val locale = if (locales.isEmpty) null else locales[0]
        val language = locale?.language?.lowercase(Locale.ROOT)
            ?: Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (language == "ta") "ta" else "en"
    }

    /**
     * Every label the suite looks up, resolved for [context]'s locale.
     *
     * [context] should be the app under test's own context: the labels must come from the same
     * resources the Activity renders with, including any per-app locale override Android has applied.
     */
    fun of(context: Context): Labels = Labels(context.applicationContext)

    /** The resolved label set. See [E2eLabels] for why these are resource-backed. */
    class Labels internal constructor(private val context: Context) {

        /**
         * The shared screens' copy for the locale in force RIGHT NOW.
         *
         * Recomputed on every access rather than cached in a constructor. Phase 7 of [FullJourneyTest]
         * switches the app's own language through the Settings picker, so the locale genuinely changes
         * mid-run — a table captured once at harness construction would answer in the OLD language for
         * the rest of the run, which is the same class of bug as the English literals this replaces, just
         * smaller. The lookup is an enum branch plus a field read, so re-resolving per call is free next
         * to the semantics-tree query it is an argument to.
         */
        private val shared: UiStrings
            get() = uiStringsFor(localeTag(context))

        /** Resolves a string resource through the same locale Android applies to the app's own calls. */
        private fun res(id: Int): String = context.getString(id)

        // ── bottom navigation ──────────────────────────────────────────────────────────────────────
        val tasks: String get() = shared.settingsDatabaseTasks
        val notes: String get() = shared.settingsDatabaseNotes
        val journal: String get() = shared.locJournal
        val lenses: String get() = shared.locLenses
        val time: String get() = shared.locTime
        val habits: String get() = shared.locHabits
        val settings: String get() = shared.settingsTitle

        // ── tasks ───────────────────────────────────────────────────────────────────────────────────
        val perDimension: String get() = shared.locPerDimension
        val noTasksDueToday: String get() = shared.locNoTasksDueToday
        val addTask: String get() = shared.locAddTask
        val saveTask: String get() = shared.locSaveTask
        val taskTitleField: String get() = shared.locTaskTitle
        val descriptionOptional: String get() = shared.locDescriptionOptional
        val taskDetails: String get() = shared.locTaskDetails

        // ── habits ──────────────────────────────────────────────────────────────────────────────────
        val noHabitsYet: String get() = shared.locNoHabitsYet

        // ── time tracking ──────────────────────────────────────────────────────────────────────────
        val startTracking: String get() = shared.locStartTracking
        val start: String get() = shared.locStart
        val stopTracking: String get() = shared.locStopTracking
        val cancel: String get() = shared.settingsActionCancel

        // ── notes ───────────────────────────────────────────────────────────────────────────────────
        val noNotesYet: String get() = shared.locNoNotesYet
        val addNote: String get() = shared.locAddNote
        val noteTitleField: String get() = shared.locTitle
        val save: String get() = shared.locSave

        // ── dimension setup ─────────────────────────────────────────────────────────────────────────
        val physicalHealth: String get() = shared.locDimensionNamePhysicalHealth
        val workLivelihood: String get() = shared.locDimensionNameWorkLivelihood
        val editDimensionDialog: String get() = shared.dbInitDimensionSetupEditDialogTitle
        val addDimensionDialog: String get() = shared.dbInitDimensionSetupAddDialogTitle
        val edit: String get() = shared.locEdit
        val chooseColor: String get() = shared.dimensionPickerChooseColor
        val chooseIcon: String get() = shared.dimensionPickerChooseIcon
        val addNew: String get() = shared.dbInitDimensionSetupAddNewAction
        val dimensionNameRequired: String get() = shared.dbInitDimensionSetupErrorNameRequired
        val verifyAndProceed: String get() = shared.dbInitDimensionSetupContinueAction
        val dimensionsTitle: String get() = shared.dbInitDimensionSetupTitle

        // ── first run / database init / passphrase ──────────────────────────────────────────────────
        val welcomeToPayanam: String get() = shared.locWelcomeToPayanam
        val createNewEmptyDatabase: String get() = shared.locCreateNewEmptyDatabase
        val secureYourDatabase: String get() = shared.dbPassphraseSetupTitle
        val passphraseField: String get() = shared.dbPassphraseInputLabel
        val confirmPassphraseField: String get() = shared.dbPassphraseConfirmLabel
        val setPassphraseAndContinue: String get() = shared.dbPassphraseSetupAction
        val unlock: String get() = shared.dbPassphraseUnlockAction

        /**
         * The unlock gate's forgot-passphrase entry, and the destructive action its dialog confirms.
         *
         * Both are needed by `ResetJourneyTest`, which drives the reset the way a user does: the entry on the
         * gate, then the confirmation. Read from the shared table like every other label here, so the journey
         * runs on a Tamil-locale device unchanged.
         */
        val forgotPassphrase: String get() = shared.dbPassphraseUnlockForgotAction
        val resetConfirm: String get() = shared.dbPassphraseUnlockResetConfirm

        /**
         * The onboarding tour's forward action.
         *
         * The tour is a three-slide pager: the button reads "Next" until the last slide and "Get
         * Started" on it, and "Skip Tour" disappears there too. A fresh install opens on slide 1, but
         * the slide a run resumes on is not something the test controls, so a single label cannot
         * identify the tour. This returns the LAST slide's action — the one that ends the tour from any
         * slide — because the tour is dismissed by the pager being advanced, not by any one label.
         *
         * [FreshSetup] treats "either the tour's action or the database-init title is on screen" as the
         * first-run signal, which is slide-independent.
         */
        val onboardingAction: String get() = shared.onboardingActionStart

        /** "Skip Tour", which only exists off the last slide. */
        val onboardingSkip: String get() = shared.onboardingActionSkip

        /** "Next", the forward action on every slide but the last. */
        val onboardingNext: String get() = shared.onboardingActionNext

        // ── settings ────────────────────────────────────────────────────────────────────────────────
        val about: String get() = shared.settingsAboutTitle
        val appearance: String get() = shared.settingsAppearanceTitle
        val themeMode: String get() = shared.settingsThemeMode
        val themeDark: String get() = shared.settingsOptionThemeDark
        val themeSystem: String get() = shared.settingsOptionThemeSystem
        val fontSansSerif: String get() = shared.settingsOptionFontSansSerif
        val fontMonospace: String get() = shared.settingsOptionFontMonospace
        val timeFormat: String get() = shared.settingsTimeFormat
        val timeFormat12h: String get() = shared.settingsOptionTimeFormat12h
        val timeFormat24h: String get() = shared.settingsOptionTimeFormat24h
        val appLanguage: String get() = shared.settingsAppLanguage
        val languageEnglish: String get() = shared.settingsLanguageOptionEnglish
        val defaultLandingScreen: String get() = shared.settingsDefaultLandingTitle
        val lifeDimensions: String get() = shared.settingsLifeDimensionsTitle
        val scoringConfiguration: String get() = shared.settingsScoringTitle
        val configureScoringWeights: String get() = shared.settingsActionConfigureScoring
        val dataManagement: String get() = shared.settingsDataManagementTitle
        val updatePassphrase: String get() = shared.settingsActionChangePassphrase
        val debug: String get() = shared.settingsDebugTitle
        val exportCurrentSessionLog: String get() = shared.settingsActionExportLatestLog

        // ── colour picker (localized swatch labels; contentDescription, per SPEC.md) ─────────────────
        val deepPurple500: String get() = res(R.string.dimension_color_deep_purple_500)

        // ── assistant ───────────────────────────────────────────────────────────────────────────────
        val aiAssistance: String get() = shared.assistantTitle
        val experimentalChip: String get() = shared.assistantChipExperimental
        val assistantOpen: String get() = shared.assistantEntryCta

        // ── focus-mode onboarding gate ───────────────────────────────────────────────────────────────
        // A startup GATE, not a screen the journey navigates to: it is evaluated when the ACTIVITY starts, so a
        // database whose focus-mode onboarding was never completed is asked to choose a mode on the next launch.
        // The copy comes from the shared table like every other label here, so a Tamil device resolves it too.
        val chooseYourFocus: String get() = shared.chooseYourFocus
        val skipFocusMode: String get() = shared.skipFocusMode
    }
}
