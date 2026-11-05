//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import com.google.common.truth.Truth.assertThat
import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import org.junit.Test
import java.time.LocalDate

/**
 * Pure Phase-1 catch-up tail compute — verifies that [CatchUpTailCompute.extendHabitTail]
 * extends a lagging habit's L1 rows through yesterday with gap days filled with 0.0,
 * and that a habit already current through yesterday is NOT extended.
 *
 * Every fixture date is `today.minusDays(n)` rather than a fixed date, matching the
 * convention in [ScoreRollupComputeTest].
 */
class CatchUpTailComputeTest {
    private val today: LocalDate = LocalDate.now()

    private fun day(daysAgo: Int): LocalDate = today.minusDays(daysAgo.toLong())

    private fun task(
        id: String = "h1",
        recurrenceEnabled: Int = 1,
        recurrenceRule: String? = "1/1",
    ) = TaskEntity(
        id = id,
        title = "Habit",
        createdAt = day(7).toString(),
        updatedAt = day(7).toString(),
        recurrenceEnabled = recurrenceEnabled,
        recurrenceRule = recurrenceRule,
    )

    private fun occurrence(
        taskId: String = "h1",
        dueDate: String,
        status: String,
    ) = TaskOccurrenceEntity(
        id = "$taskId-$dueDate-$status",
        taskId = taskId,
        dueDate = dueDate,
        status = status,
        createdAt = dueDate,
    )

    private fun habitRow(
        dayKey: String,
        score: Double,
        runningAvg: Double,
        progress: Double,
        streakPos: Int,
        streakNet: Int,
        posContinue: Int,
    ) = HabitMetricEntity(
        habitId = "h1",
        dayKey = dayKey,
        score = score,
        runningAvg = runningAvg,
        progress = progress,
        streakPos = streakPos,
        streakNet = streakNet,
        posContinue = posContinue,
    )

    @Test
    fun `a lagging habit tail extends through yesterday with gap days filled with zero`() {
        // Habit lagging 3 days: DB has rows through day(3), occurrences through day(3).
        val existingRows =
            listOf(
                habitRow(day(5).toString(), 1.0, 1.0, 1.0, 1, 1, 1),
                habitRow(day(4).toString(), 1.0, 1.0, 0.0, 2, 2, 2),
                habitRow(day(3).toString(), 1.0, 1.0, 0.0, 3, 3, 3),
            )
        val occurrences =
            listOf(
                occurrence(dueDate = day(5).toString(), status = "completed"),
                occurrence(dueDate = day(4).toString(), status = "completed"),
                occurrence(dueDate = day(3).toString(), status = "completed"),
            )

        val rows =
            CatchUpTailCompute.extendHabitTail(
                task = task(),
                occurrences = occurrences,
                existingRows = existingRows,
                today = today,
            )

        // Tail should cover day(2) and day(1) — both gap days with no occurrences.
        assertThat(rows).hasSize(2)
        assertThat(rows[0].dayKey).isEqualTo(day(2).toString())
        assertThat(rows[0].score).isEqualTo(0.0)
        assertThat(rows[1].dayKey).isEqualTo(day(1).toString())
        assertThat(rows[1].score).isEqualTo(0.0)
    }

    @Test
    fun `a habit already current through yesterday is not extended`() {
        // DB has rows through yesterday (day(1)) — no gap to fill.
        val existingRows =
            listOf(
                habitRow(day(3).toString(), 1.0, 1.0, 1.0, 1, 1, 1),
                habitRow(day(2).toString(), 0.0, 0.5, -0.5, 0, 0, 1),
                habitRow(day(1).toString(), 1.0, 0.6667, 0.1667, 1, 1, 2),
            )
        val occurrences =
            listOf(
                occurrence(dueDate = day(3).toString(), status = "completed"),
                occurrence(dueDate = day(2).toString(), status = "missed"),
                occurrence(dueDate = day(1).toString(), status = "completed"),
            )

        val rows =
            CatchUpTailCompute.extendHabitTail(
                task = task(),
                occurrences = occurrences,
                existingRows = existingRows,
                today = today,
            )

        assertThat(rows).isEmpty()
    }
}
