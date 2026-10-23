//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import io.payanam.domain.model.Frequency
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.shared.logging.AppLogger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * `yyyy-MM-dd` — the length of an ISO date, which is what an occurrence's date string starts with.
 *
 * Named rather than inlined because it appears inside a parse, where a bare 10 cannot be checked by eye.
 */
private const val ISO_DATE_LENGTH = 10

/**
 * The window bookkeeping for a FREQUENCY habit — "3 times per 7 days" rather than "every 1 day".
 *
 * Extracted from [RecurrenceManager] for one reason: [RecurrenceManager] arrived in
 * `:core:scoring` with no tests of its own, and 709 lines in one class moved that module's coverage from
 * 0.91 to 0.59. The two halves are genuinely different concerns — a frequency habit is RESYNCED to a window
 * boundary, while a plain recurring task is ADVANCED occurrence by occurrence — and separating them lets each
 * be read, and tested, on its own.
 *
 * Nothing here is new logic: every rule is the code that was in [RecurrenceManager], unchanged. What changed
 * is that the rules now live behind this one collaborator, so [RecurrenceManager] holds the day-boundary
 * advance/repair and delegates the frequency case.
 */
internal class FrequencyHabitSync(
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
) {
    private val logger = AppLogger.current

    /**
     * Re-syncs one frequency habit's window state by [taskId]. A no-op for a task that is not a frequency
     * habit, which is the only thing the caller gets to decide before asking.
     */
    suspend fun refresh(taskId: String) {
        val task = taskRepository.getTaskById(taskId) ?: return
        if (RecurrenceManager.isFrequencyRule(task)) {
            sync(task)
        }
    }

    /**
     * Syncs [task]'s window and moves its due date to the next reminder.
     *
     * No occurrence is generated here: a frequency habit records what the user actually did, and this only
     * decides WHEN the next reminder is due. That is why it is separate from the advance path, which does
     * create occurrences for interval-style recurrences.
     */
    suspend fun sync(task: Task) {
        val frequencyRule = Frequency.legacyParse(task.recurrenceRule)
        val occurrences = taskOccurrenceRepository.getOccurrencesByTaskId(task.id)
        val today = LocalDate.now()
        val windowState = evaluateWindow(task, frequencyRule, occurrences, today)
        val occurrenceMap = occurrences.associateNotNull { occurrence ->
            parseOccurrenceDate(occurrence)?.let { it to occurrence.status }
        }
        val anchorDate = frequencyRule.anchorDate ?: task.dueDate?.toLocalDate() ?: task.createdAt.toLocalDate()
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(
            occurrences = occurrenceMap,
            frequency = frequencyRule,
            anchorDate = anchorDate,
            today = today,
        )
        val nextReminderDate =
            if (windowState.isSatisfied) {
                windowState.end.plusDays(1)
            } else {
                val todayReminderDateTime = today.atTime(task.dueDate?.toLocalTime() ?: LocalTime.of(9, 0))
                if (!todayReminderDateTime.isBefore(LocalDateTime.now())) today else today.plusDays(1)
            }
        val reminderTime = task.dueDate?.toLocalTime() ?: LocalTime.of(9, 0)
        val newDueDate = nextReminderDate.atTime(reminderTime)
        val latestOccurrenceDate =
            occurrences
                .mapNotNull { parseOccurrenceDate(it) }
                .maxOrNull()
                ?.atTime(reminderTime)
                ?: task.lastOccurrenceDate
                ?: LocalDateTime.now()
        // Inc 4b: decay derived score removed — score roll-up (L1) owns scoring now.

        taskRepository.updateRecurrenceState(
            taskId = task.id,
            newDueDate = newDueDate,
            lastOccurrenceDate = latestOccurrenceDate,
        )

        logger.i(
            "FrequencyHabitSync.sync",
            "Synced frequency habit state without occurrence generation",
            mapOf(
                "taskId" to task.id,
                "windowStart" to windowState.start.toString(),
                "windowEnd" to windowState.end.toString(),
                "completedCount" to windowState.completedCount,
                "skippedCount" to windowState.skippedCount,
                "targetCount" to windowState.targetCount,
                "effectiveTargetCount" to windowState.effectiveTargetCount,
                "windowSatisfied" to windowState.isSatisfied,
                "nextReminderDate" to newDueDate.toString(),
                "currentStreak" to stats.currentStreak,
            ),
        )
    }

    private fun evaluateWindow(
        task: Task,
        frequency: Frequency,
        occurrences: List<TaskOccurrence>,
        today: LocalDate,
    ): FrequencyWindowState {
        val anchorDate = frequency.anchorDate ?: task.dueDate?.toLocalDate() ?: task.createdAt.toLocalDate()
        val denominator = max(1, frequency.denominator)
        val daysSinceAnchor = max(0L, ChronoUnit.DAYS.between(anchorDate, today))
        val windowOffset = (daysSinceAnchor / denominator) * denominator
        val windowStart = anchorDate.plusDays(windowOffset)
        val windowEnd = windowStart.plusDays((denominator - 1).toLong())
        val occurrenceMap = occurrences.associateNotNull { occurrence ->
            parseOccurrenceDate(occurrence)?.let { it to occurrence.status }
        }
        val windowSummary =
            RecurrenceScoreCalculator.buildFrequencyWindows(
                occurrences = occurrenceMap,
                frequency = frequency,
                anchorDate = anchorDate,
                rangeStart = windowStart,
                rangeEnd = windowEnd,
            ).firstOrNull() ?: fallbackWindowSummary(anchorDate, frequency)

        return FrequencyWindowState(
            start = windowSummary.start,
            end = windowSummary.end,
            completedCount = windowSummary.completedCount,
            skippedCount = windowSummary.skippedCount,
            targetCount = windowSummary.targetCount,
            effectiveTargetCount = windowSummary.effectiveTargetCount,
        )
    }

    /**
     * Statistics for a frequency habit, or null when [task] is not one.
     *
     * A null return rather than a default: a caller that is not asking about a frequency habit must not
     * silently receive a frequency-shaped answer, and one that is asking can tell "not a frequency habit"
     * from "a frequency habit with no occurrences".
     */
    suspend fun completionStatsOrNull(task: Task): CompletionStats? {
        if (!RecurrenceManager.isFrequencyRule(task)) return null
        val occurrences = taskOccurrenceRepository.getOccurrencesByTaskId(task.id)
        val frequency = Frequency.legacyParse(task.recurrenceRule)
        val anchorDate = frequency.anchorDate ?: task.dueDate?.toLocalDate() ?: task.createdAt.toLocalDate()
        val occurrenceMap = occurrences.associateNotNull { occurrence ->
            parseOccurrenceDate(occurrence)?.let { it to occurrence.status }
        }
        return RecurrenceScoreCalculator.calculateFrequencyAwareStats(
            occurrences = occurrenceMap,
            frequency = frequency,
            anchorDate = anchorDate,
            today = LocalDate.now(),
        )
    }

    private fun parseOccurrenceDate(occurrence: TaskOccurrence): LocalDate? =
        // 10 is the length of an ISO date, `yyyy-MM-dd`. The column is a STRING and may carry a time or
        // extra padding, so the first 10 characters are the date - named here rather than left as a literal
        // in a parse, which is exactly the kind of number a reader cannot check by eye.
        runCatching { LocalDate.parse(occurrence.occurrenceDate.take(ISO_DATE_LENGTH)) }.getOrNull()

    /**
     * The window when the calculator returned none, which happens for an anchor date with no history behind
     * it. The counts are zero on purpose: an unmeasured window is not a satisfied one.
     */
    private fun fallbackWindowSummary(
        anchorDate: LocalDate,
        frequency: Frequency,
    ): FrequencyWindowSummary {
        val denominator = max(1, frequency.denominator)
        val daysSinceAnchor = max(0L, ChronoUnit.DAYS.between(anchorDate, LocalDate.now()))
        val windowOffset = (daysSinceAnchor / denominator) * denominator
        val windowStart = anchorDate.plusDays(windowOffset)
        return FrequencyWindowSummary(
            start = windowStart,
            end = windowStart.plusDays((denominator - 1).toLong()),
            coveredDays = denominator,
            completedCount = 0,
            skippedCount = 0,
            targetCount = max(1, frequency.numerator),
            effectiveTargetCount = max(1, frequency.numerator),
        )
    }

    private inline fun <T, R> Iterable<T>.associateNotNull(transform: (T) -> Pair<LocalDate, R>?): Map<LocalDate, R> =
        buildMap {
            for (item in this@associateNotNull) {
                val pair = transform(item) ?: continue
                put(pair.first, pair.second)
            }
        }
}

/**
 * Where a frequency habit's current window sits and whether it is met.
 *
 * [isSatisfied] is derived, never stored: the count can change under it, and a cached flag would then
 * disagree with the counts a user can see.
 */
internal data class FrequencyWindowState(
    val start: LocalDate,
    val end: LocalDate,
    val completedCount: Int,
    val skippedCount: Int,
    val targetCount: Int,
    val effectiveTargetCount: Int,
) {
    val isSatisfied: Boolean get() = effectiveTargetCount > 0 && completedCount >= effectiveTargetCount
}
