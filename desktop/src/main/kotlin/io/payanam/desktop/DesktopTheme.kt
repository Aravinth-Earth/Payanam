//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.desktop

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal object DesktopTheme {
    internal data class Palette(
        /** Material colors. */
        val materialColors: Colors,
        /** Background. */
        val background: Color,
    )

    @Composable
    /**
     * Palette.
     */
    fun palette(): Palette {
        val darkMaterial =
            darkColors(
                primary = Color(0xFF8AB4F8),
                secondary = Color(0xFF7AD7B0),
                background = Color(0xFF10151C),
                surface = Color(0xFF18202A),
                onPrimary = Color(0xFF0D1117),
                onSecondary = Color(0xFF0D1117),
                onBackground = Color(0xFFE8EEF6),
                onSurface = Color(0xFFE8EEF6),
            )
        return Palette(
            materialColors = darkMaterial,
            background = Color(0xFF10151C),
        )
    }
}

@Composable
internal fun desktopColorPalette(): DesktopTheme.Palette = DesktopTheme.palette()

internal fun desktopChromeBackgroundColor(): Color = Color(0xFF111924)

internal fun desktopCardColor(): Color = Color(0xFF16212C)

internal fun desktopSurfaceColor(): Color = Color(0xFF1B2733)

internal fun desktopAccentCardColor(): Color = Color(0xFF202C3A)

internal fun desktopBannerCardColor(): Color = Color(0xFF17303B)

internal fun desktopSelectedCardColor(): Color = Color(0xFF23445A)

internal fun desktopMutedTextColor(): Color = Color(0xFFAEB9C7)

internal fun desktopBodyTextColor(): Color = Color(0xFFD5DEE8)
