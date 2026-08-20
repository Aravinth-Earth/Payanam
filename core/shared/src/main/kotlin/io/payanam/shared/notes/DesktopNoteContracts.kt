//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.notes

import java.time.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
/**
 * DesktopNoteRecord.

 */
data class DesktopNoteRecord(
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Details. */
    val details: String? = null,
    /** Dimension id. */
    val dimensionId: String = DesktopNoteContracts.DEFAULT_DIMENSION_ID,
    /** Dimension label. */
    val dimensionLabel: String = DesktopNoteContracts.DEFAULT_DIMENSION_LABEL,
    /** Tags. */
    val tags: List<String> = emptyList(),
    /** Created at iso. */
    val createdAtIso: String,
    /** Updated at iso. */
    val updatedAtIso: String,
)

@Serializable
/**
 * DesktopNotesSnapshot.

 */
data class DesktopNotesSnapshot(
    /** Schema version. */
    val schemaVersion: Int = DesktopNoteContracts.SCHEMA_VERSION,
    /** Notes. */
    val notes: List<DesktopNoteRecord> = emptyList(),
)

/**
 * DesktopNoteContracts.
 */
object DesktopNoteContracts {
    /** S c h e m a  v e r s i o n. */
    const val SCHEMA_VERSION = 1
    /** D e f a u l t  d i m e n s i o n  i d. */
    const val DEFAULT_DIMENSION_ID = "dim_work_livelihood"
    /** D e f a u l t  d i m e n s i o n  l a b e l. */
    const val DEFAULT_DIMENSION_LABEL = "Work & Livelihood"

    /**
     * Empty snapshot.
     */
    fun emptySnapshot(): DesktopNotesSnapshot = DesktopNotesSnapshot()

    /**
     * Create record.
     */
    fun createRecord(
        /** Id. */
        id: String,
        /** Title. */
        title: String,
        details: String?,
        dimensionId: String?,
        dimensionLabel: String?,
        tags: List<String>,
        /** Now. */
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
     * Update record.
     */
    fun updateRecord(
        /** Existing. */
        existing: DesktopNoteRecord,
        /** Title. */
        title: String,
        details: String?,
        dimensionId: String?,
        dimensionLabel: String?,
        tags: List<String>,
        /** Now. */
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
        /** Tags. */
        tags
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
}
