//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import com.google.common.truth.Truth.assertThat
import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import java.time.LocalDate
import org.junit.Test

/**
 * L3 aggregation and baseline derivation - the two halves of [ScoreRollupCompute] that decide what a day
 * is worth.
 *
 * Two properties are pinned here. First, the C2 weighted day score: a dimension with no weight row falls back
 * to 1.0, a zero weight must reach neither the numerator nor the divisor (so an all-zero weight map yields
 * 0.0 rather than a division), and a missing dimension row must leave a gap instead of a phantom zero. Second,
 * the C1 baseline: seeded cumulative state has to reproduce the full rebuild's arithmetic exactly, which is the
 * whole promise of tail builds - a backfill that seeds `sumScores` without `count` reports a running average
 * the user never earned.
 *
 * Every fixture date is `today.minusDays(n)` rather than a fixed date. L3 iterates the day keys present in its
 * input and never reads the wall clock, so the relative dates cost nothing here - but a hard-coded day key in
 * this package is exactly what took `FrequencyHabitSyncTest` red overnight with no code change.
 */
class ScoreRollupComputeBaselineTest {
    private val today: LocalDate = LocalDate.now()

    private fun day(daysAgo: Int): LocalDate = today.minusDays(daysAgo.toLong())

    /** [runningAvg] and the streaks default to the shape a fresh tail build would carry. */
    private fun dimensionMetric(
        dimensionId: String = "health",
        dayKey: String = day(1).toString(),
        score: Double = 1.0,
        runningAvg: Double = score,
        progress: Double = score,
        streakPos: Int = 1,
        streakNet: Int = 1,
        posContinue: Int = 1,
    ) = DimensionMetricEntity(
        dimensionId = dimensionId,
        dayKey = dayKey,
        score = score,
        runningAvg = runningAvg,
        progress = progress,
        streakPos = streakPos,
        streakNet = streakNet,
        posContinue = posContinue,
    )

    private fun dayMetric(
        dayKey: String = day(1).toString(),
        dayScore: Double = 1.0,
        runningAvg: Double = dayScore,
        progress: Double = dayScore,
        streakPos: Int = 1,
        streakNet: Int = 1,
        posContinue: Int = 1,
    ) = DayMetricEntity(
        dayKey = dayKey,
        dayScore = dayScore,
        runningAvg = runningAvg,
        progress = progress,
        streakPos = streakPos,
        streakNet = streakNet,
        posContinue = posContinue,
    )

    private fun habitMetric(
        habitId: String = "h1",
        dayKey: String = day(1).toString(),
        score: Double = 1.0,
        runningAvg: Double = score,
        progress: Double = score,
        streakPos: Int = 1,
        streakNet: Int = 1,
        posContinue: Int = 1,
    ) = HabitMetricEntity(
        habitId = habitId,
        dayKey = dayKey,
        score = score,
        runningAvg = runningAvg,
        progress = progress,
        streakPos = streakPos,
        streakNet = streakNet,
        posContinue = posContinue,
    )

    private fun baseline(
        sumScores: Double = 0.0,
        count: Int = 0,
        prevAvg: Double? = null,
        streakPos: Int = 0,
        streakNet: Int = 0,
        posContinue: Int = 0,
    ) = MetricBaseline(
        sumScores = sumScores,
        count = count,
        prevAvg = prevAvg,
        streakPos = streakPos,
        streakNet = streakNet,
        posContinue = posContinue,
    )

    // -- buildDayMetricsFrom ---------------------------------------------------------------------------

    @Test
    fun `day score is the plain mean of its dimensions when no weights are given`() {
        val rows = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = listOf(
                dimensionMetric(dimensionId = "health", dayKey = day(1).toString(), score = 1.0),
                dimensionMetric(dimensionId = "work", dayKey = day(1).toString(), score = 0.0),
            ),
            fromDay = day(1),
        )

