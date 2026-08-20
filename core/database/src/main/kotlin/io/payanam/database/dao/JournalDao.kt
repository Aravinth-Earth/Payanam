//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.payanam.database.entity.AppSettingEntity
import io.payanam.database.entity.DayJournalEntryEntity
import io.payanam.database.entity.DayJournalResponseEntity
import io.payanam.database.entity.JournalNoteEntity
import io.payanam.database.entity.ScheduledNotificationEntity
import kotlinx.coroutines.flow.Flow

@Suppress("TooManyFunctions")
@Dao
/**
 * JournalDao.
 */
interface JournalDao {
    // Day Journal Entry
    @Query("SELECT * FROM day_journal_entries WHERE entryDate = :date")
    /**
     * Get entry for date.
     */
    suspend fun getEntryForDate(date: String): DayJournalEntryEntity?

    @Query("SELECT * FROM day_journal_entries WHERE entryDate = :date")
    /**
     * Observe entry for date.
     */
    fun observeEntryForDate(date: String): Flow<DayJournalEntryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert entry.
     */
    suspend fun insertEntry(entry: DayJournalEntryEntity)

    @Update
    /**
     * Update entry.
     */
    suspend fun updateEntry(entry: DayJournalEntryEntity)

    @Query("SELECT * FROM day_journal_entries")
    /**
     * Get all entries.
     */
    fun getAllEntries(): Flow<List<DayJournalEntryEntity>>

    @Query("SELECT * FROM day_journal_responses")
    /**
     * Get all responses.
     */
    fun getAllResponses(): Flow<List<DayJournalResponseEntity>>

    // Day Journal Responses
    @Query("SELECT * FROM day_journal_responses WHERE entryId = :entryId")
    /**
     * Get responses for entry.
     */
    fun getResponsesForEntry(entryId: String): Flow<List<DayJournalResponseEntity>>

    @Query("SELECT * FROM day_journal_responses WHERE entryId = :entryId")
    /**
     * Get responses for entry once.
     */
    suspend fun getResponsesForEntryOnce(entryId: String): List<DayJournalResponseEntity>

    @Query(
        """
        SELECT * FROM day_journal_responses 
        WHERE entryId = :entryId AND scope = :scope 
        /** And. */
        AND (dimensionKey = :dimensionKey OR (dimensionKey IS NULL AND :dimensionKey IS NULL)) 
        AND promptKey = :promptKey
    """,
    )
    /**
     * Get response.
     */
    suspend fun getResponse(
        /** Entry id. */
        entryId: String,
        /** Scope. */
        scope: String,
        dimensionKey: String?,
        /** Prompt key. */
        promptKey: String,
    ): DayJournalResponseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert response.
     */
    suspend fun insertResponse(response: DayJournalResponseEntity)

    @Update
    /**
     * Update response.
     */
    suspend fun updateResponse(response: DayJournalResponseEntity)

    @Query("DELETE FROM day_journal_responses WHERE id = :id")
    /**
     * Delete response.
     */
    suspend fun deleteResponse(id: String)

    // Journal notes
    @Query("SELECT * FROM journal_notes ORDER BY updated_at DESC")
    /**
     * Get all notes.
     */
    fun getAllNotes(): Flow<List<JournalNoteEntity>>

    @Query("SELECT * FROM journal_notes WHERE day_key = :dayKey ORDER BY updated_at DESC")
    /**
     * Get notes for day.
     */
    fun getNotesForDay(dayKey: String): Flow<List<JournalNoteEntity>>

    @Query(
        """
        SELECT * FROM journal_notes
        WHERE dimension_id = :dimension
           OR lifeIntentionCategory = :dimension
        ORDER BY updated_at DESC
        """,
    )
    /**
     * Get notes by dimension.
     */
    fun getNotesByDimension(dimension: String): Flow<List<JournalNoteEntity>>

    @Query("SELECT * FROM journal_notes WHERE id = :id")
    /**
     * Get note by id.
     */
    suspend fun getNoteById(id: String): JournalNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert note.
     */
    suspend fun insertNote(note: JournalNoteEntity)

    @Update
    /**
     * Update note.
     */
    suspend fun updateNote(note: JournalNoteEntity)

    @Query("DELETE FROM journal_notes WHERE id = :id")
    /**
     * Delete note by id.
     */
    suspend fun deleteNoteById(id: String)

    @Query("DELETE FROM day_journal_responses")
    /**
     * Delete all responses.
     */
    suspend fun deleteAllResponses()

    @Query("DELETE FROM day_journal_entries")
    /**
     * Delete all entries.
     */
    suspend fun deleteAllEntries()

    @Query("DELETE FROM journal_notes")
    /**
     * Delete all notes.
     */
    suspend fun deleteAllNotes()
}

@Dao
/**
 * AppSettingsDao.
 */
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    /**
     * Get setting.
     */
    suspend fun getSetting(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    /**
     * Observe setting.
     */
    fun observeSetting(key: String): Flow<AppSettingEntity?>

    @Query("SELECT * FROM app_settings")
    /**
     * Get all settings.
     */
    fun getAllSettings(): Flow<List<AppSettingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert setting.
     */
    suspend fun insertSetting(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    /**
     * Delete setting.
     */
    suspend fun deleteSetting(key: String)
}

@Dao
/**
 * ScheduledNotificationDao.
 */
interface ScheduledNotificationDao {
    @Query("SELECT * FROM scheduled_notifications WHERE taskId = :taskId")
    /**
     * Get notifications for task.
     */
    suspend fun getNotificationsForTask(taskId: String): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE isDelivered = 0 AND scheduledAt > :now ORDER BY scheduledAt ASC")
    /**
     * Get pending notifications.
     */
    suspend fun getPendingNotifications(now: String): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE isDelivered = 0 AND scheduledAt <= :now")
    /**
     * Get overdue notifications.
     */
    suspend fun getOverdueNotifications(now: String): List<ScheduledNotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert.
     */
    suspend fun insert(notification: ScheduledNotificationEntity)

    @Query("UPDATE scheduled_notifications SET isDelivered = 1 WHERE id = :id")
    /**
     * Mark delivered.
     */
    suspend fun markDelivered(id: String)

    @Query("DELETE FROM scheduled_notifications WHERE taskId = :taskId")
    /**
     * Delete for task.
     */
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM scheduled_notifications WHERE id = :id")
    /**
     * Delete by id.
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_notifications WHERE isDelivered = 1")
    /**
     * Delete delivered.
     */
    suspend fun deleteDelivered()
}
