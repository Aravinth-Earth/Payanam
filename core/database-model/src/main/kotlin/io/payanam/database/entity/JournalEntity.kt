//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for day journal entry - one per day.
 */
@Entity(
    tableName = "day_journal_entries",
    indices = [Index("entryDate", unique = true)],
)
data class DayJournalEntryEntity(
    @PrimaryKey
    val id: String,
    val entryDate: String,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Room entity for journal response to a prompt.
 */
@Entity(
    tableName = "day_journal_responses",
    foreignKeys = [
        ForeignKey(
            entity = DayJournalEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index("entryId"),
        Index("dimension_id"),
        Index(value = ["entryId", "scope", "dimensionKey", "promptKey"], unique = true),
    ],
)
data class DayJournalResponseEntity(
    @PrimaryKey
    val id: String,
    val entryId: String,
    val scope: String,
    val dimensionKey: String?,
    @ColumnInfo(name = "dimension_id")
    val dimensionId: String? = null,
    val promptKey: String,
    val responseText: String?,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Freeform journal note entries (multi-entry per day per dimension).
 */
@Entity(
    tableName = "journal_notes",
    foreignKeys = [
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("day_key"), Index("dimension_id"), Index("updated_at")],
)
data class JournalNoteEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val details: String? = null,
    val lifeIntentionCategory: String,
    @ColumnInfo(name = "dimension_id")
    val dimensionId: String? = null,
    @ColumnInfo(name = "day_key")
    val dayKey: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

/**
 * Room entity for app settings (key-value store).
 */
@Entity(
    tableName = "app_settings",
    indices = [Index("key", unique = true)],
)
data class AppSettingEntity(
    @PrimaryKey
    val key: String,
    val value: String?,
    val updatedAt: String,
)

/**
 * Room entity for scheduled notifications.
 */
@Entity(
    tableName = "scheduled_notifications",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId"), Index("scheduledAt")],
)
data class ScheduledNotificationEntity(
    @PrimaryKey
    val id: String,
    val taskId: String,
    val scheduledAt: String,
    val notificationType: String,
    val title: String,
    val body: String,
    val isDelivered: Int = 0,
    val createdAt: String,
)
