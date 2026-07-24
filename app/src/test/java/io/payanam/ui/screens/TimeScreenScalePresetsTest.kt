//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimeScreenScalePresetsTest {
    private val logger: UnifiedLogger by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Before
    fun setUp() {
        logger.i("TimeScreenScalePresetsTest.setUp", "Preparing time-scale preset regression tests")
    }

    @Test
    fun hourHeightDpForSlotMinutes_supports_sub_five_minute_presets() {
        val oneMinute = hourHeightDpForSlotMinutes(1)
        val twoMinutes = hourHeightDpForSlotMinutes(2)
        val threeMinutes = hourHeightDpForSlotMinutes(3)
        val fiveMinutes = hourHeightDpForSlotMinutes(5)

        logger.i(
            "TimeScreenScalePresetsTest.hourHeightDpForSlotMinutes_supports_sub_five_minute_presets",
            "Validated explicit 1m/2m/3m scaling is unique",
            mapOf(
                "oneMinute" to oneMinute,
                "twoMinutes" to twoMinutes,
                "threeMinutes" to threeMinutes,
                "fiveMinutes" to fiveMinutes,
            ),
        )

        assertTrue(oneMinute > twoMinutes)
        assertTrue(twoMinutes > threeMinutes)
        assertTrue(threeMinutes > fiveMinutes)
    }

    @Test
    fun nearestTimeScalePreset_roundTrips_for_one_two_three_minute_presets() {
        val oneMinutePreset = nearestTimeScalePreset(hourHeightDpForSlotMinutes(1))
        val twoMinutePreset = nearestTimeScalePreset(hourHeightDpForSlotMinutes(2))
        val threeMinutePreset = nearestTimeScalePreset(hourHeightDpForSlotMinutes(3))

        logger.i(
            "TimeScreenScalePresetsTest.nearestTimeScalePreset_roundTrips_for_one_two_three_minute_presets",
            "Validated nearest preset round-trip for low-minute scales",
            mapOf(
                "oneMinuteSlot" to oneMinutePreset.slotMinutes,
                "twoMinuteSlot" to twoMinutePreset.slotMinutes,
                "threeMinuteSlot" to threeMinutePreset.slotMinutes,
            ),
        )

        assertEquals(1, oneMinutePreset.slotMinutes)
        assertEquals(2, twoMinutePreset.slotMinutes)
        assertEquals(3, threeMinutePreset.slotMinutes)
    }
}
