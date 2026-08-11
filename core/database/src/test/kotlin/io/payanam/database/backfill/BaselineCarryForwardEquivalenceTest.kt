//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.backfill

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.payanam.database.PayanamDatabase
import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.UUID

/**
 * C1 baseline carry-forward equivalence tests (Inc 4 Part C).
 *
 * Property: for any habit/dimension/day state, a tail build seeded from a
 * cumulative baseline (rows strictly before the change day) must produce
 * EXACTLY the same rows as a full rebuild from the timeline start.
 * This is what makes recalc cost O(gap) and constant over a habit's
 * lifetime (10-year histories included).
 */
@RunWith(RobolectricTestRunner::class)
class BaselineCarryForwardEquivalenceTest {

    private lateinit var db: PayanamDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PayanamDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun habit(id: String, rule: String, createdDaysAgo: Long): TaskEntity = TaskEntity(
        id = id,
        title = "h-$id",
        status = "pending",
        recurrenceEnabled = 1,
        recurrenceRule = rule,
        createdAt = LocalDate.now().minusDays(createdDaysAgo).atStartOfDay().toString(),
        updatedAt = LocalDate.now().toString(),
    )

    private fun occ(taskId: String, daysAgo: Long, status: String = "completed"): TaskOccurrenceEntity =
        TaskOccurrenceEntity(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            dueDate = LocalDate.now().minusDays(daysAgo).toString(),
            status = status,
            createdAt = LocalDate.now().toString(),
        )

    // ── L1 equivalence ────────────────────────────────────────────────────

    @Test
    fun `L1 tail with baseline equals full rebuild`() = runTest {
        // Daily habit, 30 days of history, completions at 25, 20, 15, 10, 5 days ago
        val task = habit("h1", "CONFIG:type=DAILY|start=2026-07-01", 40)
        val occurrences = listOf(25L, 20L, 15L, 10L, 5L).map { occ("h1", it) }

        val (fullRows, _, _) = ScoreRollupBackfillService.buildHabitMetrics(task, occurrences)

        // Simulate: change happened 12 days ago → baseline = rows before day-12
        val changeDay = LocalDate.now().minusDays(12)
        val baselineRows = fullRows.filter { LocalDate.parse(it.dayKey).isBefore(changeDay) }
        val baseline = MetricBaseline.fromHabitRows(baselineRows)
        val (tailRows, _, _) = ScoreRollupBackfillService.buildHabitMetricsFrom(
            task, occurrences, fromDay = changeDay, includeToday = false, baseline = baseline,
        )

        val expectedTail = fullRows.filter { !LocalDate.parse(it.dayKey).isBefore(changeDay) }
        assertEquals("tail row count", expectedTail.size, tailRows.size)
        assertEquals("tail == full tail", expectedTail, tailRows)
    }

    @Test
    fun `L1 tail without baseline equals full build`() = runTest {
        val task = habit("h1", "CONFIG:type=DAILY|start=2026-07-01", 40)
        val occurrences = listOf(25L, 20L, 15L).map { occ("h1", it) }

        val (fullRows, firstDue, _) = ScoreRollupBackfillService.buildHabitMetrics(task, occurrences)
        val (tailRows, tailDue, _) = ScoreRollupBackfillService.buildHabitMetricsFrom(
            task, occurrences, fromDay = firstDue!!, includeToday = false, baseline = MetricBaseline.empty(),
        )

        assertEquals(fullRows, tailRows)
        assertEquals(firstDue, tailDue)
    }

    @Test
    fun `L1 tail from day before firstDue equals full build`() = runTest {
        val task = habit("h1", "CONFIG:type=DAILY|start=2026-07-01", 40)
        val occurrences = listOf(25L, 20L).map { occ("h1", it) }

        val (fullRows, firstDue, _) = ScoreRollupBackfillService.buildHabitMetrics(task, occurrences)
        // fromDay BEFORE firstDue — build must clamp to firstDue
        val (tailRows, _, _) = ScoreRollupBackfillService.buildHabitMetricsFrom(
            task, occurrences, fromDay = firstDue!!.minusDays(3), includeToday = false, baseline = MetricBaseline.empty(),
        )

        assertEquals(fullRows, tailRows)
    }

    // ── L2 equivalence ────────────────────────────────────────────────────

