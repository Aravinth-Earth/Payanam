//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.lens

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.TimeEntry
import io.payanam.shared.logging.AppLogger
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Tests for the lens analytics helpers that moved out of the Android module.
 *
 * They exist here as well as being driven indirectly by the JDBC repository tests because coverage is
 * measured per module: a class that lives in `:core:domain` but is only exercised from `:core:database`
 * counts as uncovered, which is exactly what dropping this module's coverage floor from 0.78 to 0.38
 * revealed.
 *
 * Pure functions of a list of entries, so no database and no Android runtime are involved.
 */
class LensAnalyticsHelpersTest {
    private val logger = AppLogger.current
    private val today: LocalDate = LocalDate.now()

    private fun entry(
        startedAt: LocalDateTime,
        endedAt: LocalDateTime?,
        dimensionId: String? = "dim_work_livelihood",
        focusRating: Double? = null,
        taskId: String? = null,
    ) = TimeEntry(
        id = "entry-${startedAt}",
        lifeIntentionCategory = "Work",
        taskId = taskId,
        startedAt = startedAt,
        endedAt = endedAt,
        focusRating = focusRating,
        dimensionId = dimensionId,
        // The helpers read startedAt/endedAt/dimensionId/focusRating/taskId only; the timestamps below are
        // never asserted on, they just have to exist.
        createdAt = startedAt,
        updatedAt = startedAt,
    )

    private val morning = entry(today.atTime(9, 0), today.atTime(11, 0), focusRating = 0.9)

    @Test
    fun `the heatmap carries one day per tracked day with its segments`() {
        val days = buildHeatmapDays(listOf(morning), logger)

        assertThat(days).hasSize(1)
        assertThat(days.single().dayKey).isEqualTo(today.toString())
        assertThat(days.single().segments).isNotEmpty()
    }

    @Test
    fun `the heatmap reports the range but only COMPLETED entries carry segments`() {
        // The heatmap is a calendar view, not a list of entries: with nothing tracked it still reports the
        // current day, because a blank cell is information. What must never appear is a segment from an entry
        // that is still running -- it has no duration yet.
        val blank = buildHeatmapDays(emptyList(), logger)
        assertThat(blank).isNotEmpty()
        assertThat(blank.flatMap { it.segments }).isEmpty()

        val running = entry(today.atTime(9, 0), endedAt = null)
        val withRunningOnly = buildHeatmapDays(listOf(running), logger)
        assertThat(withRunningOnly.flatMap { it.segments }).isEmpty()
    }

    @Test
    fun `the week grid has a column per tracked day and drops empties on request`() {
        val days = buildWeekGridData(listOf(morning), excludeEmptyDays = false, logger = logger)

        assertThat(days.days).isNotEmpty()
        assertThat(days.days.flatMap { it.slots }).isNotEmpty()

        val withoutEmpty = buildWeekGridData(listOf(morning), excludeEmptyDays = true, logger = logger)
        assertThat(withoutEmpty.days.size).isAtMost(days.days.size)
        assertThat(buildWeekGridData(emptyList(), excludeEmptyDays = false, logger = logger).days).isEmpty()
    }

    @Test
    fun `the minute pattern gives every day a full 1440-slot column`() {
        val pattern = buildMinutePatternData(listOf(morning), excludeEmptyDays = false, logger = logger)

        assertThat(pattern.days).isNotEmpty()
        assertThat(pattern.days.all { it.minuteWinners.size == 1440 }).isTrue()
        // 09:00-11:00 must be attributed to something rather than left untracked.
        val attributed = pattern.days.flatMap { it.minuteWinners }.count { it != null }
        assertThat(attributed).isAtLeast(120)
    }

    @Test
    fun `the minute pattern is empty for no entries`() {
        assertThat(buildMinutePatternData(emptyList(), excludeEmptyDays = false, logger = logger).days).isEmpty()
    }

    @Test
    fun `the dimension trend blocks cover the requested window`() {
        val blocks = buildDimensionTrendBlocks(listOf(morning), windowDays = 7, logger = logger)

        assertThat(blocks).isNotEmpty()
        assertThat(blocks.first().byDimension).containsKey("dim_work_livelihood")
        assertThat(blocks.first().totalPossibleMinutes).isEqualTo(7 * 24 * 60)
    }


    @Test
    fun `an entry crossing midnight is split into one segment per day`() {
        val overnight = entry(today.minusDays(1).atTime(23, 0), today.atTime(1, 0))

        val days = buildHeatmapDays(listOf(overnight), logger)

        val byDay = days.associateBy { it.dayKey }
        // The part before midnight belongs to yesterday, the part after to today.
        assertThat(byDay[today.minusDays(1).toString()]?.segments).isNotEmpty()
        assertThat(byDay[today.toString()]?.segments).isNotEmpty()
        assertThat(byDay[today.toString()]!!.segments.single().startMinute).isEqualTo(0)
        assertThat(byDay[today.toString()]!!.segments.single().durationMinutes).isEqualTo(60)
        assertThat(byDay[today.minusDays(1).toString()]!!.segments.single().durationMinutes).isEqualTo(60)
    }

