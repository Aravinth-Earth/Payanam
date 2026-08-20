//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Day Journal Entry - one per day for journaling.
 */
data class DayJournalEntry(
    /** Id. */
    val id: String,
    /** Entry date. */
    val entryDate: String, // YYYY-MM-DD in local timezone (ISO format)
    /** Created at. */
    val createdAt: String,
    /** Updated at. */
    val updatedAt: String
)

/**
 * Journal response to a prompt.
 */
data class DayJournalResponse(
    /** Id. */
    val id: String,
    /** Entry id. */
    val entryId: String,
    /** Scope. */
    val scope: String, // "overall" or "dimension"
    /** Dimension key. */
    val dimensionKey: String? = null,
    /** Prompt key. */
    val promptKey: String,
    /** Response text. */
    val responseText: String
)

/**
 * JournalPromptScope.
 */
enum class JournalPromptScope {
    /** Overall. */
    OVERALL,
    /** Dimension. */
    DIMENSION
}

/**
 * DayJournalResponseInput.
 */
data class DayJournalResponseInput(
    /** Scope. */
    val scope: JournalPromptScope,
    /** Dimension key. */
    val dimensionKey: String? = null,
    /** Prompt key. */
    val promptKey: String,
    /** Response text. */
    val responseText: String? = null
)
