//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.TimeEntryEntity
import io.payanam.domain.model.TimeEntry

/**
 * Mapper functions between TimeEntryEntity (Room) and TimeEntry (Domain).
 */
object TimeEntryMapper {
    fun TimeEntryEntity.toDomain(): TimeEntry =
        TimeEntry(
            id = id,
            lifeIntentionCategory = lifeIntentionCategory,
            dimensionId = dimensionId,
            taskId = taskId,
            startedAt = PersistedDateTime.parse(startedAt),
            endedAt = PersistedDateTime.parseOrNull(endedAt),
            focusRating = focusRating,
            focusNote = focusNote,
            focusRatedAt = PersistedDateTime.parseOrNull(focusRatedAt),
            createdAt = PersistedDateTime.parse(createdAt),
            updatedAt = PersistedDateTime.parse(updatedAt),
        )

    fun TimeEntry.toEntity(): TimeEntryEntity =
        TimeEntryEntity(
            id = id,
            lifeIntentionCategory = lifeIntentionCategory,
            dimensionId = dimensionId,
            dayKey = PersistedDateTime.dayKey(startedAt),
            taskId = taskId,
            startedAt = PersistedDateTime.format(startedAt),
            endedAt = endedAt?.let(PersistedDateTime::format),
            focusRating = focusRating,
            focusNote = focusNote,
            focusRatedAt = focusRatedAt?.let(PersistedDateTime::format),
            createdAt = PersistedDateTime.format(createdAt),
            updatedAt = PersistedDateTime.format(updatedAt),
        )
}
