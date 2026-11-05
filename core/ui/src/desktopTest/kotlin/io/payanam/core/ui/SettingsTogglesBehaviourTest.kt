//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import io.payanam.core.ui.settings.SettingsScreenActions
import io.payanam.core.ui.settings.SettingsScreenContent
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The Settings screen's controls, driven for real on the JVM target, asserting the WRITE each one reports.
 *
 * WHY THIS EXISTS. The a11y and readability sweeps prove a control is present, named and laid out; they say
 * nothing about whether pressing it reports the right value. This is the layer that closes that: it mounts the
 * real [SettingsScreenContent] with a [SettingsScreenActions] whose callbacks record what the screen sends, so
 * a switch that fires the wrong callback, or fires none, fails here.
 *
 * It reuses the SAME state builders the sweeps use ([loadedPrefs], [settingsUiState], [openCard]) rather than
 * re-declaring them, so a change to the fixture cannot leave this test measuring a different screen.
 *
 * WHAT IT DOES NOT ASSERT, and why: it does not re-render the screen from the written value. The screen is a
 * pure function of its inputs here, so the write is proved by the callback and the re-render is the
 * ViewModel's job, covered by the ViewModel tests. Toggles are asserted one press at a time, so a switch that
 * only reports its INITIAL value cannot pass by accident.
 */
class SettingsTogglesBehaviourTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val strings: UiStrings get() = UiStrings.of(UiLanguage.ENGLISH)

    @Test
    fun `the debug logging switch reports the value it was flipped to`() {
        val seen = mutableListOf<Boolean>()
        render(SettingsScreenActions(onDebugLoggingEnabledChanged = { seen += it }))

        composeRule.openCard(strings.settingsDebugTitle)
        composeRule.onNode(hasContentDescription(strings.settingsDebugEnable)).performClick()

        assertEquals(listOf(true), seen, "the debug-logging switch did not report the value it was flipped to")
    }

    @Test
    fun `the auto-backup switch reports enabling`() {
        val seen = mutableListOf<Boolean>()
        render(SettingsScreenActions(onAutoBackupEnabledChanged = { seen += it }))

        composeRule.openCard(strings.settingsAutoBackupTitle)
        composeRule.onNode(hasContentDescription(strings.settingsAutoBackupEnable)).performClick()

        assertEquals(listOf(true), seen, "the auto-backup switch did not report enabling")
    }

    @Test
    fun `a chart-visibility switch reports the key it owns`() {
        val seen = mutableListOf<Pair<String, Boolean>>()
        render(SettingsScreenActions(onChartVisibilityChanged = { key, enabled -> seen += key to enabled }))

        composeRule.openCard(strings.settingsInsightsChartsVisibilityTitle)
        composeRule.onNode(hasContentDescription(strings.locTime)).performClick()

        assertEquals(
            listOf("chart_time_module_enabled" to !loadedPrefs().chartTimeModuleEnabled),
            seen,
            "the Time chart-visibility switch did not report its own key with the value it was flipped to",
        )
    }

    private fun render(actions: SettingsScreenActions) {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                SettingsScreenContent(
                    prefsState = loadedPrefs(),
                    uiState = settingsUiState(),
                    snackbarHostState = SnackbarHostState(),
                    actions = actions,
                )
            }
        }
        composeRule.waitForIdle()
    }
}
