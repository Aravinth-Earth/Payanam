//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.labelForDimension
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal sealed interface TimeBlockModalTarget {
    /**
     * Holds the existing entry.
     */
    data class ExistingEntry(val entry: TimeEntry) : TimeBlockModalTarget
    /**
     * Holds the manual create.
     */
    data class ManualCreate(val selectedDate: LocalDate) : TimeBlockModalTarget
    /**
     * Holds the gap create.
     */
    data class GapCreate(val gapStart: LocalDateTime, val gapEnd: LocalDateTime) : TimeBlockModalTarget
    /**
     * Holds the task block.
     */
    data class TaskBlock(val task: Task, val occurrence: TaskOccurrence?) : TimeBlockModalTarget
}

internal data class TimeBlockModalInitialContext(
    val titleResId: Int,
    val initialDimensionId: String,
    val initialDimensionLabel: String,
    val initialTaskId: String?,
    val initialStart: LocalDateTime,
    val initialEnd: LocalDateTime?,
    val initialFocusRating: Double?,
    val initialFocusNote: String?,
)

internal fun buildTimeBlockModalInitialContext(
    target: TimeBlockModalTarget,
    selectedDate: LocalDate,
    appPreferences: AppPreferencesState,
    fallbackDimensionId: String,
    fallbackDimensionLabel: String,
): TimeBlockModalInitialContext {
    val logger = UnifiedLogger.getInstance()
    val context = when (target) {
        is TimeBlockModalTarget.ExistingEntry -> {
            val entryDimensionId = target.entry.dimensionId ?: fallbackDimensionId
            val entryDimensionLabel = appPreferences.labelForDimension(
                dimensionId = target.entry.dimensionId,
                dimensionName = target.entry.lifeIntentionCategory,
            )
                ?: fallbackDimensionLabel
            TimeBlockModalInitialContext(
                titleResId = R.string.loc_edit_time_entry,
                initialDimensionId = entryDimensionId,
                initialDimensionLabel = entryDimensionLabel,
                initialTaskId = target.entry.taskId,
                initialStart = target.entry.startedAt,
                initialEnd = target.entry.endedAt,
                initialFocusRating = target.entry.focusRating,
                initialFocusNote = target.entry.focusNote,
            )
        }

        is TimeBlockModalTarget.ManualCreate -> {
            val start = LocalDateTime.of(target.selectedDate, LocalTime.of(9, 0))
            TimeBlockModalInitialContext(
                titleResId = R.string.loc_add_time_entry,
                initialDimensionId = fallbackDimensionId,
                initialDimensionLabel = fallbackDimensionLabel,
                initialTaskId = null,
                initialStart = start,
                initialEnd = start.plusHours(1),
                initialFocusRating = null,
                initialFocusNote = null,
            )
        }

        is TimeBlockModalTarget.GapCreate -> {
            TimeBlockModalInitialContext(
                titleResId = R.string.loc_assign_time,
                initialDimensionId = fallbackDimensionId,
                initialDimensionLabel = fallbackDimensionLabel,
                initialTaskId = null,
                initialStart = target.gapStart,
                initialEnd = target.gapEnd,
                initialFocusRating = null,
                initialFocusNote = null,
            )
        }

        is TimeBlockModalTarget.TaskBlock -> {
            val taskDimensionId = target.task.dimensionId ?: fallbackDimensionId
            val taskDimensionLabel = appPreferences.labelForDimension(
                dimensionId = target.task.dimensionId,
                dimensionName = target.task.lifeIntentionCategory,
            )
                ?: fallbackDimensionLabel
            val defaultDurationMinutes = target.task.durationMinutes.takeIf { it > 0 } ?: 30
            val completionEnd = target.occurrence?.actualCompletedAt
                ?: target.task.dueDate
                ?: LocalDateTime.of(selectedDate, LocalTime.of(12, 0))
            val completionDuration = target.occurrence?.actualDurationMinutes
                ?.takeIf { it > 0 }
                ?: defaultDurationMinutes
            val start = if (target.occurrence != null) {
                completionEnd.minusMinutes(completionDuration.toLong())
            } else {
                completionEnd.minusMinutes((defaultDurationMinutes / 2).toLong())
            }
            val end = if (target.occurrence != null) {
                completionEnd
            } else {
                start.plusMinutes(defaultDurationMinutes.toLong())
            }
            TimeBlockModalInitialContext(
                titleResId = R.string.loc_edit_time_entry,
                initialDimensionId = taskDimensionId,
                initialDimensionLabel = taskDimensionLabel,
                initialTaskId = target.task.id,
                initialStart = start,
                initialEnd = end,
                initialFocusRating = target.task.focusRequired,
                initialFocusNote = null,
            )
        }
    }
    logger.d(
        "TimeScreenModalContext.buildTimeBlockModalInitialContext",
        "Built modal context",
        mapOf(
            "target" to target::class.simpleName.orEmpty(),
            "titleResId" to context.titleResId.toString(),
            "hasTaskId" to (context.initialTaskId != null).toString(),
        ),
    )
    return context
}
