//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import io.payanam.database.entity.TagEntity
import io.payanam.domain.model.Tag
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * TagMapper.
 */
object TagMapper {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * Tag entity.
     */
    fun TagEntity.toDomain(): Tag =
        /** Tag. */
        Tag(
            id = id,
            name = name,
            normalizedName = normalizedName,
            usageCount = usageCount,
            lastUsedAt = lastUsedAt?.takeIf { it.isNotBlank() }?.let { parseDateTime(it) },
            createdAt = parseDateTime(createdAt),
            updatedAt = parseDateTime(updatedAt),
        )

    private fun parseDateTime(isoString: String): LocalDateTime {
        /** Normalized string. */
        val normalizedString =
            /** If. */
            if (isoString.endsWith("Z")) {
                isoString.dropLast(1)
            } else {
                /** Iso string. */
                isoString
            }
        return try {
            LocalDateTime.parse(normalizedString, formatter)
        } catch (_: Exception) {
            LocalDateTime.parse(normalizedString)
        }
    }
}
