//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * LensesTimeColorScaleTest.
 */
class LensesTimeColorScaleTest {
    private val logger: UnifiedLogger by lazy {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        logger.i("LensesTimeColorScaleTest.setUp", "Preparing Lenses time color-scale tests")
    }

    @Test
    /**
     * Lenses time gradient color clamps out of range inputs.
     */
    fun lensesTimeGradientColor_clamps_out_of_range_inputs() {
        /** Below zero. */
        val belowZero = lensesTimeGradientColor(-0.5f)
        /** At zero. */
        val atZero = lensesTimeGradientColor(0f)
        /** Above one. */
        val aboveOne = lensesTimeGradientColor(1.7f)
        /** At one. */
        val atOne = lensesTimeGradientColor(1f)

        /** Assert equals. */
        assertEquals(atZero.toArgb(), belowZero.toArgb())
        /** Assert equals. */
        assertEquals(atOne.toArgb(), aboveOne.toArgb())
    }

    @Test
    /**
     * Lenses time gradient color returns distinct low mid high colors.
     */
    fun lensesTimeGradientColor_returns_distinct_low_mid_high_colors() {
        /** Low. */
        val low = lensesTimeGradientColor(0f)
        /** Mid. */
        val mid = lensesTimeGradientColor(0.5f)
        /** High. */
        val high = lensesTimeGradientColor(1f)

        logger.i(
            "LensesTimeColorScaleTest.lensesTimeGradientColor_returns_distinct_low_mid_high_colors",
            "Validated low/mid/high gradient stops are visually distinct",
            /** Map of. */
            mapOf(
                "lowArgb" to low.toArgb(),
                "midArgb" to mid.toArgb(),
                "highArgb" to high.toArgb(),
            ),
        )

        /** Assert not equals. */
        assertNotEquals(low.toArgb(), mid.toArgb())
        /** Assert not equals. */
        assertNotEquals(mid.toArgb(), high.toArgb())
        /** Assert not equals. */
        assertNotEquals(low.toArgb(), high.toArgb())
    }

    @Test
    /**
     * Lenses time gradient color uses safe fallback for non finite inputs.
     */
    fun lensesTimeGradientColor_uses_safe_fallback_for_non_finite_inputs() {
        /** Nan color. */
        val nanColor = lensesTimeGradientColor(Float.NaN)
        /** Positive inf color. */
        val positiveInfColor = lensesTimeGradientColor(Float.POSITIVE_INFINITY)
        /** Negative inf color. */
        val negativeInfColor = lensesTimeGradientColor(Float.NEGATIVE_INFINITY)

        /** Low. */
        val low = lensesTimeGradientColor(0f)

        /** Assert equals. */
        assertEquals(low.toArgb(), nanColor.toArgb())
        /** Assert equals. */
        assertEquals(low.toArgb(), positiveInfColor.toArgb())
        /** Assert equals. */
        assertEquals(low.toArgb(), negativeInfColor.toArgb())
    }

    @Test
    /**
     * Focused hours to percent converts with clamp and defaults.
     */
    fun focusedHoursToPercent_converts_with_clamp_and_defaults() {
        /** Assert equals. */
        assertEquals(0, focusedHoursToPercent(0.0))
        /** Assert equals. */
        assertEquals(25, focusedHoursToPercent(6.0))
        /** Assert equals. */
        assertEquals(100, focusedHoursToPercent(24.0))
        /** Assert equals. */
        assertEquals(100, focusedHoursToPercent(30.0))
        /** Assert equals. */
        assertEquals(0, focusedHoursToPercent(-2.0))
    }

    @Test
    /**
     * Focused hours to percent handles non finite and invalid max hours.
     */
    fun focusedHoursToPercent_handles_non_finite_and_invalid_max_hours() {
        /** Assert equals. */
        assertEquals(0, focusedHoursToPercent(Double.NaN))
        /** Assert equals. */
        assertEquals(0, focusedHoursToPercent(Double.POSITIVE_INFINITY))
        /** Assert equals. */
        assertEquals(0, focusedHoursToPercent(5.0, maxHours = 0.0))
        /** Assert equals. */
        assertEquals(0, focusedHoursToPercent(5.0, maxHours = Double.NaN))
    }

    @Test
    /**
     * Sanitize focused hours returns zero for non finite values.
     */
    fun sanitizeFocusedHours_returns_zero_for_non_finite_values() {
        /** Assert equals. */
        assertEquals(0.0, sanitizeFocusedHours(Double.NaN), 0.0)
        /** Assert equals. */
        assertEquals(0.0, sanitizeFocusedHours(Double.POSITIVE_INFINITY), 0.0)
        /** Assert equals. */
        assertEquals(0.0, sanitizeFocusedHours(Double.NEGATIVE_INFINITY), 0.0)
        /** Assert equals. */
        assertEquals(2.5, sanitizeFocusedHours(2.5), 0.0)
    }
}
