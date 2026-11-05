//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import org.junit.Rule
import org.junit.Test

/**
 * The preference-driven theme, which is the entry point both apps use.
 *
 * `SYSTEM` is the clause worth pinning: it used to be answered by each platform separately, and the
 * desktop's answer treated it as light. Here it is asserted against a signal the test supplies, so the
 * resolution is checked without a platform under it - which is the reason the signal is a parameter.
 */
class PayanamThemePreferenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun renderedPrimary(
        themeMode: ThemeModeOption,
        systemInDarkTheme: Boolean,
    ): Color {
        var primary: Color? = null
        composeRule.setContent {
            PayanamTheme(themeMode = themeMode, systemInDarkTheme = systemInDarkTheme) {
                primary = MaterialTheme.colorScheme.primary
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(primary)
    }

    @Test
    fun `an explicit light mode renders light even when the platform says dark`() {
        assertThat(renderedPrimary(ThemeModeOption.LIGHT, systemInDarkTheme = true)).isEqualTo(PayanamLightPrimary)
    }

    @Test
    fun `an explicit dark mode renders dark even when the platform says light`() {
        assertThat(renderedPrimary(ThemeModeOption.DARK, systemInDarkTheme = false)).isEqualTo(PayanamDarkPrimary)
    }

    @Test
    fun `SYSTEM renders dark when the platform's own signal says dark`() {
        assertThat(renderedPrimary(ThemeModeOption.SYSTEM, systemInDarkTheme = true)).isEqualTo(PayanamDarkPrimary)
    }

    @Test
    fun `SYSTEM renders light when the platform's own signal says light`() {
        assertThat(renderedPrimary(ThemeModeOption.SYSTEM, systemInDarkTheme = false)).isEqualTo(PayanamLightPrimary)
    }

    @Test
    fun `the font-family preference reaches the text styles`() {
        var bodyFont: FontFamily? = null
        composeRule.setContent {
            PayanamTheme(themeMode = ThemeModeOption.LIGHT, fontFamily = FontFamilyOption.MONOSPACE) {
                bodyFont = MaterialTheme.typography.bodyLarge.fontFamily
            }
        }
        composeRule.waitForIdle()
        assertThat(bodyFont).isEqualTo(FontFamily.Monospace)
    }
}
