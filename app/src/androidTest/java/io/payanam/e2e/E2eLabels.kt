//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.e2e

import android.app.LocaleManager
import android.content.Context
import android.os.Build
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
        // Mirror MainActivity.resolveCurrentAppLanguage: the app renders from the OS PER-APP LOCALE
        // (LocaleManager, API 33+), which the plain application context's configuration does not
        // always carry — its Resources can be cached from before the override was applied. Reading
        // only the configuration therefore resolved English labels on a device whose app was
        // rendering Tamil, and the failure looked like a missing screen ("(none)" matches) rather
        // than a locale mismatch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val appLocales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            val appLanguage = appLocales?.takeIf { !it.isEmpty }?.get(0)?.language?.lowercase(Locale.ROOT)
            if (appLanguage != null) return if (appLanguage == "ta") "ta" else "en"
        }
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

        /**
         * The whole shared string table for the locale in force right now.
         *
         * Every label a screen renders through `UiStrings` is reachable here, so a test that needs a
         * label not listed as a named property above can read it directly (`l.ui.locSaveTask`) without
         * this file growing a property per control. Resolved live, like [shared] itself.
         */
        val ui: UiStrings get() = shared

        /** An Android-only string resource (`R.string.*`), resolved for the app's current locale. */
        fun androidString(id: Int): String = res(id)

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
        val assistantConsentProceed: String get() = shared.assistantConsentProceed

        // ── focus-mode onboarding gate ───────────────────────────────────────────────────────────────
        // A startup GATE, not a screen the journey navigates to: it is evaluated when the ACTIVITY starts, so a
        // database whose focus-mode onboarding was never completed is asked to choose a mode on the next launch.
        // The copy comes from the shared table like every other label here, so a Tamil device resolves it too.
        val chooseYourFocus: String get() = shared.chooseYourFocus
        val skipFocusMode: String get() = shared.skipFocusMode

        // ── notification-permission offer ─────────────────────────────────────────────────────────────
        // The app asks for the notification permission AFTER the action that would notify - a started timer, a
        // task saved with a reminder - and never before it. On a device where notifications are switched off
        // (a fresh install on Android 13 and later) that offer is a modal dialog, so a journey that saves a
        // task or starts a timer meets it. Read from the shared table, so a Tamil device resolves it too.
        val notificationOfferTitle: String get() = shared.locNotificationPermissionTitle
        val notificationOfferNotNow: String get() = shared.locNotificationPermissionNotNow

        // ── focus mode + tab visibility (shared settings) ──────────────────────────────────────────
        val focusMode: String get() = shared.focusModeTitle
        val tabVisibility: String get() = shared.tabVisibility

        // ── dimensions ─────────────────────────────────────────────────────────────────────────────
        val lifeDimension: String get() = shared.locLifeDimension

        // ── assistant surfaces ─────────────────────────────────────────────────────────────────────
        val assistantSettings: String get() = shared.assistantSettingsTitle
        val assistantSetupHeadline: String get() = shared.assistantSetupHeadline
        val assistantApiKey: String get() = shared.assistantKeyLabel
        val assistantLoadModels: String get() = shared.assistantKeyLoadModels
        val assistantNoModel: String get() = shared.assistantNoModel
        val assistantGotIt: String get() = shared.assistantNoticeOk

        /** The setup surface's caption: why the feature is optional and where the key is stored. */
        val assistantSetupCaption: String get() = shared.assistantSetupCaption

        /**
         * The unconfigured chat surface's one route into the settings editor.
         *
         * Setup moved off the chat screen, so this button is now the entry a fresh install has: the
         * settings destination is reachable only through it (no key) or through the top-bar gear
         * ([io.payanam.core.ui.assistant.ASSISTANT_SETTINGS_BUTTON], a testTag, because the gear is an
         * icon with no user-facing label of its own).
         */
        val assistantSetupPointer: String get() = shared.assistantSetupPointer

        /** The connection section's own copy: where the model runs, and the two backend choices. */
        val assistantProviderWhere: String get() = shared.assistantProviderWhere
        val assistantProviderLocal: String get() = shared.assistantProviderLocal
        val assistantProviderCloud: String get() = shared.assistantProviderCloud

        /**
         * The key-removal confirmation: raised when saving an endpoint change would present the stored
         * key to a different provider or address.
         */
        val assistantKeyRemovalTitle: String get() = shared.assistantKeyRemovalTitle
        val assistantKeyRemovalBody: String get() = shared.assistantKeyRemovalBody
        val assistantKeyRemovalConfirm: String get() = shared.assistantKeyRemovalConfirm

        // ── update channel: ANDROID-ONLY copy ──────────────────────────────────────────────────────
        // The in-app updater exists only on Android, so these two strings have no shared counterpart —
        // they are resolved from this module's own resources rather than from [shared]. Reading them
        // through [res] keeps them locale-correct on a ta-IN device all the same.
        val updateChannelDev: String get() = res(io.payanam.R.string.settings_update_channel_dev)
        val updateChannelBeta: String get() = res(io.payanam.R.string.settings_update_channel_beta)

        // ── assistant setup + insights surfaces (shared copy) ──────────────────────────────────────
        val assistantModelPick: String get() = shared.assistantModelPick
        val assistantRemoveKey: String get() = shared.assistantRemoveKey
        val assistantSaveStart: String get() = shared.assistantSaveStart
        val assistantNoticeTitle: String get() = shared.assistantNoticeTitle
        val insightsDailyScoreTrend: String get() = shared.settingsInsightsTimeDailyScoreTrendTitle
        val insightsOverallScoreCard: String get() = shared.settingsInsightsTimeOverallScoreCardTitle

        // ── surfaces the fresh-install journey classes reach (shared copy) ─────────────────────────
        val assistantInputHint: String get() = shared.assistantInputHint
        val assistantKeyStoredHint: String get() = shared.assistantKeyStoredHint
        val importAction: String get() = shared.settingsActionImport
        val exportAction: String get() = shared.settingsActionExport
        val insightsChartsVisibility: String get() = shared.settingsInsightsChartsVisibilityTitle
        val lensTimeAverageDaily: String get() = shared.locLensTimeAverageDailyTitle
        val insightsScoreCards: String get() = shared.settingsInsightsTimeScoreCardsTitle
        val insightsLineGraphs: String get() = shared.settingsInsightsTimeLineGraphsTitle
        val lensWeekGrid: String get() = shared.locLensWeekGridTitle
        val lensMinutePattern: String get() = shared.locLensMinutePatternTitle
        val chartExcludeEmptyDays: String get() = shared.locSettingsChartExclEmptyDays

        // ── insights row labels (shared copy; the row lists in InsightsVisibilityTest read these) ──
        val locLensTimeAverageDailyTitle: String get() = shared.locLensTimeAverageDailyTitle
        val locLensDimSplitTitle: String get() = shared.locLensDimSplitTitle
        val locLensDimTrendTitle: String get() = shared.locLensDimTrendTitle
        val locLensHeatmapTitle: String get() = shared.locLensHeatmapTitle
        val locLensWeekGridTitle: String get() = shared.locLensWeekGridTitle
        val locLensMinutePatternTitle: String get() = shared.locLensMinutePatternTitle
        val settingsInsightsTimeOverallSnapshotTitle: String get() = shared.settingsInsightsTimeOverallSnapshotTitle
        val settingsInsightsTimeExecutionDetailsTitle: String get() = shared.settingsInsightsTimeExecutionDetailsTitle
        val locTime: String get() = shared.locTime
        val settingsDatabaseTasks: String get() = shared.settingsDatabaseTasks
        val locHabits: String get() = shared.locHabits
        val locJournalNotes: String get() = shared.locJournalNotes
        val settingsDatabaseNotes: String get() = shared.settingsDatabaseNotes
        val settingsInsightsTimeOverallScoreCardTitle: String get() = shared.settingsInsightsTimeOverallScoreCardTitle
        val settingsInsightsTimeDimensionScoreCardsTitle: String get() = shared.settingsInsightsTimeDimensionScoreCardsTitle
        val settingsInsightsTimeDailyScoreTrendTitle: String get() = shared.settingsInsightsTimeDailyScoreTrendTitle
        val settingsInsightsTimeProgressTrendTitle: String get() = shared.settingsInsightsTimeProgressTrendTitle
        val settingsInsightsTimeHistoricalRankingTitle: String get() = shared.settingsInsightsTimeHistoricalRankingTitle
        val settingsInsightsTimeMomentumStreakTitle: String get() = shared.settingsInsightsTimeMomentumStreakTitle

        /** "Build type mismatch — no compatible update is available for this install." */
        val updateTypeMismatch: String get() = shared.settingsUpdateTypeMismatch

        /**
         * "Dev (daily) channel ships: debug APK" — composed from the SAME resource the app formats, so the
         * expectation follows the locale instead of asserting English copy.
         */
        fun updateChannelShips(channel: String, apk: String): String = shared.settingsUpdateChannelShips.format(channel, apk)

        /** "You are on: debug build" — composed from the app's own pattern for the same reason. */
        fun runningBuildType(buildType: String): String = shared.settingsUpdateRunningBuildType.format(buildType)
    }
}
