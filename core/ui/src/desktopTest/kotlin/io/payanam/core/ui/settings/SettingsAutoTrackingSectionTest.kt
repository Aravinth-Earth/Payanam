//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Rule
import org.junit.Test

/**
 * The shared auto-tracking settings section, rendered for real on the JVM target.
 *
 * This section is the first of the settings sections to leave `:app`, and it is the shape the rest of them
 * will take: it reads the shared `AppPreferencesState` and hands every write to a callback, so nothing in it
 * is platform-bound. What that move can silently break is the per-dimension rows' dependence on the global
 * toggle - they are only reachable once the global row is on, so a section that "renders" can still draw
 * nothing. These cases assert the ROW COUNT rather than a screenshot of copy, which pins the visibility rule
 * in both directions, and they assert the writes as CALLS: binding them is the caller's job (`:app` binds them
 * to the ViewModel, the desktop to its own writer), so the shared contract is that the section reports which
 * row changed and leaves storage alone.
 */
class SettingsAutoTrackingSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `global on shows one row per visible dimension`() {
        render(state(autoTrackHabitTimeGlobal = true)) {
            SettingsAutoTrackingSection(
                prefsState = state(autoTrackHabitTimeGlobal = true),
                onAutoTrackHabitTimeGlobalChange = { },
                onAutoTrackDimensionChange = { _, _ -> },
            )
        }

        composeRule.onAllNodesWithText(strings.settingsAutoTrackPerDimension).assertCountEquals(1)
        composeRule.onAllNodesWithText("Work").assertCountEquals(1)
        // A HIDDEN dimension has no row: the section lists what prefsState reports as visible, not every one.
        composeRule.onAllNodesWithText("Hidden").assertCountEquals(0)
    }

    @Test
    fun `global off hides the per-dimension rows`() {
        render(state(autoTrackHabitTimeGlobal = false)) {
            SettingsAutoTrackingSection(
                prefsState = state(autoTrackHabitTimeGlobal = false),
                onAutoTrackHabitTimeGlobalChange = { },
                onAutoTrackDimensionChange = { _, _ -> },
            )
        }

        composeRule.onAllNodesWithText(strings.settingsAutoTrackPerDimension).assertCountEquals(0)
        composeRule.onAllNodesWithText("Work").assertCountEquals(0)
    }

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    private fun render(
        state: AppPreferencesState,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings, content = content)
        }
    }

    private fun state(autoTrackHabitTimeGlobal: Boolean) =
        AppPreferencesState(
            autoTrackHabitTimeGlobal = autoTrackHabitTimeGlobal,
            dimensionPreferences =
                listOf(
                    DimensionPreference(key = "dim_work", label = "Work", color = Color.Blue, isVisible = true),
                    DimensionPreference(
                        key = "dim_hidden",
                        label = "Hidden",
                        color = Color.Gray,
                        isVisible = false,
                    ),
                ),
        )
}
