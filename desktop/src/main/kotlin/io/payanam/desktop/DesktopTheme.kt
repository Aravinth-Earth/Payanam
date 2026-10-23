//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.desktop

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.payanam.core.ui.theme.PayanamLightPrimary
import io.payanam.core.ui.theme.PayanamLightSecondary

/**
 * Whether the desktop is rendering its dark appearance, for the whole subtree.
 *
 * A CompositionLocal rather than a parameter because these colours are read from ~30 call sites inside the
 * desktop's screens, and threading a boolean through every one of them would be a large mechanical edit to
 * code that V1–V5 delete. It is `static` (not `compositionLocalOf`) because it changes only when the user
 * changes the theme mode — a rare, whole-tree change, not a per-frame one.
 */
internal val LocalDesktopDarkTheme = staticCompositionLocalOf { true }

/**
 * The desktop's Material 2 palette.
 *
 * MATERIAL 2, deliberately, and only for as long as the desktop's hand-written routes exist: they are
 * Material 2 screens, and V1–V5 replace them with the shared Material 3 screens that render under
 * [io.payanam.core.ui.theme.PayanamTheme]. Migrating this palette's consumers first would be work on code
 * that is already scheduled for deletion.
 *
 * It DOES follow the user's theme mode. Before this it was a single hardcoded dark palette, so the
 * `DesktopThemeMode` setting was persisted, rendered in Settings, and had no effect on anything.
 */
internal object DesktopTheme {
    internal data class Palette(
        val materialColors: Colors,
        val background: Color,
    )

    @Composable
    fun palette(): Palette = if (LocalDesktopDarkTheme.current) dark() else light()

    private fun dark(): Palette {
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

    /**
     * The light appearance, built on the SHARED light tokens so the desktop and Android agree on the brand
     * colours even while the surface treatment differs.
     */
    private fun light(): Palette {
        val lightMaterial =
            lightColors(
                primary = PayanamLightPrimary,
                secondary = PayanamLightSecondary,
                background = Color(0xFFF5F7F9),
                surface = Color(0xFFFFFFFF),
                onPrimary = Color(0xFFFFFFFF),
                onSecondary = Color(0xFFFFFFFF),
                onBackground = Color(0xFF1A1F26),
                onSurface = Color(0xFF1A1F26),
            )
        return Palette(
            materialColors = lightMaterial,
            background = Color(0xFFF5F7F9),
        )
    }
}

@Composable
internal fun desktopColorPalette(): DesktopTheme.Palette = DesktopTheme.palette()

@Composable
internal fun desktopChromeBackgroundColor(): Color = if (LocalDesktopDarkTheme.current) Color(0xFF111924) else Color(0xFFE8ECF1)

@Composable
internal fun desktopCardColor(): Color = if (LocalDesktopDarkTheme.current) Color(0xFF16212C) else Color(0xFFFFFFFF)

@Composable
internal fun desktopSurfaceColor(): Color = if (LocalDesktopDarkTheme.current) Color(0xFF1B2733) else Color(0xFFF0F3F6)

@Composable
internal fun desktopAccentCardColor(): Color = if (LocalDesktopDarkTheme.current) Color(0xFF202C3A) else Color(0xFFEDF3F8)

@Composable
internal fun desktopBannerCardColor(): Color = if (LocalDesktopDarkTheme.current) Color(0xFF17303B) else Color(0xFFE3F0F4)

@Composable
internal fun desktopSelectedCardColor(): Color = if (LocalDesktopDarkTheme.current) Color(0xFF23445A) else Color(0xFFD3E4EF)

@Composable
internal fun desktopMutedTextColor(): Color = if (LocalDesktopDarkTheme.current) Color(0xFFAEB9C7) else Color(0xFF5A6673)

@Composable
internal fun desktopBodyTextColor(): Color = if (LocalDesktopDarkTheme.current) Color(0xFFD5DEE8) else Color(0xFF2A3138)
