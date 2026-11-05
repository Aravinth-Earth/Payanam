//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.payanam.core.ui.preferences.FontFamilyOption
import io.payanam.core.ui.preferences.ThemeModeOption
import io.payanam.shared.logging.AppLogger

/**
 * The fallback dark scheme, used when a platform offers no dynamic colours.
 */
val PayanamDarkColorScheme: ColorScheme =
    darkColorScheme(
        primary = PayanamDarkPrimary,
        secondary = PayanamDarkSecondary,
        tertiary = PayanamDarkTertiary,
    )

/**
 * The fallback light scheme.
 */
val PayanamLightColorScheme: ColorScheme =
    lightColorScheme(
        primary = PayanamLightPrimary,
        secondary = PayanamLightSecondary,
        tertiary = PayanamLightTertiary,
    )

/**
 * A platform's colours derived from the device, or null when it has none.
 *
 * EXPECT/ACTUAL because this is the one part of the theme that genuinely differs: Android asks the system
 * for a wallpaper-derived palette on API 31+, and the desktop has no such source and must answer null rather
 * than inventing one. `commonMain` cannot say either thing.
 *
 * Answering null is not a failure — it selects the fallback scheme, which is what a platform without dynamic
 * colour is supposed to render.
 */
@Composable
expect fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme?

/**
 * The shared theme, rendered by both apps, for a caller that has already resolved the appearance.
 *
 * [darkTheme] arrives RESOLVED rather than as a preference enum: this is the entry point for a caller that
 * has decided the palette itself (previews, tests, and any surface with no preference rows behind it). The
 * preference-driven entry point is the [themeMode] overload below, and that is the one both apps call in
 * production - so there is one translation of the stored preference, not one per platform.
 *
 * [typography] is a parameter for the same reason: a caller that has resolved the user's font-family
 * preference passes the type scale it built, and a caller that has not keeps Material's own.
 */
@Composable
fun PayanamTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = true,
    typography: Typography = MaterialTheme.typography,
    content: @Composable () -> Unit,
) {
    val platformScheme = if (dynamicColor) platformDynamicColorScheme(darkTheme) else null
    val colorScheme =
        platformScheme
            ?: if (darkTheme) PayanamDarkColorScheme else PayanamLightColorScheme

    // Logged once per distinct combination rather than per recomposition: the theme recomposes on every
    // preference change, and this line only says which one took effect.
    LaunchedEffect(darkTheme, dynamicColor, platformScheme != null) {
        val source = if (platformScheme != null) "platform" else "fallback"
        val signature = "$darkTheme:$dynamicColor:$source"
        if (lastThemeSignature != signature) {
            AppLogger.current.i(
                "PayanamTheme",
                "Applied app theme",
                mapOf(
                    "darkTheme" to darkTheme,
                    "dynamicColor" to dynamicColor,
                    "colorSchemeSource" to source,
                ),
            )
            lastThemeSignature = signature
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content,
    )
}

private var lastThemeSignature: String? = null

/** Test hook: the deduplicating log in [PayanamTheme] keeps state across calls. */
internal fun resetThemeLogForTests() {
    lastThemeSignature = null
}

/**
 * The shared theme, applied from the user's stored preferences - the single translation both apps use.
 *
 * `themeMode` is the resolution this exists for. `SYSTEM` means "whatever the platform's dark-mode signal
 * says", and the signal is a parameter rather than a call made here, for two reasons: each platform answers
 * it its own way (Android reads the system setting; the desktop reads the one its toolkit exposes), and
 * keeping it injectable is what lets the resolution be tested without a platform under it.
 *
 * The font-family preference is applied here too, so a user's typeface choice reaches the screens on both
 * platforms instead of only the one that used to translate it.
 */
@Composable
fun PayanamTheme(
    themeMode: ThemeModeOption,
    fontFamily: FontFamilyOption = FontFamilyOption.SANS_SERIF,
    dynamicColor: Boolean = true,
    systemInDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            ThemeModeOption.DARK -> true
            ThemeModeOption.LIGHT -> false
            ThemeModeOption.SYSTEM -> systemInDarkTheme
        }
    PayanamTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        typography = buildTypography(fontFamily),
        content = content,
    )
}
