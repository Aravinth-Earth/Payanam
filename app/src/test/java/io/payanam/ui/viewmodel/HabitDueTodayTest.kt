//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Frequency
import io.payanam.domain.model.RecurrenceConfig
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.ui.components.CheckmarkStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * HabitDueTodayTest.
 */
class HabitDueTodayTest {

    @Before
    /**
     * Setup.
     */
    fun setup() {
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                "test",
                0,
            )
        }
    }

    private val baseTime = LocalDateTime.of(2026, 8, 3, 9, 0)

    private fun habit(id: String, rule: String?, createdAt: LocalDateTime = baseTime): Task = Task(
        id = id,
        title = id,
        recurrenceEnabled = true,
        recurrenceRule = rule,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun occurrence(date: LocalDate, status: String = "completed"): TaskOccurrence =
        /** Task occurrence. */
        TaskOccurrence(
            id = "occ-$date",
            taskId = "t",
            occurrenceDate = date.toString(),
            status = status,
        )

    // ── Serialized Frequency rules (the format the UI picker writes) ──────

    @Test
    /**
     * Serialized daily is due until completed today.
     */
    fun serialized_daily_is_due_until_completed_today() = runBlocking {
        /** Task. */
        val task = habit("d", Frequency(numerator = 1, denominator = 1).serialize())
        /** Assert true. */
        assertTrue(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 16)))
        /** Assert false. */
        assertFalse(
            /** Compute due today for task. */
            computeDueTodayForTask(
                /** Task. */
                task,
                /** List of. */
                listOf(occurrence(LocalDate.of(2026, 8, 16))),
                LocalDate.of(2026, 8, 16),
            ),
        )
    }

    @Test
    /**
     * Serialized weekly due when quota unmet.
     */
    fun serialized_weekly_due_when_quota_unmet() = runBlocking {
        /** Task. */
        val task = habit("w", "3/7") // anchor falls back to createdAt = 2026-08-03
        /** Window. */
        val window = LocalDate.of(2026, 8, 16)
        /** One done. */
        val oneDone = listOf(occurrence(LocalDate.of(2026, 8, 12)))
        /** Assert true. */
        assertTrue(computeDueTodayForTask(task, oneDone, window))
    }

    @Test
    /**
     * Serialized weekly not due when quota met.
     */
    fun serialized_weekly_not_due_when_quota_met() = runBlocking {
        /** Task. */
        val task = habit("w", "3/7")
        /** Three done. */
        val threeDone = listOf(
            /** Occurrence. */
            occurrence(LocalDate.of(2026, 8, 10)),
            /** Occurrence. */
            occurrence(LocalDate.of(2026, 8, 12)),
            /** Occurrence. */
            occurrence(LocalDate.of(2026, 8, 14)),
        )
        /** Assert false. */
        assertFalse(computeDueTodayForTask(task, threeDone, LocalDate.of(2026, 8, 16)))
    }

    @Test
    /**
     * Serialized weekly skips reduce target.
     */
    fun serialized_weekly_skips_reduce_target() = runBlocking {
        /** Task. */
        val task = habit("w", "3/7")
        /** Today. */
        val today = LocalDate.of(2026, 8, 16)
        // 1 completed + 1 skipped -> effective target 2, still under -> due
        /** Assert true. */
        assertTrue(
            /** Compute due today for task. */
            computeDueTodayForTask(
                /** Task. */
                task,
                /** List of. */
                listOf(
                    /** Occurrence. */
                    occurrence(LocalDate.of(2026, 8, 10)),
                    /** Occurrence. */
                    occurrence(LocalDate.of(2026, 8, 11), status = "skipped"),
                ),
                /** Today. */
                today,
            ),
        )
        // 2 completed + 1 skipped -> effective target 2, met -> not due
        /** Assert false. */
        assertFalse(
            /** Compute due today for task. */
            computeDueTodayForTask(
                /** Task. */
                task,
                /** List of. */
                listOf(
                    /** Occurrence. */
                    occurrence(LocalDate.of(2026, 8, 10)),
                    /** Occurrence. */
                    occurrence(LocalDate.of(2026, 8, 12)),
                    /** Occurrence. */
                    occurrence(LocalDate.of(2026, 8, 13), status = "skipped"),
                ),
                /** Today. */
                today,
            ),
        )
    }

    @Test
    /**
     * Serialized frequency window not started not due.
     */
    fun serialized_frequency_window_not_started_not_due() = runBlocking {
        /** Task. */
        val task = habit("f", "1/7!start=2026-09-01")
        /** Assert false. */
        assertFalse(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 16)))
    }

    @Test
    /**
     * Large denominator fetches full history for quota.
     */
    fun large_denominator_fetches_full_history_for_quota() = runBlocking {
        /** Task. */
        val task = habit("f", "1/60!start=2026-07-01")
        /** Today. */
        val today = LocalDate.of(2026, 8, 16)
        // Window 2026-07-01..08-29; completion on 07-10 is inside the window but
        // outside the 30-day lookback, so only full history reveals the quota.
        /** Completion. */
        val completion = occurrence(LocalDate.of(2026, 7, 10))
        /** Assert true. */
        assertTrue(computeDueTodayForTask(task, emptyList(), today)) // bulk only -> due
        /** Assert false. */
        assertFalse(
            /** Compute due today for task. */
            computeDueTodayForTask(
                /** Task. */
                task,
                /** Empty list. */
                emptyList(),
                /** Today. */
                today,
                fetchFullHistory = { listOf(completion) },
            ),
        )
    }

    // ── Typed rules (CONFIG:/RRULE formats) ────────────────────────────────

    @Test
    /**
     * Typed daily always due.
     */
    fun typed_daily_always_due() = runBlocking {
        /** Task. */
        val task = habit("d", RecurrenceConfig.daily().serialize())
        /** Assert true. */
        assertTrue(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 16)))
        /** Assert true. */
        assertTrue(
            /** Compute due today for task. */
            computeDueTodayForTask(
                /** Task. */
                task,
                /** List of. */
                listOf(occurrence(LocalDate.of(2026, 8, 16))),
                LocalDate.of(2026, 8, 16),
            ),
        )
    }

    @Test
    /**
     * Typed weekdays only skips weekend.
     */
    fun typed_weekdays_only_skips_weekend() = runBlocking {
        /** Task. */
        val task = habit("wd", RecurrenceConfig.weekdays().serialize())
        /** Assert false. */
        assertFalse(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 16))) // Sunday
        /** Assert true. */
        assertTrue(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 17))) // Monday
    }

    @Test
    /**
     * Typed specific weekdays checks day set.
     */
    fun typed_specific_weekdays_checks_day_set() = runBlocking {
        /** Task. */
        val task = habit("sw", RecurrenceConfig.specificWeekdays(setOf(1, 3, 5)).serialize())
        /** Assert true. */
        assertTrue(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 17))) // Mon
        /** Assert false. */
        assertFalse(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 18))) // Tue
    }

    @Test
    /**
     * Typed monthly dates checks day of month.
     */
    fun typed_monthly_dates_checks_day_of_month() = runBlocking {
        /** Task. */
        val task = habit("m", RecurrenceConfig.monthlyOnDates(1, 15).serialize())
        /** Assert true. */
        assertTrue(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 15)))
        /** Assert false. */
        assertFalse(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 16)))
    }

    @Test
    /**
     * Typed interval with start uses schedule phase.
     */
    fun typed_interval_with_start_uses_schedule_phase() = runBlocking {
        /** Task. */
        val task = habit("i", RecurrenceConfig.everyNDays(2, LocalDate.of(2026, 8, 1)).serialize())
        /** Assert false. */
        assertFalse(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 16))) // 15 days -> off
        /** Assert true. */
        assertTrue(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 17))) // 16 days -> on
    }

    @Test
    /**
     * Interval null start anchors on first occurrence.
     */
    fun interval_null_start_anchors_on_first_occurrence() = runBlocking {
        /** Today. */
        val today = LocalDate.of(2026, 8, 16)
        /** Even phase. */
        val evenPhase = habit("i1", RecurrenceConfig.everyNDays(2).serialize())
        /** Odd phase. */
        val oddPhase = habit("i2", RecurrenceConfig.everyNDays(2).serialize())
        // Anchors 08-02 (14 days before) vs 08-03 (13 days before) -> opposite phase.
        /** Assert true. */
        assertTrue(computeDueTodayForTask(evenPhase, listOf(occurrence(LocalDate.of(2026, 8, 2))), today))
        /** Assert false. */
        assertFalse(computeDueTodayForTask(oddPhase, listOf(occurrence(LocalDate.of(2026, 8, 3))), today))
    }

    @Test
    /**
     * Interval null start without occurrence stays visible.
     */
    fun interval_null_start_without_occurrence_stays_visible() = runBlocking {
        /** Task. */
        val task = habit("i", RecurrenceConfig.everyNDays(2).serialize())
        /** Assert true. */
        assertTrue(computeDueTodayForTask(task, emptyList(), LocalDate.of(2026, 8, 16)))
    }

    // ── Map builder ────────────────────────────────────────────────────────

    @Test
    /**
     * Build due today map covers every task.
     */
    fun build_due_today_map_covers_every_task() = runBlocking {
        /** Daily. */
        val daily = habit("daily", "1/1")
        /** Weekly. */
        val weekly = habit("weekly", "3/7")
        /** Map. */
        val map = buildDueTodayByTaskId(
            tasks = listOf(daily, weekly),
            occurrencesMap = mapOf("weekly" to listOf(occurrence(LocalDate.of(2026, 8, 10)))),
            today = LocalDate.of(2026, 8, 16),
        )
        /** Assert equals. */
        assertEquals(setOf("daily", "weekly"), map.keys)
        /** Assert true. */
        assertTrue(map.getValue("daily"))
        /** Assert true. */
        assertTrue(map.getValue("weekly"))
    }

    // ── Filter composition ─────────────────────────────────────────────────

    @Test
    /**
     * Visible habits narrow to due today.
     */
    fun visible_habits_narrow_to_due_today() {
        /** Due. */
        val due = habit("due", "1/1")
        /** Not due. */
        val notDue = habit("notDue", "1/7!start=2026-09-01")
        /** Visible. */
        val visible = visibleHabitsForDisplay(
            habits = listOf(due, notDue),
            todayStatusByTaskId = mapOf("due" to CheckmarkStatus.PENDING, "notDue" to CheckmarkStatus.PENDING),
            showCompletedHabits = true,
            dueTodayOnly = true,
            dueTodayByTaskId = mapOf("due" to true, "notDue" to false),
        )
        /** Assert equals. */
        assertEquals(listOf("due"), visible.map { it.id })
    }

    @Test
    /**
     * Visible habits compose due today with hide all marked.
     */
    fun visible_habits_compose_due_today_with_hide_all_marked() {
        /** Due done. */
        val dueDone = habit("dueDone", "1/1")
        /** Due pending. */
        val duePending = habit("duePending", "1/1")
        /** Visible. */
        val visible = visibleHabitsForDisplay(
            habits = listOf(dueDone, duePending),
            todayStatusByTaskId = mapOf(
                "dueDone" to CheckmarkStatus.COMPLETED,
                "duePending" to CheckmarkStatus.PENDING,
            ),
            showCompletedHabits = true,
            hideAllMarkedToday = true,
            dueTodayOnly = true,
            dueTodayByTaskId = mapOf("dueDone" to true, "duePending" to true),
        )
        /** Assert equals. */
        assertEquals(listOf("duePending"), visible.map { it.id })
    }

    @Test
    /**
     * Visible habits keep entry missing from due map.
     */
    fun visible_habits_keep_entry_missing_from_due_map() {
        /** Unknown. */
        val unknown = habit("unknown", "1/1")
        /** Visible. */
        val visible = visibleHabitsForDisplay(
            habits = listOf(unknown),
            todayStatusByTaskId = mapOf("unknown" to CheckmarkStatus.UNKNOWN),
            showCompletedHabits = true,
            dueTodayOnly = true,
            dueTodayByTaskId = emptyMap(),
        )
        /** Assert equals. */
        assertEquals(listOf("unknown"), visible.map { it.id })
    }

    @Test
    /**
     * Habit rows narrow to due today.
     */
    fun habit_rows_narrow_to_due_today() {
        /** Due. */
        val due = habit("due", "1/1")
        /** Not due. */
        val notDue = habit("notDue", "1/7!start=2026-09-01")
        /** Rows. */
        val rows = TasksRowCacheManager.buildHabitRows(
            tasks = listOf(due, notDue),
            checkmarksByTaskId = emptyMap(),
            todayStatusByTaskId = mapOf("due" to CheckmarkStatus.PENDING, "notDue" to CheckmarkStatus.PENDING),
            showCompletedHabits = true,
            dueTodayOnly = true,
            dueTodayByTaskId = mapOf("due" to true, "notDue" to false),
        )
        /** Assert equals. */
        assertEquals(listOf("due"), rows.map { it.id })
    }
}
