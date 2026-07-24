//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.LifeDimension
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimeDeviationScoringTest {

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    fun perfectMatch_returnsOne() {
        val score = TimeDeviationScoring.calculateDimensionScore(120, 120)
        assertThat(score).isWithin(0.001).of(1.0)
    }

    @Test
    fun zeroActual_returnsNearZero() {
        val score = TimeDeviationScoring.calculateDimensionScore(120, 0)
        assertThat(score).isLessThan(0.05)
    }

    @Test
    fun overTracking_penalizes() {
        val score = TimeDeviationScoring.calculateDimensionScore(120, 240)
        assertThat(score).isLessThan(0.5)
        assertThat(score).isAtLeast(0.0)
    }

    @Test
    fun symmetricPenalty() {
        // 50% under-tracking
        val underScore = TimeDeviationScoring.calculateDimensionScore(100, 50)
        // 50% over-tracking
        val overScore = TimeDeviationScoring.calculateDimensionScore(100, 150)
        assertThat(underScore).isWithin(0.001).of(overScore)
    }

    @Test
    fun noPlanNoActual_returnsOne() {
        val score = TimeDeviationScoring.calculateDimensionScore(0, 0)
        assertThat(score).isWithin(0.001).of(1.0)
    }

    @Test
    fun noPlanWithActual_penalizes() {
        val score = TimeDeviationScoring.calculateDimensionScore(0, 60)
        assertThat(score).isLessThan(0.05)
    }

    @Test
    fun scoreAlwaysBetweenZeroAndOne() {
        val testCases = listOf(
            0 to 0L, 0 to 100L, 100 to 0L,
            100 to 100L, 100 to 50L, 100 to 200L,
            60 to 1440L, 480 to 480L, 30 to 30L
        )
        testCases.forEach { (planned, actual) ->
            val score = TimeDeviationScoring.calculateDimensionScore(planned, actual)
            assertThat(score).isAtLeast(0.0)
            assertThat(score).isAtMost(1.0)
        }
    }

    @Test
    fun smallDeviation_highScore() {
        // 10% deviation should still give a high score
        val score = TimeDeviationScoring.calculateDimensionScore(100, 110)
        assertThat(score).isGreaterThan(0.9)
    }

    @Test
    fun dayScore_weightedAverage() {
        val scores = listOf(
            DimensionTimeScore(
                dimensionId = LifeDimension.CAREER_WORK.id,
                plannedMinutes = 480,
                actualMinutes = 480,
                score = 1.0
            ),
            DimensionTimeScore(
                dimensionId = LifeDimension.HEALTH_WELLNESS.id,
                plannedMinutes = 120,
                actualMinutes = 0,
                score = 0.0
            )
        )
        val dayScore = TimeDeviationScoring.calculateDayScore(scores)
        // Both have weight 1.0, so average = 0.5
        assertThat(dayScore).isWithin(0.001).of(0.5)
    }

    @Test
    fun emptyAllocations_returnsZero() {
        val result = TimeDeviationScoring.calculateFullDayScore(
            dayKey = "2026-02-09",
            allocations = emptyList(),
            actualByDimension = emptyMap()
        )
        assertThat(result.overallScore).isWithin(0.001).of(0.0)
        assertThat(result.isPlanned).isFalse()
    }

    @Test
    fun fullDayScore_matchesExpected() {
        val allocations = listOf(
            LifeDimension.CAREER_WORK.id to 480,
            LifeDimension.HEALTH_WELLNESS.id to 120
        )
        val actual = mapOf(
            LifeDimension.CAREER_WORK.id to 480L,
            LifeDimension.HEALTH_WELLNESS.id to 120L
        )
        val result = TimeDeviationScoring.calculateFullDayScore(
            dayKey = "2026-02-09",
            allocations = allocations,
            actualByDimension = actual
        )
        assertThat(result.overallScore).isWithin(0.001).of(1.0)
        assertThat(result.isPlanned).isTrue()
        assertThat(result.dimensionScores).hasSize(2)
    }

    @Test
    fun unplannedDimensionActual_excluded() {
        // Only Career planned, but Health also tracked
        val allocations = listOf(LifeDimension.CAREER_WORK.id to 480)
        val actual = mapOf(
            LifeDimension.CAREER_WORK.id to 480L,
            LifeDimension.HEALTH_WELLNESS.id to 60L
        )
        val result = TimeDeviationScoring.calculateFullDayScore(
            dayKey = "2026-02-09",
            allocations = allocations,
            actualByDimension = actual
        )
        // Only Career scored (perfect match), Health not in allocations
        assertThat(result.overallScore).isWithin(0.001).of(1.0)
        assertThat(result.dimensionScores).hasSize(1)
    }
}
