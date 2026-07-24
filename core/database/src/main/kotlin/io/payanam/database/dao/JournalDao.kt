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
interface JournalDao {
    // Day Journal Entry
    @Query("SELECT * FROM day_journal_entries WHERE entryDate = :date")
    suspend fun getEntryForDate(date: String): DayJournalEntryEntity?

    @Query("SELECT * FROM day_journal_entries WHERE entryDate = :date")
    fun observeEntryForDate(date: String): Flow<DayJournalEntryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: DayJournalEntryEntity)

    @Update
    suspend fun updateEntry(entry: DayJournalEntryEntity)

    @Query("SELECT * FROM day_journal_entries")
    fun getAllEntries(): Flow<List<DayJournalEntryEntity>>

    @Query("SELECT * FROM day_journal_responses")
    fun getAllResponses(): Flow<List<DayJournalResponseEntity>>

    // Day Journal Responses
    @Query("SELECT * FROM day_journal_responses WHERE entryId = :entryId")
    fun getResponsesForEntry(entryId: String): Flow<List<DayJournalResponseEntity>>

    @Query("SELECT * FROM day_journal_responses WHERE entryId = :entryId")
    suspend fun getResponsesForEntryOnce(entryId: String): List<DayJournalResponseEntity>

    @Query(
        """
        SELECT * FROM day_journal_responses 
        WHERE entryId = :entryId AND scope = :scope 
        AND (dimensionKey = :dimensionKey OR (dimensionKey IS NULL AND :dimensionKey IS NULL)) 
        AND promptKey = :promptKey
    """,
    )
    suspend fun getResponse(
        entryId: String,
        scope: String,
        dimensionKey: String?,
        promptKey: String,
    ): DayJournalResponseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResponse(response: DayJournalResponseEntity)

    @Update
    suspend fun updateResponse(response: DayJournalResponseEntity)

    @Query("DELETE FROM day_journal_responses WHERE id = :id")
    suspend fun deleteResponse(id: String)

    // Journal notes
    @Query("SELECT * FROM journal_notes ORDER BY updated_at DESC")
    fun getAllNotes(): Flow<List<JournalNoteEntity>>

    @Query("SELECT * FROM journal_notes WHERE day_key = :dayKey ORDER BY updated_at DESC")
    fun getNotesForDay(dayKey: String): Flow<List<JournalNoteEntity>>

    @Query(
        """
        SELECT * FROM journal_notes
        WHERE dimension_id = :dimension
           OR lifeIntentionCategory = :dimension
        ORDER BY updated_at DESC
        """,
    )
    fun getNotesByDimension(dimension: String): Flow<List<JournalNoteEntity>>

    @Query("SELECT * FROM journal_notes WHERE id = :id")
    suspend fun getNoteById(id: String): JournalNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: JournalNoteEntity)

    @Update
    suspend fun updateNote(note: JournalNoteEntity)

    @Query("DELETE FROM journal_notes WHERE id = :id")
    suspend fun deleteNoteById(id: String)

    @Query("DELETE FROM day_journal_responses")
    suspend fun deleteAllResponses()

    @Query("DELETE FROM day_journal_entries")
    suspend fun deleteAllEntries()

    @Query("DELETE FROM journal_notes")
    suspend fun deleteAllNotes()
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    suspend fun getSetting(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    fun observeSetting(key: String): Flow<AppSettingEntity?>

    @Query("SELECT * FROM app_settings")
    fun getAllSettings(): Flow<List<AppSettingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)
}

@Dao
interface ScheduledNotificationDao {
    @Query("SELECT * FROM scheduled_notifications WHERE taskId = :taskId")
    suspend fun getNotificationsForTask(taskId: String): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE isDelivered = 0 AND scheduledAt > :now ORDER BY scheduledAt ASC")
    suspend fun getPendingNotifications(now: String): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE isDelivered = 0 AND scheduledAt <= :now")
    suspend fun getOverdueNotifications(now: String): List<ScheduledNotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: ScheduledNotificationEntity)

    @Query("UPDATE scheduled_notifications SET isDelivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: String)

    @Query("DELETE FROM scheduled_notifications WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM scheduled_notifications WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_notifications WHERE isDelivered = 1")
    suspend fun deleteDelivered()
}
