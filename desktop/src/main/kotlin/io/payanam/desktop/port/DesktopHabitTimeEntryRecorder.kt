//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooGenericExceptionCaught")

package io.payanam.desktop.port

import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.port.HabitTimeEntryRecorder
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

/**
 * The desktop's [HabitTimeEntryRecorder]: the same rules Android's `CreateTimeEntryForHabitUseCase`
 * applies, over this platform's own repositories.
 *
 * WHY THIS EXISTS: the shared completion path (`TasksViewModel.confirmCompletion`) calls the recorder on
 * every platform, and the desktop bound a log-and-drop no-op, so completing a habit on the desktop wrote
 * the occurrence but never the time it took. The Android use case cannot be reused - it is Hilt- and
 * app-module-only - so the rules are mirrored here rather than duplicated by accident:
 *
 * 1. only recurring tasks are habits, so only they auto-track;
 * 2. the stored dimension id is resolved through [DimensionTaxonomyCatalog], and a task with no
 *    canonical dimension is skipped (an entry needs a dimension, not a free-text label);
 * 3. `auto_track_habit_time_global` must be on, and `auto_track_dimension_<id>` may switch the
 *    dimension off; a missing per-dimension key inherits the global answer, which is what makes the
 *    toggle behave as "on unless I said otherwise";
 * 4. the entry runs from `completedAt - duration` (15 minutes when the caller knows no duration) to
 *    `completedAt`;
 * 5. failure never fails the completion: the habit is already recorded by the time this runs.
 *
 * One deliberate difference: a coroutine cancellation is rethrown instead of swallowed, so a cancelled
 * completion stops here as it does everywhere else on this platform.
 */
internal class DesktopHabitTimeEntryRecorder(
    private val timeEntries: TimeEntryRepository,
    private val appSettings: AppSettingsRepository,
) : HabitTimeEntryRecorder {
    private val logger = AppLogger.current

    override suspend operator fun invoke(
        task: Task,
        actualCompletedAt: LocalDateTime?,
        actualDurationMinutes: Int?,
    ) {
        logger.i(
            "DesktopHabitTimeEntryRecorder.invoke",
            "Habit auto-track evaluation started",
            mapOf(
                "taskId" to task.id,
                "recurrenceEnabled" to task.recurrenceEnabled,
                "actualCompletedAtProvided" to (actualCompletedAt != null),
                "actualDurationProvided" to (actualDurationMinutes != null),
            ),
        )
        try {
            if (!task.recurrenceEnabled) {
                logger.d(
                    "DesktopHabitTimeEntryRecorder.invoke",
                    "Skipping auto-track for non-recurring task",
                    mapOf("taskId" to task.id),
                )
                return
            }
            val resolvedDimensionId = task.dimensionId?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.id }
            if (resolvedDimensionId.isNullOrBlank()) {
                logger.w(
                    "DesktopHabitTimeEntryRecorder.invoke",
                    "Skipping auto-track - canonical dimension id missing",
                    mapOf(
                        "taskId" to task.id,
                        "lifeIntentionCategory" to (task.lifeIntentionCategory ?: "none"),
                    ),
                )
                return
            }
            val resolvedDimensionLabel = DimensionTaxonomyCatalog.fromCanonicalId(resolvedDimensionId)?.fallbackLabel
                ?: resolvedDimensionId

            val settings = appSettings.getAllSettings().first()
            val globalEnabled = settings[GLOBAL_KEY]?.toBoolean() ?: false
            logger.d(
                "DesktopHabitTimeEntryRecorder.invoke",
                "Resolved auto-track settings",
                mapOf(
                    "globalEnabled" to globalEnabled,
                    "settingsCount" to settings.size,
                ),
            )
            if (!globalEnabled) {
                logger.d(
                    "DesktopHabitTimeEntryRecorder.invoke",
                    "Auto-tracking disabled globally",
                    mapOf("dimensionId" to resolvedDimensionId),
                )
                return
            }
            val dimensionEnabled = settings["$DIMENSION_KEY_PREFIX$resolvedDimensionId"]?.toBoolean() ?: globalEnabled
            if (!dimensionEnabled) {
                logger.d(
                    "DesktopHabitTimeEntryRecorder.invoke",
                    "Auto-tracking disabled for dimension",
                    mapOf("dimensionId" to resolvedDimensionId),
                )
                return
            }

            val completedAt = actualCompletedAt ?: LocalDateTime.now()
            val durationMins = actualDurationMinutes ?: DEFAULT_DURATION_MINUTES
            val startTime = completedAt.minusMinutes(durationMins.toLong())

            timeEntries.createTimeEntry(
                TimeEntryInput(
                    lifeIntentionCategory = resolvedDimensionLabel,
                    dimensionId = resolvedDimensionId,
                    taskId = task.id,
                    startedAt = startTime,
                    endedAt = completedAt,
                    focusRating = null,
                    focusNote = AUTO_TRACK_NOTE,
                ),
            )

            logger.i(
                "DesktopHabitTimeEntryRecorder.invoke",
                "Auto-created time entry for habit",
                mapOf(
                    "taskId" to task.id,
                    "dimensionId" to resolvedDimensionId,
                    "durationMins" to durationMins,
                    "startTime" to startTime.toString(),
                    "endTime" to completedAt.toString(),
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.e(
                "DesktopHabitTimeEntryRecorder.invoke",
                "Failed to auto-create time entry",
                error,
                mapOf("taskId" to task.id),
            )
        }
    }

    private companion object {
        const val GLOBAL_KEY = "auto_track_habit_time_global"
        const val DIMENSION_KEY_PREFIX = "auto_track_dimension_"
        const val DEFAULT_DURATION_MINUTES = 15
        const val AUTO_TRACK_NOTE = "Auto-tracked from habit completion"
    }
}
