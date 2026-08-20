//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "task_tags",
    primaryKeys = ["task_id", "tag_id"],
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        /** Foreign key. */
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("task_id"), Index("tag_id")],
)
/**
 * TaskTagEntity.
 */
data class TaskTagEntity(
    @ColumnInfo(name = "task_id")
    /** Task id. */
    val taskId: String,
    @ColumnInfo(name = "tag_id")
    /** Tag id. */
    val tagId: String,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
)