    @Test
    fun `L2 tail with baseline and carry-forward equals full rebuild`() = runTest {
        val start = LocalDate.now().minusDays(30)
        val t1 = habit("h2", "CONFIG:type=DAILY|start=${start}", 40)
        val t2 = habit("h3", "CONFIG:type=DAILY|start=${start}", 40)
        val occ1 = listOf(25L, 15L, 5L).map { occ("h2", it) }
        val occ2 = listOf(20L, 10L).map { occ("h3", it) }

        // Build full L1 timelines for both habits
        val (r1, due1, _) = ScoreRollupBackfillService.buildHabitMetrics(t1, occ1)
        val (r2, due2, _) = ScoreRollupBackfillService.buildHabitMetrics(t2, occ2)
        val allRows = r1 + r2
        val firstDue = mapOf("h2" to due1!!, "h3" to due2!!)
        val members = listOf(t1, t2)

        val fullDim = ScoreRollupBackfillService.buildDimensionMetrics(members, firstDue, allRows)

        // Change day = 12 days ago
        val changeDay = LocalDate.now().minusDays(12)
        val baselineDimRows = fullDim.filter { LocalDate.parse(it.dayKey).isBefore(changeDay) }
        val baseline = MetricBaseline.fromDimensionRows(baselineDimRows)
        val lastScores = allRows
            .groupBy { it.habitId }
            .mapValues { (_, rs) -> rs.filter { LocalDate.parse(it.dayKey).isBefore(changeDay) }.maxByOrNull { it.dayKey } }
            .filterValues { it != null }
            .mapValues { it.value!!.score }

        val tailDim = ScoreRollupBackfillService.buildDimensionMetricsFrom(
            recurring = members,
            firstDuePerHabit = firstDue,
            habitRows = allRows,
            fromDay = changeDay,
            includeToday = false,
            baseline = baseline,
            lastScores = lastScores,
        )

        val expectedTail = fullDim.filter { !LocalDate.parse(it.dayKey).isBefore(changeDay) }
        assertEquals("L2 tail row count", expectedTail.size, tailDim.size)
        assertEquals("L2 tail == full tail", expectedTail, tailDim)
    }

    // ── L3 equivalence ────────────────────────────────────────────────────

    @Test
    fun `L3 tail with baseline equals full rebuild`() = runTest {
        // Build some dimension rows spanning 30 days (2 dims)
        val start = LocalDate.now().minusDays(30)
        val dimRows = mutableListOf<DimensionMetricEntity>()
        var day = start
        var sum = 0.0
        var count = 0
        while (day.isBefore(LocalDate.now())) {
            val s = if (day.dayOfWeek.value % 2 == 0) 0.8 else 0.4
            sum += s
            count++
            dimRows += DimensionMetricEntity(
                dimensionId = "d1", dayKey = day.toString(), score = s,
                runningAvg = sum / count, progress = 0.0, streakPos = count, streakNet = count, posContinue = count,
            )
            day = day.plusDays(1)
        }
        val fullDay = ScoreRollupBackfillService.buildDayMetrics(dimRows)

        val changeDay = LocalDate.now().minusDays(10)
        val baselineRows = fullDay.filter { LocalDate.parse(it.dayKey).isBefore(changeDay) }
        val baseline = MetricBaseline.fromDayRows(baselineRows)
        val tailDay = ScoreRollupBackfillService.buildDayMetricsFrom(dimRows, changeDay, baseline)

        val expectedTail = fullDay.filter { !LocalDate.parse(it.dayKey).isBefore(changeDay) }
        assertEquals("L3 tail row count", expectedTail.size, tailDay.size)
        assertEquals("L3 tail == full tail", expectedTail, tailDay)
    }

    // ── Baseline accumulation sanity ──────────────────────────────────────

    @Test
    fun `baseline derivation matches manual accumulation`() = runTest {
        val rows = listOf(
            HabitMetricEntity("h1", "2026-07-01", 1.0, 1.0, 0.0, 1, 1, 1),
            HabitMetricEntity("h1", "2026-07-02", 0.0, 0.5, -0.5, 0, 0, 1),
            HabitMetricEntity("h1", "2026-07-03", 1.0, 0.6666666666666666, 0.16666666666666663, 1, 1, 2),
        )
        val b = MetricBaseline.fromHabitRows(rows)
        assertEquals(2.0, b.sumScores, 1e-12)
        assertEquals(3, b.count)
        assertEquals(0.6666666666666666, b.prevAvg!!, 1e-12)
        assertEquals(1, b.streakPos)
        assertEquals(1, b.streakNet)
        assertEquals(2, b.posContinue)
    }

