//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.theme

import androidx.compose.ui.graphics.Color
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shared palette's behaviour, and the one thing about it that is easy to get wrong: which band a score
 * falls into at the boundaries.
 *
 * These tokens are shared so a colour cannot differ between the two apps, so the tests are about the
 * decisions inside them rather than about the values being pretty.
 */
class PayanamColorTokensTest {
    @BeforeTest
    fun setUp() {
        AppLogger.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        AppLogger.resetForTests()
    }

    @Test
    fun `score bands take the score AT the threshold, and never fall between two`() {
        // Half-open bands: `>= 0.8` is the top band. A chain of `>` comparisons would drop this point.
        assertEquals(Color(0xFF4CAF50), scoreColor(1.0f))
        assertEquals(Color(0xFF4CAF50), scoreColor(0.8f))
        assertEquals(Color(0xFF8BC34A), scoreColor(0.79f))
        assertEquals(Color(0xFF8BC34A), scoreColor(0.6f))
        assertEquals(Color(0xFFFFC107), scoreColor(0.59f))
        assertEquals(Color(0xFFFFC107), scoreColor(0.4f))
        assertEquals(Color(0xFFFF9800), scoreColor(0.39f))
        assertEquals(Color(0xFFFF9800), scoreColor(0.2f))
        assertEquals(Color(0xFFF44336), scoreColor(0.19f))
        assertEquals(Color(0xFFF44336), scoreColor(0f))
    }

    @Test
    fun `a six-digit hex is opaque, with or without the hash`() {
        // Both spellings occur in stored data, and a missing alpha must mean OPAQUE rather than transparent.
        assertEquals(Color(0xFFFF0000), LifeDimensionColors.parseHexColor("FF0000"))
        assertEquals(Color(0xFFFF0000), LifeDimensionColors.parseHexColor("#FF0000"))
    }

    @Test
    fun `an eight-digit hex carries its own alpha`() {
        assertEquals(Color(0x80FF0000), LifeDimensionColors.parseHexColor("80FF0000"))
        assertEquals(Color(0x80FF0000), LifeDimensionColors.parseHexColor("#80FF0000"))
    }

    @Test
    fun `a canonical dimension resolves to its taxonomy colour`() {
        val dimension = DimensionTaxonomyCatalog.WORK_LIVELIHOOD

        assertEquals(
            LifeDimensionColors.parseHexColor(dimension.defaultColorHex),
            LifeDimensionColors.forDimensionId(dimension.id),
        )
    }

    @Test
    fun `an unknown or absent dimension id resolves to null rather than a wrong colour`() {
        // Null, not a fallback: a caller rendering "the user's colour" must be able to tell that there is
        // no such colour instead of drawing a default as though the user had chosen it.
        assertNull(LifeDimensionColors.forDimensionId(null))
        assertNull(LifeDimensionColors.forDimensionId(""))
        assertNull(LifeDimensionColors.forDimensionId("not_a_dimension"))
    }

    @Test
    fun `an unknown dimension falls back, and its two log lines have different frequencies`() {
        val captured = mutableListOf<String>()
        AppLogger.install(capturingLogger(captured))

        val first = LifeDimensionColors.forDimension("definitely_not_a_dimension")
        val second = LifeDimensionColors.forDimension("definitely_not_a_dimension")

        assertEquals(PayanamLightSecondary, first)
        assertEquals(PayanamLightSecondary, second)
        // MEASURED, not assumed: the once-guard covers the first line only, and the warning fires on every
        // call. That asymmetry is inherited verbatim from Android's implementation, which is why it is
        // pinned here rather than "tidied" - a desktop that throttled the warning would log less than
        // Android for the same input, and one that un-throttled the debug line would log far more.
        assertEquals(1, captured.count { it.contains("Using fallback colours") })
        assertEquals(2, captured.count { it.contains("Missing canonical dimension id") })
    }
}

/** Records the messages the palette logs, so the once-only rule can be asserted. */
private fun capturingLogger(recorded: MutableList<String>): UnifiedLogger =
    object : UnifiedLogger {
        private fun note(message: String) {
            recorded.add(message)
        }

        override fun d(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = note(message)

        override fun i(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = note(message)

        override fun w(
            source: String,
            message: String,
            data: Map<String, Any?>,
        ) = note(message)

        override fun e(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = note(message)

        override fun eSync(
            source: String,
            message: String,
            error: Throwable?,
            data: Map<String, Any?>,
        ) = note(message)

        override fun toString(): String = recorded.joinToString("\n")
    }
