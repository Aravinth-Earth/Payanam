//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * ColorContrastVerificationTest.
 */
class ColorContrastVerificationTest {

    @Test
    fun `life dimension colors have accessible text contrast`() {
        /** Colors. */
        val colors = mapOf(
            "CareerWork" to LifeDimensionColors.CareerWork,
            "HealthWellness" to LifeDimensionColors.HealthWellness,
            "Relationships" to LifeDimensionColors.Relationships,
            "PersonalGrowth" to LifeDimensionColors.PersonalGrowth,
            "Financial" to LifeDimensionColors.Financial,
            "Spiritual" to LifeDimensionColors.Spiritual,
            "Recreation" to LifeDimensionColors.Recreation,
            "Learning" to LifeDimensionColors.Learning,
            "Contribution" to LifeDimensionColors.Contribution,
            "Fallback" to LifeDimensionColors.forDimension("Unknown"),
        )

        colors.forEach { (name, background) ->
            /** Assert accessible text contrast. */
            assertAccessibleTextContrast(name, background)
        }
    }

    @Test
    fun `status colors have accessible text contrast`() {
        /** Colors. */
        val colors = mapOf(
            "Pending" to StatusColors.Pending,
            "Completed" to StatusColors.Completed,
            "Skipped" to StatusColors.Skipped,
            "Missed" to StatusColors.Missed,
            "Archived" to StatusColors.Archived,
        )

        colors.forEach { (name, background) ->
            /** Assert accessible text contrast. */
            assertAccessibleTextContrast(name, background)
        }
    }

    @Test
    fun `score colors have accessible text contrast`() {
        /** Backgrounds. */
        val backgrounds = listOf(0.0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f)
            .map { score -> "score=$score" to scoreColor(score) }

        backgrounds.forEach { (name, background) ->
            /** Assert accessible text contrast. */
            assertAccessibleTextContrast(name, background)
        }
    }

    private fun assertAccessibleTextContrast(name: String, background: Color) {
        /** White ratio. */
        val whiteRatio = contrastRatio(background, Color.White)
        /** Black ratio. */
        val blackRatio = contrastRatio(background, Color.Black)
        /** Best ratio. */
        val bestRatio = max(whiteRatio, blackRatio)

        /** Assert true. */
        assertTrue(
            "$name background has insufficient text contrast (best ratio=$bestRatio)",
            bestRatio >= MIN_TEXT_CONTRAST_RATIO,
        )
    }

    private fun contrastRatio(background: Color, foreground: Color): Double {
        /** L1. */
        val l1 = relativeLuminance(background)
        /** L2. */
        val l2 = relativeLuminance(foreground)
        /** Lighter. */
        val lighter = max(l1, l2)
        /** Darker. */
        val darker = min(l1, l2)
        /** Return. */
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        /** R. */
        val r = linearize(color.red.toDouble())
        /** G. */
        val g = linearize(color.green.toDouble())
        /** B. */
        val b = linearize(color.blue.toDouble())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(channel: Double): Double = if (channel <= 0.03928) {
        channel / 12.92
    } else {
        ((channel + 0.055) / 1.055).pow(2.4)
    }

    companion object {
        private const val MIN_TEXT_CONTRAST_RATIO = 4.5
    }
}
