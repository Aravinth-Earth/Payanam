//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.TimeDayOverallSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TimeScreenVisualPanelsTest.
 */
class TimeScreenVisualPanelsTest {
    private val logger: UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

    @Test
    /**
     * Should show timeline quality cues returns false when all cues are zero.
     */
    fun shouldShowTimelineQualityCues_returnsFalse_whenAllCuesAreZero() {
        /** Summary. */
        val summary = TimeDayOverallSummary(gapCount = 0, overlapCount = 0)
        logger?.d("TimeScreenVisualPanelsTest", "Validating hidden quality cues for all-zero summary")

        /** Assert false. */
        assertFalse(shouldShowTimelineQualityCues(summary))
    }

    @Test
    /**
     * Should show timeline quality cues returns true when any cue is present.
     */
    fun shouldShowTimelineQualityCues_returnsTrue_whenAnyCueIsPresent() {
        /** Summary. */
        val summary = TimeDayOverallSummary(gapCount = 1, overlapCount = 0)
        logger?.d("TimeScreenVisualPanelsTest", "Validating visible quality cues when signal exists")

        /** Assert true. */
        assertTrue(shouldShowTimelineQualityCues(summary))
    }

    @Test
    /**
     * Short dimension label returns initials for two word label.
     */
    fun shortDimensionLabel_returnsInitials_forTwoWordLabel() {
        /** Assert equals. */
        assertEquals("LG", shortDimensionLabel("Learning & Grow"))
    }

    @Test
    /**
     * Short dimension label returns first two letters for single word label.
     */
    fun shortDimensionLabel_returnsFirstTwoLetters_forSingleWordLabel() {
        /** Assert equals. */
        assertEquals("FI", shortDimensionLabel("Financial"))
    }

    @Test
    /**
     * Short dimension label supports tamil two word initials.
     */
    fun shortDimensionLabel_supportsTamil_twoWordInitials() {
        /** Assert equals. */
        assertEquals("கவ", shortDimensionLabel("கற்று வளர"))
    }

    @Test
    /**
     * Short dimension label supports tamil single word first two graphemes.
     */
    fun shortDimensionLabel_supportsTamil_singleWordFirstTwoGraphemes() {
        /** Assert equals. */
        assertEquals("நேர", shortDimensionLabel("நேரம்"))
    }
}
