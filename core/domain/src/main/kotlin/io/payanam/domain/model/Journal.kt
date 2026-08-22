//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Day journal entry - one per day for journaling.
 */
data class DayJournalEntry(
    val id: String,
    val entryDate: String, // YYYY-MM-DD in local timezone (ISO format)
    val createdAt: String,
    val updatedAt: String
)

/**
 * Journal response to a prompt.
 */
data class DayJournalResponse(
    val id: String,
    val entryId: String,
    val scope: String, // "overall" or "dimension"
    val dimensionKey: String? = null,
    val promptKey: String,
    val responseText: String
)
/**
 * The two prompt scopes a journal question can target: the [OVERALL] day
 * summary or a specific [DIMENSION] of life.
 */
enum class JournalPromptScope {
    OVERALL,
    DIMENSION
}
/**
 * Editable payload for a journal prompt response (excludes id + entry link).
 */
data class DayJournalResponseInput(
    val scope: JournalPromptScope,
    val dimensionKey: String? = null,
    val promptKey: String,
    val responseText: String? = null
)
