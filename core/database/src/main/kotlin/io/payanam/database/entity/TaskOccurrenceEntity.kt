//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for TaskOccurrence table.
 */
@Entity(
    tableName = "task_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId"), Index("dueDate")],
)
data class TaskOccurrenceEntity(
    @PrimaryKey
    val id: String,
    val taskId: String,
    val dueDate: String,
    val completedAt: String? = null,
    val actualCompletedAt: String? = null,
    val actualDurationMinutes: Int? = null,
    val status: String,
    val statusReason: String? = null,
    val createdAt: String,
    val completionRate: Double? = null,
    val note: String? = null,
)

/**
 * Room entity for TaskReschedule table.
 */
@Entity(
    tableName = "task_reschedules",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class TaskRescheduleEntity(
    @PrimaryKey
    val id: String,
    val taskId: String,
    val previousDueDate: String,
    val newDueDate: String,
    val rescheduledAt: String,
    val wasOverdue: Int,
)
