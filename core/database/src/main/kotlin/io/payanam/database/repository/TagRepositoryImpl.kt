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
 * TagRepositoryImpl.
 */
class TagRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : TagRepository {
        private val logger = UnifiedLogger.getInstance()
        private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        override fun observeAllTags(): Flow<List<Tag>> {
            logger.d("TagRepositoryImpl.observeAllTags", "Subscribing to all tags")
            return sessionManager.requireDatabase().tagDao().observeAllTags().map { entities ->
                entities.map { it.toDomain() }
            }
        }

        override fun searchTagsByPrefix(
            /** Query. */
            query: String,
            /** Limit. */
            limit: Int,
        ): Flow<List<Tag>> {
            /** Normalized prefix. */
            val normalizedPrefix = normalizeTagName(query)
            logger.d(
                "TagRepositoryImpl.searchTagsByPrefix",
                "Subscribing to tag prefix search",
                /** Map of. */
                mapOf(
                    "prefix" to normalizedPrefix,
                    "limit" to limit,
                ),
            )
            return sessionManager.requireDatabase().tagDao().searchByPrefix(normalizedPrefix, limit).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        override fun observeTagsForTask(taskId: String): Flow<List<Tag>> {
            logger.d("TagRepositoryImpl.observeTagsForTask", "Subscribing to tags for task", mapOf("taskId" to taskId))
            return sessionManager.requireDatabase().tagDao().observeTagsForTask(taskId).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        override fun observeTagsForNote(noteId: String): Flow<List<Tag>> {
            logger.d("TagRepositoryImpl.observeTagsForNote", "Subscribing to tags for note", mapOf("noteId" to noteId))
            return sessionManager.requireDatabase().tagDao().observeTagsForNote(noteId).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> {
            /** If. */
            if (noteIds.isEmpty()) {
                return emptyMap()
            }
            logger.d(
                "TagRepositoryImpl.getTagNamesForNotes",
                "Loading note tags in batch",
                /** Map of. */
                mapOf("noteCount" to noteIds.size),
            )
            return sessionManager.requireDatabase()
                .tagDao()
                .getTagNamesForNotes(noteIds)
                .groupBy(keySelector = { it.noteId }, valueTransform = { it.tagName })
        }

        override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> {
            logger.d("TagRepositoryImpl.observeTagsForTimeEntry", "Subscribing to tags for time entry", mapOf("timeEntryId" to timeEntryId))
            return sessionManager.requireDatabase().tagDao().observeTagsForTimeEntry(timeEntryId).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        override suspend fun replaceTaskTags(
            /** Task id. */
            taskId: String,
            tagNames: List<String>,
        ) {
            /** Replace tags. */
            replaceTags(
                ownerId = taskId,
                tagNames = tagNames,
                clearOwnerTags = { id -> sessionManager.requireDatabase().tagDao().clearTaskTags(id) },
                insertLinks = { tagIds, now ->
                    sessionManager.requireDatabase().tagDao().insertTaskTags(
                        tagIds.map { tagId ->
                            /** Task tag entity. */
                            TaskTagEntity(taskId = taskId, tagId = tagId, createdAt = now)
                        },
                    )
                },
            )
        }

        override suspend fun replaceNoteTags(
            /** Note id. */
            noteId: String,
            tagNames: List<String>,
        ) {
            /** Replace tags. */
            replaceTags(
                ownerId = noteId,
                tagNames = tagNames,
                clearOwnerTags = { id -> sessionManager.requireDatabase().tagDao().clearNoteTags(id) },
                insertLinks = { tagIds, now ->
                    sessionManager.requireDatabase().tagDao().insertNoteTags(
                        tagIds.map { tagId ->
                            /** Note tag entity. */
                            NoteTagEntity(noteId = noteId, tagId = tagId, createdAt = now)
                        },
                    )
                },
            )
        }

        override suspend fun replaceTimeEntryTags(
            /** Time entry id. */
            timeEntryId: String,
            tagNames: List<String>,
        ) {
            /** Replace tags. */
            replaceTags(
                ownerId = timeEntryId,
                tagNames = tagNames,
                clearOwnerTags = { id -> sessionManager.requireDatabase().tagDao().clearTimeEntryTags(id) },
                insertLinks = { tagIds, now ->
                    sessionManager.requireDatabase().tagDao().insertTimeEntryTags(
                        tagIds.map { tagId ->
                            /** Time entry tag entity. */
                            TimeEntryTagEntity(timeEntryId = timeEntryId, tagId = tagId, createdAt = now)
                        },
                    )
                },
            )
        }

        private suspend fun replaceTags(
            /** Owner id. */
            ownerId: String,
            tagNames: List<String>,
            clearOwnerTags: suspend (String) -> Unit,
            insertLinks: suspend (List<String>, String) -> Unit,
        ) {
            /** Clean names. */
            val cleanNames =
                /** Tag names. */
                tagNames
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinctBy { normalizeTagName(it) }

            sessionManager.requireDatabase().withTransaction {
                /** Clear owner tags. */
                clearOwnerTags(ownerId)
                /** If. */
                if (cleanNames.isEmpty()) {
                    logger.d(
                        "TagRepositoryImpl.replaceTags",
                        "Cleared tags for owner with empty input",
                        /** Map of. */
                        mapOf(
                            "ownerId" to ownerId,
                        ),
                    )
                    return@withTransaction
                }

                /** Now. */
                val now = LocalDateTime.now().format(formatter)
                /** Tag ids. */
                val tagIds =
                    cleanNames.map { tagName ->
                        /** Ensure tag exists. */
                        ensureTagExists(tagName, now)
                    }
                /** Insert links. */
                insertLinks(tagIds, now)
                tagIds.forEach { tagId ->
                    sessionManager.requireDatabase().tagDao().markUsed(tagId, usedAt = now, updatedAt = now)
                }

                logger.i(
                    "TagRepositoryImpl.replaceTags",
                    "Updated owner tags",
                    /** Map of. */
                    mapOf(
                        "ownerId" to ownerId,
                        "tagCount" to tagIds.size.toString(),
                    ),
                )
            }
        }

        private suspend fun ensureTagExists(
            /** Tag name. */
            tagName: String,
            /** Now. */
            now: String,
        ): String {
            /** Normalized. */
            val normalized = normalizeTagName(tagName)
            /** Existing. */
            val existing = sessionManager.requireDatabase().tagDao().getByNormalizedName(normalized)
            /** If. */
            if (existing != null) {
                return existing.id
            }

            /** Entity. */
            val entity =
                /** Tag entity. */
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
