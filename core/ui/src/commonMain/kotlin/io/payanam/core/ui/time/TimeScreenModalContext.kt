//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.labelForDimension
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.shared.logging.AppLogger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the time-block modal was opened for.
 *
 * Set once when the user taps an entry, the add button, an untracked gap, or a task/occurrence block,
 * and read by [buildTimeBlockModalInitialContext] to decide the form's starting values. The sealed
 * cases carry only what the trigger already knows; none of them is a form field, and
 * [ExistingEntry] doubles as "this entry is still running" (`endedAt == null`) instead of needing a
 * separate case.
 */
sealed interface TimeBlockModalTarget {
    /**
     * Editing an existing tracked time entry.
     */
    data class ExistingEntry(
        val entry: TimeEntry,
    ) : TimeBlockModalTarget

    /**
     * Creating a fresh entry at a default slot on [selectedDate].
     */
    data class ManualCreate(
        val selectedDate: LocalDate,
    ) : TimeBlockModalTarget

    /**
     * Creating an entry that fills a detected timeline gap.
     */
    data class GapCreate(
        val gapStart: LocalDateTime,
        val gapEnd: LocalDateTime,
    ) : TimeBlockModalTarget

    /**
     * Logging time against a task (optionally a completed occurrence).
     */
    data class TaskBlock(
        val task: Task,
        val occurrence: TaskOccurrence?,
    ) : TimeBlockModalTarget
}

/** Which copy the time-block modal titles itself with. */
enum class TimeBlockModalTitle {
    EDIT_ENTRY,
    ADD_ENTRY,
    ASSIGN_TIME,
}

/**
 * The title for this kind, from the generated copy table.
 *
 * A `when` rather than a stored resource id: `commonMain` has no `R`, and this makes a renamed or dropped
 * string key a compile error.
 */
fun TimeBlockModalTitle.label(strings: UiStrings): String =
    when (this) {
        TimeBlockModalTitle.EDIT_ENTRY -> strings.locEditTimeEntry
        TimeBlockModalTitle.ADD_ENTRY -> strings.locAddTimeEntry
        TimeBlockModalTitle.ASSIGN_TIME -> strings.locAssignTime
    }

/**
 * The form state the time-block modal opens with: the pre-filled fields for one launch.
 *
 * Built once per [TimeBlockModalTarget] by [buildTimeBlockModalInitialContext] and read back by the
 * dialog, so it is a snapshot, not live state - the modal owns its edits from then on. The nullable
 * fields distinguish "nothing to restore" from "restore this": a null [initialTaskId] means the form
 * opens with no task attached, which is every non-task target and also an existing entry that was
 * logged without one; a null [initialEnd] means the block is still open-ended and the dialog must
 * treat the end as unset rather than as midnight; and a null [initialFocusRating] or [initialFocusNote]
 * means there is no stored focus verdict to show. [initialDimensionId] and [initialDimensionLabel]
 * are never null - the builder substitutes a caller-supplied fallback rather than leaving the
 * dimension blank, so the form always opens on a resolvable dimension.
 */
data class TimeBlockModalInitialContext(
    val titleKind: TimeBlockModalTitle,
    val initialDimensionId: String,
    val initialDimensionLabel: String,
    val initialTaskId: String?,
    val initialStart: LocalDateTime,
    val initialEnd: LocalDateTime?,
    val initialFocusRating: Double?,
    val initialFocusNote: String?,
)

