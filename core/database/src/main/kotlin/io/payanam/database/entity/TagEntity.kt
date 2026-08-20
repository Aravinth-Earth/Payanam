//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tags",
    indices = [
        /** Index. */
        Index(value = ["normalized_name"], unique = true),
        /** Index. */
        Index("name"),
        /** Index. */
        Index("last_used_at"),
    ],
)
/**
 * TagEntity.
 */
data class TagEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Name. */
    val name: String,
    @ColumnInfo(name = "normalized_name")
    /** Normalized name. */
    val normalizedName: String,
    @ColumnInfo(name = "usage_count")
    /** Usage count. */
    val usageCount: Int = 0,
    @ColumnInfo(name = "last_used_at")
    /** Last used at. */
    val lastUsedAt: String? = null,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    /** Updated at. */
    val updatedAt: String,
)
