//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.domain.model.Tag
import kotlinx.coroutines.flow.Flow

/**
 * Repository for optional cross-module tags.
 */
interface TagRepository {
    /**
     * Registers the observe all tags.
     */
    fun observeAllTags(): Flow<List<Tag>>

    /**
     * Search tags by name prefix (case-insensitive normalized match).
     */
    fun searchTagsByPrefix(query: String, limit: Int = 20): Flow<List<Tag>>

    /**
     * Observe tags linked to a task.
     */
    fun observeTagsForTask(taskId: String): Flow<List<Tag>>

    /**
     * Observe tags linked to a note.
     */
    fun observeTagsForNote(noteId: String): Flow<List<Tag>>

    /**
     * Load tag names for a set of notes in one batch.
     */
    suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>>

    /**
     * Observe tags linked to a time entry.
     */
    fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>>

    /**
     * Replace task tags with the provided names.
     */
    suspend fun replaceTaskTags(taskId: String, tagNames: List<String>)

    /**
     * Replace note tags with the provided names.
     */
    suspend fun replaceNoteTags(noteId: String, tagNames: List<String>)

    /**
     * Replace time-entry tags with the provided names.
     */
    suspend fun replaceTimeEntryTags(timeEntryId: String, tagNames: List<String>)
}
