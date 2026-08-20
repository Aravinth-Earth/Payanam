//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDateTime

/**
 * Core Task model for Payanam.
 * 
 * This is the domain model used throughout the app.
 * Mirrors the TypeScript Task interface from archive-v0.0.2/src/core/types/index.ts
 */
data class Task(
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Description. */
    val description: String? = null,
    /** Status. */
    val status: String = "pending", // pending | completed | archived | skipped | missed
    /** Due date. */
    val dueDate: LocalDateTime? = null,
    /** Created at. */
    val createdAt: LocalDateTime,
    /** Updated at. */
    val updatedAt: LocalDateTime,
    /** Completed at. */
    val completedAt: LocalDateTime? = null,
    /** Archived at. */
    val archivedAt: LocalDateTime? = null,
    
    // Recurrence
    /** Recurrence enabled. */
    val recurrenceEnabled: Boolean = false,
    /** Recurrence rule. */
    val recurrenceRule: String? = null,
    
    // Elegant scoring parameters
    /** Duration minutes. */
    val durationMinutes: Int = 10,
    /** Impact level. */
    val impactLevel: String = "Moderate Impact",
    /** Goal alignment. */
    val goalAlignment: String = "Moderate Alignment",
    /** Energy level. */
    val energyLevel: String = "Moderate",
    /** Control level. */
    val controlLevel: String = "Office/Colleagues Dependent",
    /** Life intention category. */
    val lifeIntentionCategory: String = "Career & Work",
    
    // POC Priority List fields
    /** Explicit urgency. */
    val explicitUrgency: Double? = null, // 0..1
    /** Focus required. */
    val focusRequired: Double? = null, // 0..1
    /** Recurrence strategy. */
    val recurrenceStrategy: String? = null, // planned | actual; DEPRECATED in v17
    /** Blocked reason. */
    val blockedReason: String? = null,
    /** Completion rate. */
    val completionRate: Double? = null, // 0..1
    /** External dependency. */
    val externalDependency: String? = null,

    // Notification settings
    /** Notification mode. */
    val notificationMode: String? = "auto", // auto | custom | off
    /** Custom notification minutes. */
    val customNotificationMinutes: Int? = null,

    // Calculated score
    /** Task score. */
    val taskScore: Double? = null,

    // Recurrence redesign - score roll-up (Inc 4b: decay currentScore removed)
    /** Last occurrence date. */
    val lastOccurrenceDate: LocalDateTime? = null,  // Date of last completion/skip
    /** Day boundary hour. */
    val dayBoundaryHour: Int = 0,             // DEPRECATED in v17; kept for RecurrenceManager compat
    /** Dimension id. */
    val dimensionId: String? = null
) {
    /**
     * Computed frequency from recurrenceRule string.
     * Falls back to DAILY for non-recurring or unparseable rules.
     */
    val frequency: Frequency
        /** Get. */
        get() = if (recurrenceEnabled) Frequency.parse(recurrenceRule) else Frequency.DAILY
}

/**
 * Input model for creating/updating tasks.
 * All fields except title are optional.
 */
data class TaskInput(
    /** Title. */
    val title: String,
    /** Description. */
    val description: String? = null,
    /** Status. */
    val status: String? = null,
    /** Due date. */
    val dueDate: LocalDateTime? = null,
    /** Archived at. */
    val archivedAt: LocalDateTime? = null,
    /** Recurrence enabled. */
    val recurrenceEnabled: Boolean? = null,
    /** Recurrence rule. */
    val recurrenceRule: String? = null,
    /** Duration minutes. */
    val durationMinutes: Int? = null,
    /** Impact level. */
    val impactLevel: String? = null,
    /** Goal alignment. */
    val goalAlignment: String? = null,
    /** Energy level. */
    val energyLevel: String? = null,
    /** Control level. */
    val controlLevel: String? = null,
    /** Life intention category. */
    val lifeIntentionCategory: String? = null,
    /** Explicit urgency. */
    val explicitUrgency: Double? = null,
    /** Focus required. */
    val focusRequired: Double? = null,
    /** Recurrence strategy. */
    val recurrenceStrategy: String? = null, // DEPRECATED in v17
    /** Blocked reason. */
    val blockedReason: String? = null,
    /** Completion rate. */
    val completionRate: Double? = null,
    /** External dependency. */
    val externalDependency: String? = null,
    /** Notification mode. */
    val notificationMode: String? = null,
    /** Custom notification minutes. */
    val customNotificationMinutes: Int? = null,
    /** Dimension id. */
    val dimensionId: String? = null
)
