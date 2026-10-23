//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.theme.PayanamDarkPrimary
import io.payanam.core.ui.theme.PayanamLightPrimary
import io.payanam.core.ui.text.UiLanguage
import org.junit.Rule
import org.junit.Test

/**
 * The desktop's root surface renders the appearance the SHARED preference rows name.
 *
 * This is the wiring the theme used to lose: the surface took a resolved `darkTheme` from above, and the
 * desktop resolved it itself - `DARK` meant dark and everything else, `SYSTEM` included, meant light. These
 * assertions start from a row and read what the surface actually installed, which is the step that was
 * missing rather than the palette.
 */
class DesktopRootThemePreferenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun renderedPrimary(themeMode: ThemeModeOption): Color {
        var primary: Color? = null
        composeRule.setContent {
            desktopRootSurface(
                language = UiLanguage.ENGLISH,
                preferences = AppPreferencesState(themeMode = themeMode),
            ) {
                primary = MaterialTheme.colorScheme.primary
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(primary)
    }

    @Test
    fun `a DARK row renders the dark scheme`() {
        assertThat(renderedPrimary(ThemeModeOption.DARK)).isEqualTo(PayanamDarkPrimary)
    }

    @Test
    fun `a LIGHT row renders the light scheme`() {
        assertThat(renderedPrimary(ThemeModeOption.LIGHT)).isEqualTo(PayanamLightPrimary)
    }

    @Test
    fun `the row's font-family preference reaches the typography`() {
        var bodyFont: FontFamily? = null
        composeRule.setContent {
            desktopRootSurface(
                language = UiLanguage.ENGLISH,
                preferences = AppPreferencesState(fontFamily = FontFamilyOption.MONOSPACE),
            ) {
                bodyFont = MaterialTheme.typography.bodyLarge.fontFamily
            }
        }
        composeRule.waitForIdle()
        assertThat(bodyFont).isEqualTo(FontFamily.Monospace)
    }
}
