//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "time_entry_tags",
    primaryKeys = ["time_entry_id", "tag_id"],
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = TimeEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["time_entry_id"],
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
    indices = [Index("time_entry_id"), Index("tag_id")],
)
/**
 * TimeEntryTagEntity.
 */
data class TimeEntryTagEntity(
    @ColumnInfo(name = "time_entry_id")
    /** Time entry id. */
    val timeEntryId: String,
    @ColumnInfo(name = "tag_id")
    /** Tag id. */
    val tagId: String,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
)
