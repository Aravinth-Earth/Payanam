//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Repository interface for timeEntry operations.
 */
interface TimeEntryRepository {
    
    /**
     * Get the currently active time entry (endedAt is null).
     */
    suspend fun getActiveTimeEntry(): TimeEntry?
    
    /**
     * Observe the active time entry reactively.
     */
    fun observeActiveTimeEntry(): Flow<TimeEntry?>
    
    /**
     * Get time entries for a date range.
     */
    fun getTimeEntriesForRange(start: LocalDateTime, end: LocalDateTime): Flow<List<TimeEntry>>
    
    /**
     * Get time entries for a specific date.
     */
    fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>>
    
    /**
     * Start a new time tracking session.
     */
    suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry
    
    /**
     * Stop the currently active time entry.
     */
    suspend fun stopActiveTimeEntry(): TimeEntry?

    /**
     * Stop the currently active time entry while persisting focus feedback.
     */
    suspend fun stopActiveTimeEntryWithFocus(
        focusRating: Double,
        focusNote: String? = null
    ): TimeEntry?
    
    /**
     * Update a time entry.
     */
    suspend fun updateTimeEntry(id: String, input: TimeEntryInput): TimeEntry
    
    /**
     * Delete a time entry.
     */
    suspend fun deleteTimeEntry(id: String)
    
    /**
     * Create a manual time entry (with both start and end).
     */
    suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry
    /**
     * Emits every time entry as a [Flow], for reactive list updates.
     */
    fun getAllTimeEntries(): Flow<List<TimeEntry>>
    
    /**
     * Get all active time entries (where endTime is null).
     */
    fun getActiveTimeEntries(): Flow<List<TimeEntry>>
    
    /**
     * Update a time entry directly.
     */
    suspend fun updateTimeEntry(entry: TimeEntry)
}
