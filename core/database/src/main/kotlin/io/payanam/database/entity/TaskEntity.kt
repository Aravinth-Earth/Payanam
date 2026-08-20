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
        Index("dimension_id"),
        /** Index. */
        Index("day_key"),
        /** Index. */
        Index("import_batch_id"),
        /** Index. */
        Index(value = ["import_source", "import_id"]),
    ],
)
/**
 * TaskEntity.
 */
data class TaskEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Description. */
    val description: String? = null,
    /** Status. */
    val status: String = "pending",
    /** Due date. */
    val dueDate: String? = null,
    /** Created at. */
    val createdAt: String,
    /** Updated at. */
    val updatedAt: String,
    /** Completed at. */
    val completedAt: String? = null,
    /** Archived at. */
    val archivedAt: String? = null,
    // Recurrence
    /** Recurrence enabled. */
    val recurrenceEnabled: Int = 0,
    /** Recurrence rule. */
    val recurrenceRule: String? = null,
    // Scoring parameters
    /** Duration minutes. */
    val durationMinutes: Int = 60,
    /** Impact level. */
    val impactLevel: String = "Moderate Impact",
    /** Goal alignment. */
    val goalAlignment: String = "Moderate Alignment",
    /** Energy level. */
    val energyLevel: String = "Moderate",
    /** Control level. */
    val controlLevel: String = "Office/Colleagues Dependent",
    /** Life intention category. */
    val lifeIntentionCategory: String = "Career & Work",
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String? = null,
    @ColumnInfo(name = "day_key")
    /** Day key. */
    val dayKey: String? = null,
    // POC fields
    /** Explicit urgency. */
    val explicitUrgency: Double? = null, // 0..1
    /** Focus required. */
    val focusRequired: Double? = null, // 0..1
    /** Recurrence strategy. */
    val recurrenceStrategy: String? = null, // DEPRECATED in v17; kept for compat
    /** Blocked reason. */
    val blockedReason: String? = null,
    /** Completion rate. */
    val completionRate: Double? = null, // 0..1
    /** External dependency. */
    val externalDependency: String? = null,
    // Notification
    /** Notification mode. */
    val notificationMode: String? = "auto",
    /** Custom notification minutes. */
    val customNotificationMinutes: Int? = null,
    // Calculated
    /** Task score. */
    val taskScore: Double? = null,
    // Recurrence redesign - score roll-up (Inc 4b: decay currentScore removed)
    /** Last occurrence date. */
    val lastOccurrenceDate: String? = null,
    /** Day boundary hour. */
    val dayBoundaryHour: Int = 0, // DEPRECATED in v17; kept for compat
    // External-import metadata (nullable for locally created rows)
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
)
