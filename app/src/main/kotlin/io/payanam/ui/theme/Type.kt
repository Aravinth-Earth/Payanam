//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.FontFamilyOption

private val logger: UnifiedLogger? by lazy { runCatching { UnifiedLogger.getInstance() }.getOrNull() }
private var lastTypographySignature: String? = null

private fun TextStyle.withAppTypography(fontFamily: FontFamily): TextStyle = copy(fontFamily = fontFamily)

private fun TextStyle.withAppLabel(fontFamily: FontFamily): TextStyle = copy(fontFamily = fontFamily, letterSpacing = 0.04.em)

private fun resolveFontFamily(option: FontFamilyOption): FontFamily = when (option) {
    FontFamilyOption.SANS_SERIF -> FontFamily.SansSerif
    FontFamilyOption.MONOSPACE -> FontFamily.Monospace
    FontFamilyOption.SERIF -> FontFamily.Serif
    FontFamilyOption.CURSIVE -> FontFamily.Cursive
}
/**
 * Material typography with the user's font-family preference applied.
 */
fun buildTypography(fontFamilyOption: FontFamilyOption): Typography {
    val fontFamily = resolveFontFamily(fontFamilyOption)
    val signature = fontFamilyOption.key
    if (lastTypographySignature != signature) {
        logger?.i(
            "Type.buildTypography",
            "Applied typography preference",
            mapOf("fontFamily" to fontFamilyOption.key),
        )
        lastTypographySignature = signature
    }
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.withAppTypography(fontFamily),
        displayMedium = base.displayMedium.withAppTypography(fontFamily),
        displaySmall = base.displaySmall.withAppTypography(fontFamily),
        headlineLarge = base.headlineLarge.withAppTypography(fontFamily),
        headlineMedium = base.headlineMedium.withAppTypography(fontFamily),
        headlineSmall = base.headlineSmall.withAppTypography(fontFamily),
        titleLarge = base.titleLarge.withAppTypography(fontFamily),
        titleMedium = base.titleMedium.withAppTypography(fontFamily),
        titleSmall = base.titleSmall.withAppTypography(fontFamily),
        bodyLarge = base.bodyLarge.withAppTypography(fontFamily),
        bodyMedium = base.bodyMedium.withAppTypography(fontFamily),
        bodySmall = base.bodySmall.withAppTypography(fontFamily),
        labelLarge = base.labelLarge.withAppTypography(fontFamily),
        labelMedium = base.labelMedium.withAppLabel(fontFamily),
        labelSmall = base.labelSmall.withAppLabel(fontFamily),
    )
}
