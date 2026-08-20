//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "note_tags",
    primaryKeys = ["note_id", "tag_id"],
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
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
    indices = [Index("note_id"), Index("tag_id")],
)
/**
 * NoteTagEntity.
 */
data class NoteTagEntity(
    @ColumnInfo(name = "note_id")
    /** Note id. */
    val noteId: String,
    @ColumnInfo(name = "tag_id")
    /** Tag id. */
    val tagId: String,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
)
