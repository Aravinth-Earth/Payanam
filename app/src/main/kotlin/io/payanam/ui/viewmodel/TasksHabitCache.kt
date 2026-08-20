//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.ui.components.CheckmarkStatus
import io.payanam.ui.components.DayCheckmark
import java.time.LocalDate

private val tasksHabitCacheLogger = UnifiedLogger.getInstance()

internal data class HabitCheckmarkPayload(
    /** Task checkmarks. */
    val taskCheckmarks: Map<String, List<DayCheckmark>>,
    /** Today status by task id. */
    val todayStatusByTaskId: Map<String, CheckmarkStatus>,
)

internal fun buildHabitCheckmarkPayload(
    tasks: List<Task>,
    occurrencesMap: Map<String, List<TaskOccurrence>>,
    /** Today. */
    today: LocalDate,
    /** Days. */
    days: Int,
): HabitCheckmarkPayload {
    /** Checkmarks by task id. */
    val checkmarksByTaskId = linkedMapOf<String, List<DayCheckmark>>()
    /** Today status by task id. */
    val todayStatusByTaskId = linkedMapOf<String, CheckmarkStatus>()
    tasks.forEach { task ->
        /** Task checkmarks. */
        val taskCheckmarks = buildCheckmarksForTask(
            occurrences = occurrencesMap[task.id] ?: emptyList(),
            today = today,
            days = days,
        )
        checkmarksByTaskId[task.id] = taskCheckmarks
        todayStatusByTaskId[task.id] = taskCheckmarks.firstOrNull()?.status ?: CheckmarkStatus.UNKNOWN
    }
    /** Completed today. */
    val completedToday = todayStatusByTaskId.values.count { it == CheckmarkStatus.COMPLETED }
    tasksHabitCacheLogger.d(
        "TasksHabitCache.buildHabitCheckmarkPayload",
        "Habit checkmark payload built",
        /** Map of. */
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

internal fun buildCheckmarksForTask(
    occurrences: List<TaskOccurrence>,
    /** Today. */
    today: LocalDate,
    /** Days. */
    days: Int,
): List<DayCheckmark> {
    /** Occurrence map. */
    val occurrenceMap = occurrences.associateBy { occurrence ->
        try {
            LocalDate.parse(occurrence.occurrenceDate.take(10))
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            tasksHabitCacheLogger.w(
                "TasksHabitCache.buildCheckmarksForTask",
                "Skipping occurrence with invalid date",
                /** Map of. */
                mapOf("occurrenceDate" to occurrence.occurrenceDate),
            )
            /** Null. */
            null
        }
    }.filterKeys { it != null }.mapKeys { it.key!! }

    /** Return. */
    return (0 until days).map { daysAgo ->
        /** Date. */
        val date = today.minusDays(daysAgo.toLong())
        /** Occurrence. */
        val occurrence = occurrenceMap[date]
        /** Status. */
        val status = when {
            daysAgo == 0 && occurrence == null -> CheckmarkStatus.PENDING
            occurrence == null -> CheckmarkStatus.UNKNOWN
            occurrence.status == "completed" -> CheckmarkStatus.COMPLETED
            occurrence.status == "skipped" -> CheckmarkStatus.SKIPPED
            occurrence.status == "missed" -> CheckmarkStatus.MISSED
            else -> CheckmarkStatus.UNKNOWN
        }
        /** Day checkmark. */
        DayCheckmark(
            date = date,
            status = status,
            hasNote = occurrence?.statusNote?.isNotBlank() == true || occurrence?.note?.isNotBlank() == true,
            note = occurrence?.statusNote ?: occurrence?.note,
        )
    }
}
