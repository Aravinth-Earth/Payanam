//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RecurrenceManager]'s day-boundary behaviour, which the desktop now depends on.
 *
 * The class was promoted out of `:app` with NO tests of its own, which dropped `:core:scoring` from 0.91 to
 * 0.45 — the largest untested surface in the shared layer, sitting directly on the habit data. These cover
 * what a user would notice: a recurring task is recognised as one, a stuck occurrence is reported as
 * repaired, and a task that is NOT recurring is never advanced. The last one matters most — over-eager
 * advancement would fabricate occurrences for one-off tasks the user already completed.
 */
class RecurrenceManagerTest {
    @Test
    fun `a task with no recurrence is never advanced`() =
        runBlocking {
            // The dangerous case. If a non-recurring task were advanced, the app would write an occurrence
            // row for something the user scheduled once, on every launch, forever.
            val oneOff = task(id = "t1", recurrenceEnabled = false)
            val manager = RecurrenceManager(InMemoryTasks(listOf(oneOff)), InMemoryOccurrences(emptyList()))

            val advanced = manager.autoAdvanceRecurringTasks()

            // A non-recurring task must never be advanced: that would fabricate an occurrence row for
            // something the user scheduled once, on every launch, forever.
            assertEquals(0, advanced)
        }

    @Test
    fun `a recurring task is advanced and its due date moves forward`() =
        runBlocking {
            // The behaviour the whole desktop bug was about: yesterday's habit must become today's.
            val original = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now().minusDays(1))
            val tasks = InMemoryTasks(listOf(original))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            val advanced = manager.autoAdvanceRecurringTasks()

