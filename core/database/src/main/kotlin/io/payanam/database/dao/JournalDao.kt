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
 * Defines the contract for journal dao.
 */
interface JournalDao {
    // Day Journal Entry
    @Query("SELECT * FROM day_journal_entries WHERE entryDate = :date")
    /**
     * Returns the entry for date.
     */
    suspend fun getEntryForDate(date: String): DayJournalEntryEntity?

    @Query("SELECT * FROM day_journal_entries WHERE entryDate = :date")
    /**
     * Registers the observe entry for date.
     */
    fun observeEntryForDate(date: String): Flow<DayJournalEntryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert entry.
     */
    suspend fun insertEntry(entry: DayJournalEntryEntity)

    @Update
    /**
     * Updates the update entry.
     */
    suspend fun updateEntry(entry: DayJournalEntryEntity)

    @Query("SELECT * FROM day_journal_entries")
    /**
     * Returns the all entries.
     */
    fun getAllEntries(): Flow<List<DayJournalEntryEntity>>

    @Query("SELECT * FROM day_journal_responses")
    /**
     * Returns the all responses.
     */
    fun getAllResponses(): Flow<List<DayJournalResponseEntity>>

    // Day Journal Responses
    @Query("SELECT * FROM day_journal_responses WHERE entryId = :entryId")
    /**
     * Returns the responses for entry.
     */
    fun getResponsesForEntry(entryId: String): Flow<List<DayJournalResponseEntity>>

    @Query("SELECT * FROM day_journal_responses WHERE entryId = :entryId")
    /**
     * Returns the responses for entry once.
     */
    suspend fun getResponsesForEntryOnce(entryId: String): List<DayJournalResponseEntity>

    @Query(
        """
        SELECT * FROM day_journal_responses 
        WHERE entryId = :entryId AND scope = :scope 
        AND (dimensionKey = :dimensionKey OR (dimensionKey IS NULL AND :dimensionKey IS NULL)) 
        AND promptKey = :promptKey
    """,
    )
    /**
     * Returns the response.
     */
    suspend fun getResponse(
        entryId: String,
        scope: String,
        dimensionKey: String?,
        promptKey: String,
    ): DayJournalResponseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert response.
     */
    suspend fun insertResponse(response: DayJournalResponseEntity)

    @Update
    /**
     * Updates the update response.
     */
    suspend fun updateResponse(response: DayJournalResponseEntity)

    @Query("DELETE FROM day_journal_responses WHERE id = :id")
    /**
     * Removes the delete response.
     */
    suspend fun deleteResponse(id: String)

    // Journal notes
    @Query("SELECT * FROM journal_notes ORDER BY updated_at DESC")
    /**
     * Returns the all notes.
     */
    fun getAllNotes(): Flow<List<JournalNoteEntity>>

    @Query("SELECT * FROM journal_notes WHERE day_key = :dayKey ORDER BY updated_at DESC")
    /**
     * Returns the notes for day.
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
     * Returns the notes by dimension.
     */
    fun getNotesByDimension(dimension: String): Flow<List<JournalNoteEntity>>

    @Query("SELECT * FROM journal_notes WHERE id = :id")
    /**
     * Returns the note by id.
     */
    suspend fun getNoteById(id: String): JournalNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert note.
     */
    suspend fun insertNote(note: JournalNoteEntity)

    @Update
    /**
     * Updates the update note.
     */
    suspend fun updateNote(note: JournalNoteEntity)

    @Query("DELETE FROM journal_notes WHERE id = :id")
    /**
     * Removes the delete note by id.
     */
    suspend fun deleteNoteById(id: String)

    @Query("DELETE FROM day_journal_responses")
    /**
     * Removes the delete all responses.
     */
    suspend fun deleteAllResponses()

    @Query("DELETE FROM day_journal_entries")
    /**
     * Removes the delete all entries.
     */
    suspend fun deleteAllEntries()

    @Query("DELETE FROM journal_notes")
    /**
     * Removes the delete all notes.
     */
    suspend fun deleteAllNotes()
}

@Dao
/**
 * Defines the contract for app settings dao.
 */
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    /**
     * Returns the setting.
     */
    suspend fun getSetting(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    /**
     * Registers the observe setting.
     */
    fun observeSetting(key: String): Flow<AppSettingEntity?>

    @Query("SELECT * FROM app_settings")
    /**
     * Returns the all settings.
     */
    fun getAllSettings(): Flow<List<AppSettingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert setting.
     */
    suspend fun insertSetting(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    /**
     * Removes the delete setting.
     */
    suspend fun deleteSetting(key: String)
}

@Dao
/**
 * Defines the contract for scheduled notification dao.
 */
interface ScheduledNotificationDao {
    @Query("SELECT * FROM scheduled_notifications WHERE taskId = :taskId")
    /**
     * Returns the notifications for task.
     */
    suspend fun getNotificationsForTask(taskId: String): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE isDelivered = 0 AND scheduledAt > :now ORDER BY scheduledAt ASC")
    /**
     * Returns the pending notifications.
     */
    suspend fun getPendingNotifications(now: String): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE isDelivered = 0 AND scheduledAt <= :now")
    /**
     * Returns the overdue notifications.
     */
    suspend fun getOverdueNotifications(now: String): List<ScheduledNotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Performs the insert.
     */
    suspend fun insert(notification: ScheduledNotificationEntity)

    @Query("UPDATE scheduled_notifications SET isDelivered = 1 WHERE id = :id")
    /**
     * Performs the mark delivered.
     */
    suspend fun markDelivered(id: String)

    @Query("DELETE FROM scheduled_notifications WHERE taskId = :taskId")
    /**
     * Removes the delete for task.
     */
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM scheduled_notifications WHERE id = :id")
    /**
     * Removes the delete by id.
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_notifications WHERE isDelivered = 1")
    /**
     * Removes the delete delivered.
     */
    suspend fun deleteDelivered()
}
