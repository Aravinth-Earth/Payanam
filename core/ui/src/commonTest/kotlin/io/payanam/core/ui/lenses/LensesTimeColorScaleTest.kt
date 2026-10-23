//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.lenses

import androidx.compose.ui.graphics.toArgb
import io.payanam.shared.logging.AppLogger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The Lenses time color scale - pure functions.
 *
 * Lives beside the section that uses them: an `internal` helper is not visible across the module boundary, and a
 * color scale's rules are properties of the chart that renders it. Robolectric and its Context existed only to
 * initialize a logger, so this is a plain kotlin.test suite.
 */
class LensesTimeColorScaleTest {
    @BeforeTest
    fun setUp() {
        AppLogger.current.i("LensesTimeColorScaleTest.setUp", "Preparing Lenses time color-scale tests")
    }

    @Test
    fun lensesTimeGradientColor_clamps_out_of_range_inputs() {
        val belowZero = lensesTimeGradientColor(-0.5f)
        val atZero = lensesTimeGradientColor(0f)
        val aboveOne = lensesTimeGradientColor(1.7f)
        val atOne = lensesTimeGradientColor(1f)
        assertEquals(atZero.toArgb(), belowZero.toArgb())
        assertEquals(atOne.toArgb(), aboveOne.toArgb())
    }

    @Test
    fun lensesTimeGradientColor_returns_distinct_low_mid_high_colors() {
        val low = lensesTimeGradientColor(0f)
        val mid = lensesTimeGradientColor(0.5f)
        val high = lensesTimeGradientColor(1f)

        AppLogger.current.i(
            "LensesTimeColorScaleTest.lensesTimeGradientColor_returns_distinct_low_mid_high_colors",
            "Validated low/mid/high gradient stops are visually distinct",
            mapOf(
                "lowArgb" to low.toArgb(),
                "midArgb" to mid.toArgb(),
                "highArgb" to high.toArgb(),
            ),
        )
        assertNotEquals(low.toArgb(), mid.toArgb())
        assertNotEquals(mid.toArgb(), high.toArgb())
        assertNotEquals(low.toArgb(), high.toArgb())
    }

    @Test
    fun lensesTimeGradientColor_uses_safe_fallback_for_non_finite_inputs() {
        val nanColor = lensesTimeGradientColor(Float.NaN)
        val positiveInfColor = lensesTimeGradientColor(Float.POSITIVE_INFINITY)
        val negativeInfColor = lensesTimeGradientColor(Float.NEGATIVE_INFINITY)
        val low = lensesTimeGradientColor(0f)
        assertEquals(low.toArgb(), nanColor.toArgb())
        assertEquals(low.toArgb(), positiveInfColor.toArgb())
        assertEquals(low.toArgb(), negativeInfColor.toArgb())
    }

    @Test
    fun focusedHoursToPercent_converts_with_clamp_and_defaults() {
        assertEquals(0, focusedHoursToPercent(0.0))
        assertEquals(25, focusedHoursToPercent(6.0))
        assertEquals(100, focusedHoursToPercent(24.0))
        assertEquals(100, focusedHoursToPercent(30.0))
        assertEquals(0, focusedHoursToPercent(-2.0))
    }

    @Test
    fun focusedHoursToPercent_handles_non_finite_and_invalid_max_hours() {
        assertEquals(0, focusedHoursToPercent(Double.NaN))
        assertEquals(0, focusedHoursToPercent(Double.POSITIVE_INFINITY))
        assertEquals(0, focusedHoursToPercent(5.0, maxHours = 0.0))
        assertEquals(0, focusedHoursToPercent(5.0, maxHours = Double.NaN))
    }

    @Test
    fun sanitizeFocusedHours_returns_zero_for_non_finite_values() {
        assertEquals(0.0, sanitizeFocusedHours(Double.NaN), 0.0)
        assertEquals(0.0, sanitizeFocusedHours(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, sanitizeFocusedHours(Double.NEGATIVE_INFINITY), 0.0)
        assertEquals(2.5, sanitizeFocusedHours(2.5), 0.0)
    }
}
