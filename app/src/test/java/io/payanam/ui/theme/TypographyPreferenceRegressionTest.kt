//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.FontFamilyOption
import org.junit.Assert.assertEquals
import org.junit.Test
class TypographyPreferenceRegressionTest {

    private val logger: UnifiedLogger? by lazy { runCatching { UnifiedLogger.getInstance() }.getOrNull() }

    @Test
    fun `buildTypography applies configured font family to all text styles`() {
        val base = Typography()
        val typography = buildTypography(FontFamilyOption.MONOSPACE)
        logger?.d("TypographyPreferenceRegressionTest", "Validated typography font family for monospace profile")
        assertFontFamily(base.displayLarge, typography.displayLarge, FontFamily.Monospace)
        assertFontFamily(base.displayMedium, typography.displayMedium, FontFamily.Monospace)
        assertFontFamily(base.displaySmall, typography.displaySmall, FontFamily.Monospace)
        assertFontFamily(base.headlineLarge, typography.headlineLarge, FontFamily.Monospace)
        assertFontFamily(base.headlineMedium, typography.headlineMedium, FontFamily.Monospace)
        assertFontFamily(base.headlineSmall, typography.headlineSmall, FontFamily.Monospace)
        assertFontFamily(base.titleLarge, typography.titleLarge, FontFamily.Monospace)
        assertFontFamily(base.titleMedium, typography.titleMedium, FontFamily.Monospace)
        assertFontFamily(base.titleSmall, typography.titleSmall, FontFamily.Monospace)
        assertFontFamily(base.bodyLarge, typography.bodyLarge, FontFamily.Monospace)
        assertFontFamily(base.bodyMedium, typography.bodyMedium, FontFamily.Monospace)
        assertFontFamily(base.bodySmall, typography.bodySmall, FontFamily.Monospace)
        assertFontFamily(base.labelLarge, typography.labelLarge, FontFamily.Monospace)
        assertFontFamily(base.labelMedium, typography.labelMedium, FontFamily.Monospace)
        assertFontFamily(base.labelSmall, typography.labelSmall, FontFamily.Monospace)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun assertFontFamily(
        base: TextStyle,
        actual: TextStyle,
        expectedFamily: FontFamily,
    ) {
        assertEquals(expectedFamily, actual.fontFamily)
        // Font sizes should be unchanged (no scaling applied)
        assertEquals(base.fontSize.value, actual.fontSize.value, FLOAT_DELTA)
    }

    companion object {
        private const val FLOAT_DELTA = 0.001f
    }
}
