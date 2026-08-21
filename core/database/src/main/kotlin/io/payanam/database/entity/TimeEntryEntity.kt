//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for timeEntry table.
 */
@Entity(
    tableName = "time_entries",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_batch_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("taskId"),
        Index("lifeIntentionCategory"),
        Index("dimension_id"),
        Index("day_key"),
        Index("import_batch_id"),
        Index(value = ["import_source", "import_id"]),
        Index("startedAt"),
        Index("endedAt"),
    ],
)
/**
 * Holds the time entry entity.
 */
data class TimeEntryEntity(
    @PrimaryKey
    val id: String,
    val lifeIntentionCategory: String,
    @ColumnInfo(name = "dimension_id")
    val dimensionId: String? = null,
    @ColumnInfo(name = "day_key")
    val dayKey: String? = null,
    val taskId: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val focusRating: Double? = null,
    val focusNote: String? = null,
    val focusRatedAt: String? = null,
    @ColumnInfo(name = "import_source")
    val importSource: String? = null,
    @ColumnInfo(name = "import_id")
    val importId: String? = null,
    @ColumnInfo(name = "imported_at")
    val importedAt: String? = null,
    @ColumnInfo(name = "import_batch_id")
    val importBatchId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)
