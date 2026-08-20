//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.DayJournalEntry
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DayJournalResponseInput
import io.payanam.domain.model.JournalPromptScope
import io.payanam.domain.model.TaskOccurrence
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Repository interface for Day Journal operations.
 */
interface JournalRepository {
    
    /**
     * Get or create a journal entry for a specific date.
     */
    suspend fun getOrCreateEntry(date: LocalDate): DayJournalEntry
    
    /**
     * Observe journal entry for a date.
     */
    fun observeEntry(date: LocalDate): Flow<DayJournalEntry?>
    
    /**
     * Get all responses for a journal entry.
     */
    fun getResponses(entryId: String): Flow<List<DayJournalResponse>>
    
    /**
     * Save a journal response. Creates or updates as needed.
     */
    suspend fun saveResponse(entryId: String, input: DayJournalResponseInput): DayJournalResponse
    
    /**
     * Get a specific response.
     */
    suspend fun getResponse(
        /** Entry id. */
        entryId: String,
        /** Scope. */
        scope: JournalPromptScope,
        dimensionKey: String?,
        /** Prompt key. */
        promptKey: String
    ): DayJournalResponse?
    
    // Additional methods for DayViewModel
    
    /**
     * Get journal entry by date string (YYYY-MM-DD).
     */
    suspend fun getEntryByDate(dateString: String): DayJournalEntry?
    
    /**
     * Insert a new journal entry.
     */
    suspend fun insertEntry(entry: DayJournalEntry)
    
    /**
     * Get all responses for an entry by entry ID.
     */
    suspend fun getResponsesByEntryId(entryId: String): List<DayJournalResponse>
    
    /**
     * Get all journal entries.
     */
    fun getAllJournalEntries(): Flow<List<DayJournalEntry>>
    
    /**
     * Get total count of all journal responses across all entries.
     */
    fun getTotalResponseCount(): Flow<Int>
    
    /**
     * Upsert a journal response (insert or update).
     */
    suspend fun upsertResponse(response: DayJournalResponse)
}

/**
 * Repository interface for App Settings.
 */
interface AppSettingsRepository {
    
    /**
     * Get a setting value by key.
     */
    suspend fun getSetting(key: String): String?
    
    /**
     * Observe a setting value.
     */
    fun observeSetting(key: String): Flow<String?>
    
    /**
     * Set a setting value.
     */
    suspend fun setSetting(key: String, value: String?)
    
    /**
     * Delete a setting.
     */
    suspend fun deleteSetting(key: String)
    
    /**
     * Get all settings as a map.
     */
    fun getAllSettings(): Flow<Map<String, String?>>
}

/**
 * Repository interface for Task Occurrence operations.
 */
interface TaskOccurrenceRepository {
    
    /**
     * Get all occurrences for a task by task ID.
     */
    suspend fun getOccurrencesByTaskId(taskId: String): List<io.payanam.domain.model.TaskOccurrence>
    
    /**
     * Get all occurrences for a task as Flow.
     */
    fun getOccurrencesForTask(taskId: String): Flow<List<io.payanam.domain.model.TaskOccurrence>>
    
    /**
     * Get occurrences for a task within the last N days.
     * Used for building checkmark grids in habit view.
     */
    suspend fun getOccurrencesForLastNDays(
        /** Task id. */
        taskId: String,
        /** Days. */
        days: Int
    ): List<io.payanam.domain.model.TaskOccurrence>
    
    /**
     * Get occurrences for multiple tasks within the last N days.
     * Used for building checkmark grids in habit view - optimized for bulk loading.
     */
    suspend fun getOccurrencesForTasksInLastNDays(
        taskIds: List<String>,
        /** Days. */
        days: Int
    ): Map<String, List<io.payanam.domain.model.TaskOccurrence>>
    
    /**
     * Get or create occurrence for a specific task and date.
     * Returns existing occurrence or null if none exists.
     */
    suspend fun getOccurrenceForDate(
        /** Task id. */
        taskId: String,
        date: java.time.LocalDate
    ): io.payanam.domain.model.TaskOccurrence?
    
    /**
     * Toggle occurrence status for a task on a specific date.
     * If no occurrence exists, creates one with the given status.
     * If occurrence exists, updates its status.
     * 
     * @return The updated/created occurrence
     */
    suspend fun toggleOccurrence(
        /** Task id. */
        taskId: String,
        date: java.time.LocalDate,
        /** New status. */
        newStatus: String,
        note: String? = null,
        reason: String? = null,
        actualCompletedAt: java.time.LocalDateTime? = null,
        actualDurationMinutes: Int? = null
    ): io.payanam.domain.model.TaskOccurrence
    
    /**
     * Get all occurrences for a specific date.
     * Used to show past habit completions/skips/misses in TimeScreen.
     */
    fun getOccurrencesForDate(date: java.time.LocalDate): Flow<List<io.payanam.domain.model.TaskOccurrence>>
    
    /**
     * Delete occurrence for a task on a specific date.
     * Used when clearing/resetting checkmarks to PENDING state.
     */
    suspend fun deleteOccurrence(
        /** Task id. */
        taskId: String,
        date: java.time.LocalDate
    )
    
    /**
     * Record a new occurrence when a recurring task is completed/skipped/missed.
     */
    suspend fun recordOccurrence(occurrence: io.payanam.domain.model.TaskOccurrence)
    
    /**
     * Record a new occurrence with parameters (legacy).
     */
    suspend fun recordOccurrence(
        /** Task id. */
        taskId: String,
        dueDate: java.time.LocalDateTime,
        /** Status. */
        status: String, // completed | skipped | missed
        note: String? = null,
        completionRate: Double? = null
    ): io.payanam.domain.model.TaskOccurrence
    
    /**
     * Delete an occurrence.
     */
    suspend fun deleteOccurrence(id: String)
}

/**
 * Repository interface for Notification scheduling.
 */
interface NotificationRepository {
    
    /**
     * Schedule a notification for a task.
     */
    suspend fun scheduleNotification(
        /** Task id. */
        taskId: String,
        scheduledAt: java.time.LocalDateTime,
        /** Notification type. */
        notificationType: String,
        /** Title. */
        title: String,
        /** Body. */
        body: String
    ): String // Returns notification ID
    
    /**
     * Get pending notifications for a task.
     */
    suspend fun getNotificationsForTask(taskId: String): List<ScheduledNotification>
    
    /**
     * Get all pending notifications.
     */
    suspend fun getPendingNotifications(): List<ScheduledNotification>
    
    /**
     * Mark a notification as delivered.
     */
    suspend fun markDelivered(id: String)
    
    /**
     * Cancel all notifications for a task.
     */
    suspend fun cancelNotificationsForTask(taskId: String)
    
    /**
     * Cancel a specific notification.
     */
    suspend fun cancelNotification(id: String)
}

/**
 * Scheduled notification data class.
 */
data class ScheduledNotification(
    /** Id. */
    val id: String,
    /** Task id. */
    val taskId: String,
    /** Scheduled at. */
    val scheduledAt: java.time.LocalDateTime,
    /** Notification type. */
    val notificationType: String,
    /** Title. */
    val title: String,
    /** Body. */
    val body: String,
    /** Is delivered. */
    val isDelivered: Boolean
)
