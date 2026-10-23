//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import io.payanam.database.entity.HabitMetricEntity
import io.payanam.database.entity.TaskEntity
import io.payanam.database.entity.TaskOccurrenceEntity
import java.time.LocalDate

/**
 * Pure Phase-1 catch-up tail compute — the L1 extension that
 * `ScoreRollupCascadeService.catchUpTail` performs at startup.
 *
 * Given a recurring task, its occurrences, and its existing L1 rows, this
 * function returns the rows needed to extend the habit's tail through
 * yesterday. Gap days (no occurrence) are filled with score 0.0, matching
 * the original cascade exactly. The baseline is seeded from all existing
 * rows strictly before the computed `fromDay`, so the tail build reproduces
 * the full rebuild's arithmetic (C1 baseline carry-forward).
 *
 * A habit whose stored rows already reach yesterday is NOT extended —
 * an empty list is returned. This is the fast-path skip from the original.
 *
 * No I/O, no Room, no logger, no app_settings. All inputs are hand-buildable.
 */
object CatchUpTailCompute {
    /**
     * Extend one habit's L1 tail through yesterday.
     *
     * @param task the recurring task (must have recurrenceEnabled == 1)
     * @param occurrences all occurrences for this task
     * @param existingRows all existing L1 rows for this habit (any date)
     * @param today the current date (injected for testability)
     * @return the rows to insert (empty if the habit is already current)
     */
    fun extendHabitTail(
        task: TaskEntity,
        occurrences: List<TaskOccurrenceEntity>,
        existingRows: List<HabitMetricEntity>,
        today: LocalDate,
    ): List<HabitMetricEntity> {
        if (task.recurrenceEnabled != 1) return emptyList()

        val yesterday = today.minusDays(1)
        val dbMax = existingRows.maxOfOrNull { it.dayKey }

        // Fast path: habit already current (rows through yesterday or later)
        if (dbMax != null && dbMax >= yesterday.toString()) return emptyList()

        val fromDay =
            if (dbMax != null) {
                LocalDate.parse(dbMax).plusDays(1)
            } else {
                // No existing rows — start from the first occurrence date
                val firstOccurrence =
                    occurrences
                        .mapNotNull { runCatching { LocalDate.parse(it.dueDate.take(10)) }.getOrNull() }
                        .minOrNull() ?: return emptyList()
                firstOccurrence
            }

        // Baseline = all existing rows strictly before fromDay
        val existing = existingRows.filter { it.dayKey < fromDay.toString() }
        val baseline = MetricBaseline.fromHabitRows(existing)

        val (rows, _, _) =
            ScoreRollupCompute.buildHabitMetricsFrom(
                task = task,
                occurrences = occurrences,
                fromDay = fromDay,
                includeToday = false,
                baseline = baseline,
            )
        return rows
    }
}
