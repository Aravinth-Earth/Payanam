//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.TimeEntryEntity
import io.payanam.database.mapper.TimeEntryMapper.toDomain
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.TimeEntryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * TimeEntryRepositoryImpl.
 */
class TimeEntryRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : TimeEntryRepository {
        private val logger = UnifiedLogger.getInstance()

        override suspend fun getActiveTimeEntry(): TimeEntry? {
            val entry =
                sessionManager
                    .requireDatabase()
                    .timeEntryDao()
                    .getActiveTimeEntry()
                    ?.toDomain()
            logger.d(
                "TimeEntryRepositoryImpl.getActiveTimeEntry",
                "Fetched active time entry snapshot",
                mapOf("hasActiveEntry" to (entry != null)),
            )
            return entry
        }

        override fun observeActiveTimeEntry(): Flow<TimeEntry?> =
            sessionManager.requireDatabase().timeEntryDao().observeActiveTimeEntry().map {
                it?.toDomain()
            }

        override fun getTimeEntriesForRange(
            start: LocalDateTime,
            end: LocalDateTime,
        ): Flow<List<TimeEntry>> {
            logger.d(
                "TimeEntryRepositoryImpl.getTimeEntriesForRange",
                "Subscribing to time entries for range",
                mapOf("start" to start.toString(), "end" to end.toString()),
            )
            return sessionManager
                .requireDatabase()
                .timeEntryDao()
                .getTimeEntriesForRange(
                    PersistedDateTime.format(start),
                    PersistedDateTime.format(end),
                ).map { entities ->
                    logger.d(
                        "TimeEntryRepositoryImpl.getTimeEntriesForRange",
                        "Time entries emitted for range",
                        mapOf("count" to entities.size),
                    )
                    entities.map { it.toDomain() }
                }
        }

        override fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>> {
            val dayStart = date.atStartOfDay()
            val dayEnd = dayStart.plusDays(1)
            val now = LocalDateTime.now()
            return sessionManager
                .requireDatabase()
                .timeEntryDao()
                .getTimeEntriesForDate(
                    dayStart = PersistedDateTime.format(dayStart),
                    dayEnd = PersistedDateTime.format(dayEnd),
                    currentTime = PersistedDateTime.format(now),
                ).map { entities ->
                    logger.d(
                        "TimeEntryRepositoryImpl.getTimeEntriesForDate",
                        "Time entries emitted for day",
                        mapOf("date" to date.toString(), "count" to entities.size),
                    )
                    entities.map { it.toDomain() }
                }
        }

        override suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry {
            logger.i(
                "TimeEntryRepositoryImpl.startTimeEntry",
                "Starting time entry",
                mapOf(
                    "dimensionId" to (input.dimensionId ?: "unknown"),
                    "taskId" to (input.taskId ?: "none"),
                    "startedAt" to input.startedAt.toString(),
                ),
            )

            // Stop any existing active entry first
            stopActiveTimeEntry()
            val resolvedDimensionId =
                resolvePersistedDimensionId(
                    dimensionId = input.dimensionId,
                    lifeIntentionCategory = input.lifeIntentionCategory,
                )
            val now = LocalDateTime.now()
            val id = UUID.randomUUID().toString()
            val entity =
                TimeEntryEntity(
                    id = id,
                    lifeIntentionCategory = input.lifeIntentionCategory,
                    dimensionId = resolvedDimensionId,
                    dayKey = PersistedDateTime.dayKey(input.startedAt),
                    taskId = normalizeOptionalIdentifier(input.taskId),
                    startedAt = PersistedDateTime.format(input.startedAt),
                    endedAt = null,
                    focusRating = input.focusRating,
                    focusNote = input.focusNote,
                    focusRatedAt = input.focusRatedAt?.let(PersistedDateTime::format),
                    createdAt = PersistedDateTime.format(now),
                    updatedAt = PersistedDateTime.format(now),
                )

            sessionManager.requireDatabase().timeEntryDao().insert(entity)
            markDirtyForDay(input.startedAt.toLocalDate(), "time_entry_started")
            logger.i(
                "TimeEntryRepositoryImpl.startTimeEntry",
                "Time entry started",
                mapOf(
                    "id" to id,
                    "dimensionId" to (input.dimensionId ?: "unknown"),
                ),
            )
            return entity.toDomain()
        }

        override suspend fun stopActiveTimeEntry(): TimeEntry? =
            stopActiveTimeEntryInternal(
                focusRating = 0.0,
                focusNote = null,
            )

