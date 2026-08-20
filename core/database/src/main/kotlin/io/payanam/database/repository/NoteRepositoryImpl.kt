//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length")

package io.payanam.database.repository

import androidx.room.withTransaction
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.JournalNoteEntity
import io.payanam.database.entity.NoteEntity
import io.payanam.database.mapper.NoteMapper.toDomain
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Note
import io.payanam.domain.model.NoteInput
import io.payanam.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * NoteRepositoryImpl.
 */
class NoteRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : NoteRepository {
        private val logger = UnifiedLogger.getInstance()
        private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        override fun getAllNotes(): Flow<List<Note>> {
            logger.d("NoteRepositoryImpl.getAllNotes", "Subscribing to all notes")
            return sessionManager.requireDatabase().journalDao().getAllNotes().map { entities ->
                logger.d("NoteRepositoryImpl.getAllNotes", "Notes emitted", mapOf("count" to entities.size))
                entities.map { it.toDomain() }
            }
        }

        override fun getNotesByDimension(dimension: String): Flow<List<Note>> {
            logger.d("NoteRepositoryImpl.getNotesByDimension", "Subscribing to notes by dimension", mapOf("dimension" to dimension))
            return sessionManager.requireDatabase().journalDao().getNotesByDimension(dimension).map { entities ->
                logger.d(
                    "NoteRepositoryImpl.getNotesByDimension",
                    "Notes emitted for dimension",
                    /** Map of. */
                    mapOf(
                        "dimension" to dimension,
                        "count" to entities.size,
                    ),
                )
                entities.map { it.toDomain() }
            }
        }

        override fun getNotesForDate(date: LocalDate): Flow<List<Note>> {
            logger.d("NoteRepositoryImpl.getNotesForDate", "Subscribing to notes for date", mapOf("date" to date.toString()))
            return sessionManager.requireDatabase().journalDao().getNotesForDay(date.toString()).map { entities ->
                logger.d(
                    "NoteRepositoryImpl.getNotesForDate",
                    "Notes emitted for date",
                    /** Map of. */
                    mapOf(
                        "date" to date.toString(),
                        "count" to entities.size,
                    ),
                )
                entities.map { it.toDomain() }
            }
        }

        override suspend fun getNoteById(id: String): Note? {
            /** Note. */
            val note =
                /** Session manager. */
                sessionManager
                    .requireDatabase()
                    .journalDao()
                    .getNoteById(id)
                    ?.toDomain()
            logger.d("NoteRepositoryImpl.getNoteById", "Fetched note by id", mapOf("id" to id, "found" to (note != null)))
            return note
        }

        override suspend fun createNote(input: NoteInput): Note {
            logger.i("NoteRepositoryImpl.createNote", "Creating new note")
            /** Now. */
            val now = LocalDateTime.now()
            /** Id. */
            val id = UUID.randomUUID().toString()
            /** Resolved dimension id. */
            val resolvedDimensionId =
                /** Resolve dimension id. */
                resolveDimensionId(
                    explicitDimensionId = input.dimensionId,
                    categoryLabel = input.lifeIntentionCategory,
                )
            /** Resolved dimension label. */
            val resolvedDimensionLabel =
                /** Resolve dimension label. */
                resolveDimensionLabel(
                    explicitLabel = input.lifeIntentionCategory,
                    resolvedDimensionId = resolvedDimensionId,
                )

            /** Note. */
            val note =
                /** Journal note entity. */
                JournalNoteEntity(
                    id = id,
                    title = input.title,
                    details = input.details,
                    lifeIntentionCategory = resolvedDimensionLabel,
                    dimensionId = resolvedDimensionId,
                    dayKey = now.toLocalDate().toString(),
                    createdAt = now.format(formatter),
                    updatedAt = now.format(formatter),
                )

            sessionManager.requireDatabase().withTransaction {
                sessionManager.requireDatabase().journalDao().insertNote(note)
                /** Sync legacy note shadow. */
                syncLegacyNoteShadow(note)
            }

            logger.i("NoteRepositoryImpl.createNote", "Note created successfully", mapOf("id" to id))
            return note.toDomain()
        }