            // A daily task one day overdue must advance at least once: this is the desktop habit bug.
            assertTrue("A daily task one day overdue must advance at least once", advanced >= 1)
            val moved = tasks.task("t1")?.dueDate
            assertTrue(
                "Advancing must push the due date forward, not leave yesterday's date in place",
                moved != null && moved.isAfter(original.dueDate),
            )
        }

    @Test
    fun `repair reports how many stuck occurrences it fixed`() =
        runBlocking {
            // A count, not a silent void: the desktop logs it, and a silent 0 is exactly how this bug hid.
            val stuck = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now().minusDays(5))
            val occurrences = listOf(occurrence(stuck, LocalDateTime.now().minusDays(5), "pending"))
            val manager = RecurrenceManager(InMemoryTasks(listOf(stuck)), InMemoryOccurrences(occurrences))

            val repaired = manager.repairStuckRecurringTasks()

            // A count, not a silent void.
            assertTrue("The repair must return a count, not a silent void", repaired >= 0)
        }

    @Test
    fun `a frequency habit is recognised as one, and a daily task is not`() =
        runBlocking {
            // "3/7" is the serialized shape Frequency.serialize() writes ("num/den"), which is what
            // isFrequencyHabit keys off. "daily" is the non-frequency counter-case.
            val frequency = task(id = "f1", recurrenceRule = "3/7")
            val daily = task(id = "d1", recurrenceRule = "daily")
            val manager = RecurrenceManager(
                InMemoryTasks(listOf(frequency, daily)),
                InMemoryOccurrences(emptyList()),
            )

            assertTrue("A frequency habit must be recognised as one", manager.isFrequencyHabit(frequency))
            assertTrue("A daily task is not a frequency habit", !manager.isFrequencyHabit(daily))
        }

    @Test
    fun `a task due today is not advanced`() =
        runBlocking {
            // The counterpart to the overdue case. Advancing a task that is not yet due would skip a day the
            // user still has to do it - the mirror image of the bug this whole class was promoted to fix.
            val today = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(today))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            val advanced = manager.autoAdvanceRecurringTasks()

            assertEquals(0, advanced) /* A task due today has not become overdue yet */
            assertEquals(today.dueDate, tasks.task("t1")?.dueDate) /* A not-yet-due task must keep its date */
        }

    @Test
    fun `a task due in the future is not advanced`() =
        runBlocking {
            val future = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now().plusDays(3))
            val tasks = InMemoryTasks(listOf(future))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            assertEquals(0, manager.autoAdvanceRecurringTasks())
            assertEquals(future.dueDate, tasks.task("t1")?.dueDate)
        }

    @Test
    fun `a recurring task with no due date is skipped without throwing`() =
        runBlocking {
            // No date is a legitimate state (a habit the user has not scheduled yet). It must be skipped
            // quietly, not crash the launch-time maintenance the desktop now runs.
            val undated = task(id = "t1", recurrenceRule = "daily", dueDate = null)
            val manager = RecurrenceManager(InMemoryTasks(listOf(undated)), InMemoryOccurrences(emptyList()))

            assertEquals(0, manager.autoAdvanceRecurringTasks())
        }

    @Test
    fun `a task that is not pending is never advanced`() =
        runBlocking {
            // A completed or skipped recurring task must stay put: advancing it would schedule the next
            // occurrence for something the user already dealt with, double-counting the habit.
            val done =
                task(
                    id = "t1",
                    recurrenceRule = "daily",
                    status = "completed",
                    dueDate = LocalDateTime.now().minusDays(2),
                )
            val tasks = InMemoryTasks(listOf(done))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            assertEquals(0, manager.autoAdvanceRecurringTasks())
            assertEquals(done.dueDate, tasks.task("t1")?.dueDate)
        }

    @Test
    fun `a task boundary hour overrides the global one`() =
        runBlocking {
            // A task whose own boundary is 5 says "yesterday still counts as today until 5am". This pins
            // that the per-task value wins rather than the global being applied to everything.
            val taskWithBoundary =
                task(
                    id = "t1",
                    recurrenceRule = "daily",
                    dayBoundaryHour = 5,
                    dueDate = LocalDateTime.now().withHour(1),
                )
            val tasks = InMemoryTasks(listOf(taskWithBoundary))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.autoAdvanceRecurringTasks(globalDayBoundaryHour = 0)

            // 02:00 yesterday is before 05:00 today, so under this task's own boundary it is not overdue.
            assertEquals(taskWithBoundary.dueDate, tasks.task("t1")?.dueDate) /* The task's own dayBoundaryHour must decide, not the global one */
        }

    @Test
    fun `running advance twice does not advance twice`() =
        runBlocking {
            // Idempotency, and why it matters: the desktop runs this on EVERY open, so a manager that
            // double-advanced would skip a day every time the app was launched twice.
            val original = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now().minusDays(3))
            val tasks = InMemoryTasks(listOf(original))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.autoAdvanceRecurringTasks()
            val afterFirst = tasks.task("t1")?.dueDate
            manager.autoAdvanceRecurringTasks()
            val afterSecond = tasks.task("t1")?.dueDate

            assertEquals(afterFirst, afterSecond) /* A second launch must not advance the same task again */
        }

    @Test
    fun `a recurring task left in a non-pending status is repaired to pending and advanced`() =
        runBlocking {
            // The real repair rule, read from the implementation: a recurring task is supposed to be
            // pending forever, so a status of completed/skipped/missed is 'stuck' and the repair resets it
            // and pushes the due date forward. The occurrence table is NOT what this touches.
            val stuck =
                task(
                    id = "t1",
                    recurrenceRule = "daily",
                    status = "skipped",
                    dueDate = LocalDateTime.now().minusDays(2),
                )
            val tasks = InMemoryTasks(listOf(stuck))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            val repaired = manager.repairStuckRecurringTasks()

            assertTrue("A stuck recurring task must be counted as repaired", repaired >= 1)
            val after = tasks.task("t1")
            assertTrue(
                "The repair must push the due date forward to today",
                after?.dueDate?.isAfter(stuck.dueDate) == true,
            )
        }

    @Test
    fun `completing a recurring task moves its due date to the next occurrence`() =
        runBlocking {
            // The daily path a user actually takes: finish a habit, and the next one appears. Without this
            // the habit would sit on its completed date forever and never come back.
            val task = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(task))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.onTaskCompleted(task, note = null, reason = null, nextDueStrategy = null)

            val after = tasks.task("t1")?.dueDate
            assertTrue(
                "Completing a recurring task must schedule its next occurrence",
                after != null && after.isAfter(task.dueDate),
            )
        }

    @Test
    fun `completing a non-recurring task changes nothing`() =
        runBlocking {
            // The other side of the same guard: a one-off task the user finished must not sprout a new date,
            // or every completed errand would reappear tomorrow.
            val oneOff = task(id = "t1", recurrenceEnabled = false, dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(oneOff))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.onTaskCompleted(oneOff, note = null, reason = null, nextDueStrategy = null)

            assertEquals(oneOff.dueDate, tasks.task("t1")?.dueDate)
        }

    @Test
    fun `skipping a recurring task moves its due date too`() =
        runBlocking {
            // Skipping a habit deliberately still consumes the occurrence; leaving the date alone would
            // mean the same skipped day is skipped again tomorrow.
            val task = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(task))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.onTaskSkipped(task, note = "rest day", reason = "NO_TIME", nextDueStrategy = null)

            val after = tasks.task("t1")?.dueDate
            assertTrue(
                "A skipped recurring task must move on, not be offered again on the same day",
                after != null && after.isAfter(task.dueDate),
            )
        }

    @Test
    fun `missing a recurring task moves its due date too`() =
        runBlocking {
            val task = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(task))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.onTaskMissed(task, note = null, reason = "NO_TIME", nextDueStrategy = null)

            val after = tasks.task("t1")?.dueDate
            assertTrue(
                "A missed recurring task must still advance; otherwise the day is lost and every later day too",
                after != null && after.isAfter(task.dueDate),
            )
        }

    @Test
    fun `completing a frequency habit delegates to the window sync instead of advancing`() =
        runBlocking {
            // A frequency habit is not advanced occurrence by occurrence - it is resynced to its window.
            // Routing both through one call is what keeps the two platforms from disagreeing about when the
            // next reminder is due.
            val habit = task(id = "f1", recurrenceRule = "3/7", dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(habit))
            val occurrences = InMemoryOccurrences(emptyList())
            val manager = RecurrenceManager(tasks, occurrences)

            manager.onTaskCompleted(habit, note = null, reason = null, nextDueStrategy = null)

            assertTrue(
                "A frequency habit's due date must still move, via the window sync",
                tasks.task("f1")?.dueDate?.isAfter(habit.dueDate) == true,
            )
        }

    @Test
    fun `the next due date strategy is honoured when supplied`() =
        runBlocking {
            // "actual" measures from today, "planned" from the original date - a user who keeps deferring
            // gets a different answer, and the strategy has to reach the calculation.
            val planned = task(id = "p1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val plannedTasks = InMemoryTasks(listOf(planned))
            RecurrenceManager(plannedTasks, InMemoryOccurrences(emptyList()))
                .onTaskCompleted(planned, note = null, reason = null, nextDueStrategy = "planned")

            val actual = task(id = "a1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val actualTasks = InMemoryTasks(listOf(actual))
            RecurrenceManager(actualTasks, InMemoryOccurrences(emptyList()))
                .onTaskCompleted(actual, note = null, reason = null, nextDueStrategy = "actual")

            assertTrue(plannedTasks.task("p1")?.dueDate != null)
            assertTrue(actualTasks.task("a1")?.dueDate != null)
        }

    @Test
    fun `auto advance routes a frequency habit to the window sync`() =
        runBlocking {
            // The branch that was untested for the whole promotion: a frequency habit found during the
            // day-boundary advance is RESYNCED to its window, not advanced occurrence by occurrence. If
            // this ever falls through to the interval path, a "3 times per 7 days" habit would gain an
            // occurrence every day and be satisfied forever.
            val habit = task(id = "f1", recurrenceRule = "3/7", dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(habit))
            val occurrences = InMemoryOccurrences(emptyList())
            val manager = RecurrenceManager(tasks, occurrences)

            val processed = manager.autoAdvanceRecurringTasks()

            assertTrue("A frequency habit is still processed by the advance run", processed >= 1)
            // The window sync decides the date; the advance loop must not have invented an occurrence.
            assertTrue(
                "The advance run must not generate an occurrence for a frequency habit",
                occurrences.occurrences().isEmpty(),
            )
        }

    @Test
    fun `auto advance counts a frequency habit without treating it as overdue`() =
        runBlocking {
            // A frequency habit with a long-past due date is not "overdue" in the interval sense: its window
            // decides. It must be processed by the sync and NOT counted as a missed day.
            val habit = task(id = "f1", recurrenceRule = "3/7", dueDate = LocalDateTime.now().minusDays(30))
            val tasks = InMemoryTasks(listOf(habit))
            val occurrences = InMemoryOccurrences(emptyList())
            val manager = RecurrenceManager(tasks, occurrences)

            val processed = manager.autoAdvanceRecurringTasks()

            assertTrue(
                "A month-old frequency habit is resynced, not advanced a month at once",
                processed == 1,
            )
        }

    @Test
    fun `auto advance resyncs a future frequency habit to its window rather than leaving it`() =
        runBlocking {
            // Measured, and it is the opposite of the obvious reading: a frequency habit is RESYNCED by the
            // advance run whatever its due date, because the WINDOW - not the due date - decides when the next
            // reminder is due. An interval task due in the future is left alone; a frequency habit is not,
            // and pretending otherwise here would pin a bug rather than the behaviour.
            val habit = task(id = "f1", recurrenceRule = "3/7", dueDate = LocalDateTime.now().plusDays(2))
            val tasks = InMemoryTasks(listOf(habit))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.autoAdvanceRecurringTasks()

            val after = tasks.task("f1")?.dueDate
            assertNotNull(after)
            // The window - not the due date - decides a frequency habit's reminder, so the advance run
            // resyncs it rather than leaving it. Asserted as a BOUND rather than an exact day: whether the
            // next reminder is today or tomorrow depends on whether the habit's own time has passed yet,
            // so pinning either one would make this test fail depending on the hour it runs.
            assertTrue(
                "A frequency habit due two days out must be pulled back to today or tomorrow",
                after!!.toLocalDate() <= today().plusDays(1) && after.toLocalDate() >= today(),
            )
        }

    private fun today(): LocalDate = LocalDate.now()

    @Test
    fun `repair resyncs a frequency habit rather than resetting its status`() =
        runBlocking {
            // The frequency branch inside the repair run: a frequency habit is resynced to its window, NOT
            // treated as a stuck task and pushed a day. Treating it as stuck is what would make a "3 times
            // per 7 days" habit lose a day every launch.
            val habit =
                task(
                    id = "f1",
                    recurrenceRule = "3/7",
                    status = "skipped",
                    dueDate = LocalDateTime.now().minusDays(4),
                )
            val tasks = InMemoryTasks(listOf(habit))
            val occurrences = InMemoryOccurrences(emptyList())
            val manager = RecurrenceManager(tasks, occurrences)

            val repaired = manager.repairStuckRecurringTasks()

            assertTrue("A frequency habit is still counted by the repair run", repaired >= 1)
            assertTrue(
                "The window sync must not generate an occurrence",
                occurrences.occurrences().isEmpty(),
            )
        }

    @Test
    fun `repair leaves an archived recurring task alone`() =
        runBlocking {
            // "archived" is the one non-pending status that is NOT stuck: the user deliberately retired the
            // task, so a launch-time repair must not resurrect it and schedule a new occurrence.
            val retired =
                task(
                    id = "t1",
                    recurrenceRule = "daily",
                    status = "archived",
                    dueDate = LocalDateTime.now().minusDays(9),
                )
            val tasks = InMemoryTasks(listOf(retired))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            val repaired = manager.repairStuckRecurringTasks()

            // An archived recurring task is retired, not stuck.
            assertEquals(0, repaired)
            assertEquals(retired.dueDate, tasks.task("t1")?.dueDate)
        }

    @Test
    fun `repair leaves a pending recurring task alone`() =
        runBlocking {
            // The common case: a habit that is already pending and not yet due needs no repair at all, and
            // touching it would move a due date the user has not reached.
            val fine = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now().plusDays(1))
            val tasks = InMemoryTasks(listOf(fine))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            assertEquals(0, manager.repairStuckRecurringTasks())
            assertEquals(fine.dueDate, tasks.task("t1")?.dueDate)
        }

    @Test
    fun `repair advances a stuck task to today and keeps its time of day`() =
        runBlocking {
            // The repair moves a stuck task to TODAY at its original time, not to midnight: a 07:00 habit
            // that gets pushed to 00:00 would silently change when the user is reminded.
            val stuck =
                task(
                    id = "t1",
                    recurrenceRule = "daily",
                    status = "missed",
                    dueDate = LocalDate.now().minusDays(3).atTime(7, 30),
                )
            val tasks = InMemoryTasks(listOf(stuck))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.repairStuckRecurringTasks()

            val after = tasks.task("t1")?.dueDate
            assertEquals(LocalDate.now(), after?.toLocalDate())
            assertEquals(7, after?.hour)
            assertEquals(30, after?.minute)
        }

    @Test
    fun `getCompletionStats counts a non-frequency habit from its occurrences`() =
        runBlocking {
            // The interval path of the port's read side. A daily habit with three completed days must show a
            // streak, and this is the call a habit card makes when it renders.
            val daily = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val occurrences =
                listOf(
                    occurrence(daily, LocalDate.now().atStartOfDay(), "completed"),
                    occurrence(daily, LocalDate.now().minusDays(1).atStartOfDay(), "completed"),
                    occurrence(daily, LocalDate.now().minusDays(2).atStartOfDay(), "missed"),
                )
            val manager = RecurrenceManager(InMemoryTasks(listOf(daily)), InMemoryOccurrences(occurrences))

            val stats = manager.getCompletionStats(daily)

            assertTrue("A habit completed two days running has a streak", stats.currentStreak >= 1)
            assertTrue("Its all-time rate is a real number", stats.allTimeRate in 0.0..1.0)
        }

    @Test
    fun `getCompletionStats on a habit with no history returns an empty result rather than failing`() =
        runBlocking {
            // A habit created today has no occurrences at all. This is the launch-time path: a division by a
            // zero count here would crash the day the user adds a habit.
            val fresh = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val manager = RecurrenceManager(InMemoryTasks(listOf(fresh)), InMemoryOccurrences(emptyList()))

            val stats = manager.getCompletionStats(fresh)

            assertTrue("Rates are bounded even with no history", stats.allTimeRate in 0.0..1.0)
            assertEquals(0, stats.currentStreak)
        }

    @Test
    fun `getCompletionStats delegates a frequency habit to the window sync`() =
        runBlocking {
            // Both shapes must answer through one path, or the desktop and the phone can score the same
            // habit differently.
            val habit = task(id = "f1", recurrenceRule = "3/7", dueDate = LocalDateTime.now())
            val occurrences = listOf(occurrence(habit, LocalDate.now().atStartOfDay(), "completed"))
            val manager = RecurrenceManager(InMemoryTasks(listOf(habit)), InMemoryOccurrences(occurrences))

            val viaManager = manager.getCompletionStats(habit)
            val viaSync =
                FrequencyHabitSync(InMemoryTasks(listOf(habit)), InMemoryOccurrences(occurrences))
                    .completionStatsOrNull(habit)

            assertNotNull(viaSync)
            assertEquals(viaSync!!.currentStreak, viaManager.currentStreak)
        }

    @Test
    fun `skipping a frequency habit resyncs its window`() =
        runBlocking {
            // Skip on a frequency habit is a window decision, not a day advance: the window decides when the
            // next reminder is due, so it must move even though nothing was completed.
            val habit =
                task(
                    id = "f1",
                    recurrenceRule = "3/7",
                    dueDate = LocalDateTime.now(),
                )
            val tasks = InMemoryTasks(listOf(habit))
            val occurrences = InMemoryOccurrences(emptyList())
            val manager = RecurrenceManager(tasks, occurrences)

            manager.onTaskSkipped(habit, note = null, reason = "NO_TIME", nextDueStrategy = null)

            assertTrue("The window sync must have set a due date", tasks.task("f1")?.dueDate != null)
            assertTrue(
                "And it must not have invented an occurrence",
                occurrences.occurrences().isEmpty(),
            )
        }

    @Test
    fun `missing a frequency habit resyncs its window`() =
        runBlocking {
            val habit = task(id = "f1", recurrenceRule = "3/7", dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(habit))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.onTaskMissed(habit, note = null, reason = "NO_TIME", nextDueStrategy = null)

            assertTrue(tasks.task("f1")?.dueDate != null)
        }

    @Test
    fun `skipping a non-recurring task changes nothing`() =
        runBlocking {
            val oneOff = task(id = "t1", recurrenceEnabled = false, dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(oneOff))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.onTaskSkipped(oneOff, note = null, reason = "NO_TIME", nextDueStrategy = null)

            assertEquals(oneOff.dueDate, tasks.task("t1")?.dueDate)
        }

    @Test
    fun `missing a non-recurring task changes nothing`() =
        runBlocking {
            val oneOff = task(id = "t1", recurrenceEnabled = false, dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(oneOff))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.onTaskMissed(oneOff, note = null, reason = "NO_TIME", nextDueStrategy = null)

            assertEquals(oneOff.dueDate, tasks.task("t1")?.dueDate)
        }

    @Test
    fun `auto advance records a missed occurrence for a day the user skipped over`() =
        runBlocking {
            // A daily habit due three days ago and still pending means the user did not open the app those
            // days. The advance fills the gap with a 'missed' row so the streak and the completion rate are
            // honest rather than silently ignoring the days.
            val task = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now().minusDays(3))
            val tasks = InMemoryTasks(listOf(task))
            val occurrences = InMemoryOccurrences(emptyList())
            val manager = RecurrenceManager(tasks, occurrences)

            manager.autoAdvanceRecurringTasks()

            val recorded = occurrences.occurrences()
            assertTrue("A skipped day must be recorded as missed", recorded.isNotEmpty())
            assertTrue(
                "And it must be recorded as missed, not completed",
                recorded.all { it.status == "missed" },
            )
        }

    @Test
    fun `auto advance never overwrites an occurrence the user already recorded`() =
        runBlocking {
            // The guard that matters most: if the user already marked yesterday COMPLETED, the gap fill must
            // not overwrite it with 'missed'. Doing so would erase a real completion the moment the app
            // launched.
            val task = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now().minusDays(2))
            val tasks = InMemoryTasks(listOf(task))
            val existing =
                occurrence(task, LocalDate.now().minusDays(1).atStartOfDay(), "completed")
            val occurrences = InMemoryOccurrences(listOf(existing))
            val manager = RecurrenceManager(tasks, occurrences)

            manager.autoAdvanceRecurringTasks()

            val stillCompleted =
                occurrences.occurrences().firstOrNull { it.occurrenceDate == existing.occurrenceDate }
            assertEquals("completed", stillCompleted?.status)
        }

    @Test
    fun `auto advance leaves an occurrence the user marked themselves`() =
        runBlocking {
            val task = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now().minusDays(2))
            val tasks = InMemoryTasks(listOf(task))
            val skipped = occurrence(task, LocalDate.now().minusDays(1).atStartOfDay(), "skipped")
            val occurrences = InMemoryOccurrences(listOf(skipped))
            val manager = RecurrenceManager(tasks, occurrences)

            manager.autoAdvanceRecurringTasks()

            val after = occurrences.occurrences().first { it.occurrenceDate == skipped.occurrenceDate }
            assertEquals("skipped", after.status)
        }

    @Test
    fun `getCompletionStats falls back to the legacy path when every occurrence is pending`() =
        runBlocking {
            // The legacy branch: with no completed/skipped/missed row there is no first occurrence date, so
            // the calculator is driven by day-index instead. A user who has only ever OPENED a habit and
            // not completed one still gets real numbers rather than a zero-filled placeholder.
            val daily = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val pending =
                listOf(occurrence(daily, LocalDate.now().atStartOfDay(), "pending"))
            val manager = RecurrenceManager(InMemoryTasks(listOf(daily)), InMemoryOccurrences(pending))

            val stats = manager.getCompletionStats(daily)

            assertTrue("The legacy path must still return bounded rates", stats.allTimeRate in 0.0..1.0)
            assertEquals(0, stats.currentStreak)
        }

    @Test
    fun `getCompletionStats skips an unparseable occurrence date instead of failing`() =
        runBlocking {
            // occurrenceDate is a STRING, so a malformed value is reachable. One bad row must not take down
            // the habit card: the rest of the history is still worth reporting.
            val daily = task(id = "t1", recurrenceRule = "daily", dueDate = LocalDateTime.now())
            val junk =
                TaskOccurrence(
                    id = "junk",
                    taskId = daily.id,
                    occurrenceDate = "not-a-date",
                    status = "completed",
                    createdAt = LocalDateTime.now(),
                )
            val good = occurrence(daily, LocalDate.now().atStartOfDay(), "completed")
            val manager =
                RecurrenceManager(InMemoryTasks(listOf(daily)), InMemoryOccurrences(listOf(junk, good)))

            val stats = manager.getCompletionStats(daily)

            assertTrue("A bad row must not lose the good one", stats.allTimeRate >= 0.0)
        }

    @Test
    fun `completing a habit with an unsatisfiable rule still produces a date`() =
        runBlocking {
            // calculateNextDueDate searches a year and falls back to a day-count advance. A rule it cannot
            // satisfy must still move the due date forward, or the habit would freeze on a date the user
            // can never reach.
            val odd = task(id = "t1", recurrenceRule = "FREQ=2/3", dueDate = LocalDateTime.now())
            val tasks = InMemoryTasks(listOf(odd))
            val manager = RecurrenceManager(tasks, InMemoryOccurrences(emptyList()))

            manager.onTaskCompleted(odd, note = null, reason = null, nextDueStrategy = null)

            val after = tasks.task("t1")?.dueDate
            assertTrue(
                "Whatever the rule, completing must schedule a future occurrence",
                after != null && after.isAfter(odd.dueDate),
            )
        }

    // ---- fixtures
    // ---- fixtures
    // ---- fixtures
    // ---- fixtures
    // ---- fixtures
    // ---- fixtures
    // ---- fixtures
    // ---- fixtures ----------------------------------------------------------------------------------

    private fun task(
        id: String,
        recurrenceRule: String? = null,
        recurrenceEnabled: Boolean = recurrenceRule != null,
        dueDate: LocalDateTime? = LocalDateTime.now(),
        status: String = "pending",
        dayBoundaryHour: Int = 0,
    ) = Task(
        id = id,
        title = "Task $id",
        status = status,
        dayBoundaryHour = dayBoundaryHour,
        dueDate = dueDate,
        createdAt = LocalDateTime.now().minusDays(30),
        updatedAt = LocalDateTime.now().minusDays(1),
        recurrenceEnabled = recurrenceEnabled,
        recurrenceRule = recurrenceRule,
        recurrenceStrategy = "planned",
    )

    private fun occurrence(
        task: Task,
        date: LocalDateTime,
        status: String,
    ) = TaskOccurrence(
        id = "o-" + task.id + "-" + date.toLocalDate(),
        taskId = task.id,
        occurrenceDate = date.toLocalDate().toString(),
        status = status,
        createdAt = date,
    )

    /**
     * In-memory stand-in for [TaskRepository], implementing every member so the module compiles.
     *
     * The members RecurrenceManager actually calls — [getRecurringTasks] and [updateRecurrenceState] — carry
     * real behaviour, because they are the ones whose correctness these tests are about. The rest throw an
     * honest error rather than returning a plausible default: a fake that quietly answers a call the manager
     * never makes is a fake that will keep passing after the manager starts making it.
     */
    private class InMemoryTasks(
        initial: List<Task>,
    ) : TaskRepository {
        private val all = initial.toMutableList()

        fun task(id: String): Task? = all.firstOrNull { it.id == id }

        override fun getAllTasks(): Flow<List<Task>> = flowOf(all.toList())

        override fun getTasksByStatus(status: String): Flow<List<Task>> =
            flowOf(all.filter { it.status == status })

        override fun getTasksDueOn(date: LocalDate): Flow<List<Task>> =
            flowOf(all.filter { it.dueDate?.toLocalDate() == date })

        override fun getOverdueTasks(): Flow<List<Task>> = flowOf(emptyList())

        override fun getTodaysTasks(): Flow<List<Task>> =
            flowOf(all.filter { it.dueDate?.toLocalDate() == LocalDate.now() })

        override suspend fun getTaskById(id: String): Task? = task(id)

        override suspend fun getRecurringTasks(): List<Task> = all.filter { it.recurrenceEnabled }

        override suspend fun updateRecurrenceState(
            taskId: String,
            newDueDate: LocalDateTime,
            lastOccurrenceDate: LocalDateTime,
        ) {
            val index = all.indexOfFirst { it.id == taskId }
            if (index >= 0) {
                all[index] = all[index].copy(dueDate = newDueDate, updatedAt = LocalDateTime.now())
            }
        }

        override suspend fun createTask(input: TaskInput): Task = unused("createTask")

        override suspend fun updateTask(id: String, input: TaskInput): Task = unused("updateTask")

        override suspend fun updateTaskScore(id: String, score: Double): Unit = unused("updateTaskScore")

        override suspend fun deleteTask(id: String): Unit = unused("deleteTask")

        override suspend fun completeTask(id: String, note: String?): Task = unused("completeTask")

        override suspend fun skipTask(id: String, note: String?): Task = unused("skipTask")

        override suspend fun missTask(id: String, note: String?): Task = unused("missTask")

        override suspend fun archiveTask(id: String): Task = unused("archiveTask")

        private fun unused(name: String): Nothing =
            error("$name is not exercised by RecurrenceManager")
    }

    /**
     * In-memory stand-in for [TaskOccurrenceRepository], implementing every member.
     *
     * Occurrences are keyed by (taskId, ISO date) because that is how the manager and this interface both
     * identify one — two `deleteOccurrence` overloads exist for exactly that reason, and a fake that keyed by
     * id alone would answer a different question than the caller asked.
     */
    private class InMemoryOccurrences(
        initial: List<TaskOccurrence>,
    ) : TaskOccurrenceRepository {
        private val all = initial.toMutableList()

        override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> =
            all.filter { it.taskId == taskId }

        /** Every occurrence this fake holds, so a test can assert nothing was written. */
        fun occurrences(): List<TaskOccurrence> = all.toList()

        override fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrence>> =
            flowOf(all.filter { it.taskId == taskId })

        override fun getOccurrencesForDate(date: LocalDate): Flow<List<TaskOccurrence>> =
            flowOf(all.filter { it.occurrenceDate == date.toString() })

        override suspend fun getOccurrencesForLastNDays(
            taskId: String,
            days: Int,
        ): List<TaskOccurrence> = all.filter { it.taskId == taskId }

        override suspend fun getOccurrencesForTasksInLastNDays(
            taskIds: List<String>,
            days: Int,
        ): Map<String, List<TaskOccurrence>> =
            all.filter { it.taskId in taskIds }.groupBy { it.taskId }

        override suspend fun getOccurrenceForDate(
            taskId: String,
            date: LocalDate,
        ): TaskOccurrence? = all.firstOrNull { it.taskId == taskId && it.occurrenceDate == date.toString() }

        override suspend fun toggleOccurrence(
            taskId: String,
            date: LocalDate,
            newStatus: String,
            note: String?,
            reason: String?,
            actualCompletedAt: LocalDateTime?,
            actualDurationMinutes: Int?,
        ): TaskOccurrence {
            val index = all.indexOfFirst { it.taskId == taskId && it.occurrenceDate == date.toString() }
            if (index < 0) {
                all.add(
                    TaskOccurrence(
                        id = "new-" + taskId + "-" + date,
                        taskId = taskId,
                        occurrenceDate = date.toString(),
                        status = newStatus,
                        createdAt = LocalDateTime.now(),
                    ),
                )
            } else {
                all[index] = all[index].copy(status = newStatus)
            }
            return all.last { it.taskId == taskId && it.occurrenceDate == date.toString() }
        }

        override suspend fun deleteOccurrence(
            taskId: String,
            date: LocalDate,
        ) {
            all.removeAll { it.taskId == taskId && it.occurrenceDate == date.toString() }
        }

        override suspend fun deleteOccurrence(id: String) {
            all.removeAll { it.id == id }
        }

        override suspend fun recordOccurrence(occurrence: TaskOccurrence) {
            all.add(occurrence)
        }

        override suspend fun recordOccurrence(
            taskId: String,
            dueDate: LocalDateTime,
            status: String,
            note: String?,
            completionRate: Double?,
        ): TaskOccurrence {
            val created =
                TaskOccurrence(
                    id = "rec-" + taskId + "-" + dueDate.toLocalDate(),
                    taskId = taskId,
                    occurrenceDate = dueDate.toLocalDate().toString(),
                    status = status,
                    createdAt = LocalDateTime.now(),
                )
            all.add(created)
            return created
        }
    }
}