/**
 * Derives the modal's opening form state from the trigger that opened it.
 *
 * Each [TimeBlockModalTarget] case maps to its own starting values, and the differences are
 * deliberate rather than cosmetic:
 *
 *  - [TimeBlockModalTarget.ExistingEntry] restores the entry verbatim, inventing nothing. Its `endedAt`
 *    is passed through unchanged, so a still-running entry arrives as a null
 *    [TimeBlockModalInitialContext.initialEnd]. An entry with no `dimensionId` resolves to the
 *    caller's fallback, and the display label prefers the user's own dimension naming over the
 *    entry's stored category name.
 *  - [TimeBlockModalTarget.ManualCreate] has nothing to restore, so it proposes a 09:00-10:00 hour on
 *    the target's own date and leaves task, focus rating, and focus note unset. It titles the modal
 *    [TimeBlockModalTitle.ADD_ENTRY] and starts on the caller's fallback dimension.
 *  - [TimeBlockModalTarget.GapCreate] takes its window verbatim from the tapped gap and titles the
 *    modal [TimeBlockModalTitle.ASSIGN_TIME].
 *  - [TimeBlockModalTarget.TaskBlock] derives a window backwards from completion, because a task block
 *    is about time already spent. With an occurrence, the occurrence's actual completion time is the
 *    end and the actual duration is the length, so the block lands on what was really done. Without
 *    one, the block is centred on the task's due time at the default duration, so the due moment
 *    falls in the middle rather than at either edge. When the occurrence carries no completion time
 *    the task's due time is used instead, and a task that is due neither is centred on noon of
 *    [selectedDate]. A non-positive task or occurrence duration falls back to 30 minutes, so a
 *    proposed block is never zero-length.
 *
 * The returned dimension is the one resolved here, not necessarily the only one offered: the dialog
 * widens the picker separately, and only for the non-existing-entry cases. The focus rating is seeded
 * from the task's declared focus requirement, which is a pre-fill hint rather than a recorded
 * verdict, and the focus note is always left null because no target carries one to restore.
 */
fun buildTimeBlockModalInitialContext(
    target: TimeBlockModalTarget,
    selectedDate: LocalDate,
    appPreferences: AppPreferencesState,
    fallbackDimensionId: String,
    fallbackDimensionLabel: String,
): TimeBlockModalInitialContext {
    val logger = AppLogger.current
    val context =
        when (target) {
            is TimeBlockModalTarget.ExistingEntry -> {
                val entryDimensionId = target.entry.dimensionId ?: fallbackDimensionId
                val entryDimensionLabel =
                    appPreferences.labelForDimension(
                        dimensionId = target.entry.dimensionId,
                        dimensionName = target.entry.lifeIntentionCategory,
                    )
                        ?: fallbackDimensionLabel
                TimeBlockModalInitialContext(
                    titleKind = TimeBlockModalTitle.EDIT_ENTRY,
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
                    titleKind = TimeBlockModalTitle.ADD_ENTRY,
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
                    titleKind = TimeBlockModalTitle.ASSIGN_TIME,
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
                val taskDimensionLabel =
                    appPreferences.labelForDimension(
                        dimensionId = target.task.dimensionId,
                        dimensionName = target.task.lifeIntentionCategory,
                    )
                        ?: fallbackDimensionLabel
                val defaultDurationMinutes = target.task.durationMinutes.takeIf { it > 0 } ?: 30
                val completionEnd =
                    target.occurrence?.actualCompletedAt
                        ?: target.task.dueDate
                        ?: LocalDateTime.of(selectedDate, LocalTime.of(12, 0))
                val completionDuration =
                    target.occurrence
                        ?.actualDurationMinutes
                        ?.takeIf { it > 0 }
                        ?: defaultDurationMinutes
                val start =
                    if (target.occurrence != null) {
                        completionEnd.minusMinutes(completionDuration.toLong())
                    } else {
                        completionEnd.minusMinutes((defaultDurationMinutes / 2).toLong())
                    }
                val end =
                    if (target.occurrence != null) {
                        completionEnd
                    } else {
                        start.plusMinutes(defaultDurationMinutes.toLong())
                    }
                TimeBlockModalInitialContext(
                    titleKind = TimeBlockModalTitle.EDIT_ENTRY,
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
            "titleKind" to context.titleKind.toString(),
            "hasTaskId" to (context.initialTaskId != null).toString(),
        ),
    )
    return context
}
