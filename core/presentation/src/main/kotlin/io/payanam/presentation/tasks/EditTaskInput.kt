//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import java.time.LocalDateTime

/**
 * Edit-task form fields collected by the edit screen, converted to a
 * TaskInput on save.
 *
 * Lives in the state layer because the ViewModel owns the form, not because the screen reads it: the screen
 * edits a copy and hands the finished value back, so the shape belongs with the state it describes.
 */
data class EditTaskInput(
    val title: String,
    val description: String?,
    val dimensionId: String,
    val dimensionLabel: String,
    val dueDate: LocalDateTime?,
    val impactLevel: String,
    val goalAlignment: String,
    val energyLevel: String,
    val controlLevel: String,
    val durationMinutes: Int,
    val recurrenceEnabled: Boolean,
    val recurrenceRule: String?,
    val notificationMode: String,
    val customNotificationMinutes: Int?,
    val explicitUrgency: Double?,
    val focusRequired: Double?,
    val blockedReason: String?,
    val externalDependency: String?,
    val tags: List<String> = emptyList(),
)
