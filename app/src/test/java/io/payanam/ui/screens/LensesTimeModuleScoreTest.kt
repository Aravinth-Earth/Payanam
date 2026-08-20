//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/**
 * LensesTimeModuleScoreTest.
 */
class LensesTimeModuleScoreTest {

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    /**
     * Calculate bounded time module score returns one at planned target.
     */
    fun calculateBoundedTimeModuleScore_returnsOneAtPlannedTarget() {
        /** Assert equals. */
        assertEquals(1.0, calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 360), 0.000001)
    }

    @Test
    /**
     * Calculate bounded time module score reaches zero at lower bound.
     */
    fun calculateBoundedTimeModuleScore_reachesZeroAtLowerBound() {
        /** Assert equals. */
        assertEquals(0.0, calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 0), 0.000001)
    }

    @Test
    /**
     * Calculate bounded time module score reaches zero at upper bound.
     */
    fun calculateBoundedTimeModuleScore_reachesZeroAtUpperBound() {
        /** Assert equals. */
        assertEquals(0.0, calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 1440), 0.000001)
    }

    @Test
    /**
     * Calculate bounded time module score planned zero falls linearly to day upper bound.
     */
    fun calculateBoundedTimeModuleScore_plannedZeroFallsLinearlyToDayUpperBound() {
        /** Assert equals. */
        assertEquals(1.0, calculateBoundedTimeModuleScore(plannedMinutes = 0, actualMinutes = 0), 0.000001)
        /** Assert equals. */
        assertEquals(0.5, calculateBoundedTimeModuleScore(plannedMinutes = 0, actualMinutes = 720), 0.000001)
    }

    @Test
    /**
     * Format signed minutes formats sign and magnitude.
     */
    fun formatSignedMinutes_formatsSignAndMagnitude() {
        /** Assert equals. */
        assertEquals("+1h", formatSignedMinutes(60))
        /** Assert equals. */
        assertEquals("-30m", formatSignedMinutes(-30))
        /** Assert equals. */
        assertEquals("0m", formatSignedMinutes(0))
    }

    @Test
    /**
     * Format lens score uses five decimals.
     */
    fun formatLensScore_usesFiveDecimals() {
        /** Assert equals. */
        assertEquals("0.12500", formatLensScore(0.125))
        /** Assert equals. */
        assertEquals("1.00000", formatLensScore(2.0))
    }
}
