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
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : JournalRepository {
        private val logger = UnifiedLogger.getInstance()
        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        override suspend fun getOrCreateEntry(date: LocalDate): DayJournalEntry {
            val dateStr = date.format(dateFormatter)
            var entry = sessionManager.requireDatabase().journalDao().getEntryForDate(dateStr)
            if (entry == null) {
                val now = LocalDateTime.now()
                val newEntry =
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
            entryId: String,
            input: DayJournalResponseInput,
        ): DayJournalResponse {
            val now = LocalDateTime.now()
            val scopeStr = input.scope.name
            val existing = sessionManager.requireDatabase().journalDao().getResponse(entryId, scopeStr, input.dimensionKey, input.promptKey)
            val entity =
                if (existing != null) {
                    existing
                        .copy(
                            responseText = input.responseText,
                            updatedAt = now.format(dateTimeFormatter),
                        ).also { sessionManager.requireDatabase().journalDao().updateResponse(it) }
                } else {
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
                mapOf(
                    "entryId" to entryId,
                    "scope" to scopeStr,
                    "promptKey" to input.promptKey,
                ),
            )

            return entity.toDomain()
        }

        override suspend fun getResponse(
            entryId: String,
            scope: JournalPromptScope,
            dimensionKey: String?,
            promptKey: String,
        ): DayJournalResponse? {
            val response =
                sessionManager
                    .requireDatabase()
                    .journalDao()
                    .getResponse(entryId, scope.name, dimensionKey, promptKey)
                    ?.toDomain()
            logger.d(
                "JournalRepositoryImpl.getResponse",
                "Fetched journal response",
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
            val entry =
                sessionManager
                    .requireDatabase()
                    .journalDao()
                    .getEntryForDate(dateString)
                    ?.toDomain()
            logger.d(
                "JournalRepositoryImpl.getEntryByDate",
                "Fetched journal entry by date",
                mapOf(
                    "date" to dateString,
                    "found" to (entry != null),
                ),
            )
            return entry
        }

        override suspend fun insertEntry(entry: DayJournalEntry) {
            val entity =
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
            val responses =
                sessionManager
                    .requireDatabase()
                    .journalDao()
                    .getResponsesForEntryOnce(entryId)
                    .map { it.toDomain() }
            logger.d(
                "JournalRepositoryImpl.getResponsesByEntryId",
                "Fetched responses for entry",
                mapOf(
                    "entryId" to entryId,
                    "count" to responses.size,
                ),
            )
            return responses
        }

        override suspend fun upsertResponse(response: DayJournalResponse) {
            val now = LocalDateTime.now().format(dateTimeFormatter)
            val existing =
                sessionManager.requireDatabase().journalDao().getResponse(
                    response.entryId,
                    response.scope,
                    response.dimensionKey,
                    response.promptKey,
                )
            if (existing != null) {
                val updated =
                    existing.copy(
                        responseText = response.responseText,
                        updatedAt = now,
                    )
                sessionManager.requireDatabase().journalDao().updateResponse(updated)
                logger.d("JournalRepositoryImpl.upsertResponse", "Response updated", mapOf("id" to response.id))
            } else {
                val entity =
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
            DayJournalEntry(
                id = id,
                entryDate = entryDate,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )

        private fun DayJournalResponseEntity.toDomain() =
            DayJournalResponse(
                id = id,
                entryId = entryId,
                scope = scope,
                dimensionKey = dimensionKey,
                promptKey = promptKey,
                responseText = responseText ?: "",
            )
    }
