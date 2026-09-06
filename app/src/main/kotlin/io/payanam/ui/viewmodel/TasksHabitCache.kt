//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.ui.components.CheckmarkStatus
import io.payanam.ui.components.DayCheckmark
import java.time.LocalDate
import java.time.format.DateTimeParseException

private val tasksHabitCacheLogger = UnifiedLogger.getInstance()

internal data class HabitCheckmarkPayload(
    val taskCheckmarks: Map<String, List<DayCheckmark>>,
    val todayStatusByTaskId: Map<String, CheckmarkStatus>,
)

internal fun buildHabitCheckmarkPayload(
    tasks: List<Task>,
    occurrencesMap: Map<String, List<TaskOccurrence>>,
    today: LocalDate,
    days: Int,
): HabitCheckmarkPayload {
    val checkmarksByTaskId = linkedMapOf<String, List<DayCheckmark>>()
    val todayStatusByTaskId = linkedMapOf<String, CheckmarkStatus>()
    tasks.forEach { task ->
        val taskCheckmarks = buildCheckmarksForTask(
            occurrences = occurrencesMap[task.id] ?: emptyList(),
            today = today,
            days = days,
        )
        checkmarksByTaskId[task.id] = taskCheckmarks
        todayStatusByTaskId[task.id] = taskCheckmarks.firstOrNull()?.status ?: CheckmarkStatus.UNKNOWN
    }
    val completedToday = todayStatusByTaskId.values.count { it == CheckmarkStatus.COMPLETED }
    tasksHabitCacheLogger.d(
        "TasksHabitCache.buildHabitCheckmarkPayload",
        "Habit checkmark payload built",
        mapOf(
            "habitCount" to tasks.size,
            "days" to days,
            "completedToday" to completedToday,
        ),
    )
    return HabitCheckmarkPayload(
        taskCheckmarks = checkmarksByTaskId,
        todayStatusByTaskId = todayStatusByTaskId,
    )
}

@Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; broad catch intentional
internal fun buildCheckmarksForTask(
    occurrences: List<TaskOccurrence>,
    today: LocalDate,
    days: Int,
): List<DayCheckmark> {
    val occurrenceMap = occurrences.associateBy { occurrence ->
        try {
            LocalDate.parse(occurrence.occurrenceDate.take(10))
        } catch (e: DateTimeParseException) {
            tasksHabitCacheLogger.w(
                "TasksHabitCache.buildCheckmarksForTask",
                "Skipping occurrence with invalid date",
                mapOf("occurrenceDate" to occurrence.occurrenceDate),
            )
            null
        }
    }.filterKeys { it != null }.mapKeys { it.key!! }
    return (0 until days).map { daysAgo ->
        val date = today.minusDays(daysAgo.toLong())
        val occurrence = occurrenceMap[date]
        val status = when {
            daysAgo == 0 && occurrence == null -> CheckmarkStatus.PENDING
            occurrence == null -> CheckmarkStatus.UNKNOWN
            occurrence.status == "completed" -> CheckmarkStatus.COMPLETED
            occurrence.status == "skipped" -> CheckmarkStatus.SKIPPED
            occurrence.status == "missed" -> CheckmarkStatus.MISSED
            else -> CheckmarkStatus.UNKNOWN
        }
        DayCheckmark(
            date = date,
            status = status,
            hasNote = occurrence?.statusNote?.isNotBlank() == true || occurrence?.note?.isNotBlank() == true,
            note = occurrence?.statusNote ?: occurrence?.note,
        )
    }
}
