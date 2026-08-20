//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Day Journal Entry - one per day.
 */
@Entity(
    tableName = "day_journal_entries",
    indices = [Index("entryDate", unique = true)],
)
/**
 * DayJournalEntryEntity.
 */
data class DayJournalEntryEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Entry date. */
    val entryDate: String,
    /** Created at. */
    val createdAt: String,
    /** Updated at. */
    val updatedAt: String,
)

/**
 * Room entity for Journal Response to a prompt.
 */
@Entity(
    tableName = "day_journal_responses",
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = DayJournalEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        /** Foreign key. */
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        /** Index. */
        Index("entryId"),
        /** Index. */
        Index("dimension_id"),
        /** Index. */
        Index(value = ["entryId", "scope", "dimensionKey", "promptKey"], unique = true),
    ],
)
/**
 * DayJournalResponseEntity.
 */
data class DayJournalResponseEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Entry id. */
    val entryId: String,
    /** Scope. */
    val scope: String,
    /** Dimension key. */
    val dimensionKey: String?,
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String? = null,
    /** Prompt key. */
    val promptKey: String,
    /** Response text. */
    val responseText: String?,
    /** Created at. */
    val createdAt: String,
    /** Updated at. */
    val updatedAt: String,
)

/**
 * Freeform journal note entries (multi-entry per day per dimension).
 */
@Entity(
    tableName = "journal_notes",
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("day_key"), Index("dimension_id"), Index("updated_at")],
)
/**
 * JournalNoteEntity.
 */
data class JournalNoteEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Details. */
    val details: String? = null,
    /** Life intention category. */
    val lifeIntentionCategory: String,
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String? = null,
    @ColumnInfo(name = "day_key")
    /** Day key. */
    val dayKey: String,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    /** Updated at. */
    val updatedAt: String,
)

/**
 * Room entity for App Settings (key-value store).
 */
@Entity(
    tableName = "app_settings",
    indices = [Index("key", unique = true)],
)
/**
 * AppSettingEntity.
 */
data class AppSettingEntity(
    @PrimaryKey
    /** Key. */
    val key: String,
    /** Value. */
    val value: String?,
    /** Updated at. */
    val updatedAt: String,
)

/**
 * Room entity for scheduled notifications.
 */
@Entity(
    tableName = "scheduled_notifications",
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId"), Index("scheduledAt")],
)
/**
 * ScheduledNotificationEntity.
 */
data class ScheduledNotificationEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Task id. */
    val taskId: String,
    /** Scheduled at. */
    val scheduledAt: String,
    /** Notification type. */
    val notificationType: String,
    /** Title. */
    val title: String,
    /** Body. */
    val body: String,
    /** Is delivered. */
    val isDelivered: Int = 0,
    /** Created at. */
    val createdAt: String,
)
