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
 * Room DAO for day-journal data: [DayJournalEntryEntity] per calendar date,
 * [DayJournalResponseEntity] answers, free-form [JournalNoteEntity] notes, and
 * key/value [AppSettingEntity] preferences. Read methods are exposed as [Flow]
 * for reactive UI; the rest are single-shot.
 */
interface JournalDao {
    // Day Journal Entry
    @Query("SELECT * FROM day_journal_entries WHERE entryDate = :date")
    /**
     * Returns the journal entry for [date], or null when none exists yet.
     */
    suspend fun getEntryForDate(date: String): DayJournalEntryEntity?

    @Query("SELECT * FROM day_journal_entries WHERE entryDate = :date")
    /**
     * Emits the journal entry for [date] as a [Flow] (null when no entry).
     */
    fun observeEntryForDate(date: String): Flow<DayJournalEntryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a day-journal entry.
     */
    suspend fun insertEntry(entry: DayJournalEntryEntity)

    @Update
    /**
     * Updates all columns of an existing day-journal entry.
     */
    suspend fun updateEntry(entry: DayJournalEntryEntity)

    @Query("SELECT * FROM day_journal_entries")
    /**
     * Emits every day-journal entry as a [Flow].
     */
    fun getAllEntries(): Flow<List<DayJournalEntryEntity>>

    @Query("SELECT * FROM day_journal_responses")
    /**
     * Emits every journal response as a [Flow].
     */
    fun getAllResponses(): Flow<List<DayJournalResponseEntity>>

    // Day Journal Responses
    @Query("SELECT * FROM day_journal_responses WHERE entryId = :entryId")
    /**
     * Emits all responses belonging to [entryId] as a [Flow].
     */
    fun getResponsesForEntry(entryId: String): Flow<List<DayJournalResponseEntity>>

    @Query("SELECT * FROM day_journal_responses WHERE entryId = :entryId")
    /**
     * Returns all responses for [entryId] once (not a stream).
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
     * Returns the single response matching [entryId], [scope], [promptKey], and
     * [dimensionKey]. The dimension match is null-safe: a response with no
     * dimension matches only when [dimensionKey] is also null.
     */
    suspend fun getResponse(
        entryId: String,
        scope: String,
        dimensionKey: String?,
        promptKey: String,
    ): DayJournalResponseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a journal response.
     */
    suspend fun insertResponse(response: DayJournalResponseEntity)

    @Update
    /**
     * Updates all columns of an existing journal response.
     */
    suspend fun updateResponse(response: DayJournalResponseEntity)

    @Query("DELETE FROM day_journal_responses WHERE id = :id")
    /**
     * Deletes the response with [id].
     */
    suspend fun deleteResponse(id: String)

    // Journal notes
    @Query("SELECT * FROM journal_notes ORDER BY updated_at DESC")
    /**
     * Emits all notes ordered by last-updated (newest first) as a [Flow].
     */
    fun getAllNotes(): Flow<List<JournalNoteEntity>>

    @Query("SELECT * FROM journal_notes WHERE day_key = :dayKey ORDER BY updated_at DESC")
    /**
     * Emits notes for a specific [dayKey], newest first, as a [Flow].
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
     * Emits notes tied to [dimension], matching either the dimension id or its
     * life-intention category, newest first, as a [Flow].
     */
    fun getNotesByDimension(dimension: String): Flow<List<JournalNoteEntity>>

    @Query("SELECT * FROM journal_notes WHERE id = :id")
    /**
     * Returns the note with [id], or null.
     */
    suspend fun getNoteById(id: String): JournalNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a note.
     */
    suspend fun insertNote(note: JournalNoteEntity)

    @Update
    /**
     * Updates all columns of an existing note.
     */
    suspend fun updateNote(note: JournalNoteEntity)

    @Query("DELETE FROM journal_notes WHERE id = :id")
    /**
     * Deletes the note with [id].
     */
    suspend fun deleteNoteById(id: String)

    @Query("DELETE FROM day_journal_responses")
    /**
     * Deletes every journal response row.
     */
    suspend fun deleteAllResponses()

    @Query("DELETE FROM day_journal_entries")
    /**
     * Deletes every day-journal entry row.
     */
    suspend fun deleteAllEntries()

    @Query("DELETE FROM journal_notes")
    /**
     * Deletes every note row.
     */
    suspend fun deleteAllNotes()
}

@Dao
/**
 * Room DAO for the key/value [AppSettingEntity] table backing user preferences
 * and local flags.
 */
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    /**
     * Returns the setting stored under [key], or null when unset.
     */
    suspend fun getSetting(key: String): AppSettingEntity?

    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    /**
     * Emits the setting for [key] as a [Flow] (null when unset).
     */
    fun observeSetting(key: String): Flow<AppSettingEntity?>

    @Query("SELECT * FROM app_settings")
    /**
     * Emits all settings as a [Flow].
     */
    fun getAllSettings(): Flow<List<AppSettingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a setting.
     */
    suspend fun insertSetting(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    /**
     * Deletes the setting stored under [key].
     */
    suspend fun deleteSetting(key: String)
}

@Dao
/**
 * Room DAO for [ScheduledNotificationEntity] rows tracking pending/overdue
 * reminder notifications for tasks.
 */
interface ScheduledNotificationDao {
    @Query("SELECT * FROM scheduled_notifications WHERE taskId = :taskId")
    /**
     * Returns all scheduled notifications for [taskId].
     */
    suspend fun getNotificationsForTask(taskId: String): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE isDelivered = 0 AND scheduledAt > :now ORDER BY scheduledAt ASC")
    /**
     * Returns notifications not yet delivered whose [ScheduledNotificationEntity.scheduledAt]
     * is after [now], ordered soonest first.
     */
    suspend fun getPendingNotifications(now: String): List<ScheduledNotificationEntity>

    @Query("SELECT * FROM scheduled_notifications WHERE isDelivered = 0 AND scheduledAt <= :now")
    /**
     * Returns notifications not yet delivered whose fire time is at or before
     * [now] — i.e. missed and still pending.
     */
    suspend fun getOverdueNotifications(now: String): List<ScheduledNotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a scheduled notification.
     */
    suspend fun insert(notification: ScheduledNotificationEntity)

    @Query("UPDATE scheduled_notifications SET isDelivered = 1 WHERE id = :id")
    /**
     * Marks the notification with [id] as delivered.
     */
    suspend fun markDelivered(id: String)

    @Query("DELETE FROM scheduled_notifications WHERE taskId = :taskId")
    /**
     * Deletes all notifications for [taskId] (e.g. when the task is rescheduled).
     */
    suspend fun deleteForTask(taskId: String)

    @Query("DELETE FROM scheduled_notifications WHERE id = :id")
    /**
     * Deletes the notification with [id].
     */
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_notifications WHERE isDelivered = 1")
    /**
     * Deletes every already-delivered notification, keeping only outstanding
     * ones. Run periodically to bound table growth.
     */
    suspend fun deleteDelivered()
}
