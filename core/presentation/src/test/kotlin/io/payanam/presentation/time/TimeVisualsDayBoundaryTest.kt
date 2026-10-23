//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import io.payanam.domain.model.TimeEntry
import io.payanam.shared.logging.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pins where a past day's intervals stop: at the boundary where its successor begins, exclusive.
 *
 * That boundary used to be the wall-clock second 23:59:59, so a day ran out one second before the day
 * grid does and an entry that crossed midnight lost that second - 23:30 to 00:30 read as 29 minutes on
 * the earlier day instead of 30. The production line all of this turns on is the `else` branch of
 * `effectiveDayEnd` in `TimeVisualsCalculator`: restoring `LocalDateTime.of(selectedDate, LocalTime.of(23,
 * 59, 59))` there turns the three past-day cases below red on their tracked-minute assertions while
 * leaving the ordinary-entry and today cases green, which is what separates "the boundary moved" from
 * "the arithmetic broke".
 */
class TimeVisualsDayBoundaryTest {
    private val logger = AppLogger.current

    /**
     * A past day's entry running past midnight is clipped to midnight, not to 23:59:59.
     *
     * 23:30 to 00:30 the next morning is 30 minutes on the day being summarised. Against the 23:59:59
     * end it measured 29m59s, which `Duration.toMinutes()` floors to 29 - a minute the user tracked and
     * the calendar does not have.
     */
    @Test
    fun past_day_clips_midnight_crossing_entry_to_the_boundary() {
        logger.d("TimeVisualsDayBoundaryTest", "Running midnight-crossing clip case")
        val day = LocalDate.of(2026, 2, 15)
        val entries = listOf(entry("e1", day.atTime(23, 30), day.plusDays(1).atTime(0, 30), 1.0))

        val summary = TimeVisualsCalculator.computeDayOverall(day, entries, now = day.plusDays(1).atTime(9, 0))

        assertEquals(30L, summary.trackedMinutes)
    }

    /**
     * The boundary is the successor's start, so a past day's final minute is tracked in full.
     *
     * 23:00 to midnight is a whole hour on the day being summarised and nothing of the successor. This is
     * the assertion that pins the boundary from below: it is red if the day's end is pulled back to
     * 23:59:59 (59 minutes) or anywhere earlier, and green only at midnight or later - so a fix that
     * over-corrected into the successor's own end of day still passes it, which the crossing case below
     * is the one that catches.
     */
    @Test
    fun past_day_tracks_the_final_minute_of_the_day() {
        logger.d("TimeVisualsDayBoundaryTest", "Running final-minute case")
        val day = LocalDate.of(2026, 2, 15)
        val entries = listOf(entry("e1", day.atTime(23, 0), day.plusDays(1).atStartOfDay(), 1.0))

        val summary = TimeVisualsCalculator.computeDayOverall(day, entries, now = day.plusDays(1).atTime(9, 0))

        assertEquals(60L, summary.trackedMinutes)
    }

    /**
     * An entry wholly inside an ordinary past day is measured exactly, whichever end the day has.
     *
     * The guard against the fix being read as "clip everything to midnight": 09:00 to 10:30 is 90 minutes
     * under both boundaries, so a change that broke ordinary days could not pass this file.
     */
    @Test
    fun past_day_measures_an_entry_inside_the_day_exactly() {
        logger.d("TimeVisualsDayBoundaryTest", "Running ordinary-entry case")
        val day = LocalDate.of(2026, 2, 15)
        val entries = listOf(entry("e1", day.atTime(9, 0), day.atTime(10, 30), 1.0))

        val summary = TimeVisualsCalculator.computeDayOverall(day, entries, now = day.plusDays(1).atTime(9, 0))

        assertEquals(90L, summary.trackedMinutes)
    }

    /**
     * Today is bounded by now, not by its successor, so a running entry stops at the clock.
     *
     * This is what "past day" has to mean for the other boundary to mean anything. With now at 23:45 on
     * the day being summarised, an entry started at 23:30 and not yet ended has tracked 15 minutes; a
     * boundary that reached forward to midnight would claim 30 and credit the day with time the clock has
     * not reached. Green before this change too, because the past-day branch was only reached when the
     * dates differed - it is here so that always clipping to the next midnight cannot pass.
     */
    @Test
    fun today_clips_a_running_entry_to_now_not_to_the_boundary() {
        logger.d("TimeVisualsDayBoundaryTest", "Running today-is-not-past case")
        val day = LocalDate.of(2026, 2, 15)
        val entries = listOf(entry("e1", day.atTime(23, 30), null, 1.0))

        val summary = TimeVisualsCalculator.computeDayOverall(day, entries, now = day.atTime(23, 45))

        assertEquals(15L, summary.trackedMinutes)
    }

    /**
     * A past day tracked from midnight to midnight reports the whole 1440 minutes, and no trailing gap.
     *
     * The day-wide sweep is the second consumer of the same boundary, and the gap count is what it is
     * for: an entry reaching the day's edge used to stop at 23:59:59, so the day came out 1439 minutes
     * long. The gap assertion is 0 under either boundary, because a sweep that reaches the end of the day
     * has no hole left at its foot.
     */
    @Test
    fun past_day_tracked_end_to_end_is_a_full_day_with_no_trailing_gap() {
        logger.d("TimeVisualsDayBoundaryTest", "Running full-day sweep case")
        val day = LocalDate.of(2026, 2, 15)
        val entries = listOf(entry("e1", day.atStartOfDay(), day.plusDays(1).atStartOfDay(), 1.0))

        val summary = TimeVisualsCalculator.computeDayOverall(day, entries, now = day.plusDays(1).atTime(9, 0))

        assertEquals(24 * 60L, summary.trackedMinutes)
        assertEquals(0, summary.gapCount)
    }

    /**
     * A day that has not arrived yet is credited nothing, however busy its entries look.
     *
     * A future date used to fall through to the successor-start rule, so a 09:00-10:30 entry on it was
     * measured against a window that began before the day and ended after it - the day reported 90 tracked
     * minutes on a date the clock has not reached. The assertion is 0, and it is 0 for the reason the
     * branch exists: a future day owns no time at all, so every interval on it clips to nothing.
     *
     * The red this case is for: delete the `selectedDate.isAfter(now.toLocalDate())` branch from
     * `effectiveDayEnd` and this reads 90 - which is the defect the item describes, and the only assertion
     * in this file that looks forward rather than back.
     */
    @Test
    fun future_day_is_credited_no_tracked_minutes() {
        logger.d("TimeVisualsDayBoundaryTest", "Running future-day-is-empty case")
        val day = LocalDate.of(2026, 2, 16)
        val entries = listOf(entry("e1", day.atTime(9, 0), day.atTime(10, 30), 1.0))

        val summary = TimeVisualsCalculator.computeDayOverall(day, entries, now = day.minusDays(1).atTime(9, 0))

        assertEquals(0L, summary.trackedMinutes)
    }

    private fun entry(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime?,
        focus: Double,
    ): TimeEntry =
        TimeEntry(
            id = id,
            lifeIntentionCategory = "Learning & Growth",
            taskId = null,
            startedAt = start,
            endedAt = end,
            focusRating = focus,
            focusNote = null,
            focusRatedAt = null,
            createdAt = start,
            updatedAt = start,
            dimensionId = "dim_learning_growth",
        )
}