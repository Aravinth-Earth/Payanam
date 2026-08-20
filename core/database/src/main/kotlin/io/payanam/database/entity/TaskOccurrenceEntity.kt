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
        /** Foreign key. */
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        /** Index. */
        Index("taskId"),
        /** Index. */
        Index("dueDate"),
        // Day-level duplicate guard (migration 20→21): one row per (task, day).
        // The code paths (toggle/recordOccurrence) check-then-update, so this
        // index is the DB-level backstop — Room requires it declared here to
        // match the migration's CREATE UNIQUE INDEX.
        /** Index. */
        Index(value = ["taskId", "dueDate"], unique = true),
    ],
)
/**
 * TaskOccurrenceEntity.
 */
data class TaskOccurrenceEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Task id. */
    val taskId: String,
    /** Due date. */
    val dueDate: String,
    /** Completed at. */
    val completedAt: String? = null,
    /** Actual completed at. */
    val actualCompletedAt: String? = null,
    /** Actual duration minutes. */
    val actualDurationMinutes: Int? = null,
    /** Status. */
    val status: String,
    /** Status reason. */
    val statusReason: String? = null,
    /** Created at. */
    val createdAt: String,
    /** Completion rate. */
    val completionRate: Double? = null,
    /** Note. */
    val note: String? = null,
)

/**
 * Room entity for TaskReschedule table.
 */
@Entity(
    tableName = "task_reschedules",
    foreignKeys = [
        /** Foreign key. */
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
 * TaskRescheduleEntity.
 */
data class TaskRescheduleEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Task id. */
    val taskId: String,
    /** Previous due date. */
    val previousDueDate: String,
    /** New due date. */
    val newDueDate: String,
    /** Rescheduled at. */
    val rescheduledAt: String,
    /** Was overdue. */
    val wasOverdue: Int,
)
