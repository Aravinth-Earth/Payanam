//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for taskOccurrence table.
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
    indices = [
        Index("taskId"),
        Index("dueDate"),
        // Day-level duplicate guard (migration 20→21): one row per (task, day).
        // The code paths (toggle/recordOccurrence) check-then-update, so this
        // index is the DB-level backstop — Room requires it declared here to
        // match the migration's CREATE UNIQUE INDEX.
        Index(value = ["taskId", "dueDate"], unique = true),
    ],
)
/**
 * Holds the task occurrence entity.
 */
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
 * Room entity for taskReschedule table.
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
/**
 * Holds the task reschedule entity.
 */
data class TaskRescheduleEntity(
    @PrimaryKey
    val id: String,
    val taskId: String,
    val previousDueDate: String,
    val newDueDate: String,
    val rescheduledAt: String,
    val wasOverdue: Int,
)
