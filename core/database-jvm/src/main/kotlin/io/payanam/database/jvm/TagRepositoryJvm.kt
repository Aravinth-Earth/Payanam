//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.mapper.TagMapper.toDomain
import io.payanam.domain.model.Tag
import io.payanam.domain.repository.TagRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import java.sql.ResultSet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * JDBC implementation of [TagRepository] over the encrypted database — the desktop's half of tagging,
 * on the same interface the Android Room implementation satisfies.
 *
 * The rules that matter, all copied from the Android implementation rather than re-derived:
 *
 *  - **Tag names are matched on `normalized_name`** (trimmed + lowercased), so "Focus" and "focus" are one
 *    tag instead of two. Names are compared with [Locale.ROOT] for the same reason the Android side does:
 *    a Turkish locale would otherwise lowercase "I" to a dotless "ı" and split the same tag in two.
 *  - **`replace*` is clear → ensure → link → bump usage, in ONE transaction.** A partial run would leave
 *    an owner with no tags, or with links pointing at a tag row that was never created.
 *  - **`usage_count` is what orders the suggestion list**, so every applied tag bumps it.
 *
 * Reads are exposed as [Flow] through [JdbcFlows].
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
class TagRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : TagRepository {
    private val logger = AppLogger.current
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** Emits every tag, popularity-ordered. */
    override fun observeAllTags(): Flow<List<Tag>> {
        logger.d(SOURCE, "Subscribing to all tags")
        return JdbcFlows.observe(pollIntervalMs) { readTags(ALL_TAGS_SQL, emptyArray()) }
    }

    /** Emits tags whose normalized name starts with the normalized [query], capped at [limit]. */
    override fun searchTagsByPrefix(
        query: String,
        limit: Int,
    ): Flow<List<Tag>> {
        val normalizedPrefix = normalizeTagName(query)
        logger.d(
            SOURCE,
            "Subscribing to tag prefix search",
            mapOf("prefix" to normalizedPrefix, "limit" to limit),
        )
        // LIMIT is inlined rather than bound: this layer binds every parameter as text, and a text LIMIT is
        // not reliably coerced by SQLite. The value is an Int from application code, never user input.
        val boundedLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        return JdbcFlows.observe(pollIntervalMs) {
            readTags(
                SEARCH_BY_PREFIX_SQL.replace(LIMIT_TOKEN, boundedLimit.toString()),
                arrayOf(normalizedPrefix),
            )
        }
    }

    /** Emits the tags linked to [taskId], name-ordered. */
    override fun observeTagsForTask(taskId: String): Flow<List<Tag>> {
        logger.d(SOURCE, "Subscribing to tags for task", mapOf("taskId" to taskId))
        return JdbcFlows.observe(pollIntervalMs) { readTags(TAGS_FOR_TASK_SQL, arrayOf(taskId)) }
    }

    /** Emits the tags linked to [noteId], name-ordered. */
    override fun observeTagsForNote(noteId: String): Flow<List<Tag>> {
        logger.d(SOURCE, "Subscribing to tags for note", mapOf("noteId" to noteId))
        return JdbcFlows.observe(pollIntervalMs) { readTags(TAGS_FOR_NOTE_SQL, arrayOf(noteId)) }
    }

    /** Emits the tags linked to [timeEntryId], name-ordered. */
    override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> {
        logger.d(SOURCE, "Subscribing to tags for time entry", mapOf("timeEntryId" to timeEntryId))
        return JdbcFlows.observe(pollIntervalMs) { readTags(TAGS_FOR_TIME_ENTRY_SQL, arrayOf(timeEntryId)) }
    }

