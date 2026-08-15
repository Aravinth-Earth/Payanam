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
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String = "pending", // pending | completed | archived | skipped | missed
    val dueDate: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val completedAt: LocalDateTime? = null,
    val archivedAt: LocalDateTime? = null,
    
    // Recurrence
    val recurrenceEnabled: Boolean = false,
    val recurrenceRule: String? = null,
    
    // Elegant scoring parameters
    val durationMinutes: Int = 10,
    val impactLevel: String = "Moderate Impact",
    val goalAlignment: String = "Moderate Alignment",
    val energyLevel: String = "Moderate",
    val controlLevel: String = "Office/Colleagues Dependent",
    val lifeIntentionCategory: String = "Career & Work",
    
    // POC Priority List fields
    val explicitUrgency: Double? = null, // 0..1
    val focusRequired: Double? = null, // 0..1
    val recurrenceStrategy: String? = null, // planned | actual; DEPRECATED in v17
    val blockedReason: String? = null,
    val completionRate: Double? = null, // 0..1
    val externalDependency: String? = null,

    // Notification settings
    val notificationMode: String? = "auto", // auto | custom | off
    val customNotificationMinutes: Int? = null,

    // Calculated score
    val taskScore: Double? = null,

    // Recurrence redesign - score roll-up (Inc 4b: decay currentScore removed)
    val lastOccurrenceDate: LocalDateTime? = null,  // Date of last completion/skip
    val dayBoundaryHour: Int = 0,             // DEPRECATED in v17; kept for RecurrenceManager compat
    val dimensionId: String? = null
) {
    /**
     * Computed frequency from recurrenceRule string.
     * Falls back to DAILY for non-recurring or unparseable rules.
     */
    val frequency: Frequency
        get() = if (recurrenceEnabled) Frequency.parse(recurrenceRule) else Frequency.DAILY
}

/**
 * Input model for creating/updating tasks.
 * All fields except title are optional.
 */
data class TaskInput(
    val title: String,
    val description: String? = null,
    val status: String? = null,
    val dueDate: LocalDateTime? = null,
    val archivedAt: LocalDateTime? = null,
    val recurrenceEnabled: Boolean? = null,
    val recurrenceRule: String? = null,
    val durationMinutes: Int? = null,
    val impactLevel: String? = null,
    val goalAlignment: String? = null,
    val energyLevel: String? = null,
    val controlLevel: String? = null,
    val lifeIntentionCategory: String? = null,
    val explicitUrgency: Double? = null,
    val focusRequired: Double? = null,
    val recurrenceStrategy: String? = null, // DEPRECATED in v17
    val blockedReason: String? = null,
    val completionRate: Double? = null,
    val externalDependency: String? = null,
    val notificationMode: String? = null,
    val customNotificationMinutes: Int? = null,
    val dimensionId: String? = null
)
