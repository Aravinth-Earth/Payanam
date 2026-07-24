//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDateTime

/**
 * TaskOccurrence model for tracking recurring task completions.
 */
data class TaskOccurrence(
    val id: String,
    val taskId: String,
    val occurrenceDate: String,       // ISO date string for the occurrence
    val status: String,               // completed | skipped | missed | pending
    val statusNote: String? = null,   // Optional note for the status change
    val statusReason: String? = null, // Reason enum value for skip/miss (e.g., "NO_TIME", "LOW_ENERGY")
    val completedAt: String? = null,  // Timestamp when completed
    val actualCompletedAt: LocalDateTime? = null,  // Actual completion timestamp
    val actualDurationMinutes: Int? = null,        // Actual time spent in minutes
    val skippedAt: String? = null,    // Timestamp when skipped/missed
    val dueDate: LocalDateTime? = null,  // Legacy: original due date
    val createdAt: LocalDateTime? = null,
    val completionRate: Double? = null,  // Legacy: 0..1 at time of occurrence
    val note: String? = null,            // Legacy: Optional reason for skip/miss
)

/**
 * TaskReschedule model for tracking task reschedule history.
 */
data class TaskReschedule(
    val id: String,
    val taskId: String,
    val previousDueDate: LocalDateTime,
    val newDueDate: LocalDateTime,
    val rescheduledAt: LocalDateTime,
    val wasOverdue: Boolean
)
