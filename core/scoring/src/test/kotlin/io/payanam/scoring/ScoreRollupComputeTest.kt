//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import com.google.common.truth.Truth.assertThat
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import java.time.LocalDate
import org.junit.Test

/**
 * L1 habit metrics and the streak arithmetic every level above it reuses.
 *
 * Two properties are pinned here. First, [ScoreRollupCompute.computeStreaks]: the three streak counters are not
 * symmetric. A negative day resets the positive streak but leaves the positive-continue counter frozen, and zero
 * progress at a running average of 1.0 still advances the positive streak - the ceiling rule, without which a
 * habit held at 100% silently loses its streak on the first flat day. Second, L1 row selection: rows are sparse
 * (due days only), today stays pending unless an occurrence is logged for it, a `skipped` occurrence scores like
 * `completed`, an unparseable due date is dropped rather than crashing the build, and an off-grid completion is
 * not counted at all.
 *
 * The baseline cases are the other half. A tail build seeded from cumulative rows before the change day has to
 * reproduce the full rebuild's arithmetic exactly - a baseline that seeds `sumScores` without `count`, or that
 * drops the net streak, reports a history the user never earned.
 *
 * Every fixture date is `today.minusDays(n)` rather than a fixed date. Every loop here bounds on
 * `LocalDate.now()`, so hard-coded days would take this suite red overnight with no code change - the failure
 * documented in `FrequencyHabitSyncTest`.
 */
class ScoreRollupComputeTest {
    private val today: LocalDate = LocalDate.now()

    private fun day(daysAgo: Int): LocalDate = today.minusDays(daysAgo.toLong())

