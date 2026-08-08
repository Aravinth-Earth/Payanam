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

class ScoreRollupBackfillServiceTest {


    private fun task(
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
        taskId: String,
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
        val s = ScoreRollupBackfillService.computeStreaks(progress = 0.0, runningAvg = 1.0, streakPos = 5, streakNet = 5, posContinue = 5)
        assertEquals(6, s[0])
        assertEquals(5, s[1])
        assertEquals(5, s[2])
    }

    @Test
    fun `missed day resets streakPos`() {
        val s = ScoreRollupBackfillService.computeStreaks(progress = -0.2, runningAvg = 0.8, streakPos = 4, streakNet = 4, posContinue = 5)
        assertEquals(0, s[0])
        assertEquals(3, s[1])
        assertEquals(5, s[2])
    }

    @Test
    fun `positive progress advances streaks`() {
        val s = ScoreRollupBackfillService.computeStreaks(progress = 0.1, runningAvg = 0.9, streakPos = 2, streakNet = 1, posContinue = 3)
        assertEquals(3, s[0])
        assertEquals(2, s[1])
        assertEquals(4, s[2])
    }

    // ── buildHabitMetrics: daily habit with history ───────────────────────
    @Test
    fun `daily habit with all completions gets perfect metrics`() {
        val t = task("h1")
        val start = LocalDate.of(2026, 8, 1)
        val occs = (0 until 7).map { occurrence("h1", start.plusDays(it.toLong())) }
        val (rows, firstDue, rule) = ScoreRollupBackfillService.buildHabitMetrics(t, occs)

        assertEquals(LocalDate.of(2026, 8, 1), firstDue)
        assertEquals("CONFIG:type=DAILY", rule) // num/den 1/1 → DAILY conversion
        assertTrue(rows.isNotEmpty())
        // 7 days before today (today excluded) — if today is 2026-08-08, days 1..7 = 7 rows
        val today = LocalDate.now()
        val expectedRows = 7L.coerceAtMost(today.toEpochDay() - start.toEpochDay())
        assertEquals(expectedRows, rows.size.toLong())
        assertEquals(1.0, rows.last().runningAvg, 1e-9)
        assertEquals(1.0, rows.last().score, 1e-9)
    }

    @Test
    fun `missed due day scores zero and drops running avg`() {
        val t = task("h2")
        val start = LocalDate.of(2026, 8, 1)
        // complete day1, miss day2, complete day3
        val occs = listOf(
            occurrence("h2", start),
            occurrence("h2", start.plusDays(2)),
        )
        val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetrics(t, occs)
        assertEquals(1.0, rows[0].score, 1e-9)
        assertEquals(0.0, rows[1].score, 1e-9)
        assertEquals(0.5, rows[1].runningAvg, 1e-9)
        assertEquals(1.0, rows[2].score, 1e-9)
        assertEquals(2.0 / 3.0, rows[2].runningAvg, 1e-9)
    }

    @Test
    fun `no occurrences produces no rows`() {
        val t = task("h3")
        val (rows, firstDue, _) = ScoreRollupBackfillService.buildHabitMetrics(t, emptyList())
        assertTrue(rows.isEmpty())
        assertEquals(null, firstDue)
    }

    @Test
    fun `non-recurring task is skipped`() {
        val t = task("h4", recurrenceEnabled = 0)
        val occs = listOf(occurrence("h4", LocalDate.of(2026, 8, 1)))
        val (rows, _, _) = ScoreRollupBackfillService.buildHabitMetrics(t, occs)
        assertTrue(rows.isEmpty())
    }

    // ── buildDimensionMetrics: dense rows with carry-forward ─────────────
    @Test
    fun `dimension metrics use equal weights and carry forward`() {
        // habit a: daily, 2 due days both completed; habit b: daily, starts later
        val today = LocalDate.now()
        val a = task("a", dimensionId = "dim_x")
        val b = task("b", dimensionId = "dim_x")
        val start = today.minusDays(5)
        val aRows = listOf(
            HabitMetricEntity("a", start.toString(), 1.0, 1.0, 1.0, 1, 1, 1),
            HabitMetricEntity("a", start.plusDays(1).toString(), 1.0, 1.0, 0.0, 2, 1, 1),
        )
        val bRows = listOf(
            HabitMetricEntity("b", start.plusDays(2).toString(), 0.0, 0.0, 0.0, 0, 0, 0),
        )
        val firstDue = mapOf("a" to start, "b" to start.plusDays(2))
        val rows = ScoreRollupBackfillService.buildDimensionMetrics(listOf(a, b), firstDue, aRows + bRows)
        assertTrue("expected dimension rows, got ${rows.size}", rows.isNotEmpty())
        // day start: only habit a (b not yet started) -> score 1.0
        val day0 = rows.first { it.dayKey == start.toString() }
        assertEquals(1.0, day0.score, 1e-9)
        // day start+2: a carries 1.0, b = 0.0 -> (1.0+0.0)/2 = 0.5
        val day2 = rows.first { it.dayKey == start.plusDays(2).toString() }
        assertEquals(0.5, day2.score, 1e-9)
    }

    // ── buildDayMetrics: dense day rows from dimension rows ───────────────
    @Test
    fun `day metrics average dimension scores per day`() {
        val d1 = "2026-08-01"
        val d2 = "2026-08-02"
        val dimRows = listOf(
            DimensionMetricEntity("dim_x", d1, 0.8, 0.8, 0.8, 1, 1, 1),
            DimensionMetricEntity("dim_y", d1, 0.6, 0.6, 0.6, 1, 1, 1),
            DimensionMetricEntity("dim_x", d2, 0.9, 0.85, 0.05, 2, 2, 2),
        )
        val days = ScoreRollupBackfillService.buildDayMetrics(dimRows)
        assertEquals(2, days.size)
        val day1 = days.first { it.dayKey == d1 }
        assertEquals(0.7, day1.dayScore, 1e-9) // (0.8+0.6)/2
        assertEquals(0.7, day1.runningAvg, 1e-9)
        val day2 = days.first { it.dayKey == d2 }
        assertEquals(0.9, day2.dayScore, 1e-9) // only dim_x that day
        assertEquals(0.8, day2.runningAvg, 1e-9) // (0.7+0.9)/2
    }

    @Test
    fun `empty dimension rows produce no day rows`() {
        assertTrue(ScoreRollupBackfillService.buildDayMetrics(emptyList()).isEmpty())
    }
}
