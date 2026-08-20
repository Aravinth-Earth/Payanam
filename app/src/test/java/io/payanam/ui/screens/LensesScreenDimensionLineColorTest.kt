//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.ui.graphics.Color
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
 * LensesScreenDimensionLineColorTest.
 */
class LensesScreenDimensionLineColorTest {

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
    }

    @Test
    /**
     * Tagged dimension line applies color to dimension name span.
     */
    fun taggedDimensionLine_appliesColorToDimensionNameSpan() {
        /** Label. */
        val label = "Health & Wellness"
        /** Line. */
        val line = "$label: 1h 0m (planned) vs 45m (actual)"
        /** Color. */
        val color = Color(0xFF3A7BD5)

        /** Result. */
        val result = taggedDimensionLine(
            line = line,
            dimensionLabel = label,
            dimensionColor = color,
        )

        /** Assert equals. */
        assertEquals(line, result.text)
        /** Assert true. */
        assertTrue(
            result.spanStyles.any { span ->
                span.start == 0 &&
                    span.end == label.length &&
                    span.item.color == color
            },
        )
    }

    @Test
    /**
     * Tagged dimension line returns unstyled text when label missing.
     */
    fun taggedDimensionLine_returnsUnstyledTextWhenLabelMissing() {
        /** Line. */
        val line = "Unassigned: 30m"

        /** Result. */
        val result = taggedDimensionLine(
            line = line,
            dimensionLabel = "Career & Work",
            dimensionColor = Color.Red,
        )

        /** Assert equals. */
        assertEquals(line, result.text)
        /** Assert true. */
        assertTrue(result.spanStyles.isEmpty())
    }
}