        override suspend fun stopActiveTimeEntryWithFocus(
            focusRating: Double,
            focusNote: String?,
        ): TimeEntry? =
            stopActiveTimeEntryInternal(
                focusRating = focusRating.coerceIn(0.0, 1.0),
                focusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() },
            )

        private suspend fun stopActiveTimeEntryInternal(
            focusRating: Double?,
            focusNote: String?,
        ): TimeEntry? {
            val active = sessionManager.requireDatabase().timeEntryDao().getActiveTimeEntry()
            if (active == null) {
                logger.d("TimeEntryRepositoryImpl.stopActiveTimeEntry", "No active entry to stop")
                return null
            }

            logger.i(
                "TimeEntryRepositoryImpl.stopActiveTimeEntry",
                "Stopping active entry",
                mapOf(
                    "id" to active.id,
                    "dimensionId" to (active.dimensionId ?: "unknown"),
                ),
            )
            val now = LocalDateTime.now()
            sessionManager.requireDatabase().timeEntryDao().stopEntry(
                id = active.id,
                endedAt = PersistedDateTime.format(now),
                focusRating = focusRating,
                focusNote = focusNote,
                focusRatedAt = PersistedDateTime.format(now),
                updatedAt = PersistedDateTime.format(now),
            )
            active.dayKey?.let { markDirtyForDay(LocalDate.parse(it), "time_entry_stopped") }

            logger.i("TimeEntryRepositoryImpl.stopActiveTimeEntry", "Entry stopped", mapOf("id" to active.id))
            return sessionManager
                .requireDatabase()
                .timeEntryDao()
                .getById(active.id)
                ?.toDomain()
        }

        override suspend fun updateTimeEntry(
            id: String,
            input: TimeEntryInput,
        ): TimeEntry {
            logger.i(
                "TimeEntryRepositoryImpl.updateTimeEntry",
                "Updating time entry",
                mapOf(
                    "id" to id,
                    "taskId" to (input.taskId ?: "none"),
                    "dimensionId" to (input.dimensionId ?: "resolved_from_category"),
                ),
            )
            val existing =
                sessionManager.requireDatabase().timeEntryDao().getById(id)
                    ?: throw IllegalArgumentException("TimeEntry not found: $id")
            val now = LocalDateTime.now()
            val updated =
                existing.copy(
                    lifeIntentionCategory = input.lifeIntentionCategory,
                    dimensionId =
                        resolvePersistedDimensionId(
                            dimensionId = input.dimensionId,
                            lifeIntentionCategory = input.lifeIntentionCategory,
                            fallbackDimensionId = existing.dimensionId,
                        ),
                    taskId = normalizeOptionalIdentifier(input.taskId),
                    dayKey = PersistedDateTime.dayKey(input.startedAt),
                    startedAt = PersistedDateTime.format(input.startedAt),
                    endedAt = input.endedAt?.let(PersistedDateTime::format),
                    focusRating = input.focusRating,
                    focusNote = input.focusNote,
                    focusRatedAt = input.focusRatedAt?.let(PersistedDateTime::format),
                    updatedAt = PersistedDateTime.format(now),
                )

            sessionManager.requireDatabase().timeEntryDao().update(updated)
            existing.dayKey?.let { markDirtyForDay(LocalDate.parse(it), "time_entry_updated_previous_day") }
            markDirtyForDay(input.startedAt.toLocalDate(), "time_entry_updated_target_day")
            logger.i(
                "TimeEntryRepositoryImpl.updateTimeEntry",
                "Time entry updated",
                mapOf("id" to id, "dayKey" to updated.dayKey),
            )
            return updated.toDomain()
        }

        override suspend fun deleteTimeEntry(id: String) {
            logger.w("TimeEntryRepositoryImpl.deleteTimeEntry", "Deleting time entry", mapOf("id" to id))
            val existing = sessionManager.requireDatabase().timeEntryDao().getById(id)
            sessionManager.requireDatabase().timeEntryDao().deleteById(id)
            existing?.dayKey?.let { markDirtyForDay(LocalDate.parse(it), "time_entry_deleted") }
            logger.i("TimeEntryRepositoryImpl.deleteTimeEntry", "Time entry deleted", mapOf("id" to id))
        }

        override suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry {
            logger.i(
                "TimeEntryRepositoryImpl.createTimeEntry",
                "Creating explicit time entry",
                mapOf(
                    "taskId" to (input.taskId ?: "none"),
                    "dimensionId" to (input.dimensionId ?: "resolved_from_category"),
                    "startedAt" to input.startedAt.toString(),
                    "endedAt" to (input.endedAt?.toString() ?: "none"),
                ),
            )
            val now = LocalDateTime.now()
            val id = UUID.randomUUID().toString()
            val resolvedDimensionId =
                resolvePersistedDimensionId(
                    dimensionId = input.dimensionId,
                    lifeIntentionCategory = input.lifeIntentionCategory,
                )
            val entity =
                TimeEntryEntity(
                    id = id,
                    lifeIntentionCategory = input.lifeIntentionCategory,
                    dimensionId = resolvedDimensionId,
                    dayKey = PersistedDateTime.dayKey(input.startedAt),
                    taskId = normalizeOptionalIdentifier(input.taskId),
                    startedAt = PersistedDateTime.format(input.startedAt),
                    endedAt = input.endedAt?.let(PersistedDateTime::format),
                    focusRating = input.focusRating,
                    focusNote = input.focusNote,
                    focusRatedAt = input.focusRatedAt?.let(PersistedDateTime::format),
                    createdAt = PersistedDateTime.format(now),
                    updatedAt = PersistedDateTime.format(now),
                )

            sessionManager.requireDatabase().timeEntryDao().insert(entity)
            markDirtyForDay(input.startedAt.toLocalDate(), "time_entry_created")
            logger.i(
                "TimeEntryRepositoryImpl.createTimeEntry",
                "Time entry created",
                mapOf("id" to id, "dayKey" to entity.dayKey),
            )
            return entity.toDomain()
        }

        override fun getAllTimeEntries(): Flow<List<TimeEntry>> =
            sessionManager.requireDatabase().timeEntryDao().getAll().map { entities ->
                entities.map { it.toDomain() }
            }

        override fun getActiveTimeEntries(): Flow<List<TimeEntry>> =
            sessionManager.requireDatabase().timeEntryDao().getAllActiveTimeEntries().map { entities ->
                entities.map { it.toDomain() }
            }

        override suspend fun updateTimeEntry(entry: TimeEntry) {
            logger.i(
                "TimeEntryRepositoryImpl.updateTimeEntryDirect",
                "Updating time entry via domain model",
                mapOf("id" to entry.id, "taskId" to (entry.taskId ?: "none")),
            )
            val existing =
                sessionManager.requireDatabase().timeEntryDao().getById(entry.id)
                    ?: throw IllegalArgumentException("TimeEntry not found: ${entry.id}")
            val now = LocalDateTime.now()
            val updated =
                existing.copy(
                    lifeIntentionCategory = entry.lifeIntentionCategory,
                    dimensionId =
                        resolvePersistedDimensionId(
                            dimensionId = entry.dimensionId,
                            lifeIntentionCategory = entry.lifeIntentionCategory,
                            fallbackDimensionId = existing.dimensionId,
                        ),
                    taskId = normalizeOptionalIdentifier(entry.taskId),
                    dayKey = PersistedDateTime.dayKey(entry.startedAt),
                    startedAt = PersistedDateTime.format(entry.startedAt),
                    endedAt = entry.endedAt?.let(PersistedDateTime::format),
                    focusRating = entry.focusRating,
                    focusNote = entry.focusNote,
                    focusRatedAt = entry.focusRatedAt?.let(PersistedDateTime::format),
                    updatedAt = PersistedDateTime.format(now),
                )

            sessionManager.requireDatabase().timeEntryDao().update(updated)
            existing.dayKey?.let { markDirtyForDay(LocalDate.parse(it), "time_entry_direct_updated_previous_day") }
            markDirtyForDay(entry.startedAt.toLocalDate(), "time_entry_direct_updated_target_day")
            logger.i(
                "TimeEntryRepositoryImpl.updateTimeEntryDirect",
                "Time entry updated via domain model",
                mapOf("id" to entry.id, "dayKey" to updated.dayKey),
            )
        }

        private suspend fun markDirtyForDay(
            day: LocalDate,
            reason: String,
        ) {
            logger.d(
                "TimeEntryRepositoryImpl.markDirtyForDay",
                "Marking daily insight dirty due to time entry mutation",
                mapOf("day" to day.toString(), "reason" to reason),
            )
            markLensDayDirty(
                dailyInsightDao = sessionManager.requireDatabase().dailyInsightDao(),
                logger = logger,
                dayKey = day.toString(),
                changedModules = setOf("time_entry"),
                reason = reason,
            )
        }
    }

internal fun resolvePersistedDimensionId(
    dimensionId: String?,
    lifeIntentionCategory: String,
    fallbackDimensionId: String? = null,
): String? {
    val normalizedDimensionId = normalizeOptionalIdentifier(dimensionId)
    val canonicalDimensionId = normalizedDimensionId?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.id }
    if (canonicalDimensionId == null && normalizedDimensionId != null) {
        UnifiedLogger.getInstance().w(
            "TimeEntryRepositoryImpl.resolvePersistedDimensionId",
            "Ignoring non-canonical dimension id",
            mapOf("dimensionId" to normalizedDimensionId),
        )
    }
    if (canonicalDimensionId == null && lifeIntentionCategory.isNotBlank()) {
        UnifiedLogger.getInstance().w(
            "TimeEntryRepositoryImpl.resolvePersistedDimensionId",
            "Ignoring non-canonical time-entry category label during dimension resolution",
            mapOf("lifeIntentionCategory" to lifeIntentionCategory),
        )
    }
    return canonicalDimensionId ?: fallbackDimensionId?.let { DimensionTaxonomyCatalog.fromCanonicalId(it)?.id }
}

internal fun normalizeOptionalIdentifier(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
