//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.FontFamilyOption
import io.payanam.ui.viewmodel.ThemeModeOption

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

private val logger = UnifiedLogger.getInstance()
private var lastThemeSignature: String? = null

@Composable
/**
 * Payanam theme.
 */
fun PayanamTheme(
    themeMode: ThemeModeOption = ThemeModeOption.SYSTEM,
    fontFamily: FontFamilyOption = FontFamilyOption.SANS_SERIF,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeModeOption.DARK -> true
        ThemeModeOption.LIGHT -> false
        ThemeModeOption.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme

        else -> LightColorScheme
    }
    val typography = buildTypography(fontFamily)
    LaunchedEffect(themeMode, fontFamily, darkTheme, dynamicColor) {
        val signature = "${themeMode.key}:${fontFamily.key}:$darkTheme:$dynamicColor"
        if (lastThemeSignature != signature) {
            logger.i(
                "Theme.PayanamTheme",
                "Applied app theme preferences",
                mapOf(
                    "themeMode" to themeMode.key,
                    "fontFamily" to fontFamily.key,
                    "darkTheme" to darkTheme,
                    "dynamicColor" to dynamicColor,
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
