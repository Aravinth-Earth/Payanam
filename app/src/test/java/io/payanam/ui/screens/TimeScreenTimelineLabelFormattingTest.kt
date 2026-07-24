//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeScreenTimelineLabelFormattingTest {

    @Test
    fun formatCompactFocusValue_formats_numeric_value_with_prefix() {
        assertEquals("F: 0.7", formatCompactFocusValue(0.7))
        assertEquals("F: 1.0", formatCompactFocusValue(1.2))
        assertEquals("F: 0.0", formatCompactFocusValue(-0.2))
    }

    @Test
    fun formatCompactFocusValue_returnsNull_for_missing_or_invalid_values() {
        assertNull(formatCompactFocusValue(null))
        assertNull(formatCompactFocusValue(Double.NaN))
        assertNull(formatCompactFocusValue(Double.POSITIVE_INFINITY))
    }

    @Test
    fun buildTimeBlockCompactLabel_preserves_expected_order() {
        val label = buildTimeBlockCompactLabel(
            dimensionLabel = "Health",
            taskLabel = "Morning Run",
            startLabel = "09:00",
            endLabel = "10:20",
            durationLabel = "1h 20m",
            focusValueLabel = "F: 0.7",
        )

        assertEquals("Health · Morning Run · 09:00 - 10:20 · 1h 20m · F: 0.7", label)
    }
}