        assertThat(rows).hasSize(1)
        assertThat(rows.single().dayScore).isEqualTo(0.5)
    }

    @Test
    fun `explicit dimension weights shift the day score toward the heavier dimension`() {
        val rows = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = listOf(
                dimensionMetric(dimensionId = "health", dayKey = day(1).toString(), score = 1.0),
                dimensionMetric(dimensionId = "work", dayKey = day(1).toString(), score = 1.0),
                dimensionMetric(dimensionId = "health", dayKey = day(2).toString(), score = 0.0),
                dimensionMetric(dimensionId = "work", dayKey = day(2).toString(), score = 1.0),
            ),
            fromDay = day(2),
            dimWeights = mapOf("health" to 2.0, "work" to 1.0),
        )

        // Rows are emitted in ascending day order, so the EARLIER day leads. Assert against the
        // day keys rather than positions: the heavier dimension is day 1's, and day 2 drops to a third.
        assertThat(rows).hasSize(2)
        val byKey = rows.associateBy { it.dayKey }
        assertThat(byKey.getValue(day(1).toString()).dayScore).isEqualTo(1.0)
        assertThat(byKey.getValue(day(2).toString()).dayScore).isWithin(1e-9).of(0.3333333333)
    }

    @Test
    fun `a dimension with no weight row falls back to weight one`() {
        val rows = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = listOf(
                dimensionMetric(dimensionId = "health", dayKey = day(1).toString(), score = 1.0),
                dimensionMetric(dimensionId = "work", dayKey = day(1).toString(), score = 1.0),
                dimensionMetric(dimensionId = "health", dayKey = day(2).toString(), score = 0.0),
                dimensionMetric(dimensionId = "work", dayKey = day(2).toString(), score = 1.0),
            ),
            fromDay = day(2),
            dimWeights = mapOf("health" to 3.0),
        )

        // The missing 'work' weight falls back to one, so day 2 is (0*3 + 1*1) / (3 + 1) = 0.25.
        assertThat(rows).hasSize(2)
        val byKey = rows.associateBy { it.dayKey }
        assertThat(byKey.getValue(day(1).toString()).dayScore).isEqualTo(1.0)
        assertThat(byKey.getValue(day(2).toString()).dayScore).isWithin(1e-9).of(0.25)
    }

    @Test
    fun `an all-zero weight map reports a zero day score instead of dividing`() {
        val rows = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = listOf(
                dimensionMetric(dimensionId = "health", dayKey = day(1).toString(), score = 1.0),
                dimensionMetric(dimensionId = "work", dayKey = day(1).toString(), score = 1.0),
                dimensionMetric(dimensionId = "health", dayKey = day(2).toString(), score = 1.0),
                dimensionMetric(dimensionId = "work", dayKey = day(2).toString(), score = 1.0),
            ),
            fromDay = day(2),
            dimWeights = mapOf("health" to 0.0, "work" to 0.0),
        )

        assertThat(rows).hasSize(2)
        rows.forEach {
            assertThat(it.dayScore).isEqualTo(0.0)
            assertThat(it.runningAvg).isEqualTo(0.0)
            assertThat(listOf(it.streakPos, it.streakNet, it.posContinue)).containsExactly(0, 0, 0)
        }
    }

    @Test
    fun `a zero-weight dimension reaches the numerator as zero so only the weighted score counts`() {
        val rows = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = listOf(
                dimensionMetric(dimensionId = "health", dayKey = day(1).toString(), score = 1.0),
                dimensionMetric(dimensionId = "work", dayKey = day(1).toString(), score = 0.0),
            ),
            fromDay = day(1),
            dimWeights = mapOf("health" to 0.0, "work" to 2.0),
        )

        assertThat(rows).hasSize(1)
        assertThat(rows.single().dayScore).isEqualTo(0.0)
    }

    @Test
    fun `a gap in the dimension rows emits no day row for the missing day`() {
        val rows = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = listOf(
                dimensionMetric(dimensionId = "health", dayKey = day(3).toString(), score = 1.0),
                dimensionMetric(dimensionId = "health", dayKey = day(1).toString(), score = 0.0),
            ),
            fromDay = day(3),
        )

        assertThat(rows.map { it.dayKey })
            .containsExactly(day(3).toString(), day(1).toString())
            .inOrder()
    }

    @Test
    fun `a fromDay after every dimension row emits nothing`() {
        val rows = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = listOf(
                dimensionMetric(dimensionId = "health", dayKey = day(3).toString(), score = 1.0),
                dimensionMetric(dimensionId = "health", dayKey = day(2).toString(), score = 1.0),
            ),
            fromDay = day(1),
        )

        assertThat(rows).isEmpty()
    }

    @Test
    fun `no dimension rows emits nothing`() {
        assertThat(ScoreRollupCompute.buildDayMetricsFrom(emptyList(), day(1))).isEmpty()
    }

    @Test
    fun `a seeded baseline reproduces the full rebuild's rows exactly`() {
        val dimRows = listOf(
            dimensionMetric(dimensionId = "health", dayKey = day(3).toString(), score = 1.0),
            dimensionMetric(dimensionId = "health", dayKey = day(2).toString(), score = 1.0),
            dimensionMetric(dimensionId = "health", dayKey = day(1).toString(), score = 0.0),
        )
        val full = ScoreRollupCompute.buildDayMetricsFrom(dimRows, day(3))
        val tail = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = dimRows,
            fromDay = day(2),
            baseline = baseline(
                sumScores = 1.0,
                count = 1,
                prevAvg = 1.0,
                streakPos = 1,
                streakNet = 1,
                posContinue = 1,
            ),
        )

        assertThat(full).hasSize(3)
        assertThat(tail).hasSize(2)
        assertThat(tail).isEqualTo(full.drop(1))
    }

    @Test
    fun `a baseline with a sum but no count divides by the incremented count not the seeded one`() {
        val rows = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = listOf(
                dimensionMetric(dimensionId = "health", dayKey = day(2).toString(), score = 1.0),
                dimensionMetric(dimensionId = "health", dayKey = day(1).toString(), score = 0.0),
            ),
            fromDay = day(2),
            baseline = baseline(sumScores = 5.0),
        )

        assertThat(rows).hasSize(2)
        assertThat(rows[0].runningAvg).isEqualTo(6.0)
        assertThat(rows[1].runningAvg).isEqualTo(3.0)
    }

    @Test
    fun `holding the ceiling advances the positive streak on flat progress`() {
        val rows = ScoreRollupCompute.buildDayMetricsFrom(
            dimensionRows = listOf(
                dimensionMetric(dimensionId = "health", dayKey = day(2).toString(), score = 1.0),
                dimensionMetric(dimensionId = "health", dayKey = day(1).toString(), score = 1.0),
            ),
            fromDay = day(2),
        )

        assertThat(rows).hasSize(2)
        assertThat(rows[0].runningAvg).isEqualTo(1.0)
        assertThat(rows[1].runningAvg).isEqualTo(1.0)
        assertThat(rows[1].progress).isEqualTo(0.0)
        assertThat(rows[1].streakPos).isEqualTo(2)
    }

    // -- MetricBaseline factories --------------------------------------------------------------------------

    @Test
    fun `an empty baseline is zeroed with no previous average`() {
        val empty = MetricBaseline.empty()

        assertThat(empty.sumScores).isEqualTo(0.0)
        assertThat(empty.count).isEqualTo(0)
        assertThat(empty.prevAvg).isNull()
        assertThat(listOf(empty.streakPos, empty.streakNet, empty.posContinue)).containsExactly(0, 0, 0)
    }

    @Test
    fun `a habit-row baseline sums the scores and reads the streaks off the last row`() {
        val rows = listOf(
            habitMetric(
                dayKey = day(3).toString(), score = 1.0, runningAvg = 1.0,
                streakPos = 1, streakNet = 0, posContinue = 0,
            ),
            habitMetric(
                dayKey = day(2).toString(), score = 1.0, runningAvg = 1.0,
                streakPos = 2, streakNet = 1, posContinue = 1,
            ),
            habitMetric(
                dayKey = day(1).toString(), score = 0.0, runningAvg = 2.0 / 3.0,
                streakPos = 1, streakNet = 2, posContinue = 1,
            ),
        )

        val base = MetricBaseline.fromHabitRows(rows)

        assertThat(base.sumScores).isEqualTo(2.0)
        assertThat(base.count).isEqualTo(3)
        assertThat(base.prevAvg).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(listOf(base.streakPos, base.streakNet, base.posContinue)).containsExactly(1, 2, 1)
    }

    @Test
    fun `a dimension-row baseline sums the scores and reads the streaks off the last row`() {
        val rows = listOf(
            dimensionMetric(
                dayKey = day(3).toString(), score = 1.0, runningAvg = 1.0,
                streakPos = 1, streakNet = 0, posContinue = 0,
            ),
            dimensionMetric(
                dayKey = day(2).toString(), score = 1.0, runningAvg = 1.0,
                streakPos = 2, streakNet = 1, posContinue = 1,
            ),
            dimensionMetric(
                dayKey = day(1).toString(), score = 0.0, runningAvg = 2.0 / 3.0,
                streakPos = 1, streakNet = 2, posContinue = 1,
            ),
        )

        val base = MetricBaseline.fromDimensionRows(rows)

        assertThat(base.sumScores).isEqualTo(2.0)
        assertThat(base.count).isEqualTo(3)
        assertThat(base.prevAvg).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(listOf(base.streakPos, base.streakNet, base.posContinue)).containsExactly(1, 2, 1)
    }

    @Test
    fun `a day-row baseline sums the day score rather than any other field`() {
        val rows = listOf(
            dayMetric(
                dayKey = day(3).toString(), dayScore = 1.0, runningAvg = 1.0,
                streakPos = 1, streakNet = 0, posContinue = 0,
            ),
            dayMetric(
                dayKey = day(2).toString(), dayScore = 1.0, runningAvg = 1.0,
                streakPos = 2, streakNet = 1, posContinue = 1,
            ),
            dayMetric(
                dayKey = day(1).toString(), dayScore = 0.0, runningAvg = 2.0 / 3.0,
                streakPos = 1, streakNet = 2, posContinue = 1,
            ),
        )

        val base = MetricBaseline.fromDayRows(rows)

        assertThat(base.sumScores).isEqualTo(2.0)
        assertThat(base.count).isEqualTo(3)
        assertThat(base.prevAvg).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(listOf(base.streakPos, base.streakNet, base.posContinue)).containsExactly(1, 2, 1)
    }

    @Test
    fun `every baseline factory falls back to empty for an empty list`() {
        assertThat(MetricBaseline.fromHabitRows(emptyList())).isEqualTo(MetricBaseline.empty())
        assertThat(MetricBaseline.fromDimensionRows(emptyList())).isEqualTo(MetricBaseline.empty())
        assertThat(MetricBaseline.fromDayRows(emptyList())).isEqualTo(MetricBaseline.empty())
    }
}
