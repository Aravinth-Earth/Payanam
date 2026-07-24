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
    val id: String,
    val lifeIntentionCategory: String,
    val taskId: String? = null, // Optional - can track dimension-only
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime? = null, // null = currently active
    val focusRating: Double? = null,
    val focusNote: String? = null,
    val focusRatedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val dimensionId: String? = null
) {
    /**
     * Whether this entry represents an active tracking session.
     */
    val isActive: Boolean
        get() = endedAt == null
    
    /**
     * Duration in minutes. For active entries, calculates from startedAt to now.
     */
    fun durationMinutes(now: LocalDateTime = LocalDateTime.now()): Long {
        val end = endedAt ?: now
        return java.time.Duration.between(startedAt, end).toMinutes()
    }
}

data class TimeEntryInput(
    val lifeIntentionCategory: String,
    val taskId: String? = null,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime? = null,
    val focusRating: Double? = null,
    val focusNote: String? = null,
    val focusRatedAt: LocalDateTime? = null,
    val dimensionId: String? = null
)
