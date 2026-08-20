//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.backfill

import io.payanam.database.entity.DayMetricEntity
import io.payanam.database.entity.DimensionMetricEntity
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * ScoreRollupBackfillServiceTest.
 */
class ScoreRollupBackfillServiceTest {


    private fun task(
        /** Id. */
        id: String,
        rule: String = "1/1",
        dimensionId: String? = "dim_health",
        recurrenceEnabled: Int = 1,
    ) = TaskEntity(
        id = id,
        title = "Habit $id",
        description = null,
        status = "pending",
        dueDate = null,
        createdAt = "2025-08-03T06:00:00",
        updatedAt = "2025-08-03T06:00:00",
        completedAt = null,
        archivedAt = null,
        recurrenceEnabled = recurrenceEnabled,
        recurrenceRule = rule,
        durationMinutes = 10,
        impactLevel = "Moderate Impact",
        goalAlignment = "Moderate Alignment",
        energyLevel = "Moderate",
        controlLevel = "Office/Colleagues Dependent",
        lifeIntentionCategory = "Health & Wellness",
        dimensionId = dimensionId,
        dayKey = null,
        explicitUrgency = null,
        focusRequired = null,
        recurrenceStrategy = null,
        blockedReason = null,
        completionRate = null,
        externalDependency = null,
        notificationMode = "auto",
        customNotificationMinutes = null,
        taskScore = null,
        lastOccurrenceDate = null,
        dayBoundaryHour = 0,
        importSource = null,
        importId = null,
        importedAt = null,
        importBatchId = null,
    )

    private fun occurrence(
        /** Task id. */
        taskId: String,
        /** Date. */
        date: LocalDate,
        status: String = "completed",
    ) = TaskOccurrenceEntity(
        id = "$taskId-$date",
        taskId = taskId,
        dueDate = date.toString(),
        completedAt = null,
        actualCompletedAt = null,
        actualDurationMinutes = null,
        status = status,
        statusReason = null,
        createdAt = java.time.LocalDateTime.now().toString(),
        completionRate = null,
        note = null,
    )

    // ── computeStreaks: self-gov ceiling fix ──────────────────────────────
    @Test
    fun `ceiling fix keeps streak when runningAvg is 1`() {
        /** S. */
        val s = ScoreRollupBackfillService.computeStreaks(progress = 0.0, runningAvg = 1.0, streakPos = 5, streakNet = 5, posContinue = 5)
        /** Assert equals. */
        assertEquals(6, s[0])
        /** Assert equals. */
        assertEquals(5, s[1])
        /** Assert equals. */
        assertEquals(5, s[2])
    }

    @Test
    fun `missed day resets streakPos`() {
        /** S. */
        val s = ScoreRollupBackfillService.computeStreaks(progress = -0.2, runningAvg = 0.8, streakPos = 4, streakNet = 4, posContinue = 5)
        /** Assert equals. */
        assertEquals(0, s[0])
        /** Assert equals. */
        assertEquals(3, s[1])
        /** Assert equals. */
        assertEquals(5, s[2])
    }

    @Test
    fun `positive progress advances streaks`() {
        /** S. */
        val s = ScoreRollupBackfillService.computeStreaks(progress = 0.1, runningAvg = 0.9, streakPos = 2, streakNet = 1, posContinue = 3)
        /** Assert equals. */
        assertEquals(3, s[0])
        /** Assert equals. */
        assertEquals(2, s[1])
        /** Assert equals. */
        assertEquals(4, s[2])
    }

    // ── buildHabitMetrics: daily habit with history ───────────────────────
    @Test
    fun `daily habit with all completions gets perfect metrics`() {
        /** T. */
        val t = task("h1")
        /** Start. */
        val start = LocalDate.of(2026, 8, 1)
        /** Occs. */
        val occs = (0 until 7).map { occurrence("h1", start.plusDays(it.toLong())) }
        /** Val. */
        val (rows, firstDue, rule) = ScoreRollupBackfillService.buildHabitMetrics(t, occs)

        /** Assert equals. */
        assertEquals(LocalDate.of(2026, 8, 1), firstDue)
        /** Assert equals. */
        assertEquals("CONFIG:type=DAILY", rule) // num/den 1/1 → DAILY conversion
        /** Assert true. */
        assertTrue(rows.isNotEmpty())
        // Days 1..N before today (today excluded). The fixture starts 2026-08-01
        // with 7 completions; every due day since then gets a row, so the count
        // advances with the real calendar — no hardcoded expectation.
        /** Today. */
        val today = LocalDate.now()
        /** Expected rows. */
        val expectedRows = today.toEpochDay() - start.toEpochDay()
        /** Assert equals. */
        assertEquals(expectedRows, rows.size.toLong())
        // Last completed fixture day (start + 6) still scores 1.0; days after it
        // (gap through yesterday) are 0.0 missed rows.
        /** Last completed. */
        val lastCompleted = rows.firstOrNull { it.dayKey == start.plusDays(6).toString() }
        /** Assert equals. */
        assertEquals(1.0, lastCompleted?.score ?: -1.0, 1e-9)
        /** Assert equals. */
        assertEquals(1.0, lastCompleted?.runningAvg ?: -1.0, 1e-9)
    }

