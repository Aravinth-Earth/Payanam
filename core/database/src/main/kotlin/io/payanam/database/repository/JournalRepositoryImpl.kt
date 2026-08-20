//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length")

package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.DayJournalEntryEntity
import io.payanam.database.entity.DayJournalResponseEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.DayJournalEntry
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DayJournalResponseInput
import io.payanam.domain.model.JournalPromptScope
import io.payanam.domain.repository.JournalRepository
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
 * JournalRepositoryImpl.
 */
class JournalRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : JournalRepository {
        private val logger = UnifiedLogger.getInstance()
        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        override suspend fun getOrCreateEntry(date: LocalDate): DayJournalEntry {
            /** Date str. */
            val dateStr = date.format(dateFormatter)

            /** Entry. */
            var entry = sessionManager.requireDatabase().journalDao().getEntryForDate(dateStr)
            /** If. */
            if (entry == null) {
                /** Now. */
                val now = LocalDateTime.now()
                /** New entry. */
                val newEntry =
                    /** Day journal entry entity. */
                    DayJournalEntryEntity(
                        id = UUID.randomUUID().toString(),
                        entryDate = dateStr,
                        createdAt = now.format(dateTimeFormatter),
                        updatedAt = now.format(dateTimeFormatter),
                    )
                sessionManager.requireDatabase().journalDao().insertEntry(newEntry)
                entry = newEntry
                logger.i("JournalRepositoryImpl.getOrCreateEntry", "Created new journal entry", mapOf("date" to dateStr))
            }

            return entry.toDomain()
        }

        override fun observeEntry(date: LocalDate): Flow<DayJournalEntry?> {
            /** Date str. */
            val dateStr = date.format(dateFormatter)
            logger.d("JournalRepositoryImpl.observeEntry", "Subscribing to journal entry", mapOf("date" to dateStr))
            return sessionManager
                .requireDatabase()
                .journalDao()
                .observeEntryForDate(dateStr)
                .map { it?.toDomain() }
        }

