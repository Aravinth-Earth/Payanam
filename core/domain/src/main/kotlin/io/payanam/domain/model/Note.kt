//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDateTime

/**
 * Note model for general notes/ideas.
 */
data class Note(
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Details. */
    val details: String? = null,
    /** Life intention category. */
    val lifeIntentionCategory: String,
    /** Created at. */
    val createdAt: LocalDateTime,
    /** Updated at. */
    val updatedAt: LocalDateTime,
    /** Dimension id. */
    val dimensionId: String? = null
)

/**
 * NoteInput.
 */
data class NoteInput(
    /** Title. */
    val title: String,
    /** Details. */
    val details: String? = null,
    /** Life intention category. */
    val lifeIntentionCategory: String,
    /** Dimension id. */
    val dimensionId: String? = null
)
