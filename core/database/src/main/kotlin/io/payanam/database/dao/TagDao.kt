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
 * Holds a (note id, tag name) pair returned when fetching tag names for a batch
 * of notes — avoids loading full [TagEntity] rows.
 */
data class NoteTagNameRow(
    val noteId: String,
    val tagName: String,
)

@Dao
/**
 * Room DAO for the `tags` table and its three junction tables
 * ([TaskTagEntity], [NoteTagEntity], [TimeEntryTagEntity]). Tags are matched
 * case-insensitively via a `normalized_name` column.
 */
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY usage_count DESC, name ASC")
    /**
     * Emits all tags ordered by popularity (most-used first), then name, as a
     * [Flow].
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
     * Emits tags whose `normalized_name` starts with [normalizedPrefix],
     * popularity-ordered, capped at [limit] — backs type-ahead tag entry.
     */
    fun searchByPrefix(
        normalizedPrefix: String,
        limit: Int,
    ): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE normalized_name = :normalizedName LIMIT 1")
    /**
     * Returns the tag whose `normalized_name` equals [normalizedName], or null.
     * Used to reuse an existing tag instead of creating a duplicate.
     */
    suspend fun getByNormalizedName(normalizedName: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    /**
     * Inserts a tag, ignoring the row when a tag with the same primary key
     * already exists. Returns the new row id, or -1 when ignored.
     */
    suspend fun insert(tag: TagEntity): Long

    @Update
    /**
     * Updates all columns of an existing tag.
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
     * Bumps `usage_count` and records [usedAt] / [updatedAt] when a tag is
     * applied. Drives the popularity ordering in the read queries.
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
     * Emits the tags linked to [taskId], name-ordered, as a [Flow].
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
     * Emits the tags linked to [noteId], name-ordered, as a [Flow].
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
     * Returns (note id, tag name) pairs for every tag on any note in [noteIds].
     * Used to display tag chips without loading full tag rows.
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
     * Emits the tags linked to [timeEntryId], name-ordered, as a [Flow].
     */
    fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<TagEntity>>

    @Query("DELETE FROM task_tags WHERE task_id = :taskId")
    /**
     * Removes every task↔tag link for [taskId] (call before re-writing tags).
     */
    suspend fun clearTaskTags(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a batch of task↔tag links.
     */
    suspend fun insertTaskTags(links: List<TaskTagEntity>)

    @Query("DELETE FROM note_tags WHERE note_id = :noteId")
    /**
     * Removes every note↔tag link for [noteId] (call before re-writing tags).
     */
    suspend fun clearNoteTags(noteId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a batch of note↔tag links.
     */
    suspend fun insertNoteTags(links: List<NoteTagEntity>)

    @Query("DELETE FROM time_entry_tags WHERE time_entry_id = :timeEntryId")
    /**
     * Removes every time-entry↔tag link for [timeEntryId] (call before
     * re-writing tags).
     */
    suspend fun clearTimeEntryTags(timeEntryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    /**
     * Inserts or replaces a batch of time-entry↔tag links.
     */
    suspend fun insertTimeEntryTags(links: List<TimeEntryTagEntity>)
}