        override fun getResponses(entryId: String): Flow<List<DayJournalResponse>> {
            logger.d("JournalRepositoryImpl.getResponses", "Subscribing to journal responses", mapOf("entryId" to entryId))
            return sessionManager.requireDatabase().journalDao().getResponsesForEntry(entryId).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        override suspend fun saveResponse(
            /** Entry id. */
            entryId: String,
            /** Input. */
            input: DayJournalResponseInput,
        ): DayJournalResponse {
            /** Now. */
            val now = LocalDateTime.now()
            /** Scope str. */
            val scopeStr = input.scope.name

            /** Existing. */
            val existing = sessionManager.requireDatabase().journalDao().getResponse(entryId, scopeStr, input.dimensionKey, input.promptKey)

            /** Entity. */
            val entity =
                /** If. */
                if (existing != null) {
                    /** Existing. */
                    existing
                        .copy(
                            responseText = input.responseText,
                            updatedAt = now.format(dateTimeFormatter),
                        ).also { sessionManager.requireDatabase().journalDao().updateResponse(it) }
                } else {
                    /** Day journal response entity. */
                    DayJournalResponseEntity(
                        id = UUID.randomUUID().toString(),
                        entryId = entryId,
                        scope = scopeStr,
                        dimensionKey = input.dimensionKey,
                        promptKey = input.promptKey,
                        responseText = input.responseText,
                        createdAt = now.format(dateTimeFormatter),
                        updatedAt = now.format(dateTimeFormatter),
                    ).also { sessionManager.requireDatabase().journalDao().insertResponse(it) }
                }

            logger.i(
                "JournalRepositoryImpl.saveResponse",
                "Saved journal response",
                /** Map of. */
                mapOf(
                    "entryId" to entryId,
                    "scope" to scopeStr,
                    "promptKey" to input.promptKey,
                ),
            )

            return entity.toDomain()
        }

        override suspend fun getResponse(
            /** Entry id. */
            entryId: String,
            /** Scope. */
            scope: JournalPromptScope,
            dimensionKey: String?,
            /** Prompt key. */
            promptKey: String,
        ): DayJournalResponse? {
            /** Response. */
            val response =
                /** Session manager. */
                sessionManager
                    .requireDatabase()
                    .journalDao()
                    .getResponse(entryId, scope.name, dimensionKey, promptKey)
                    ?.toDomain()
            logger.d(
                "JournalRepositoryImpl.getResponse",
                "Fetched journal response",
                /** Map of. */
                mapOf(
                    "entryId" to entryId,
                    "scope" to scope.name,
                    "promptKey" to promptKey,
                    "found" to (response != null),
                ),
            )
            return response
        }

        override suspend fun getEntryByDate(dateString: String): DayJournalEntry? {
            /** Entry. */
            val entry =
                /** Session manager. */
                sessionManager
                    .requireDatabase()
                    .journalDao()
                    .getEntryForDate(dateString)
                    ?.toDomain()
            logger.d(
                "JournalRepositoryImpl.getEntryByDate",
                "Fetched journal entry by date",
                /** Map of. */
                mapOf(
                    "date" to dateString,
                    "found" to (entry != null),
                ),
            )
            return entry
        }

        override suspend fun insertEntry(entry: DayJournalEntry) {
            /** Entity. */
            val entity =
                /** Day journal entry entity. */
                DayJournalEntryEntity(
                    id = entry.id,
                    entryDate = entry.entryDate,
                    createdAt = entry.createdAt,
                    updatedAt = entry.updatedAt,
                )
            sessionManager.requireDatabase().journalDao().insertEntry(entity)
            logger.d("JournalRepositoryImpl.insertEntry", "Entry inserted", mapOf("id" to entry.id))
        }

        override suspend fun getResponsesByEntryId(entryId: String): List<DayJournalResponse> {
            /** Responses. */
            val responses =
                /** Session manager. */
                sessionManager
                    .requireDatabase()
                    .journalDao()
                    .getResponsesForEntryOnce(entryId)
                    .map { it.toDomain() }
            logger.d(
                "JournalRepositoryImpl.getResponsesByEntryId",
                "Fetched responses for entry",
                /** Map of. */
                mapOf(
                    "entryId" to entryId,
                    "count" to responses.size,
                ),
            )
            return responses
        }

        override suspend fun upsertResponse(response: DayJournalResponse) {
            /** Now. */
            val now = LocalDateTime.now().format(dateTimeFormatter)
            /** Existing. */
            val existing =
                sessionManager.requireDatabase().journalDao().getResponse(
                    response.entryId,
                    response.scope,
                    response.dimensionKey,
                    response.promptKey,
                )

            /** If. */
            if (existing != null) {
                /** Updated. */
                val updated =
                    existing.copy(
                        responseText = response.responseText,
                        updatedAt = now,
                    )
                sessionManager.requireDatabase().journalDao().updateResponse(updated)
                logger.d("JournalRepositoryImpl.upsertResponse", "Response updated", mapOf("id" to response.id))
            } else {
                /** Entity. */
                val entity =
                    /** Day journal response entity. */
                    DayJournalResponseEntity(
                        id = response.id,
                        entryId = response.entryId,
                        scope = response.scope,
                        dimensionKey = response.dimensionKey,
                        promptKey = response.promptKey,
                        responseText = response.responseText,
                        createdAt = now,
                        updatedAt = now,
                    )
                sessionManager.requireDatabase().journalDao().insertResponse(entity)
                logger.d("JournalRepositoryImpl.upsertResponse", "Response inserted", mapOf("id" to response.id))
            }
        }

        override fun getAllJournalEntries(): Flow<List<DayJournalEntry>> {
            logger.d("JournalRepositoryImpl.getAllJournalEntries", "Subscribing to all journal entries")
            return sessionManager.requireDatabase().journalDao().getAllEntries().map { entities ->
                entities.map { it.toDomain() }
            }
        }

        override fun getTotalResponseCount(): Flow<Int> =
            sessionManager.requireDatabase().journalDao().getAllResponses().map { responses ->
                responses.size
            }

        // Mappers
        private fun DayJournalEntryEntity.toDomain() =
            /** Day journal entry. */
            DayJournalEntry(
                id = id,
                entryDate = entryDate,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )

        private fun DayJournalResponseEntity.toDomain() =
            /** Day journal response. */
            DayJournalResponse(
                id = id,
                entryId = entryId,
                scope = scope,
                dimensionKey = dimensionKey,
                promptKey = promptKey,
                responseText = responseText ?: "",
            )
    }