    @Test
    fun `empty baseline is identity`() {
        val e = MetricBaseline.empty()
        assertEquals(0.0, e.sumScores, 0.0)
        assertEquals(0, e.count)
        assertEquals(null, e.prevAvg)
        assertEquals(0, e.streakPos)
        assertTrue(MetricBaseline.fromHabitRows(emptyList()) == e)
        assertTrue(MetricBaseline.fromDimensionRows(emptyList()) == e)
        assertTrue(MetricBaseline.fromDayRows(emptyList()) == e)
    }

    // ── C2: weighted L3 aggregation ───────────────────────────────────────

    private fun dimRow(dimId: String, dayKey: String, score: Double): DimensionMetricEntity =
        DimensionMetricEntity(
            dimensionId = dimId, dayKey = dayKey, score = score,
            runningAvg = score, progress = 0.0, streakPos = 0, streakNet = 0, posContinue = 0,
        )

    @Test
    fun `L3 weighted average uses dimension weights`() {
        val dimRows = listOf(
            dimRow("d1", "2026-08-01", 1.0),
            dimRow("d2", "2026-08-01", 0.0),
            dimRow("d1", "2026-08-02", 1.0),
            dimRow("d2", "2026-08-02", 1.0),
        )
        // d1 weight 3.0, d2 weight 1.0 → day1 = (3*1 + 1*0)/4 = 0.75
        val weights = mapOf("d1" to 3.0, "d2" to 1.0)
        val rows = ScoreRollupBackfillService.buildDayMetrics(dimRows, weights)
        assertEquals(2, rows.size)
        assertEquals(0.75, rows[0].dayScore, 1e-12)
        assertEquals(1.0, rows[1].dayScore, 1e-12)
    }

    @Test
    fun `L3 equal weights when weight map empty matches plain average`() {
        val dimRows = listOf(
            dimRow("d1", "2026-08-01", 0.8),
            dimRow("d2", "2026-08-01", 0.4),
        )
        val plain = ScoreRollupBackfillService.buildDayMetrics(dimRows)
        val weighted = ScoreRollupBackfillService.buildDayMetrics(dimRows, emptyMap())
        assertEquals(plain, weighted)
        assertEquals(0.6, weighted[0].dayScore, 1e-12)
    }

    @Test
    fun `L3 unknown dimension falls back to weight 1`() {
        val dimRows = listOf(
            dimRow("d1", "2026-08-01", 1.0),
            dimRow("d2", "2026-08-01", 0.0),
        )
        // d2 unknown → weight 1.0, d1 weight 3.0 → (3*1 + 1*0)/4 = 0.75
        val rows = ScoreRollupBackfillService.buildDayMetrics(dimRows, mapOf("d1" to 3.0))
        assertEquals(0.75, rows[0].dayScore, 1e-12)
    }

    @Test
    fun `L3 weighted tail with baseline matches weighted full rebuild`() {
        val dimRows = listOf(
            dimRow("d1", "2026-07-30", 0.5),
            dimRow("d2", "2026-07-30", 0.5),
            dimRow("d1", "2026-07-31", 1.0),
            dimRow("d2", "2026-07-31", 0.0),
        )
        val weights = mapOf("d1" to 3.0, "d2" to 1.0)
        val full = ScoreRollupBackfillService.buildDayMetrics(dimRows, weights)
        val fromDay = LocalDate.parse("2026-07-31")
        val baseline = MetricBaseline.fromDayRows(full.filter { LocalDate.parse(it.dayKey).isBefore(fromDay) })
        val tail = ScoreRollupBackfillService.buildDayMetricsFrom(dimRows, fromDay, baseline, weights)
        assertEquals(full.filter { !LocalDate.parse(it.dayKey).isBefore(fromDay) }, tail)
        // Weighted day: (3*1 + 1*0)/4 = 0.75; runningAvg over both days.
        assertEquals(0.75, tail[0].dayScore, 1e-12)
    }
}
