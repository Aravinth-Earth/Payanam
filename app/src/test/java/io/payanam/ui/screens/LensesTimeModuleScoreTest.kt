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
class LensesTimeModuleScoreTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    fun calculateBoundedTimeModuleScore_returnsOneAtPlannedTarget() {
        assertEquals(1.0, calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 360), 0.000001)
    }

    @Test
    fun calculateBoundedTimeModuleScore_reachesZeroAtLowerBound() {
        assertEquals(0.0, calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 0), 0.000001)
    }

    @Test
    fun calculateBoundedTimeModuleScore_reachesZeroAtUpperBound() {
        assertEquals(0.0, calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 1440), 0.000001)
    }

    @Test
    fun calculateBoundedTimeModuleScore_plannedZeroFallsLinearlyToDayUpperBound() {
        assertEquals(1.0, calculateBoundedTimeModuleScore(plannedMinutes = 0, actualMinutes = 0), 0.000001)
        assertEquals(0.5, calculateBoundedTimeModuleScore(plannedMinutes = 0, actualMinutes = 720), 0.000001)
    }

    @Test
    fun formatSignedMinutes_formatsSignAndMagnitude() {
        assertEquals("+1h", formatSignedMinutes(60))
        assertEquals("-30m", formatSignedMinutes(-30))
        assertEquals("0m", formatSignedMinutes(0))
    }

    @Test
    fun formatLensScore_usesFiveDecimals() {
        assertEquals("0.12500", formatLensScore(0.125))
        assertEquals("1.00000", formatLensScore(2.0))
    }
}
