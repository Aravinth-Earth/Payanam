//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.usecase

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.TimeEntryRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for auto-creating time entries when habits are completed.
 * Extracted from TasksViewModel to maintain file size limits.
 */
@Singleton
/**
 * CreateTimeEntryForHabitUseCase.
 */
class CreateTimeEntryForHabitUseCase @Inject constructor(
    private val timeEntryRepository: TimeEntryRepository,
    private val appSettingsRepository: AppSettingsRepository,
) {
    private val logger = UnifiedLogger.getInstance()

    /**
     * Auto-create time entry for habit completion if auto-tracking is enabled.
     *
     * @param task The task (habit) that was completed
     * @param actualCompletedAt The actual completion time
     * @param actualDurationMinutes The actual duration in minutes
     */
    suspend operator fun invoke(
        /** Task. */
        task: Task,
        actualCompletedAt: LocalDateTime?,
        actualDurationMinutes: Int?,
    ) {
        logger.i(
            "CreateTimeEntryForHabitUseCase.invoke",
            "Habit auto-track evaluation started",
            /** Map of. */
            mapOf(
                "taskId" to task.id,
                "recurrenceEnabled" to task.recurrenceEnabled,
                "actualCompletedAtProvided" to (actualCompletedAt != null),
                "actualDurationProvided" to (actualDurationMinutes != null),
            ),
        )
        try {
            // Only auto-track for recurring tasks (habits)
            /** If. */
            if (!task.recurrenceEnabled) {
                logger.d(
                    "CreateTimeEntryForHabitUseCase.invoke",
                    "Skipping auto-track for non-recurring task",
                    /** Map of. */
                    mapOf("taskId" to task.id),
                )
                /** Return. */
                return
            }

            /** Resolved dimension id. */
            val resolvedDimensionId = task.dimensionId
                ?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.id }
            /** If. */
            if (resolvedDimensionId.isNullOrBlank()) {
                logger.w(
                    "CreateTimeEntryForHabitUseCase.invoke",
                    "Skipping auto-track - canonical dimension id missing",
                    /** Map of. */
                    mapOf(
                        "taskId" to task.id,
                        "lifeIntentionCategory" to (task.lifeIntentionCategory ?: "none"),
                    ),
                )
                logger.d(
                    "CreateTimeEntryForHabitUseCase.invoke",
                    "Skipping auto-track - task has no dimension",
                    /** Map of. */
                    mapOf("taskId" to task.id),
                )
                /** Return. */
                return
            }
            /** Resolved dimension label. */
            val resolvedDimensionLabel = DimensionTaxonomyCatalog.fromCanonicalId(resolvedDimensionId)?.fallbackLabel
                ?: resolvedDimensionId

            // Check if auto-tracking is enabled for this dimension
            /** Settings. */
            val settings = appSettingsRepository.getAllSettings().first()
            /** Global enabled. */
            val globalEnabled = settings["auto_track_habit_time_global"]?.toBoolean() ?: false
            logger.d(
                "CreateTimeEntryForHabitUseCase.invoke",
                "Resolved auto-track settings",
                /** Map of. */
                mapOf(
                    "globalEnabled" to globalEnabled,
                    "settingsCount" to settings.size,
                ),
            )
            /** If. */
            if (!globalEnabled) {
                logger.d(
                    "CreateTimeEntryForHabitUseCase.invoke",
                    "Auto-tracking disabled globally",
                    /** Map of. */
                    mapOf("dimensionId" to resolvedDimensionId),
                )
                /** Return. */
                return
            }

            /** Dimension enabled. */
            val dimensionEnabled = settings["auto_track_dimension_$resolvedDimensionId"]?.toBoolean() ?: globalEnabled
            /** If. */
            if (!dimensionEnabled) {
                logger.d(
                    "CreateTimeEntryForHabitUseCase.invoke",
                    "Auto-tracking disabled for dimension",
                    /** Map of. */
                    mapOf("dimensionId" to resolvedDimensionId),
                )
                /** Return. */
                return
            }

            // Calculate time bounds
            /** Completed at. */
            val completedAt = actualCompletedAt ?: LocalDateTime.now()
            /** Duration mins. */
            val durationMins = actualDurationMinutes ?: 15 // default 15min
            /** Start time. */
            val startTime = completedAt.minusMinutes(durationMins.toLong())

            // Create time entry
            /** Time entry input. */
            val timeEntryInput = TimeEntryInput(
                lifeIntentionCategory = resolvedDimensionLabel,
                dimensionId = resolvedDimensionId,
                taskId = task.id,
                startedAt = startTime,
                endedAt = completedAt,
                focusRating = null,
                focusNote = "Auto-tracked from habit completion",
            )

            timeEntryRepository.createTimeEntry(timeEntryInput)

            logger.i(
                "CreateTimeEntryForHabitUseCase.invoke",
                "Auto-created time entry for habit",
                /** Map of. */
                mapOf(
                    "taskId" to task.id,
                    "dimensionId" to resolvedDimensionId,
                    "durationMins" to durationMins,
                    "startTime" to startTime.toString(),
                    "endTime" to completedAt.toString(),
                ),
            )
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "CreateTimeEntryForHabitUseCase.invoke",
                "Failed to auto-create time entry",
                /** E. */
                e,
                /** Map of. */
                mapOf(
                    "taskId" to task.id,
                ),
            )
            // Don't fail habit completion if time entry creation fails
        }
    }
}
