//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.notes

import java.time.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
/**
 * One desktop note record (title, details, dimension, tags) for sync.
 */
data class DesktopNoteRecord(
    val id: String,
    val title: String,
    val details: String? = null,
    val dimensionId: String = DesktopNoteContracts.DEFAULT_DIMENSION_ID,
    val dimensionLabel: String = DesktopNoteContracts.DEFAULT_DIMENSION_LABEL,
    val tags: List<String> = emptyList(),
    val createdAtIso: String,
    val updatedAtIso: String,
)

@Serializable
/**
 * Serializable notes state holding every note for the desktop<->mobile sync.
 */
data class DesktopNotesSnapshot(
    val schemaVersion: Int = DesktopNoteContracts.SCHEMA_VERSION,
    val notes: List<DesktopNoteRecord> = emptyList(),
)
object DesktopNoteContracts {
    const val SCHEMA_VERSION = 1
    const val DEFAULT_DIMENSION_ID = "dim_work_livelihood"
    const val DEFAULT_DIMENSION_LABEL = "Work & Livelihood"
    /**
     * Returns an empty [DesktopNotesSnapshot] (no notes).
     */
    fun emptySnapshot(): DesktopNotesSnapshot = DesktopNotesSnapshot()
    /**
     * Builds a [DesktopNoteRecord], trimming fields and falling back to defaults for
     * blank dimension/tags.
     */
    fun createRecord(
        id: String,
        title: String,
        details: String?,
        dimensionId: String?,
        dimensionLabel: String?,
        tags: List<String>,
        now: LocalDateTime,
    ): DesktopNoteRecord {
        return DesktopNoteRecord(
            id = id,
            title = title.trim(),
            details = details?.trim()?.takeIf { it.isNotEmpty() },
            dimensionId = dimensionId?.trim().orEmpty().ifBlank { DEFAULT_DIMENSION_ID },
            dimensionLabel = dimensionLabel?.trim().orEmpty().ifBlank { DEFAULT_DIMENSION_LABEL },
            tags = normalizeTags(tags),
            createdAtIso = now.toString(),
            updatedAtIso = now.toString(),
        )
    }
    /**
     * Returns a copy of [existing] with the editable fields replaced (keeping its id
     * + createdAt); trims and normalizes inputs.
     */
    fun updateRecord(
        existing: DesktopNoteRecord,
        title: String,
        details: String?,
        dimensionId: String?,
        dimensionLabel: String?,
        tags: List<String>,
        now: LocalDateTime,
    ): DesktopNoteRecord {
        return existing.copy(
            title = title.trim(),
            details = details?.trim()?.takeIf { it.isNotEmpty() },
            dimensionId = dimensionId?.trim().orEmpty().ifBlank { existing.dimensionId },
            dimensionLabel = dimensionLabel?.trim().orEmpty().ifBlank { existing.dimensionLabel },
            tags = normalizeTags(tags),
            updatedAtIso = now.toString(),
        )
    }

    private fun normalizeTags(tags: List<String>): List<String> =
        tags
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
}
