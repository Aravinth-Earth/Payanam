//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length")

package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.JournalNoteEntity
import io.payanam.database.entity.NoteEntity
import io.payanam.database.mapper.NoteMapper.toDomain
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.note.NoteDimensionResolution
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
 * Room-backed implementation of [NoteRepository]. Wraps [JournalNoteEntity],
 * resolves the life-dimension from an explicit id or category label, and keeps
 * the legacy `notes` shadow table in sync inside a transaction (for tag FKs and
 * read paths that still read the shadow).
 */
class NoteRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : NoteRepository {
        private val logger = UnifiedLogger.getInstance()
        private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /**
         * Emits every note, mapped to the domain model, as a [Flow] (newest first).
         */
        override fun getAllNotes(): Flow<List<Note>> {
            logger.d("NoteRepositoryImpl.getAllNotes", "Subscribing to all notes")
            return sessionManager.requireDatabase().journalDao().getAllNotes().map { entities ->
                logger.d("NoteRepositoryImpl.getAllNotes", "Notes emitted", mapOf("count" to entities.size))
                entities.map { it.toDomain() }
            }
        }

        /**
         * Emits notes tied to [dimension] (by id or life-intention category),
         * mapped to the domain model, as a [Flow].
         */
        override fun getNotesByDimension(dimension: String): Flow<List<Note>> {
            logger.d("NoteRepositoryImpl.getNotesByDimension", "Subscribing to notes by dimension", mapOf("dimension" to dimension))
            return sessionManager.requireDatabase().journalDao().getNotesByDimension(dimension).map { entities ->
                logger.d(
                    "NoteRepositoryImpl.getNotesByDimension",
                    "Notes emitted for dimension",
                    mapOf(
                        "dimension" to dimension,
                        "count" to entities.size,
                    ),
                )
                entities.map { it.toDomain() }
            }
        }

        /**
         * Emits notes dated [date], mapped to the domain model, as a [Flow].
         */
        override fun getNotesForDate(date: LocalDate): Flow<List<Note>> {
            logger.d("NoteRepositoryImpl.getNotesForDate", "Subscribing to notes for date", mapOf("date" to date.toString()))
            return sessionManager.requireDatabase().journalDao().getNotesForDay(date.toString()).map { entities ->
                logger.d(
                    "NoteRepositoryImpl.getNotesForDate",
                    "Notes emitted for date",
                    mapOf(
                        "date" to date.toString(),
                        "count" to entities.size,
                    ),
                )
                entities.map { it.toDomain() }
            }
        }

        /**
         * Returns the note with [id] mapped to the domain model, or null.
         */
        override suspend fun getNoteById(id: String): Note? {
            val note =
                sessionManager
                    .requireDatabase()
                    .journalDao()
                    .getNoteById(id)
                    ?.toDomain()
            logger.d("NoteRepositoryImpl.getNoteById", "Fetched note by id", mapOf("id" to id, "found" to (note != null)))
            return note
        }

        /**
         * Creates a note from [input], generating id/timestamps, resolving the
         * life-dimension, and writing both the journal note and its legacy `notes`
         * shadow row inside one transaction. Returns the domain [Note].
         */
        override suspend fun createNote(input: NoteInput): Note {
            logger.i("NoteRepositoryImpl.createNote", "Creating new note")
            val now = LocalDateTime.now()
            val id = UUID.randomUUID().toString()
            val resolvedDimensionId =
                NoteDimensionResolution.resolveDimensionId(
                    explicitDimensionId = input.dimensionId,
                    categoryLabel = input.lifeIntentionCategory,
                )
            val resolvedDimensionLabel =
                NoteDimensionResolution.resolveDimensionLabel(
                    explicitLabel = input.lifeIntentionCategory,
                    resolvedDimensionId = resolvedDimensionId,
                )
            val note =
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

            sessionManager.inTransaction {
                sessionManager.requireDatabase().journalDao().insertNote(note)
                syncLegacyNoteShadow(note)
            }

            logger.i("NoteRepositoryImpl.createNote", "Note created successfully", mapOf("id" to id))
            return note.toDomain()
        }

        /**
         * Applies a partial update from [input] to the note [id]. Missing fields
         * fall back to current values; the dimension is re-resolved and both the
         * journal note and legacy shadow are rewritten in a transaction. Throws
         * [IllegalArgumentException] when [id] does not exist. Returns the domain
         * [Note].
         */
        override suspend fun updateNote(
            id: String,
            input: NoteInput,
        ): Note {
            logger.i("NoteRepositoryImpl.updateNote", "Updating note", mapOf("id" to id))
            val existing =
                sessionManager.requireDatabase().journalDao().getNoteById(id)
                    ?: run {
                        logger.w("NoteRepositoryImpl.updateNote", "Note not found for update", mapOf("id" to id))
                        throw IllegalArgumentException("Note not found: $id")
                    }
            val now = LocalDateTime.now()
            val resolvedDimensionId =
                NoteDimensionResolution.resolveDimensionId(
                    explicitDimensionId = input.dimensionId,
                    categoryLabel = input.lifeIntentionCategory,
                )
            val resolvedDimensionLabel =
                resolvedDimensionId
                    ?.let { NoteDimensionResolution.resolveDimensionLabel(null, it) }
                    ?: existing.lifeIntentionCategory
            val updated =
                existing.copy(
                    title = input.title,
                    details = input.details,
                    lifeIntentionCategory = resolvedDimensionLabel,
                    dimensionId = resolvedDimensionId ?: existing.dimensionId,
                    dayKey = existing.dayKey.ifEmpty { existing.createdAt.take(10) },
                    updatedAt = now.format(formatter),
                )

            sessionManager.inTransaction {
                sessionManager.requireDatabase().journalDao().updateNote(updated)
                syncLegacyNoteShadow(updated)
            }

            logger.i("NoteRepositoryImpl.updateNote", "Note updated successfully", mapOf("id" to id))
            return updated.toDomain()
        }

        /**
         * Deletes the note [id] from both the journal note table and the legacy
         * `notes` shadow in a single transaction.
         */
        override suspend fun deleteNote(id: String) {
            logger.i("NoteRepositoryImpl.deleteNote", "Deleting note", mapOf("id" to id))
            sessionManager.inTransaction {
                sessionManager.requireDatabase().journalDao().deleteNoteById(id)
                sessionManager.requireDatabase().noteDao().deleteById(id)
            }
            logger.i("NoteRepositoryImpl.deleteNote", "Note deleted successfully", mapOf("id" to id))
        }

        private suspend fun syncLegacyNoteShadow(note: JournalNoteEntity) {
            // Keep the notes shadow table in sync for tag foreign keys and read paths that still use it.
            sessionManager.requireDatabase().noteDao().insert(
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

    }
