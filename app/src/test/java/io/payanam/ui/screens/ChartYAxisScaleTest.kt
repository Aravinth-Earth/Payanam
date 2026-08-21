//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Y-axis scaling helpers (self-gov parity) — pure functions, plain JVM tests.
 * Score/RunningAvg: pad 20%, clamp [0,1]. Progress: symmetric ±(absMax+20%).
 * Streaks: padded non-negative range.
 */
class ChartYAxisScaleTest {

    @Test
    fun `clampedUnitRange pads 20 percent and clamps to unit`() {
        val (lo, hi) = io.payanam.ui.screens.clampedUnitRange(listOf(0.2, 0.4, 0.8))!!
        // range=0.6, pad=0.12 → [0.08, 0.92]
        assertEquals(0.08, lo, 1e-9)
        assertEquals(0.92, hi, 1e-9)
    }

    @Test
    fun `clampedUnitRange clamps low below zero to zero`() {
        val (lo, hi) = io.payanam.ui.screens.clampedUnitRange(listOf(0.0, 0.1))!!
        assertEquals(0.0, lo, 1e-9)
        // range=0.1 pad=0.02 → hi=0.12
        assertEquals(0.12, hi, 1e-9)
    }

    @Test
    fun `clampedUnitRange clamps high above one to one`() {
        val (lo, hi) = io.payanam.ui.screens.clampedUnitRange(listOf(0.9, 1.0))!!
        assertEquals(1.0, hi, 1e-9)
        assertEquals(0.88, lo, 1e-9)
    }

    @Test
    fun `clampedUnitRange flat data uses unit span`() {
        val (lo, hi) = io.payanam.ui.screens.clampedUnitRange(listOf(0.5, 0.5))!!
        // range=0 → 1.0; pad=0.2 → [0.3, 0.7]
        assertEquals(0.3, lo, 1e-9)
        assertEquals(0.7, hi, 1e-9)
    }

    @Test
    fun `clampedUnitRange empty returns null`() {
        assertNull(io.payanam.ui.screens.clampedUnitRange(emptyList()))
    }

    @Test
    fun `symmetricAroundZero centers positive and negative`() {
        val (lo, hi) = io.payanam.ui.screens.symmetricAroundZero(listOf(-0.4, 0.2))!!
        // pAbs=0.4, pPad=0.08 → [-0.48, 0.48]
        assertEquals(-0.48, lo, 1e-9)
        assertEquals(0.48, hi, 1e-9)
    }

    @Test
    fun `symmetricAroundZero all-zero uses epsilon floor`() {
        val (lo, hi) = io.payanam.ui.screens.symmetricAroundZero(listOf(0.0, 0.0))!!
        // pAbs=1e-6, pPad=2e-7
        assertEquals(-1.2e-6, lo, 1e-12)
        assertEquals(1.2e-6, hi, 1e-12)
    }

    @Test
    fun `paddedIntRange keeps headroom on zero-min data`() {
        val (lo, hi) = io.payanam.ui.screens.paddedIntRange(listOf(0.0, 3.0, 5.0))!!
        // range=5, pad=0.75 → [-0.75, 5.75]
        assertEquals(-0.75, lo, 1e-9)
        assertEquals(5.75, hi, 1e-9)
    }

    @Test
    fun `paddedIntRange negative min keeps headroom`() {
        val (lo, hi) = io.payanam.ui.screens.paddedIntRange(listOf(-2.0, 1.0))!!
        // range=3, pad=0.45 → [-2.45, 1.45]
        assertEquals(-2.45, lo, 1e-9)
        assertEquals(1.45, hi, 1e-9)
    }
}
