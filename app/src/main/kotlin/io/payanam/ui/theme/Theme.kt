//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.core.ui.theme.PayanamTheme

/**
 * Android's entry point to the shared theme.
 *
 * Everything visual comes from `:core:ui` — the palette, the Material 3 schemes and the dynamic-colour seam.
 * What stays here is the one thing the shared module cannot know: how to read the user's preference enum and
 * the system's dark-mode signal, and which typeface the font-family preference selects.
 *
 * The preference enums stay in the app module for now. They move with the preferences state they belong to,
 * and until then this function is the single place that translates them into the neutral inputs the shared
 * theme takes — so there is no second theme, only one translation at the platform edge.
 */
@Composable
fun PayanamTheme(
    themeMode: ThemeModeOption = ThemeModeOption.SYSTEM,
    fontFamily: FontFamilyOption = FontFamilyOption.SANS_SERIF,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    // SYSTEM resolves against the platform's own signal: Android's dark-mode setting.
    val darkTheme =
        when (themeMode) {
            ThemeModeOption.DARK -> true
            ThemeModeOption.LIGHT -> false
            ThemeModeOption.SYSTEM -> isSystemInDarkTheme()
        }
    PayanamTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        typography = buildTypography(fontFamily),
        content = content,
    )
}
