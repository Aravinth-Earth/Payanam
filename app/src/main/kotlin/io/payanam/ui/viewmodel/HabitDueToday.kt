//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Frequency
import io.payanam.domain.model.RecurrenceConfig
import io.payanam.domain.model.RecurrenceType
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.scoring.RecurrenceScoreCalculator
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val habitDueTodayLogger: UnifiedLogger?
    get() = if (UnifiedLogger.isInitialized()) UnifiedLogger.getInstance() else null

/**
 * Depth of the bulk occurrence lookup feeding the "Due today only" predicate.
 * Covers the longest UI-native FREQUENCY window (1 month = denominator 30),
 * so the current window's completions are always visible to the quota check.
 */
internal const val HABIT_OCCURRENCE_LOOKBACK_DAYS = 30

/** Depth of the checkmark history rendered on habit cards (UI shape, unchanged). */
internal const val HABIT_CHECKMARK_HISTORY_DAYS = 14

/**
 * Per-habit "due today" map for the Habits-tab filter. One entry per task in
 * [tasks]; true = the habit is actionable today under its recurrence model.
 *
 * Rule formats decide the semantics (the UI picker serializes EVERY type as a
 * Frequency rule like "5/7" or "1/1", so a serialized rule is a quota-window
 * habit; CONFIG:/RRULE rules carry their real type):
 *  - Frequency-serialized ("n/d[!start=...]") and CONFIG FREQUENCY: due when
 *    today lies inside the current anchor-based window AND the window quota
 *    (numerator, minus skips) is not yet satisfied — the same model
 *    RecurrenceManager uses for reminders/auto-miss.
 *  - DAILY (typed): always due.
 *  - WEEKDAYS_ONLY / SPECIFIC_WEEKDAYS / MONTHLY_DATES / YEARLY (typed):
 *    due when [RecurrenceConfig.isScheduledDay] says today is scheduled.
 *  - INTERVAL (typed): scheduled-day check, but a null startDate (legacy)
 *    anchors on the earliest logged occurrence instead of degrading to
 *    every-day; no occurrences at all -> keep visible (cannot determine).
 */
internal suspend fun buildDueTodayByTaskId(
    tasks: List<Task>,
    occurrencesMap: Map<String, List<TaskOccurrence>>,
    today: LocalDate,
    fetchFullHistory: suspend (Task) -> List<TaskOccurrence> = { emptyList() },
): Map<String, Boolean> {
    val dueToday = linkedMapOf<String, Boolean>()
    tasks.forEach { task ->
        dueToday[task.id] = computeDueTodayForTask(
            task = task,
            occurrences = occurrencesMap[task.id].orEmpty(),
            today = today,
            fetchFullHistory = fetchFullHistory,
        )
    }
    val dueCount = dueToday.values.count { it }
    habitDueTodayLogger?.d(
        "HabitDueToday.buildDueTodayByTaskId",
        "Due-today map built",
        mapOf("habitCount" to tasks.size, "dueTodayCount" to dueCount),
    )
    return dueToday
}

/**
 * Due-today evaluation for a single habit. [occurrences] carries the data
 * window (typically [HABIT_OCCURRENCE_LOOKBACK_DAYS] days); when a FREQUENCY
 * habit's denominator exceeds that window, [fetchFullHistory] supplies the
 * full history so the current window's quota is counted exactly.
 */
