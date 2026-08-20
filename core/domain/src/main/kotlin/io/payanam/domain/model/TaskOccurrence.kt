//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDateTime

/**
 * TaskOccurrence model for tracking recurring task completions.
 */
data class TaskOccurrence(
    /** Id. */
    val id: String,
    /** Task id. */
    val taskId: String,
    /** Occurrence date. */
    val occurrenceDate: String,       // ISO date string for the occurrence
    /** Status. */
    val status: String,               // completed | skipped | missed | pending
    /** Status note. */
    val statusNote: String? = null,   // Optional note for the status change
    /** Status reason. */
    val statusReason: String? = null, // Reason enum value for skip/miss (e.g., "NO_TIME", "LOW_ENERGY")
    /** Completed at. */
    val completedAt: String? = null,  // Timestamp when completed
    /** Actual completed at. */
    val actualCompletedAt: LocalDateTime? = null,  // Actual completion timestamp
    /** Actual duration minutes. */
    val actualDurationMinutes: Int? = null,        // Actual time spent in minutes
    /** Skipped at. */
    val skippedAt: String? = null,    // Timestamp when skipped/missed
    /** Due date. */
    val dueDate: LocalDateTime? = null,  // Legacy: original due date
    /** Created at. */
    val createdAt: LocalDateTime? = null,
    /** Completion rate. */
    val completionRate: Double? = null,  // Legacy: 0..1 at time of occurrence
    /** Note. */
    val note: String? = null,            // Legacy: Optional reason for skip/miss
)

/**
 * TaskReschedule model for tracking task reschedule history.
 */
data class TaskReschedule(
    /** Id. */
    val id: String,
    /** Task id. */
    val taskId: String,
    /** Previous due date. */
    val previousDueDate: LocalDateTime,
    /** New due date. */
    val newDueDate: LocalDateTime,
    /** Rescheduled at. */
    val rescheduledAt: LocalDateTime,
    /** Was overdue. */
    val wasOverdue: Boolean
)