    @Test
    fun `missed due day scores zero and drops running avg`() {
        /** T. */
        val t = task("h2")
        /** Start. */
        val start = LocalDate.of(2026, 8, 1)
        // complete day1, miss day2, complete day3
        /** Occs. */
        val occs = listOf(
            /** Occurrence. */
            occurrence("h2", start),
            /** Occurrence. */
            occurrence("h2", start.plusDays(2)),
        )
        /** Val. */
        val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetrics(t, occs)
        /** Assert equals. */
        assertEquals(1.0, rows[0].score, 1e-9)
        /** Assert equals. */
        assertEquals(0.0, rows[1].score, 1e-9)
        /** Assert equals. */
        assertEquals(0.5, rows[1].runningAvg, 1e-9)
        /** Assert equals. */
        assertEquals(1.0, rows[2].score, 1e-9)
        /** Assert equals. */
        assertEquals(2.0 / 3.0, rows[2].runningAvg, 1e-9)
    }

    @Test
    fun `no occurrences produces no rows`() {
        /** T. */
        val t = task("h3")
        /** Val. */
        val (rows, firstDue, _) = ScoreRollupBackfillService.buildHabitMetrics(t, emptyList())
        /** Assert true. */
        assertTrue(rows.isEmpty())
        /** Assert equals. */
        assertEquals(null, firstDue)
    }

    @Test
    fun `non-recurring task is skipped`() {
        /** T. */
        val t = task("h4", recurrenceEnabled = 0)
        /** Occs. */
        val occs = listOf(occurrence("h4", LocalDate.of(2026, 8, 1)))
        /** Val. */
        val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetrics(t, occs)
        /** Assert true. */
        assertTrue(rows.isEmpty())
    }

    // ── buildDimensionMetrics: dense rows with carry-forward ─────────────
    @Test
    fun `dimension metrics use equal weights and carry forward`() {
        // habit a: daily, 2 due days both completed; habit b: daily, starts later
        /** Today. */
        val today = LocalDate.now()
        /** A. */
        val a = task("a", dimensionId = "dim_x")
        /** B. */
        val b = task("b", dimensionId = "dim_x")
        /** Start. */
        val start = today.minusDays(5)
        /** A rows. */
        val aRows = listOf(
            /** Habit metric entity. */
            HabitMetricEntity("a", start.toString(), 1.0, 1.0, 1.0, 1, 1, 1),
            /** Habit metric entity. */
            HabitMetricEntity("a", start.plusDays(1).toString(), 1.0, 1.0, 0.0, 2, 1, 1),
        )
        /** B rows. */
        val bRows = listOf(
            /** Habit metric entity. */
            HabitMetricEntity("b", start.plusDays(2).toString(), 0.0, 0.0, 0.0, 0, 0, 0),
        )
        /** First due. */
        val firstDue = mapOf("a" to start, "b" to start.plusDays(2))
        /** Rows. */
        val rows = ScoreRollupBackfillService.buildDimensionMetrics(listOf(a, b), firstDue, aRows + bRows)
        /** Assert true. */
        assertTrue("expected dimension rows, got ${rows.size}", rows.isNotEmpty())
        // day start: only habit a (b not yet started) -> score 1.0
        /** Day0. */
        val day0 = rows.first { it.dayKey == start.toString() }
        /** Assert equals. */
        assertEquals(1.0, day0.score, 1e-9)
        // day start+2: a carries 1.0, b = 0.0 -> (1.0+0.0)/2 = 0.5
        /** Day2. */
        val day2 = rows.first { it.dayKey == start.plusDays(2).toString() }
        /** Assert equals. */
        assertEquals(0.5, day2.score, 1e-9)
    }

    // ── buildDayMetrics: dense day rows from dimension rows ───────────────
    @Test
    fun `day metrics average dimension scores per day`() {
        /** D1. */
        val d1 = "2026-08-01"
        /** D2. */
        val d2 = "2026-08-02"
        /** Dim rows. */
        val dimRows = listOf(
            /** Dimension metric entity. */
            DimensionMetricEntity("dim_x", d1, 0.8, 0.8, 0.8, 1, 1, 1),
            /** Dimension metric entity. */
            DimensionMetricEntity("dim_y", d1, 0.6, 0.6, 0.6, 1, 1, 1),
            /** Dimension metric entity. */
            DimensionMetricEntity("dim_x", d2, 0.9, 0.85, 0.05, 2, 2, 2),
        )
        /** Days. */
        val days = ScoreRollupBackfillService.buildDayMetrics(dimRows)
        /** Assert equals. */
        assertEquals(2, days.size)
        /** Day1. */
        val day1 = days.first { it.dayKey == d1 }
        /** Assert equals. */
        assertEquals(0.7, day1.dayScore, 1e-9) // (0.8+0.6)/2
        /** Assert equals. */
        assertEquals(0.7, day1.runningAvg, 1e-9)
        /** Day2. */
        val day2 = days.first { it.dayKey == d2 }
        /** Assert equals. */
        assertEquals(0.9, day2.dayScore, 1e-9) // only dim_x that day
        /** Assert equals. */
        assertEquals(0.8, day2.runningAvg, 1e-9) // (0.7+0.9)/2
    }

    @Test
    fun `empty dimension rows produce no day rows`() {
        /** Assert true. */
        assertTrue(ScoreRollupBackfillService.buildDayMetrics(emptyList()).isEmpty())
    }
}