    /** Returns note id → its tag names, for every id in [noteIds]. */
    override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> {
        if (noteIds.isEmpty()) {
            return emptyMap()
        }
        logger.d(SOURCE, "Loading note tags in batch", mapOf("noteCount" to noteIds.size))
        return JdbcFlows.onIo {
            // Room expands `IN (:noteIds)` at runtime; JDBC needs one placeholder per id, so they are
            // generated here. The parameter list and the placeholder count are built from the same source
            // so the two cannot disagree.
            val placeholders = noteIds.joinToString(separator = ",") { "?" }
            JdbcQueries
                .read(
                    session,
                    TAG_NAMES_FOR_NOTES_SQL.replace(NOTE_IDS_TOKEN, placeholders),
                    noteIds.toTypedArray(),
                ) { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.getString("noteId") to resultSet.getString("tagName"))
                        }
                    }
                }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
        }
    }

    /** Replaces [taskId]'s tag set with [tagNames] in one transaction. */
    override suspend fun replaceTaskTags(
        taskId: String,
        tagNames: List<String>,
    ) {
        replaceTags(
            ownerId = taskId,
            ownerKind = "task",
            tagNames = tagNames,
            clearSql = CLEAR_TASK_TAGS_SQL,
            linkTable = "task_tags",
            linkOwnerColumn = "task_id",
        )
    }

    /** Replaces [noteId]'s tag set with [tagNames] in one transaction. */
    override suspend fun replaceNoteTags(
        noteId: String,
        tagNames: List<String>,
    ) {
        replaceTags(
            ownerId = noteId,
            ownerKind = "note",
            tagNames = tagNames,
            clearSql = CLEAR_NOTE_TAGS_SQL,
            linkTable = "note_tags",
            linkOwnerColumn = "note_id",
        )
    }

    /** Replaces [timeEntryId]'s tag set with [tagNames] in one transaction. */
    override suspend fun replaceTimeEntryTags(
        timeEntryId: String,
        tagNames: List<String>,
    ) {
        replaceTags(
            ownerId = timeEntryId,
            ownerKind = "time entry",
            tagNames = tagNames,
            clearSql = CLEAR_TIME_ENTRY_TAGS_SQL,
            linkTable = "time_entry_tags",
            linkOwnerColumn = "time_entry_id",
        )
    }

    private suspend fun replaceTags(
        ownerId: String,
        ownerKind: String,
        tagNames: List<String>,
        clearSql: String,
        linkTable: String,
        linkOwnerColumn: String,
    ) {
        val cleanNames =
            tagNames
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { normalizeTagName(it) }

        JdbcFlows.onIo {
            JdbcQueries.transaction(session) {
                JdbcQueries.write(session, clearSql, arrayOf(ownerId))
                if (cleanNames.isEmpty()) {
                    logger.d(
                        SOURCE,
                        "Cleared tags for owner with empty input",
                        mapOf("ownerId" to ownerId),
                    )
                    return@transaction
                }
                val now = LocalDateTime.now().format(formatter)
                val tagIds = cleanNames.map { ensureTagExists(it, now) }
                tagIds.forEach { tagId ->
                    JdbcQueries.write(
                        session,
                        INSERT_TAG_LINK_SQL
                            .replace(TABLE_TOKEN, linkTable)
                            .replace(OWNER_COLUMN_TOKEN, linkOwnerColumn),
                        arrayOf(ownerId, tagId, now),
                    )
                }
                tagIds.forEach { tagId ->
                    JdbcQueries.write(session, MARK_TAG_USED_SQL, arrayOf(now, now, tagId))
                }
                logger.i(
                    SOURCE,
                    "Updated owner tags",
                    mapOf("ownerId" to ownerId, "ownerKind" to ownerKind, "tagCount" to tagIds.size),
                )
            }
        }
    }

    private fun ensureTagExists(
        tagName: String,
        now: String,
    ): String {
        val normalized = normalizeTagName(tagName)
        val existingId =
            JdbcQueries.read(session, TAG_BY_NORMALIZED_NAME_SQL, arrayOf(normalized)) { resultSet ->
                if (resultSet.next()) resultSet.getString("id") else null
            }
        if (existingId != null) {
            return existingId
        }
        val newId = UUID.randomUUID().toString()
        JdbcQueries.write(
            session,
            INSERT_TAG_SQL,
            arrayOf(newId, tagName, normalized, now, now),
        )
        logger.i(
            SOURCE,
            "Created new tag",
            mapOf("name" to tagName, "normalized" to normalized),
        )
        // Read the id back rather than assuming the generated one won: the Android implementation does the
        // same, because `INSERT OR IGNORE` can be satisfied by a row another writer created in between.
        return JdbcQueries.read(session, TAG_BY_NORMALIZED_NAME_SQL, arrayOf(normalized)) { resultSet ->
            if (resultSet.next()) resultSet.getString("id") else null
        } ?: newId
    }

    private fun readTags(
        sql: String,
        parameters: Array<out String?>,
    ): List<Tag> =
        JdbcQueries
            .read(session, sql, parameters) { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.readTag())
                    }
                }
            }.map { it.toDomain() }

    private fun ResultSet.readTag() =
        io.payanam.database.entity.TagEntity(
            id = getString("id"),
            name = getString("name"),
            normalizedName = getString("normalized_name"),
            usageCount = getInt("usage_count"),
            lastUsedAt = getString("last_used_at"),
            createdAt = getString("created_at"),
            updatedAt = getString("updated_at"),
        )

    private fun normalizeTagName(name: String): String = name.trim().lowercase(Locale.ROOT)

    private companion object {
        const val SOURCE = "TagRepositoryJvm"

        /** Matches the Android DAO's cap so a caller cannot ask for the whole table by accident. */
        const val MAX_SEARCH_LIMIT = 500

        // Substitution tokens rather than String.format placeholders: one of these statements contains a
        // literal '%' (the LIKE wildcard), which String.format reads as a conversion and throws on. Named
        // tokens cannot collide with SQL, so the same bug cannot come back through a later edit.
        const val LIMIT_TOKEN = "{limit}"
        const val NOTE_IDS_TOKEN = "{noteIds}"
        const val TABLE_TOKEN = "{table}"
        const val OWNER_COLUMN_TOKEN = "{ownerColumn}"

        /** Verbatim from `TagDao.observeAllTags`. */
        const val ALL_TAGS_SQL = "SELECT * FROM tags ORDER BY usage_count DESC, name ASC"

        /** Verbatim from `TagDao.searchByPrefix`, with LIMIT inlined (see [searchTagsByPrefix]). */
        const val SEARCH_BY_PREFIX_SQL = """
            SELECT * FROM tags
            WHERE normalized_name LIKE ? || '%'
            ORDER BY usage_count DESC, name ASC
            LIMIT {limit}
        """

        /** Verbatim from `TagDao.observeTagsForTask`. */
        const val TAGS_FOR_TASK_SQL = """
            SELECT tags.* FROM tags
            INNER JOIN task_tags ON task_tags.tag_id = tags.id
            WHERE task_tags.task_id = ?
            ORDER BY tags.name ASC
        """

        /** Verbatim from `TagDao.observeTagsForNote`. */
        const val TAGS_FOR_NOTE_SQL = """
            SELECT tags.* FROM tags
            INNER JOIN note_tags ON note_tags.tag_id = tags.id
            WHERE note_tags.note_id = ?
            ORDER BY tags.name ASC
        """

        /** Verbatim from `TagDao.observeTagsForTimeEntry`. */
        const val TAGS_FOR_TIME_ENTRY_SQL = """
            SELECT tags.* FROM tags
            INNER JOIN time_entry_tags ON time_entry_tags.tag_id = tags.id
            WHERE time_entry_tags.time_entry_id = ?
            ORDER BY tags.name ASC
        """

        /** Verbatim from `TagDao.getTagNamesForNotes`, with the IN-list expanded by the caller. */
        const val TAG_NAMES_FOR_NOTES_SQL = """
            SELECT note_tags.note_id AS noteId, tags.name AS tagName
            FROM note_tags
            INNER JOIN tags ON tags.id = note_tags.tag_id
            WHERE note_tags.note_id IN ({noteIds})
            ORDER BY note_tags.note_id ASC, tags.name ASC
        """

        /** Verbatim from `TagDao.getByNormalizedName`. */
        const val TAG_BY_NORMALIZED_NAME_SQL =
            "SELECT * FROM tags WHERE normalized_name = ? LIMIT 1"

        /** The `INSERT OR IGNORE` `TagDao.insert` performs. */
        const val INSERT_TAG_SQL = """
            INSERT OR IGNORE INTO tags
            (id, name, normalized_name, usage_count, last_used_at, created_at, updated_at)
            VALUES (?, ?, ?, 0, NULL, ?, ?)
        """

        /** Verbatim from `TagDao.markUsed`. */
        const val MARK_TAG_USED_SQL = """
            UPDATE tags
            SET usage_count = usage_count + 1,
                last_used_at = ?,
                updated_at = ?
            WHERE id = ?
        """

        const val CLEAR_TASK_TAGS_SQL = "DELETE FROM task_tags WHERE task_id = ?"
        const val CLEAR_NOTE_TAGS_SQL = "DELETE FROM note_tags WHERE note_id = ?"
        const val CLEAR_TIME_ENTRY_TAGS_SQL = "DELETE FROM time_entry_tags WHERE time_entry_id = ?"

        /**
         * The link insert, shared by all three junction tables. Each `TagDao.insert*Tags` does the same
         * `INSERT OR REPLACE`, differing only in the table and its owner column, so the table and column
         * are substituted rather than three near-identical statements being maintained side by side.
         */
        const val INSERT_TAG_LINK_SQL = """
            INSERT OR REPLACE INTO {table} ({ownerColumn}, tag_id, created_at)
            VALUES (?, ?, ?)
        """
    }
}
