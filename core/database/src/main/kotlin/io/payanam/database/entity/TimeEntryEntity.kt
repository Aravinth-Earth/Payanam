//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for TimeEntry table.
 */
@Entity(
    tableName = "time_entries",
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        /** Foreign key. */
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        /** Foreign key. */
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_batch_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        /** Index. */
        Index("taskId"),
        /** Index. */
        Index("lifeIntentionCategory"),
        /** Index. */
        Index("dimension_id"),
        /** Index. */
        Index("day_key"),
        /** Index. */
        Index("import_batch_id"),
        /** Index. */
        Index(value = ["import_source", "import_id"]),
        /** Index. */
        Index("startedAt"),
        /** Index. */
        Index("endedAt"),
    ],
)
/**
 * TimeEntryEntity.
 */
data class TimeEntryEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Life intention category. */
    val lifeIntentionCategory: String,
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String? = null,
    @ColumnInfo(name = "day_key")
    /** Day key. */
    val dayKey: String? = null,
    /** Task id. */
    val taskId: String? = null,
    /** Started at. */
    val startedAt: String,
    /** Ended at. */
    val endedAt: String? = null,
    /** Focus rating. */
    val focusRating: Double? = null,
    /** Focus note. */
    val focusNote: String? = null,
    /** Focus rated at. */
    val focusRatedAt: String? = null,
    @ColumnInfo(name = "import_source")
    /** Import source. */
    val importSource: String? = null,
    @ColumnInfo(name = "import_id")
    /** Import id. */
    val importId: String? = null,
    @ColumnInfo(name = "imported_at")
    /** Imported at. */
    val importedAt: String? = null,
    @ColumnInfo(name = "import_batch_id")
    /** Import batch id. */
    val importBatchId: String? = null,
    /** Created at. */
    val createdAt: String,
    /** Updated at. */
    val updatedAt: String,
)
