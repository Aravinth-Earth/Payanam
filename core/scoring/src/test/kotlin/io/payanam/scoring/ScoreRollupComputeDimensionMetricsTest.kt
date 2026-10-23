//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import com.google.common.truth.Truth.assertThat
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import java.time.LocalDate
import org.junit.Test

/**
 * L2 dimension roll-up - the path that decides what a life dimension is worth on a given day, and whether
 * a day gets a dimension row at all.
 *
 * L2 turns sparse per-habit due-day rows (L1) into dense per-dimension per-day rows. Four properties are
 * pinned here. First, carry-forward: a member with no row today keeps its last known score, so a habit
 * that is not due does not drag its dimension toward zero. Second, weight renormalisation: a member with
 * no L1 row at all is a ghost, and its share of the weight must leave the numerator AND the divisor - keep
 * it in the divisor and a single silent habit halves a perfect dimension. When every member is a ghost
 * nothing is left to average, and the day gets no row rather than a row worth zero. Third, the day-start
 * clamp: a dimension whose members all start later contributes nothing before its first due day, and a
 * task with no dimension is bucketed as `dim_unassigned` rather than dropped. Fourth, the C1 baseline: a
 * tail build seeded from the rows before `fromDay` must reproduce the full rebuild's arithmetic exactly,
 * which is the whole promise of an O(gap) backfill.
 *
 * Every fixture date is `today.minusDays(n)` rather than a fixed date. L2 bounds its loop on
 * `LocalDate.now()`, so a hard-coded day key here goes red overnight with no code change - the trap
 * documented in `FrequencyHabitSyncTest`.
 */
class ScoreRollupComputeDimensionMetricsTest {
    private val today: LocalDate = LocalDate.now()

    private fun day(daysAgo: Int): LocalDate = today.minusDays(daysAgo.toLong())

    /** [dimensionId] defaults to a real bucket; pass null for the unassigned bucket. */
    private fun task(
        id: String,
        dimensionId: String? = "health",
    ) = TaskEntity(
        id = id,
        title = "habit $id",
        createdAt = day(4).toString(),
        updatedAt = day(4).toString(),
        recurrenceEnabled = 1,
        dimensionId = dimensionId,
    )

    private fun habitMetric(
        habitId: String = "h1",
        dayKey: String = day(1).toString(),
        score: Double = 1.0,
    ) = HabitMetricEntity(
        habitId = habitId,
        dayKey = dayKey,
        score = score,
        runningAvg = score,
        progress = score,
        streakPos = 1,
        streakNet = 1,
        posContinue = 1,
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

    // -- carry-forward -------------------------------------------------------------------------------

    @Test
    fun `each member carries its last known score across the days it is not due`() {
        // h1 is due once, on day 4. h2 is due on days 3 and 2. Every day from 4 back to 1 still gets a row:
        // h1 carries 1.0 from day 3 onwards, and h2 carries its own last score.
        val rows = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = listOf(task("h1"), task("h2")),
            firstDuePerHabit = mapOf("h1" to day(4), "h2" to day(2)),
            habitRows = listOf(
                habitMetric(habitId = "h1", dayKey = day(4).toString(), score = 1.0),
                habitMetric(habitId = "h2", dayKey = day(3).toString(), score = 1.0),
                habitMetric(habitId = "h2", dayKey = day(2).toString(), score = 0.0),
            ),
            fromDay = day(4),
        )

        assertThat(rows).hasSize(4)
        assertThat(rows.map { it.dayKey })
            .containsExactly(day(4).toString(), day(3).toString(), day(2).toString(), day(1).toString())
            .inOrder()
        // Days 4 and 3 are both members at 1.0. Day 2 drops to (1.0 + 0.0) / 2, and day 1 carries that.
        assertThat(rows.map { it.score }).containsExactly(1.0, 1.0, 0.5, 0.5).inOrder()
        assertThat(rows[0].runningAvg).isEqualTo(1.0)
        assertThat(rows[1].runningAvg).isEqualTo(1.0)
        assertThat(rows[2].runningAvg).isWithin(1e-9).of(5.0 / 6.0)
        assertThat(rows[3].runningAvg).isWithin(1e-9).of(0.75)
        // Flat progress while sitting at a running average of 1.0 holds the ceiling, so the positive
        // streak keeps climbing; the drop resets it and keeps decrementing the net.
        assertThat(rows[1].progress).isEqualTo(0.0)
        assertThat(rows[1].streakPos).isEqualTo(2)
        assertThat(rows[2].progress).isWithin(1e-9).of(-1.0 / 6.0)
        assertThat(rows[2].streakPos).isEqualTo(0)
        assertThat(rows[3].streakNet).isEqualTo(-1)
    }

