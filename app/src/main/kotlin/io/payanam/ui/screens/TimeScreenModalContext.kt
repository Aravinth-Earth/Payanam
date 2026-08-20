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
     * ExistingEntry.
     */
    data class ExistingEntry(val entry: TimeEntry) : TimeBlockModalTarget
    /**
     * ManualCreate.
     */
    data class ManualCreate(val selectedDate: LocalDate) : TimeBlockModalTarget
    /**
     * GapCreate.
     */
    data class GapCreate(val gapStart: LocalDateTime, val gapEnd: LocalDateTime) : TimeBlockModalTarget
    /**
     * TaskBlock.
     */
    data class TaskBlock(val task: Task, val occurrence: TaskOccurrence?) : TimeBlockModalTarget
}

internal data class TimeBlockModalInitialContext(
    /** Title res id. */
    val titleResId: Int,
    /** Initial dimension id. */
    val initialDimensionId: String,
    /** Initial dimension label. */
    val initialDimensionLabel: String,
    /** Initial task id. */
    val initialTaskId: String?,
    /** Initial start. */
    val initialStart: LocalDateTime,
    /** Initial end. */
    val initialEnd: LocalDateTime?,
    /** Initial focus rating. */
    val initialFocusRating: Double?,
    /** Initial focus note. */
    val initialFocusNote: String?,
)

internal fun buildTimeBlockModalInitialContext(
    /** Target. */
    target: TimeBlockModalTarget,
    /** Selected date. */
    selectedDate: LocalDate,
    /** App preferences. */
    appPreferences: AppPreferencesState,
    /** Fallback dimension id. */
    fallbackDimensionId: String,
    /** Fallback dimension label. */
    fallbackDimensionLabel: String,
): TimeBlockModalInitialContext {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Context. */
    val context = when (target) {
        is TimeBlockModalTarget.ExistingEntry -> {
            /** Entry dimension id. */
            val entryDimensionId = target.entry.dimensionId ?: fallbackDimensionId
            /** Entry dimension label. */
            val entryDimensionLabel = appPreferences.labelForDimension(
                dimensionId = target.entry.dimensionId,
                dimensionName = target.entry.lifeIntentionCategory,
            )
                ?: fallbackDimensionLabel
            /** Time block modal initial context. */
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
            /** Start. */
            val start = LocalDateTime.of(target.selectedDate, LocalTime.of(9, 0))
            /** Time block modal initial context. */
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
            /** Time block modal initial context. */
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
            /** Task dimension id. */
            val taskDimensionId = target.task.dimensionId ?: fallbackDimensionId
            /** Task dimension label. */
            val taskDimensionLabel = appPreferences.labelForDimension(
                dimensionId = target.task.dimensionId,
                dimensionName = target.task.lifeIntentionCategory,
            )
                ?: fallbackDimensionLabel
            /** Default duration minutes. */
            val defaultDurationMinutes = target.task.durationMinutes.takeIf { it > 0 } ?: 30
            /** Completion end. */
            val completionEnd = target.occurrence?.actualCompletedAt
                ?: target.task.dueDate
                ?: LocalDateTime.of(selectedDate, LocalTime.of(12, 0))
            /** Completion duration. */
            val completionDuration = target.occurrence?.actualDurationMinutes
                ?.takeIf { it > 0 }
                ?: defaultDurationMinutes
            /** Start. */
            val start = if (target.occurrence != null) {
                completionEnd.minusMinutes(completionDuration.toLong())
            } else {
                completionEnd.minusMinutes((defaultDurationMinutes / 2).toLong())
            }
            /** End. */
            val end = if (target.occurrence != null) {
                /** Completion end. */
                completionEnd
            } else {
                start.plusMinutes(defaultDurationMinutes.toLong())
            }
            /** Time block modal initial context. */
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
        /** Map of. */
        mapOf(
            "target" to target::class.simpleName.orEmpty(),
            "titleResId" to context.titleResId.toString(),
            "hasTaskId" to (context.initialTaskId != null).toString(),
        ),
    )
    return context
}
