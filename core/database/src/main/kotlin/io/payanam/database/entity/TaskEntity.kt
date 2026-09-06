//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Task table.
 *
 * Schema matches the original SQLite schema from Capacitor version.
 */
@Entity(
    tableName = "tasks",
    foreignKeys = [
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
        Index("dimension_id"),
        Index("day_key"),
        Index("import_batch_id"),
        Index(value = ["import_source", "import_id"]),
    ],
)
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val description: String? = null,
    val status: String = "pending",
    val dueDate: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String? = null,
    val archivedAt: String? = null,
    // Recurrence
    val recurrenceEnabled: Int = 0,
    val recurrenceRule: String? = null,
    // Scoring parameters
    val durationMinutes: Int = 60,
    val impactLevel: String = "Moderate Impact",
    val goalAlignment: String = "Moderate Alignment",
    val energyLevel: String = "Moderate",
    val controlLevel: String = "Office/Colleagues Dependent",
    val lifeIntentionCategory: String = "Career & Work",
    @ColumnInfo(name = "dimension_id")
    val dimensionId: String? = null,
    @ColumnInfo(name = "day_key")
    val dayKey: String? = null,
    // POC fields
    val explicitUrgency: Double? = null, // 0..1
    val focusRequired: Double? = null, // 0..1
    val recurrenceStrategy: String? = null, // DEPRECATED in v17; kept for compat
    val blockedReason: String? = null,
    val completionRate: Double? = null, // 0..1
    val externalDependency: String? = null,
    // Notification
    val notificationMode: String? = "auto",
    val customNotificationMinutes: Int? = null,
    // Calculated
    val taskScore: Double? = null,
    // Recurrence redesign - score roll-up (Inc 4b: decay currentScore removed)
    val lastOccurrenceDate: String? = null,
    val dayBoundaryHour: Int = 0, // DEPRECATED in v17; kept for compat
    // External-import metadata (nullable for locally created rows)
    @ColumnInfo(name = "import_source")
    val importSource: String? = null,
    @ColumnInfo(name = "import_id")
    val importId: String? = null,
    @ColumnInfo(name = "imported_at")
    val importedAt: String? = null,
    @ColumnInfo(name = "import_batch_id")
    val importBatchId: String? = null,
)