internal suspend fun computeDueTodayForTask(
    task: Task,
    occurrences: List<TaskOccurrence>,
    today: LocalDate,
    fetchFullHistory: suspend (Task) -> List<TaskOccurrence> = { emptyList() },
): Boolean {
    if (!task.recurrenceEnabled) {
        habitDueTodayLogger?.d(
            "HabitDueToday.computeDueTodayForTask",
            "Not recurring -> due",
            mapOf("taskId" to task.id, "result" to true, "reason" to "recurrenceDisabled"),
        )
        return true
    }
    val rule = task.recurrenceRule
    val recType = runCatching { RecurrenceConfig.parse(rule ?: "").type.name }.getOrDefault("NONE")
    if (Frequency.isSerializedRule(rule)) {
        val frequency = Frequency.parse(rule)
        val result = frequencyWindowDue(
            numerator = frequency.numerator,
            denominator = frequency.denominator,
            anchorDate = frequency.anchorDate
                ?: task.dueDate?.toLocalDate()
                ?: task.createdAt.toLocalDate(),
            occurrences = if (frequency.denominator > HABIT_OCCURRENCE_LOOKBACK_DAYS) fetchFullHistory(task) else occurrences,
            today = today,
        )
        habitDueTodayLogger?.d(
            "HabitDueToday.computeDueTodayForTask",
            "FREQUENCY rule evaluated",
            mapOf(
                "taskId" to task.id,
                "ruleType" to "FREQUENCY",
                "numerator" to frequency.numerator,
                "denominator" to frequency.denominator,
                "result" to result,
            ),
        )
        return result
    }
    val config = RecurrenceConfig.parse(rule)
    val result = when (config.type) {
        RecurrenceType.FREQUENCY -> frequencyWindowDue(
            numerator = config.frequencyNumerator,
            denominator = config.frequencyDenominator,
            anchorDate = config.startDate
                ?: task.dueDate?.toLocalDate()
                ?: task.createdAt.toLocalDate(),
            occurrences = if (config.frequencyDenominator > HABIT_OCCURRENCE_LOOKBACK_DAYS) fetchFullHistory(task) else occurrences,
            today = today,
        )

        RecurrenceType.DAILY -> true

        RecurrenceType.INTERVAL -> {
            val start = config.startDate
            if (start != null) {
                config.isScheduledDay(today)
            } else {
                // Legacy rule without an anchor: anchor on the earliest logged
                // occurrence. Occurrences land on scheduled days, so the
                // earliest one is same-phase as the true anchor.
                val anchor = occurrences
                    .mapNotNull { occurrence ->
                        runCatching { LocalDate.parse(occurrence.occurrenceDate.take(10)) }.getOrNull()
                    }
                    .minOrNull()
                if (anchor == null) {
                    true // cannot determine the phase — keep the habit visible
                } else {
                    val daysSinceAnchor = ChronoUnit.DAYS.between(anchor, today)
                    daysSinceAnchor >= 0 && daysSinceAnchor % config.intervalDays.coerceAtLeast(1) == 0L
                }
            }
        }

        // WEEKDAYS_ONLY / SPECIFIC_WEEKDAYS / MONTHLY_DATES / YEARLY
        else -> config.isScheduledDay(today)
    }
    habitDueTodayLogger?.d(
        "HabitDueToday.computeDueTodayForTask",
        "Rule evaluated",
        mapOf(
            "taskId" to task.id,
            "ruleType" to recType,
            "result" to result,
        ),
    )
    return result
}

/**
 * FREQUENCY due-ness: today is always inside the current window (any day may
 * be used), so a frequency habit is due exactly when the window's quota
 * (target minus skips) is not yet met. Matches the window model used by
 * RecurrenceManager reminders and auto-miss.
 */
private fun frequencyWindowDue(
    numerator: Int,
    denominator: Int,
    anchorDate: LocalDate,
    occurrences: List<TaskOccurrence>,
    today: LocalDate,
): Boolean {
    val denom = denominator.coerceAtLeast(1)
    val daysSinceAnchor = ChronoUnit.DAYS.between(anchorDate, today)
    if (daysSinceAnchor < 0) return false // window has not started yet
    val windowStart = anchorDate.plusDays((daysSinceAnchor / denom) * denom)
    val windowEnd = windowStart.plusDays((denom - 1).toLong())
    val occurrenceMap = HashMap<LocalDate, String>(occurrences.size)
    occurrences.forEach { occurrence ->
        runCatching { LocalDate.parse(occurrence.occurrenceDate.take(10)) }
            .getOrNull()
            ?.let { occurrenceMap[it] = occurrence.status }
    }
    val summary = RecurrenceScoreCalculator.buildFrequencyWindows(
        occurrences = occurrenceMap,
        frequency = Frequency(
            numerator = numerator.coerceAtLeast(1),
            denominator = denom,
            anchorDate = anchorDate,
        ),
        anchorDate = anchorDate,
        rangeStart = windowStart,
        rangeEnd = windowEnd,
    ).firstOrNull()
    // summary == null is unreachable here (daysSinceAnchor >= 0 guarantees the
    // window intersects the anchor range); keep the habit visible if it happens.
    val result = summary == null || !summary.isSatisfied
    habitDueTodayLogger?.d(
        "HabitDueToday.frequencyWindowDue",
        "Window quota evaluated",
        mapOf(
            "taskId" to "n/a",
            "windowStart" to windowStart.toString(),
            "windowEnd" to windowEnd.toString(),
            "numerator" to numerator,
            "denominator" to denom,
            "doneInWindow" to (summary?.completedCount ?: "null"),
            "skipInWindow" to (summary?.skippedCount ?: "null"),
            "isSatisfied" to (summary?.isSatisfied ?: "null"),
            "result" to result,
        ),
    )
    return result
}