    @Test
    fun `a dimension whose members all start later is silent before its first due day`() {
        // The late dimension's first due day is day 3, so its loop starts there even though `early` runs
        // from day 4. Days 4 and 3 belong to early alone - the late dimension does not back-fill zeros.
        val rows = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = listOf(task("e1", dimensionId = "early"), task("l1", dimensionId = "late")),
            firstDuePerHabit = mapOf("e1" to day(4), "l1" to day(3)),
            habitRows = listOf(
                habitMetric(habitId = "e1", dayKey = day(4).toString(), score = 1.0),
                habitMetric(habitId = "e1", dayKey = day(1).toString(), score = 1.0),
                habitMetric(habitId = "l1", dayKey = day(3).toString(), score = 1.0),
                habitMetric(habitId = "l1", dayKey = day(1).toString(), score = 0.0),
            ),
            fromDay = day(4),
        )

        assertThat(rows).hasSize(7)
        val early = rows.filter { it.dimensionId == "early" }
        val late = rows.filter { it.dimensionId == "late" }
        assertThat(early.map { it.dayKey })
            .containsExactly(day(4).toString(), day(3).toString(), day(2).toString(), day(1).toString())
            .inOrder()
        assertThat(late.map { it.dayKey })
            .containsExactly(day(3).toString(), day(2).toString(), day(1).toString())
            .inOrder()
        // Day 2 has no L1 row for l1 and carries day 3's 1.0 forward at the ceiling; day 1's own row is 0.0.
        assertThat(late.map { it.score }).containsExactly(1.0, 1.0, 0.0).inOrder()
        assertThat(late[1].progress).isEqualTo(0.0)
        assertThat(late[1].streakPos).isEqualTo(2)
        assertThat(late[2].runningAvg).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(late[2].progress).isWithin(1e-9).of(-1.0 / 3.0)
        assertThat(late[2].streakPos).isEqualTo(0)
    }

    @Test
    fun `cascade mode adds today's row on top of the four closed days`() {
        val rows = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = listOf(task("h1"), task("h2")),
            firstDuePerHabit = mapOf("h1" to day(4), "h2" to day(2)),
            habitRows = listOf(
                habitMetric(habitId = "h1", dayKey = day(4).toString(), score = 1.0),
                habitMetric(habitId = "h2", dayKey = day(3).toString(), score = 1.0),
                habitMetric(habitId = "h2", dayKey = day(2).toString(), score = 0.0),
            ),
            fromDay = day(4),
            includeToday = true,
        )

        assertThat(rows).hasSize(5)
        assertThat(rows.last().dayKey).isEqualTo(today.toString())
        // Both members carry forward, so today is the same 0.5 as the two closed days before it.
        assertThat(rows.last().score).isEqualTo(0.5)
        assertThat(rows.last().runningAvg).isWithin(1e-9).of(0.7)
    }

    // -- ghost members and the weight guard ----------------------------------------------------------

    @Test
    fun `a ghost member with no L1 rows leaves both the numerator and the divisor`() {
        // hGhost is a member with a first due day but no rows at all. Its 0.5 share must leave the
        // divisor too, otherwise a perfect habit would be reported as a 0.5 dimension.
        val rows = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = listOf(task("h1"), task("hGhost")),
            firstDuePerHabit = mapOf("h1" to day(3), "hGhost" to day(3)),
            habitRows = listOf(
                habitMetric(habitId = "h1", dayKey = day(3).toString(), score = 1.0),
                habitMetric(habitId = "h1", dayKey = day(2).toString(), score = 1.0),
            ),
            fromDay = day(3),
        )

        assertThat(rows).hasSize(3)
        rows.forEach {
            assertThat(it.dimensionId).isEqualTo("health")
            assertThat(it.score).isEqualTo(1.0)
        }
        assertThat(rows.last().streakPos).isEqualTo(3)
    }

    @Test
    fun `a dimension whose members are all ghosts emits no rows at all`() {
        // habitRows is non-empty - a stranger habit keeps the build past the early return - but no member
        // of the dimension has rows, so weightSum never leaves 0.0. A row worth zero would read as "the
        // user scored nothing here", which is a different claim from "nothing to report".
        val rows = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = listOf(task("h1"), task("h2")),
            firstDuePerHabit = mapOf("h1" to day(3), "h2" to day(3)),
            habitRows = listOf(habitMetric(habitId = "stranger", dayKey = day(3).toString(), score = 1.0)),
            fromDay = day(3),
        )

        assertThat(rows).isEmpty()
    }

    @Test
    fun `a recurring task with no first due day is dropped and none left emits nothing`() {
        val rows = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = listOf(task("h1")),
            firstDuePerHabit = emptyMap(),
            habitRows = listOf(habitMetric(habitId = "h1", dayKey = day(2).toString(), score = 1.0)),
            fromDay = day(2),
        )

        assertThat(rows).isEmpty()
    }

    @Test
    fun `no habit rows emits nothing`() {
        assertThat(
            ScoreRollupCompute.buildDimensionMetricsFrom(
                recurring = listOf(task("h1")),
                firstDuePerHabit = mapOf("h1" to day(2)),
                habitRows = emptyList(),
                fromDay = day(2),
            ),
        ).isEmpty()
    }

    @Test
    fun `a task with no dimension is bucketed as dim_unassigned`() {
        val rows = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = listOf(task("h1", dimensionId = null)),
            firstDuePerHabit = mapOf("h1" to day(2)),
            habitRows = listOf(habitMetric(habitId = "h1", dayKey = day(2).toString(), score = 1.0)),
            fromDay = day(2),
        )

        assertThat(rows).hasSize(2)
        rows.forEach {
            assertThat(it.dimensionId).isEqualTo("dim_unassigned")
            assertThat(it.score).isEqualTo(1.0)
        }
    }

    // -- C1 baseline tail ----------------------------------------------------------------------------

    @Test
    fun `a seeded tail reproduces the full rebuild's rows exactly`() {
        val recurring = listOf(task("h1"), task("h2"))
        val firstDuePerHabit = mapOf("h1" to day(4), "h2" to day(4))
        val habitRows = listOf(
            habitMetric(habitId = "h1", dayKey = day(4).toString(), score = 1.0),
            habitMetric(habitId = "h1", dayKey = day(3).toString(), score = 0.0),
            habitMetric(habitId = "h1", dayKey = day(2).toString(), score = 1.0),
            habitMetric(habitId = "h2", dayKey = day(4).toString(), score = 0.0),
            habitMetric(habitId = "h2", dayKey = day(3).toString(), score = 0.0),
            habitMetric(habitId = "h2", dayKey = day(2).toString(), score = 0.0),
        )

        val full = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = recurring,
            firstDuePerHabit = firstDuePerHabit,
            habitRows = habitRows,
            fromDay = day(4),
        )
        val tailBaseline = MetricBaseline.fromDimensionRows(full.take(2))
        val seeded = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = recurring,
            firstDuePerHabit = firstDuePerHabit,
            habitRows = habitRows,
            fromDay = day(2),
            baseline = tailBaseline,
            lastScores = mapOf("h1" to 0.0, "h2" to 0.0),
        )
        // The same tail with an empty carry map: the first tailed day has an exact L1 row for both
        // members, so the carry state rebuilds itself and the numbers must not move.
        val unseeded = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = recurring,
            firstDuePerHabit = firstDuePerHabit,
            habitRows = habitRows,
            fromDay = day(2),
            baseline = tailBaseline,
        )

        assertThat(full).hasSize(4)
        // Guard against the fixture silently degrading into an all-1.0 run, which would make the tail
        // comparison pass for the wrong reason.
        assertThat(full.map { it.score }).containsExactly(0.5, 0.0, 0.5, 0.5).inOrder()
        assertThat(seeded).isEqualTo(full.drop(2))
        assertThat(unseeded).isEqualTo(full.drop(2))
    }

    @Test
    fun `a tail seeded only with a sum divides by the incremented count not the seeded one`() {
        // The L2 counterpart of the count++ -before-divide guard: sumScores without count would report a
        // running average the user never earned.
        val rows = ScoreRollupCompute.buildDimensionMetricsFrom(
            recurring = listOf(task("h1")),
            firstDuePerHabit = mapOf("h1" to day(2)),
            habitRows = listOf(
                habitMetric(habitId = "h1", dayKey = day(2).toString(), score = 1.0),
                habitMetric(habitId = "h1", dayKey = day(1).toString(), score = 0.0),
            ),
            fromDay = day(2),
            baseline = baseline(sumScores = 5.0),
        )

        assertThat(rows).hasSize(2)
        assertThat(rows[0].runningAvg).isEqualTo(6.0)
        assertThat(rows[1].runningAvg).isEqualTo(3.0)
    }
}