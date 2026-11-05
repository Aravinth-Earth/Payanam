//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.lenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The asymmetry [calculateBoundedTimeModuleScore] charges on purpose: an under-shoot is measured
 * against the plan, an over-shoot against the headroom left in the day.
 *
 * Both platforms render this one shared function, so the shape is a property of the app rather than
 * of either platform. The KDoc states the asymmetry, but a stated intent nobody asserts can still be
 * "corrected" later - and a fix would move Android and the desktop together, invisibly. These tests
 * pin it: the same size of miss either side of the plan must NOT produce the same score.
 *
 * A plain kotlin.test suite - the function is pure and its inputs are minutes, so no Context,
 * logger or Compose runtime is involved.
 */
class LensesTimeModuleScoreAsymmetryTest {
    private val tolerance = 0.000001

    @Test
    fun equal_deviation_either_side_of_the_plan_scores_differently() {
        // 36 minutes is 10% of the 360-minute plan in both directions.
        val shortfall = calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 324)
        val overshoot = calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 396)

        // Under-shoot is charged against the plan itself: 1 - 36/360.
        assertEquals(0.9, shortfall, tolerance)
        // Over-shoot is charged against the day's remaining headroom: 1 - 36/1080.
        assertEquals(1.0 - 36.0 / 1080.0, overshoot, tolerance)
        // The same size of miss, opposite directions, is deliberately not the same penalty.
        assertNotEquals(shortfall, overshoot)
    }

    @Test
    fun shortfall_penalty_is_measured_against_the_plan() {
        // Half the plan tracked is the halfway score, whatever the absolute minutes are.
        assertEquals(0.5, calculateBoundedTimeModuleScore(plannedMinutes = 600, actualMinutes = 300), tolerance)
        assertEquals(0.5, calculateBoundedTimeModuleScore(plannedMinutes = 60, actualMinutes = 30), tolerance)
        // Quarter of the plan tracked.
        assertEquals(0.25, calculateBoundedTimeModuleScore(plannedMinutes = 600, actualMinutes = 150), tolerance)
    }

    @Test
    fun overshoot_penalty_is_measured_against_the_days_remaining_headroom() {
        // 1080 minutes of headroom (1440 - 360): an overshoot of 540 costs exactly half of it.
        assertEquals(0.5, calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 900), tolerance)
        // A different plan length leaves different headroom, so the same overshoot costs differently.
        val fromSixHundred = calculateBoundedTimeModuleScore(plannedMinutes = 600, actualMinutes = 1140)
        val fromThreeSixty = calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 900)
        assertNotEquals(fromSixHundred, fromThreeSixty)
    }

    @Test
    fun exact_match_is_one_and_a_day_filling_plan_has_no_headroom_to_over_run() {
        assertEquals(1.0, calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 360), tolerance)
        // Nothing below a full-day plan can score 0.0 by over-shooting: the input clamp caps actual
        // at 1440, so the shortfall branch is the only way an under-shoot reaches the floor.
        assertEquals(0.0, calculateBoundedTimeModuleScore(plannedMinutes = 360, actualMinutes = 0), tolerance)
    }
}
