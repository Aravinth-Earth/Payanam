//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.AppLanguageOption
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.mapAppPreferences
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The shell root renders the language it derives from the shared ROW, not from the machine locale.
 *
 * The seam under test is [desktopShellRootSurface] - the composable `desktopShellSurface` calls, and the
 * only place the shell decides its language. [desktopRootSurface] is NOT that seam: it takes the language
 * as a parameter, so a test of it hands the surface the answer and passes just as happily against the
 * machine-locale read it is meant to reject. Rendering `desktopShellSurface` itself is impossible in a
 * test (documented on [desktopShellRootSurface]): its content pane measures the shared graph inside
 * `Modifier.verticalScroll`, and Material3's Scaffold throws on the unbounded height that produces.
 *
 * Properties:
 *
 * 1. A Tamil row renders Tamil copy while the JVM default locale is pinned ENGLISH - the defect the change
 *    replaced. Every case pins the locale that CONTRADICTS its expectation, so the machine-locale read
 *    fails everywhere (the machine running the suite could itself be Tamil).
 * 2. Changing the observed row after first composition re-renders the copy with no restart and no remount.
 *
 * The asserted string is `loc_search_notes`, which differs between the two tables and appears in no
 * hardcoded desktop chrome - so an English assertion cannot be satisfied by the rail or header.
 */
class DesktopShellLanguageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the shell root renders the row's Tamil language while the machine locale says English`() {
        withJvmLocale(ENGLISH_TAG) {
            composeRule.setContent {
                desktopShellRootSurface(preferences = shellPreferences(AppLanguageOption.TAMIL.key)) { _ ->
                    // The ambient table the root installed, read the way a shared screen reads it.
                    Text(text = LocalUiStrings.current.locSearchNotes)
                }
            }

            val tamil = UiStrings.of(UiLanguage.TAMIL)
            val english = UiStrings.of(UiLanguage.ENGLISH)
            assertThat(tamil.locSearchNotes).isNotEqualTo(english.locSearchNotes)

            composeRule.onNodeWithText(tamil.locSearchNotes).assertIsDisplayed()
            composeRule.onNodeWithText(english.locSearchNotes).assertDoesNotExist()
        }
    }

    @Test
    fun `changing the observed row after first composition re-renders the copy with no restart`() {
        // The machine locale pinned to the language the case does NOT expect, exactly as the case above
        // pins it. Without this the machine-locale read this file exists to reject would decide the
        // answer on a Tamil host, and the case would pass vacuously there.
        withJvmLocale(TAMIL_TAG) {
            // State, exactly as a settings write leaves it: the surface below is never remounted and this
            // test calls `setContent` once.
            var preferences by mutableStateOf(shellPreferences(AppLanguageOption.ENGLISH.key))
            composeRule.setContent {
                desktopShellRootSurface(preferences = preferences) { _ ->
                    Text(text = LocalUiStrings.current.locSearchNotes)
                }
            }

            val english = UiStrings.of(UiLanguage.ENGLISH)
            val tamil = UiStrings.of(UiLanguage.TAMIL)
            composeRule.onNodeWithText(english.locSearchNotes).assertIsDisplayed()
            composeRule.onNodeWithText(tamil.locSearchNotes).assertDoesNotExist()

            // The row changes underneath the running composition - the second language the user picked.
            composeRule.runOnIdle { preferences = shellPreferences(AppLanguageOption.TAMIL.key) }
            composeRule.waitForIdle()

            composeRule.onNodeWithText(tamil.locSearchNotes).assertIsDisplayed()
            composeRule.onNodeWithText(english.locSearchNotes).assertDoesNotExist()
        }
    }

    /**
     * The preferences [mapAppPreferences] produces for one stored language row - the SAME mapper the
     * preferences source calls, so the row (not a test's opinion of it) decides `effectiveLanguageTag`.
     */
    private fun shellPreferences(languageKey: String): AppPreferencesState =
        mapAppPreferences(
            settings =
                buildMap {
                    put(AppPreferenceKeys.KEY_APP_LANGUAGE, languageKey)
                    DesktopTopLevelRoute.entries.forEach { route ->
                        put("${AppPreferenceKeys.KEY_TAB_VISIBLE_PREFIX}${route.storageKey}", "true")
                    }
                },
            catalogRows = emptyList(),
            systemLanguageTag = ENGLISH_TAG,
            backupStatus = null,
            defaultDebugLoggingEnabled = false,
        )

    /**
     * Runs [block] with the JVM default locale pinned to [localeTag], restoring the previous one after.
     *
     * Restored in a `finally`: the default locale is process-wide, and a leaked override would silently
     * decide a later case's answer - including the machine-locale read's own test.
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

    private companion object {
        /** The platform tag this fixture reports, and the machine locale every Tamil case pins against. */
        const val ENGLISH_TAG = "en"

        /**
         * The locale the recomposition case pins against: the machine here says Tamil while the case
         * expects English first, so a machine-locale read cannot satisfy the first assertion.
         */
        const val TAMIL_TAG = "ta"
    }
}