        override suspend fun updateNote(
            /** Id. */
            id: String,
            /** Input. */
            input: NoteInput,
        ): Note {
            logger.i("NoteRepositoryImpl.updateNote", "Updating note", mapOf("id" to id))
            /** Existing. */
            val existing =
                sessionManager.requireDatabase().journalDao().getNoteById(id)
                    ?: run {
                        logger.w("NoteRepositoryImpl.updateNote", "Note not found for update", mapOf("id" to id))
                        throw IllegalArgumentException("Note not found: $id")
                    }

            /** Now. */
            val now = LocalDateTime.now()
            /** Resolved dimension id. */
            val resolvedDimensionId =
                /** Resolve dimension id. */
                resolveDimensionId(
                    explicitDimensionId = input.dimensionId,
                    categoryLabel = input.lifeIntentionCategory,
                )
            /** Resolved dimension label. */
            val resolvedDimensionLabel =
                /** Resolved dimension id. */
                resolvedDimensionId
                    ?.let { resolveDimensionLabel(null, it) }
                    ?: existing.lifeIntentionCategory

            /** Updated. */
            val updated =
                existing.copy(
                    title = input.title,
                    details = input.details,
                    lifeIntentionCategory = resolvedDimensionLabel,
                    dimensionId = resolvedDimensionId ?: existing.dimensionId,
                    dayKey = existing.dayKey.ifEmpty { existing.createdAt.take(10) },
                    updatedAt = now.format(formatter),
                )

            sessionManager.requireDatabase().withTransaction {
                sessionManager.requireDatabase().journalDao().updateNote(updated)
                /** Sync legacy note shadow. */
                syncLegacyNoteShadow(updated)
            }

            logger.i("NoteRepositoryImpl.updateNote", "Note updated successfully", mapOf("id" to id))
            return updated.toDomain()
        }

        override suspend fun deleteNote(id: String) {
            logger.i("NoteRepositoryImpl.deleteNote", "Deleting note", mapOf("id" to id))
            sessionManager.requireDatabase().withTransaction {
                sessionManager.requireDatabase().journalDao().deleteNoteById(id)
                sessionManager.requireDatabase().noteDao().deleteById(id)
            }
            logger.i("NoteRepositoryImpl.deleteNote", "Note deleted successfully", mapOf("id" to id))
        }

        private suspend fun syncLegacyNoteShadow(note: JournalNoteEntity) {
            // Keep the notes shadow table in sync for tag foreign keys and read paths that still use it.
            sessionManager.requireDatabase().noteDao().insert(
                /** Note entity. */
                NoteEntity(
                    id = note.id,
                    title = note.title,
                    details = note.details,
                    lifeIntentionCategory = note.lifeIntentionCategory,
                    dimensionId = note.dimensionId,
                    dayKey = note.dayKey,
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                ),
            )
        }

        private fun JournalNoteEntity.toDomain(): Note =
            /** Note entity. */
            NoteEntity(
                id = id,
                title = title,
                details = details,
                lifeIntentionCategory = lifeIntentionCategory,
                dimensionId = dimensionId,
                dayKey = dayKey,
                createdAt = createdAt,
                updatedAt = updatedAt,
            ).toDomain()

        private fun resolveDimensionId(
            explicitDimensionId: String?,
            categoryLabel: String?,
        ): String? =
            explicitDimensionId?.trim()?.takeIf { it.isNotEmpty() }?.let { requestedId ->
                DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id ?: requestedId
            } ?: categoryLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { label ->
                logger.w(
                    "NoteRepositoryImpl.resolveDimensionId",
                    "Ignoring non-canonical note category label during dimension resolution",
                    /** Map of. */
                    mapOf("categoryLabel" to label),
                )
                /** Null. */
                null
            }

        private fun resolveDimensionLabel(
            explicitLabel: String?,
            resolvedDimensionId: String?,
        ): String =
            explicitLabel?.trim()?.takeIf { it.isNotEmpty() }
                ?: DimensionTaxonomyCatalog.fromCanonicalId(resolvedDimensionId)?.fallbackLabel
                ?: DimensionTaxonomyCatalog.WORK_LIVELIHOOD.fallbackLabel
    }
