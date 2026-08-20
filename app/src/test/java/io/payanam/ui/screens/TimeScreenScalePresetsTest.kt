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
/**
 * TimeScreenScalePresetsTest.
 */
class TimeScreenScalePresetsTest {
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
        logger.i("TimeScreenScalePresetsTest.setUp", "Preparing time-scale preset regression tests")
    }

    @Test
    /**
     * Hour height dp for slot minutes supports sub five minute presets.
     */
    fun hourHeightDpForSlotMinutes_supports_sub_five_minute_presets() {
        /** One minute. */
        val oneMinute = hourHeightDpForSlotMinutes(1)
        /** Two minutes. */
        val twoMinutes = hourHeightDpForSlotMinutes(2)
        /** Three minutes. */
        val threeMinutes = hourHeightDpForSlotMinutes(3)
        /** Five minutes. */
        val fiveMinutes = hourHeightDpForSlotMinutes(5)

        logger.i(
            "TimeScreenScalePresetsTest.hourHeightDpForSlotMinutes_supports_sub_five_minute_presets",
            "Validated explicit 1m/2m/3m scaling is unique",
            /** Map of. */
            mapOf(
                "oneMinute" to oneMinute,
                "twoMinutes" to twoMinutes,
                "threeMinutes" to threeMinutes,
                "fiveMinutes" to fiveMinutes,
            ),
        )

        /** Assert true. */
        assertTrue(oneMinute > twoMinutes)
        /** Assert true. */
        assertTrue(twoMinutes > threeMinutes)
        /** Assert true. */
        assertTrue(threeMinutes > fiveMinutes)
    }

    @Test
    /**
     * Nearest time scale preset round trips for one two three minute presets.
     */
    fun nearestTimeScalePreset_roundTrips_for_one_two_three_minute_presets() {
        /** One minute preset. */
        val oneMinutePreset = nearestTimeScalePreset(hourHeightDpForSlotMinutes(1))
        /** Two minute preset. */
        val twoMinutePreset = nearestTimeScalePreset(hourHeightDpForSlotMinutes(2))
        /** Three minute preset. */
        val threeMinutePreset = nearestTimeScalePreset(hourHeightDpForSlotMinutes(3))

        logger.i(
            "TimeScreenScalePresetsTest.nearestTimeScalePreset_roundTrips_for_one_two_three_minute_presets",
            "Validated nearest preset round-trip for low-minute scales",
            /** Map of. */
            mapOf(
                "oneMinuteSlot" to oneMinutePreset.slotMinutes,
                "twoMinuteSlot" to twoMinutePreset.slotMinutes,
                "threeMinuteSlot" to threeMinutePreset.slotMinutes,
            ),
        )

        /** Assert equals. */
        assertEquals(1, oneMinutePreset.slotMinutes)
        /** Assert equals. */
        assertEquals(2, twoMinutePreset.slotMinutes)
        /** Assert equals. */
        assertEquals(3, threeMinutePreset.slotMinutes)
    }
}
