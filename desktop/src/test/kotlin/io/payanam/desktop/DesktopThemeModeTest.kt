//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopThemeMode
import org.junit.Rule
import org.junit.Test

/**
 * The desktop's theme mode must actually change what is drawn.
 *
 * It did not: the palette was a single hardcoded dark set, so `DesktopThemeMode` was persisted, rendered in
 * Settings, and read by nothing. A setting with no effect is worse than a missing one — the user changes it,
 * sees no change, and concludes the app is broken rather than that the control is decorative.
 */
class DesktopThemeModeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `DARK and LIGHT resolve to different appearances, and SYSTEM keeps the historical dark one`() {
        assertThat(DesktopThemeMode.DARK.resolveDarkTheme()).isTrue()
        assertThat(DesktopThemeMode.LIGHT.resolveDarkTheme()).isFalse()
        // Recorded platform difference: the desktop has no system dark-mode signal it can trust, so SYSTEM
        // must not silently move every SYSTEM user to a light appearance they never chose.
        assertThat(DesktopThemeMode.SYSTEM.resolveDarkTheme()).isTrue()
    }

    @Test
    fun `the shared theme answers a different surface for DARK and LIGHT`() {
        var dark by mutableStateOf(false)
        val drawn = mutableListOf<Color>()
        // ONE setContent: a Compose rule allows a single one per test, so the mode is flipped inside it.
        // The subject is the SHARED theme now - it is the only one the desktop installs, so "does the
        // setting change what is drawn" is a question about its token set, not about a local palette.
        composeRule.setContent {
            io.payanam.core.ui.theme.PayanamTheme(darkTheme = dark) {
                drawn.add(MaterialTheme.colorScheme.surface)
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { dark = true }
        composeRule.waitForIdle()

        assertThat(drawn).hasSize(2)
        assertThat(drawn[0]).isNotEqualTo(drawn[1])
    }
}
