//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.TaskEntity
import io.payanam.domain.model.Task

/**
 * Mapper functions between taskEntity (Room) and task (Domain).
 */
object TaskMapper {
    /**
     * Converts a Room [TaskEntity] into the domain [Task] model, parsing the
     * persisted ISO date-time strings (including the decay-score legacy field)
     * back into [PersistedDateTime] values.
     */
    fun TaskEntity.toDomain(): Task =
        Task(
            id = id,
            title = title,
            description = description,
            status = status,
            dueDate = PersistedDateTime.parseOrNull(dueDate),
            createdAt = PersistedDateTime.parse(createdAt),
            updatedAt = PersistedDateTime.parse(updatedAt),
            completedAt = PersistedDateTime.parseOrNull(completedAt),
            archivedAt = PersistedDateTime.parseOrNull(archivedAt),
            recurrenceEnabled = recurrenceEnabled == 1,
            recurrenceRule = recurrenceRule,
            durationMinutes = durationMinutes,
            impactLevel = impactLevel,
            goalAlignment = goalAlignment,
            energyLevel = energyLevel,
            controlLevel = controlLevel,
            dimensionId = dimensionId,
            lifeIntentionCategory = lifeIntentionCategory,
            explicitUrgency = explicitUrgency,
            focusRequired = focusRequired,
            recurrenceStrategy = recurrenceStrategy,
            blockedReason = blockedReason,
            completionRate = completionRate,
            externalDependency = externalDependency,
            notificationMode = notificationMode,
            customNotificationMinutes = customNotificationMinutes,
            taskScore = taskScore,
            lastOccurrenceDate = PersistedDateTime.parseOrDateStart(lastOccurrenceDate),
        )
    /**
     * Converts a domain [Task] into a Room [TaskEntity], serializing its
     * date-times to ISO strings and preserving the decay-score legacy column.
     */
    fun Task.toEntity(): TaskEntity =
        TaskEntity(
            id = id,
            title = title,
            description = description,
            status = status,
            dueDate = dueDate?.let(PersistedDateTime::format),
            createdAt = PersistedDateTime.format(createdAt),
            updatedAt = PersistedDateTime.format(updatedAt),
            completedAt = completedAt?.let(PersistedDateTime::format),
            archivedAt = archivedAt?.let(PersistedDateTime::format),
            recurrenceEnabled = if (recurrenceEnabled) 1 else 0,
            recurrenceRule = recurrenceRule,
            durationMinutes = durationMinutes,
            impactLevel = impactLevel,
            goalAlignment = goalAlignment,
            energyLevel = energyLevel,
            controlLevel = controlLevel,
            dimensionId = dimensionId,
            lifeIntentionCategory = lifeIntentionCategory,
            dayKey = dueDate?.let(PersistedDateTime::dayKey),
            explicitUrgency = explicitUrgency,
            focusRequired = focusRequired,
            blockedReason = blockedReason,
            completionRate = completionRate,
            externalDependency = externalDependency,
            notificationMode = notificationMode,
            customNotificationMinutes = customNotificationMinutes,
            taskScore = taskScore,
            lastOccurrenceDate = lastOccurrenceDate?.let(PersistedDateTime::format),
        )
}
