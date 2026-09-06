//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length")

package io.payanam.database.repository

import androidx.room.withTransaction
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.NoteTagEntity
import io.payanam.database.entity.TagEntity
import io.payanam.database.entity.TaskTagEntity
import io.payanam.database.entity.TimeEntryTagEntity
import io.payanam.database.mapper.TagMapper.toDomain
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.Tag
import io.payanam.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * Room-backed implementation of [TagRepository]. Wraps [TagDao]; tag names are
 * normalized (trim + lowercase) so matching is case-insensitive and reused
 * rather than duplicated. The three `replace*` methods share one transactional
 * clear-and-reinsert path that also bumps each tag's usage count.
 */
class TagRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : TagRepository {
        private val logger = UnifiedLogger.getInstance()
        private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /**
         * Emits every tag, popularity-ordered, as a [Flow].
         */
        override fun observeAllTags(): Flow<List<Tag>> {
            logger.d("TagRepositoryImpl.observeAllTags", "Subscribing to all tags")
            return sessionManager.requireDatabase().tagDao().observeAllTags().map { entities ->
                entities.map { it.toDomain() }
            }
        }

        /**
         * Emits tags whose normalized name starts with the normalized [query],
         * capped at [limit] — backs type-ahead tag entry.
         */
        override fun searchTagsByPrefix(
            query: String,
            limit: Int,
        ): Flow<List<Tag>> {
            val normalizedPrefix = normalizeTagName(query)
            logger.d(
                "TagRepositoryImpl.searchTagsByPrefix",
                "Subscribing to tag prefix search",
                mapOf(
                    "prefix" to normalizedPrefix,
                    "limit" to limit,
                ),
            )
            return sessionManager.requireDatabase().tagDao().searchByPrefix(normalizedPrefix, limit).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        /**
         * Emits the tags linked to [taskId], as a [Flow].
         */
        override fun observeTagsForTask(taskId: String): Flow<List<Tag>> {
            logger.d("TagRepositoryImpl.observeTagsForTask", "Subscribing to tags for task", mapOf("taskId" to taskId))
            return sessionManager.requireDatabase().tagDao().observeTagsForTask(taskId).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        /**
         * Emits the tags linked to [noteId], as a [Flow].
         */
        override fun observeTagsForNote(noteId: String): Flow<List<Tag>> {
            logger.d("TagRepositoryImpl.observeTagsForNote", "Subscribing to tags for note", mapOf("noteId" to noteId))
            return sessionManager.requireDatabase().tagDao().observeTagsForNote(noteId).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        /**
         * Returns a map of note id → its tag names for every [noteIds] (batched
         * query; empty input returns an empty map). Used to render tag chips in
         * lists without per-note round-trips.
         */
        override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> {
            if (noteIds.isEmpty()) {
                return emptyMap()
            }
            logger.d(
                "TagRepositoryImpl.getTagNamesForNotes",
                "Loading note tags in batch",
                mapOf("noteCount" to noteIds.size),
            )
            return sessionManager.requireDatabase()
                .tagDao()
                .getTagNamesForNotes(noteIds)
                .groupBy(keySelector = { it.noteId }, valueTransform = { it.tagName })
        }

        /**
         * Emits the tags linked to [timeEntryId], as a [Flow].
         */
        override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> {
            logger.d("TagRepositoryImpl.observeTagsForTimeEntry", "Subscribing to tags for time entry", mapOf("timeEntryId" to timeEntryId))
            return sessionManager.requireDatabase().tagDao().observeTagsForTimeEntry(timeEntryId).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        /**
         * Replaces [taskId]'s entire tag set with [tagNames] in one transaction:
         * clears existing links, ensures each normalized tag exists, re-links, and
         * bumps usage counts.
         */
        override suspend fun replaceTaskTags(
            taskId: String,
            tagNames: List<String>,
        ) {
            replaceTags(
                ownerId = taskId,
                tagNames = tagNames,
                clearOwnerTags = { id -> sessionManager.requireDatabase().tagDao().clearTaskTags(id) },
                insertLinks = { tagIds, now ->
                    sessionManager.requireDatabase().tagDao().insertTaskTags(
                        tagIds.map { tagId ->
                            TaskTagEntity(taskId = taskId, tagId = tagId, createdAt = now)
                        },
                    )
                },
            )
        }

        /**
         * Replaces [noteId]'s entire tag set with [tagNames] in one transaction
         * (clear → ensure → re-link → bump usage).
         */
        override suspend fun replaceNoteTags(
            noteId: String,
            tagNames: List<String>,
        ) {
            replaceTags(
                ownerId = noteId,
                tagNames = tagNames,
                clearOwnerTags = { id -> sessionManager.requireDatabase().tagDao().clearNoteTags(id) },
                insertLinks = { tagIds, now ->
                    sessionManager.requireDatabase().tagDao().insertNoteTags(
                        tagIds.map { tagId ->
                            NoteTagEntity(noteId = noteId, tagId = tagId, createdAt = now)
                        },
                    )
                },
            )
        }

        /**
         * Replaces [timeEntryId]'s entire tag set with [tagNames] in one transaction
         * (clear → ensure → re-link → bump usage).
         */
        override suspend fun replaceTimeEntryTags(
            timeEntryId: String,
            tagNames: List<String>,
        ) {
            replaceTags(
                ownerId = timeEntryId,
                tagNames = tagNames,
                clearOwnerTags = { id -> sessionManager.requireDatabase().tagDao().clearTimeEntryTags(id) },
                insertLinks = { tagIds, now ->
                    sessionManager.requireDatabase().tagDao().insertTimeEntryTags(
                        tagIds.map { tagId ->
                            TimeEntryTagEntity(timeEntryId = timeEntryId, tagId = tagId, createdAt = now)
                        },
                    )
                },
            )
        }

        private suspend fun replaceTags(
            ownerId: String,
            tagNames: List<String>,
            clearOwnerTags: suspend (String) -> Unit,
            insertLinks: suspend (List<String>, String) -> Unit,
        ) {
            val cleanNames =
                tagNames
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinctBy { normalizeTagName(it) }

            sessionManager.requireDatabase().withTransaction {
                clearOwnerTags(ownerId)
                if (cleanNames.isEmpty()) {
                    logger.d(
                        "TagRepositoryImpl.replaceTags",
                        "Cleared tags for owner with empty input",
                        mapOf(
                            "ownerId" to ownerId,
                        ),
                    )
                    return@withTransaction
                }
                val now = LocalDateTime.now().format(formatter)
                val tagIds =
                    cleanNames.map { tagName ->
                        ensureTagExists(tagName, now)
                    }
                insertLinks(tagIds, now)
                tagIds.forEach { tagId ->
                    sessionManager.requireDatabase().tagDao().markUsed(tagId, usedAt = now, updatedAt = now)
                }

                logger.i(
                    "TagRepositoryImpl.replaceTags",
                    "Updated owner tags",
                    mapOf(
                        "ownerId" to ownerId,
                        "tagCount" to tagIds.size.toString(),
                    ),
                )
            }
        }

        private suspend fun ensureTagExists(
            tagName: String,
            now: String,
        ): String {
            val normalized = normalizeTagName(tagName)
            val existing = sessionManager.requireDatabase().tagDao().getByNormalizedName(normalized)
            if (existing != null) {
                return existing.id
            }
            val entity =
                TagEntity(
                    id = UUID.randomUUID().toString(),
                    name = tagName,
                    normalizedName = normalized,
                    usageCount = 0,
                    lastUsedAt = null,
                    createdAt = now,
                    updatedAt = now,
                )
            sessionManager.requireDatabase().tagDao().insert(entity)
            logger.i("TagRepositoryImpl.ensureTagExists", "Created new tag", mapOf("name" to tagName, "normalized" to normalized))
            return sessionManager
                .requireDatabase()
                .tagDao()
                .getByNormalizedName(normalized)
                ?.id ?: entity.id
        }

        private fun normalizeTagName(name: String): String = name.trim().lowercase(Locale.ROOT)
    }
