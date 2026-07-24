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
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("task_id"), Index("tag_id")],
)
data class TaskTagEntity(
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "tag_id")
    val tagId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
)
