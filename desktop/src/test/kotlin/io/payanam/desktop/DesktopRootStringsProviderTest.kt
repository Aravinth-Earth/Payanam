//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.material.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.mapAppPreferences
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.preferences.AppPreferenceKeys
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The desktop root installs the copy table for the language the SHARED rows resolved to.
 *
 * Two properties, and the second is the defect this file exists for:
 *
 * 1. [LocalUiStrings] defaults to `error(...)` and only `PayanamNavHost` installs it - the graph the
 *    desktop's startup flow does not mount. A shared screen rendered under the root would die the moment
 *    it read its copy unless the root installs the table itself, so that install is pinned here.
 * 2. The root renders the language from `AppPreferencesState.effectiveLanguageTag`, NOT the machine
 *    locale. The root used to read `Locale.getDefault()`, so a desktop whose stored choice was Tamil
 *    rendered English on any machine whose JVM locale was not `ta`. These cases drive a stored ROW
 *    through the same shared mapper the preferences source calls, so a row is what decides the language.
 *
 * **Every case pins a JVM default locale that CONTRADICTS its expectation** ([withJvmLocale]). The
 * machine running this suite can itself be Tamil (`LANG=ta_IN.UTF-8` is a real machine here), so a case
 * whose expected language happened to equal the machine's would pass against the machine-locale read it
 * is supposed to reject. Pin the opposite locale and the old implementation fails everywhere.
 *
 * The asserted string is `settings_database_notes`, which genuinely differs between the two tables
 * ([TAMIL_NOTES] != [ENGLISH_NOTES], pinned below) - an English-only string would let cases pass vacuously.
 */
class DesktopRootStringsProviderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        /** `AppLanguageOption.TAMIL.key` / `.ENGLISH.key`, spelt so the stored row under test is visible. */
        const val STORED_TAMIL = "ta"
        const val STORED_ENGLISH = "en"

        /** The tags those rows resolve to, and the tag of a language no table exists for. */
        const val TAG_TAMIL = "ta"
        const val TAG_ENGLISH = "en"
        const val TAG_UNSHIPPED = "fr"

        /** `settings_database_notes` in each table; read from the tables rather than hand-written. */
        val TAMIL_NOTES: String = UiStrings.of(UiLanguage.TAMIL).settingsDatabaseNotes
        val ENGLISH_NOTES: String = UiStrings.of(UiLanguage.ENGLISH).settingsDatabaseNotes
    }

    @Test
    fun sharedScreenReadsItsCopyUnderTheDesktopRoot() {
        // Machine locale forced to Tamil against an English expectation: under the machine-locale read this
        // case replaces, the root would render Tamil copy and this assertion would fail.
        withJvmLocale(TAG_TAMIL) {
            composeRule.setContent {
                desktopRootSurface(
                    language = desktopUiLanguageForTag(TAG_ENGLISH),
                    preferences = AppPreferencesState(),
                ) {
                    Text(text = LocalUiStrings.current.settingsDatabaseNotes)
                }
            }

            composeRule.onNodeWithText(ENGLISH_NOTES).assertIsDisplayed()
            assertThat(ENGLISH_NOTES).isNotEmpty()
        }
    }

    @Test
    fun aTamilLanguageRowRendersTamilCopy() {
        // The row first: the language under test is a stored preference, so the tag is resolved the way the
        // desktop's preferences source resolves it rather than being handed over ready-made.
        val tag = effectiveTagFor(storedLanguageKey = STORED_TAMIL, systemLanguageTag = TAG_ENGLISH)
        assertThat(tag).isEqualTo(TAG_TAMIL)

        // Machine locale forced to English, the OPPOSITE of the expectation.
        withJvmLocale(TAG_ENGLISH) {
            renderRootCopy(tag)

            composeRule.onNodeWithText(TAMIL_NOTES).assertIsDisplayed()
            composeRule.onNodeWithText(ENGLISH_NOTES).assertDoesNotExist()
        }
    }

    @Test
    fun systemLanguageChoiceWithATamilSystemTagRendersTamilCopy() {
        // SYSTEM is the desktop's default row value and it defers to the platform's own language - the JVM
        // locale the preferences source is wired with. That tag is passed in explicitly HERE, which is the
        // point: it comes from the shared mapper's input, not from whatever this machine happens to be.
        val tag = effectiveTagFor(storedLanguageKey = "system", systemLanguageTag = TAG_TAMIL)
        assertThat(tag).isEqualTo(TAG_TAMIL)

        withJvmLocale(TAG_ENGLISH) {
            renderRootCopy(tag)

            composeRule.onNodeWithText(TAMIL_NOTES).assertIsDisplayed()
            composeRule.onNodeWithText(ENGLISH_NOTES).assertDoesNotExist()
        }
    }

    @Test
    fun anUnshippedLanguageTagFallsBackToEnglishCopy() {
        // An unrecognized stored row value resolves to SYSTEM, and a system tag no table exists for then
        // falls back to English - neither may render a Tamil table or blow up on the missing copy.
        val tag = effectiveTagFor(storedLanguageKey = "klingon", systemLanguageTag = TAG_UNSHIPPED)
        assertThat(tag).isEqualTo(TAG_ENGLISH)
        assertThat(desktopUiLanguageForTag(TAG_UNSHIPPED)).isEqualTo(UiLanguage.ENGLISH)

        // Machine locale forced to Tamil, the opposite of the English expectation.
        withJvmLocale(TAG_TAMIL) {
            renderRootCopy(tag)

            composeRule.onNodeWithText(ENGLISH_NOTES).assertIsDisplayed()
            composeRule.onNodeWithText(TAMIL_NOTES).assertDoesNotExist()
        }
    }

    @Test
    fun theMachineLocaleFunctionFollowsTheJvmLocale() {
        // The PRE-UNLOCK gate's answer, and only that: no row can be read while the encrypted database is
        // closed, so the gate renders the machine locale. This is the one case where the JVM locale IS the
        // expectation, so it sets both directions rather than pinning a contradiction.
        withJvmLocale(TAG_TAMIL) {
            assertThat(desktopMachineUiLanguage()).isEqualTo(UiLanguage.TAMIL)
        }
        withJvmLocale(TAG_ENGLISH) {
            assertThat(desktopMachineUiLanguage()).isEqualTo(UiLanguage.ENGLISH)
        }
    }

    @Test
    fun theTwoTablesDifferSoTheCasesAboveCannotPassVacuously() {
        // Without this, an English-only string would make every Tamil case above pass for the wrong reason.
        assertThat(TAMIL_NOTES).isNotEqualTo(ENGLISH_NOTES)
        assertThat(TAMIL_NOTES).isNotEmpty()
        assertThat(ENGLISH_NOTES).isNotEmpty()
    }

    /** Renders the root's ambient copy table for [effectiveLanguageTag], as the shell root would. */
    private fun renderRootCopy(effectiveLanguageTag: String) {
        composeRule.setContent {
            desktopRootSurface(
                language = desktopUiLanguageForTag(effectiveLanguageTag),
                preferences = AppPreferencesState(),
            ) {
                Text(text = LocalUiStrings.current.settingsDatabaseNotes)
            }
        }
    }

    /**
     * The effective tag the desktop would hold for [storedLanguageKey], through the SAME shared mapper the
     * preferences source calls - so this is the row's own answer and not a second implementation of it.
     */
    private fun effectiveTagFor(
        storedLanguageKey: String?,
        systemLanguageTag: String,
    ): String =
        mapAppPreferences(
            settings = mapOf(AppPreferenceKeys.KEY_APP_LANGUAGE to storedLanguageKey),
            catalogRows = emptyList(),
            systemLanguageTag = systemLanguageTag,
            backupStatus = null,
            defaultDebugLoggingEnabled = false,
        ).effectiveLanguageTag

    /**
     * Runs [block] with the JVM default locale pinned to [localeTag], restoring the previous one afterwards.
     *
     * Restored in a `finally` because the default locale is process-wide: a case that leaked an override
     * would silently decide a later case's answer.
     */
    private fun withJvmLocale(
        localeTag: String,
        block: () -> Unit,
    ) {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(localeTag))
        try {
            block()
        } finally {
            Locale.setDefault(original)
        }
    }
}
