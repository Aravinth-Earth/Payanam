//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * The shared theme, rendered by both apps.
 *
 * [darkTheme] arrives RESOLVED rather than as a preference enum, because the enums that describe the user's
 * choice (`ThemeModeOption` and friends) still live in the app module. A second copy of them here would be
 * the parallel default this migration exists to remove, so the caller resolves `SYSTEM` against its own
 * platform's dark-mode signal and passes the answer.
 *
 * [typography] is a parameter for the same reason: Android picks a typeface from the user's font-family
 * preference, and the desktop has no such preference yet. The default keeps the shared screens renderable
 * without either platform duplicating Material's type scale.
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