    private fun task(
        id: String = "h1",
        title: String = "Habit",
        createdAt: String = day(7).toString(),
        updatedAt: String = createdAt,
        recurrenceEnabled: Int = 1,
        recurrenceRule: String? = "1/1",
        dimensionId: String? = null,
    ) = TaskEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        recurrenceEnabled = recurrenceEnabled,
        recurrenceRule = recurrenceRule,
        dimensionId = dimensionId,
    )

    private fun occurrence(
        taskId: String = "h1",
        dueDate: String,
        status: String,
        id: String = "$taskId-$dueDate-$status",
    ) = TaskOccurrenceEntity(
        id = id,
        taskId = taskId,
        dueDate = dueDate,
        status = status,
        createdAt = dueDate,
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

    /** Recurring, rule "1/1" -> converts to `CONFIG:type=DAILY`, so every day is a due day. */
    private fun dailyTask(id: String = "h1") = task(id = id, recurrenceRule = "1/1")

    /** The shared three-day history: T3 and T2 completed, T1 missed. */
    private fun threeDayHistory(taskId: String = "h1") = listOf(
        occurrence(taskId = taskId, dueDate = day(3).toString(), status = "completed"),
        occurrence(taskId = taskId, dueDate = day(2).toString(), status = "completed"),
        occurrence(taskId = taskId, dueDate = day(1).toString(), status = "missed"),
    )

    private fun assertStreaks(
        row: HabitMetricEntity,
        streakPos: Int,
        streakNet: Int,
        posContinue: Int,
    ) {
        assertThat(row.streakPos).isEqualTo(streakPos)
        assertThat(row.streakNet).isEqualTo(streakNet)
        assertThat(row.posContinue).isEqualTo(posContinue)
    }

    // -- computeStreaks -----------------------------------------------------------------------------------

    @Test
    fun `positive progress advances all three streak counters`() {
        val streaks = ScoreRollupCompute.computeStreaks(
            progress = 0.5,
            runningAvg = 0.5,
            streakPos = 0,
            streakNet = 0,
            posContinue = 0,
        )

        assertThat(streaks.toList()).containsExactly(1, 1, 1).inOrder()
    }

    @Test
    fun `negative progress resets the positive streak, decrements net, and freezes the continue counter`() {
        val streaks = ScoreRollupCompute.computeStreaks(
            progress = -0.5,
            runningAvg = 0.5,
            streakPos = 5,
            streakNet = 3,
            posContinue = 4,
        )

        assertThat(streaks[0]).isEqualTo(0)
        assertThat(streaks[1]).isEqualTo(2)
        // The continue counter only ever grows, so a bad day must not shorten the run behind it.
        assertThat(streaks[2]).isEqualTo(4)
    }

    @Test
    fun `zero progress at a perfect running average still advances the positive streak`() {
        val streaks = ScoreRollupCompute.computeStreaks(
            progress = 0.0,
            runningAvg = 1.0,
            streakPos = 2,
            streakNet = 2,
            posContinue = 2,
        )

        assertThat(streaks.toList()).containsExactly(3, 2, 2).inOrder()
    }

    @Test
    fun `zero progress below the ceiling does not advance the positive streak`() {
        val streaks = ScoreRollupCompute.computeStreaks(
            progress = 0.0,
            runningAvg = 0.9,
            streakPos = 2,
            streakNet = 2,
            posContinue = 2,
        )

        assertThat(streaks.toList()).containsExactly(0, 2, 2).inOrder()
    }

    @Test
    fun `the ceiling rule starts a positive streak from cold`() {
        val streaks = ScoreRollupCompute.computeStreaks(
            progress = 0.0,
            runningAvg = 1.0,
            streakPos = 0,
            streakNet = 0,
            posContinue = 0,
        )

        assertThat(streaks.toList()).containsExactly(1, 0, 0).inOrder()
    }

    // -- buildHabitMetricsFrom (L1) -----------------------------------------------------------------------

    @Test
    fun `a non-recurring task produces no rows and no converted rule`() {
        val (rows, firstDue, convertedRule) = ScoreRollupCompute.buildHabitMetrics(
            task = task(recurrenceEnabled = 0),
            occurrences = threeDayHistory(),
        )

        assertThat(rows).isEmpty()
        assertThat(firstDue).isNull()
        assertThat(convertedRule).isNull()
    }

    @Test
    fun `a recurring habit with no occurrences still gets its rule converted`() {
        val (rows, firstDue, convertedRule) = ScoreRollupCompute.buildHabitMetrics(
            task = task(recurrenceRule = "5/7"),
            occurrences = emptyList(),
        )

        // Without this the live cascade would read the stale num/den rule, which degrades to
        // FREQUENCY (always due) and silently rewrites the habit's cadence.
        assertThat(rows).isEmpty()
        assertThat(firstDue).isNull()
        assertThat(convertedRule).isEqualTo("CONFIG:type=WEEKDAYS_ONLY")
    }

    @Test
    fun `a daily habit emits one sparse row per due day with rolling average, progress and streaks`() {
        val rows = ScoreRollupCompute.buildHabitMetrics(dailyTask(), threeDayHistory()).first

        assertThat(rows.map { it.dayKey })
            .containsExactly(day(3).toString(), day(2).toString(), day(1).toString())
            .inOrder()

        val first = rows[0]
        assertThat(first.score).isEqualTo(1.0)
        assertThat(first.runningAvg).isEqualTo(1.0)
        // The first row's progress is the score itself: there is no earlier average tocompare against.
        assertThat(first.progress).isEqualTo(1.0)
        assertStreaks(first, streakPos = 1, streakNet = 1, posContinue = 1)

        val second = rows[1]
        assertThat(second.score).isEqualTo(1.0)
        assertThat(second.runningAvg).isEqualTo(1.0)
        // Flat at the ceiling: progress 0.0 yet the positive streak still advances.
        assertThat(second.progress).isEqualTo(0.0)
        assertStreaks(second, streakPos = 2, streakNet = 1, posContinue = 1)

        val third = rows[2]
        assertThat(third.score).isEqualTo(0.0)
        assertThat(third.runningAvg).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(third.progress).isWithin(1e-9).of(-1.0 / 3.0)
        assertStreaks(third, streakPos = 0, streakNet = 0, posContinue = 1)
    }

    @Test
    fun `cascade mode scores today only when today is logged, and a missed today extends the net streak down`() {
        val occurrences = threeDayHistory() +
            occurrence(dueDate = day(0).toString(), status = "missed")

        val rows = ScoreRollupCompute.buildHabitMetrics(
            task = dailyTask(),
            occurrences = occurrences,
            includeToday = true,
        ).first

        assertThat(rows.map { it.dayKey })
            .containsExactly(
                day(3).toString(),
                day(2).toString(),
                day(1).toString(),
                day(0).toString(),
            )
            .inOrder()

        val todayRow = rows[3]
        assertThat(todayRow.score).isEqualTo(0.0)
        assertThat(todayRow.runningAvg).isEqualTo(0.5)
        assertThat(todayRow.progress).isWithin(1e-9).of(-1.0 / 6.0)
        assertStreaks(todayRow, streakPos = 0, streakNet = -1, posContinue = 1)
    }

    @Test
    fun `an unlogged today stays pending even when the rule says it is due`() {
        val rows = ScoreRollupCompute.buildHabitMetrics(
            task = dailyTask(),
            occurrences = threeDayHistory(),
            includeToday = true,
        ).first

        assertThat(rows.map { it.dayKey }).doesNotContain(day(0).toString())
        assertThat(rows).hasSize(3)
    }

    @Test
    fun `a completed today is scored and continues the positive run`() {
        val occurrences = threeDayHistory() +
            occurrence(dueDate = day(0).toString(), status = "completed")

        val rows = ScoreRollupCompute.buildHabitMetrics(
            task = dailyTask(),
            occurrences = occurrences,
            includeToday = true,
        ).first

        assertThat(rows).hasSize(4)

        val todayRow = rows[3]
        assertThat(todayRow.score).isEqualTo(1.0)
        assertThat(todayRow.runningAvg).isWithin(1e-9).of(0.75)
        assertThat(todayRow.progress).isWithin(1e-9).of(1.0 / 12.0)
        assertStreaks(todayRow, streakPos = 1, streakNet = 1, posContinue = 2)
    }

    @Test
    fun `an unparseable due date is dropped and the first due day moves to the next parseable one`() {
        val occurrences = listOf(
            occurrence(dueDate = "not-a-date", status = "completed"),
            occurrence(dueDate = day(2).toString(), status = "completed"),
            occurrence(dueDate = day(1).toString(), status = "completed"),
        )

        val (rows, firstDue) = ScoreRollupCompute.buildHabitMetrics(dailyTask(), occurrences)

        assertThat(firstDue).isEqualTo(day(2))
        assertThat(rows.map { it.dayKey })
            .containsExactly(day(2).toString(), day(1).toString())
            .inOrder()
    }

    @Test
    fun `an interval grid ignores a completion logged off its schedule and passes the rule through unchanged`() {
        val rule = "CONFIG:type=INTERVAL|interval=3|start=${day(2)}"
        val occurrences = listOf(
            occurrence(dueDate = day(2).toString(), status = "completed"),
            occurrence(dueDate = day(1).toString(), status = "completed"),
        )

        val (rows, _, convertedRule) = ScoreRollupCompute.buildHabitMetrics(
            task = task(recurrenceRule = rule),
            occurrences = occurrences,
        )

        // Strict-grid semantics (user decision): T1 is not on the grid, so its completion counts for nothing.
        assertThat(rows.map { it.dayKey }).containsExactly(day(2).toString())
        assertThat(convertedRule).isEqualTo(rule)
    }

    @Test
    fun `a weekday rule emits only weekdays and passes the rrule through unchanged`() {
        val rule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
        val occurrences = listOf(occurrence(dueDate = day(7).toString(), status = "completed"))

        val (rows, _, convertedRule) = ScoreRollupCompute.buildHabitMetrics(
            task = task(recurrenceRule = rule),
            occurrences = occurrences,
        )

        // T7..T1 is exactly one week, so the row count is 5 on any day of the week.
        assertThat(rows).hasSize(5)
        val weekendRows = rows.map { LocalDate.parse(it.dayKey) }.filter { it.dayOfWeek.value >= 6 }
        assertThat(weekendRows).isEmpty()
        assertThat(convertedRule).isEqualTo(rule)
    }

    @Test
    fun `a skipped occurrence scores like a completed one and a missed one scores zero`() {
        val occurrences = listOf(
            occurrence(dueDate = day(2).toString(), status = "skipped"),
            occurrence(dueDate = day(1).toString(), status = "missed"),
        )

        val rows = ScoreRollupCompute.buildHabitMetrics(dailyTask(), occurrences).first

        assertThat(rows.map { it.dayKey })
            .containsExactly(day(2).toString(), day(1).toString())
            .inOrder()
        assertThat(rows[0].score).isEqualTo(1.0)
        assertThat(rows[1].score).isEqualTo(0.0)
    }

    @Test
    fun `a tail build seeded with one prior row reproduces the full build's later rows exactly`() {
        val task = dailyTask()
        val occurrences = threeDayHistory()
        val full = ScoreRollupCompute.buildHabitMetrics(task, occurrences).first

        val tail = ScoreRollupCompute.buildHabitMetricsFrom(
            task = task,
            occurrences = occurrences,
            fromDay = day(2),
            baseline = baseline(
                sumScores = 1.0,
                count = 1,
                prevAvg = 1.0,
                streakPos = 1,
                streakNet = 1,
                posContinue = 1,
            ),
        ).first

        assertThat(full).hasSize(3)
        assertThat(tail).hasSize(2)
        assertThat(tail).isEqualTo(full.drop(1))
    }

    @Test
    fun `a tail baseline carries the net streak forward instead of restarting it at zero`() {
        val rows = ScoreRollupCompute.buildHabitMetricsFrom(
            task = dailyTask(),
            occurrences = threeDayHistory(),
            fromDay = day(1),
            baseline = baseline(
                sumScores = 2.0,
                count = 2,
                prevAvg = 1.0,
                streakPos = 2,
                streakNet = 2,
                posContinue = 2,
            ),
        ).first

        assertThat(rows).hasSize(1)

        val row = rows.single()
        assertThat(row.dayKey).isEqualTo(day(1).toString())
        assertThat(row.score).isEqualTo(0.0)
        assertThat(row.runningAvg).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(row.progress).isWithin(1e-9).of(-1.0 / 3.0)
        assertThat(row.streakPos).isEqualTo(0)
        // 2 prior days - 1 missed day. Resetting this to 0 would erase the two good days behind it.
        assertThat(row.streakNet).isEqualTo(1)
        assertThat(row.posContinue).isEqualTo(2)
    }
}