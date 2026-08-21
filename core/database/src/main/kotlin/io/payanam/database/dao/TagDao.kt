//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.payanam.database.entity.NoteTagEntity
import io.payanam.database.entity.TagEntity
import io.payanam.database.entity.TaskTagEntity
import io.payanam.database.entity.TimeEntryTagEntity
import kotlinx.coroutines.flow.Flow

/**
 * NoteTagNameRow.
 */
data class NoteTagNameRow(
    val noteId: String,
    val tagName: String,
)

@Dao
/**
 * TagDao.
 */
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY usage_count DESC, name ASC")
    /**
     * Observe all tags.
     */
    fun observeAllTags(): Flow<List<TagEntity>>

    @Query(
        """
        SELECT * FROM tags
        WHERE normalized_name LIKE :normalizedPrefix || '%'
        ORDER BY usage_count DESC, name ASC
        LIMIT :limit
        """,
    )
    /**
     * Search by prefix.
     */
    fun searchByPrefix(
        normalizedPrefix: String,
        limit: Int,
    ): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE normalized_name = :normalizedName LIMIT 1")
    /**
     * Get by normalized name.
     */
    suspend fun getByNormalizedName(normalizedName: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    /**
     * Insert.
     */
    suspend fun insert(tag: TagEntity): Long

    @Update
    /**
     * Update.
     */
    suspend fun update(tag: TagEntity)

    @Query(
        """
        UPDATE tags
        SET usage_count = usage_count + 1,
            last_used_at = :usedAt,
            updated_at = :updatedAt
        WHERE id = :tagId
        """,
    )
    /**
     * Mark used.
     */
    suspend fun markUsed(
        tagId: String,
        usedAt: String,
        updatedAt: String,
    )

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN task_tags ON task_tags.tag_id = tags.id
        WHERE task_tags.task_id = :taskId
        ORDER BY tags.name ASC
        """,
    )
    /**
     * Observe tags for task.
     */
    fun observeTagsForTask(taskId: String): Flow<List<TagEntity>>

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN note_tags ON note_tags.tag_id = tags.id
        WHERE note_tags.note_id = :noteId
        ORDER BY tags.name ASC
        """,
    )
    /**
     * Observe tags for note.
     */
    fun observeTagsForNote(noteId: String): Flow<List<TagEntity>>

    @Query(
        """
        SELECT note_tags.note_id AS noteId, tags.name AS tagName
        FROM note_tags
        INNER JOIN tags ON tags.id = note_tags.tag_id
        WHERE note_tags.note_id IN (:noteIds)
        ORDER BY note_tags.note_id ASC, tags.name ASC
        """,
    )
    /**
     * Get tag names for notes.
     */
    suspend fun getTagNamesForNotes(noteIds: List<String>): List<NoteTagNameRow>

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN time_entry_tags ON time_entry_tags.tag_id = tags.id
        WHERE time_entry_tags.time_entry_id = :timeEntryId
        ORDER BY tags.name ASC
        """,
    )
    /**
     * Observe tags for time entry.
     */
    fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<TagEntity>>

    @Query("DELETE FROM task_tags WHERE task_id = :taskId")
    /**
     * Clear task tags.
     */
    suspend fun clearTaskTags(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert task tags.
     */
    suspend fun insertTaskTags(links: List<TaskTagEntity>)

    @Query("DELETE FROM note_tags WHERE note_id = :noteId")
    /**
     * Clear note tags.
     */
    suspend fun clearNoteTags(noteId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert note tags.
     */
    suspend fun insertNoteTags(links: List<NoteTagEntity>)

    @Query("DELETE FROM time_entry_tags WHERE time_entry_id = :timeEntryId")
    /**
     * Clear time entry tags.
     */
    suspend fun clearTimeEntryTags(timeEntryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Insert time entry tags.
     */
    suspend fun insertTimeEntryTags(links: List<TimeEntryTagEntity>)
}
