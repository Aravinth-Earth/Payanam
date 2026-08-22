//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.NoteEntity
import io.payanam.domain.model.Note

/**
 * Mapper functions between noteEntity (Room) and note (Domain).
 */
object NoteMapper {
    /**
     * Converts a Room [NoteEntity] into the domain [Note] model, parsing the
     * persisted ISO date-time strings back into [PersistedDateTime].
     */
    fun NoteEntity.toDomain(): Note =
        Note(
            id = id,
            title = title,
            details = details,
            lifeIntentionCategory = lifeIntentionCategory,
            dimensionId = dimensionId,
            createdAt = PersistedDateTime.parse(createdAt),
            updatedAt = PersistedDateTime.parse(updatedAt),
        )
    /**
     * Converts a domain [Note] into a Room [NoteEntity], serializing its
     * date-times to ISO strings for persistence.
     */
    fun Note.toEntity(): NoteEntity =
        NoteEntity(
            id = id,
            title = title,
            details = details,
            lifeIntentionCategory = lifeIntentionCategory,
            dimensionId = dimensionId,
            dayKey = PersistedDateTime.dayKey(createdAt),
            createdAt = PersistedDateTime.format(createdAt),
            updatedAt = PersistedDateTime.format(updatedAt),
        )
}