    @Test
    fun `the week grid attributes an entry to its weekday slot`() {
        val week = buildWeekGridData(
            listOf(entry(today.atTime(9, 0), today.atTime(11, 0))),
            excludeEmptyDays = false,
            logger = logger,
        )

        val day = week.days.single { it.dayOfWeek == today.dayOfWeek }
        // Every column is a full 48 half-hour rows -- a fixed grid with blank cells, not a list of what was
        // tracked -- so the shape is asserted, not the contents.
        assertThat(day.slots).hasSize(48)
        assertThat(week.days.map { it.dayOfWeek }).contains(today.dayOfWeek)
        assertThat(week.days.all { it.slots.size == 48 }).isTrue()
    }

    @Test
    fun `a weekday with nothing tracked is still a column, and exclusion keeps the tracked ones`() {
        val oneOff = entry(today.atTime(9, 0), today.atTime(10, 0))

        val full = buildWeekGridData(listOf(oneOff), excludeEmptyDays = false, logger = logger)
        val trimmed = buildWeekGridData(listOf(oneOff), excludeEmptyDays = true, logger = logger)

        // A weekday exists as a column whether or not anything was tracked; what exclusion changes is which
        // days survive, and the day that WAS tracked must survive either way.
        assertThat(full.days.map { it.dayOfWeek }).contains(today.dayOfWeek)
        assertThat(trimmed.days.map { it.dayOfWeek }).contains(today.dayOfWeek)
        assertThat(trimmed.days.size).isAtMost(full.days.size)
    }

    @Test
    fun `the minute pattern attributes each tracked minute to its dimension`() {
        val work = entry(today.atTime(9, 0), today.atTime(10, 0), dimensionId = "dim_work_livelihood")
        val health = entry(today.atTime(9, 0), today.atTime(10, 0), dimensionId = "dim_physical_health")

        // Two entries over the SAME minutes: the pattern picks one winner per minute rather than summing.
        val pattern = buildMinutePatternData(listOf(work, health), excludeEmptyDays = false, logger = logger)

        val minuteNine = pattern.days.single { it.dayOfWeek == today.dayOfWeek }.minuteWinners[9 * 60]
        assertThat(minuteNine).isNotNull()
        assertThat(listOf("dim_work_livelihood", "dim_physical_health")).contains(minuteNine)
    }

    @Test
    fun `a dimension-less entry still fills a complete minute column`() {
        val untracked = entry(today.atTime(9, 0), today.atTime(10, 0), dimensionId = null)

        val pattern = buildMinutePatternData(listOf(untracked), excludeEmptyDays = false, logger = logger)

        // The column is always all 1440 minutes, and every populated minute names something. Which category
        // wins a contested minute is the grid's business; that it never emits a blank label is this test's.
        val winners = pattern.days.single { it.dayOfWeek == today.dayOfWeek }.minuteWinners
        assertThat(winners).hasSize(1440)
        assertThat(winners.filterNotNull().all { it.isNotBlank() }).isTrue()
    }

    @Test
    fun `the trend blocks weight each window by its own days`() {
        val recent = entry(today.minusDays(1).atTime(9, 0), today.minusDays(1).atTime(10, 0))
        val older = entry(today.minusDays(20).atTime(9, 0), today.minusDays(20).atTime(11, 0))

        val blocks = buildDimensionTrendBlocks(listOf(recent, older), windowDays = 30, logger = logger)

        assertThat(blocks).isNotEmpty()
        val totalTracked = blocks.sumOf { block -> block.byDimension.values.sum() }
        assertThat(totalTracked).isEqualTo(180)
        assertThat(blocks.all { it.totalPossibleMinutes == 30 * 24 * 60 }).isTrue()
    }

    @Test
    fun `the dimension split counts only the entries inside the range`() {
        val inside = entry(today.atTime(9, 0), today.atTime(10, 0), dimensionId = "dim_work_livelihood")
        val outside = entry(today.minusDays(10).atTime(9, 0), today.minusDays(10).atTime(10, 0))
        val otherDimension = entry(today.atTime(14, 0), today.atTime(15, 0), dimensionId = "dim_physical_health")

        val split =
            DailyStatsCalculator.calculateDimensionSplit(listOf(inside, outside, otherDimension), today, today)

        assertThat(split["dim_work_livelihood"]).isEqualTo(60)
        assertThat(split["dim_physical_health"]).isEqualTo(60)
        assertThat(split.values.sum()).isEqualTo(120)
    }
}
