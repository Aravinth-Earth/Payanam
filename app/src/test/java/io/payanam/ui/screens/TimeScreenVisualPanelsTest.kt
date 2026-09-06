//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.TimeDayOverallSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class TimeScreenVisualPanelsTest {
    private val logger: UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

    @Test
    /**
     * Should show timeline quality cues returns false when all cues are zero.
     */
    fun shouldShowTimelineQualityCues_returnsFalse_whenAllCuesAreZero() {
        val summary = TimeDayOverallSummary(gapCount = 0, overlapCount = 0)
        logger?.d("TimeScreenVisualPanelsTest", "Validating hidden quality cues for all-zero summary")
        assertFalse(shouldShowTimelineQualityCues(summary))
    }

    @Test
    /**
     * Should show timeline quality cues returns true when any cue is present.
     */
    fun shouldShowTimelineQualityCues_returnsTrue_whenAnyCueIsPresent() {
        val summary = TimeDayOverallSummary(gapCount = 1, overlapCount = 0)
        logger?.d("TimeScreenVisualPanelsTest", "Validating visible quality cues when signal exists")
        assertTrue(shouldShowTimelineQualityCues(summary))
    }

    @Test
    fun shortDimensionLabel_returnsInitials_forTwoWordLabel() {
        assertEquals("LG", shortDimensionLabel("Learning & Grow"))
    }

    @Test
    /**
     * Short dimension label returns first two letters for single word label.
     */
    fun shortDimensionLabel_returnsFirstTwoLetters_forSingleWordLabel() {
        assertEquals("FI", shortDimensionLabel("Financial"))
    }

    @Test
    fun shortDimensionLabel_supportsTamil_twoWordInitials() {
        assertEquals("கவ", shortDimensionLabel("கற்று வளர"))
    }

    @Test
    /**
     * Short dimension label supports tamil single word first two graphemes.
     */
    fun shortDimensionLabel_supportsTamil_singleWordFirstTwoGraphemes() {
        assertEquals("நேர", shortDimensionLabel("நேரம்"))
    }
}
