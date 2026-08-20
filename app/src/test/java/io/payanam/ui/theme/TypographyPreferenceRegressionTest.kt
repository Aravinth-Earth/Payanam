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

/**
 * TypographyPreferenceRegressionTest.
 */
class TypographyPreferenceRegressionTest {

    private val logger: UnifiedLogger? by lazy { runCatching { UnifiedLogger.getInstance() }.getOrNull() }

    @Test
    fun `buildTypography applies configured font family to all text styles`() {
        /** Base. */
        val base = Typography()
        /** Typography. */
        val typography = buildTypography(FontFamilyOption.MONOSPACE)
        logger?.d("TypographyPreferenceRegressionTest", "Validated typography font family for monospace profile")

        /** Assert font family. */
        assertFontFamily(base.displayLarge, typography.displayLarge, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.displayMedium, typography.displayMedium, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.displaySmall, typography.displaySmall, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.headlineLarge, typography.headlineLarge, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.headlineMedium, typography.headlineMedium, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.headlineSmall, typography.headlineSmall, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.titleLarge, typography.titleLarge, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.titleMedium, typography.titleMedium, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.titleSmall, typography.titleSmall, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.bodyLarge, typography.bodyLarge, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.bodyMedium, typography.bodyMedium, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.bodySmall, typography.bodySmall, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.labelLarge, typography.labelLarge, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.labelMedium, typography.labelMedium, FontFamily.Monospace)
        /** Assert font family. */
        assertFontFamily(base.labelSmall, typography.labelSmall, FontFamily.Monospace)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun assertFontFamily(
        /** Base. */
        base: TextStyle,
        /** Actual. */
        actual: TextStyle,
        /** Expected family. */
        expectedFamily: FontFamily,
    ) {
        /** Assert equals. */
        assertEquals(expectedFamily, actual.fontFamily)
        // Font sizes should be unchanged (no scaling applied)
        /** Assert equals. */
        assertEquals(base.fontSize.value, actual.fontSize.value, FLOAT_DELTA)
    }

    companion object {
        private const val FLOAT_DELTA = 0.001f
    }
}
