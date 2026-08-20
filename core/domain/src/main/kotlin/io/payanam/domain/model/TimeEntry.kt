//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDateTime

/**
 * TimeEntry model for time tracking.
 * 
 * Core tracking unit - when endedAt is null, this is the active tracking session.
 */
data class TimeEntry(
    /** Id. */
    val id: String,
    /** Life intention category. */
    val lifeIntentionCategory: String,
    /** Task id. */
    val taskId: String? = null, // Optional - can track dimension-only
    /** Started at. */
    val startedAt: LocalDateTime,
    /** Ended at. */
    val endedAt: LocalDateTime? = null, // null = currently active
    /** Focus rating. */
    val focusRating: Double? = null,
    /** Focus note. */
    val focusNote: String? = null,
    /** Focus rated at. */
    val focusRatedAt: LocalDateTime? = null,
    /** Created at. */
    val createdAt: LocalDateTime,
    /** Updated at. */
    val updatedAt: LocalDateTime,
    /** Dimension id. */
    val dimensionId: String? = null
) {
    /**
     * Whether this entry represents an active tracking session.
     */
    val isActive: Boolean
        /** Get. */
        get() = endedAt == null
    
    /**
     * Duration in minutes. For active entries, calculates from startedAt to now.
     */
    fun durationMinutes(now: LocalDateTime = LocalDateTime.now()): Long {
        /** End. */
        val end = endedAt ?: now
        return java.time.Duration.between(startedAt, end).toMinutes()
    }
}

/**
 * TimeEntryInput.
 */
data class TimeEntryInput(
    /** Life intention category. */
    val lifeIntentionCategory: String,
    /** Task id. */
    val taskId: String? = null,
    /** Started at. */
    val startedAt: LocalDateTime,
    /** Ended at. */
    val endedAt: LocalDateTime? = null,
    /** Focus rating. */
    val focusRating: Double? = null,
    /** Focus note. */
    val focusNote: String? = null,
    /** Focus rated at. */
    val focusRatedAt: LocalDateTime? = null,
    /** Dimension id. */
    val dimensionId: String? = null
)
