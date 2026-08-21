//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDateTime

/**
 * Note model for general notes/ideas.
 */
data class Note(
    val id: String,
    val title: String,
    val details: String? = null,
    val lifeIntentionCategory: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val dimensionId: String? = null
)
/**
 * Holds the note input.
 */
data class NoteInput(
    val title: String,
    val details: String? = null,
    val lifeIntentionCategory: String,
    val dimensionId: String? = null
)
