//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length", "LongMethod", "MagicNumber")


package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.TaskOccurrenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * TaskOccurrenceRepositoryImpl.
 */
class TaskOccurrenceRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : TaskOccurrenceRepository {
        private val logger = UnifiedLogger.getInstance()
        private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> =
            /** Session manager. */
            sessionManager
                .requireDatabase()
                .taskOccurrenceDao()
                .getOccurrencesForTask(taskId)
                .firstOrNull()
                ?.map { it.toDomain() }
                ?: emptyList()

        override fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrence>> =
            sessionManager.requireDatabase().taskOccurrenceDao().getOccurrencesForTask(taskId).map { entities ->
                entities.map { it.toDomain() }
            }

        override suspend fun getOccurrencesForLastNDays(
            /** Task id. */
            taskId: String,
            /** Days. */
            days: Int,
        ): List<TaskOccurrence> {
            /** Today. */
            val today = LocalDate.now()
            /** Start date. */
            val startDate = today.minusDays((days - 1).toLong())

            /** Entities. */
            val entities =
                sessionManager.requireDatabase().taskOccurrenceDao().getOccurrencesForTaskInRange(
                    taskId = taskId,
                    startDate = startDate.format(dateFormatter),
                    endDate = today.format(dateFormatter),
                )

            logger.d(
                "TaskOccurrenceRepositoryImpl.getOccurrencesForLastNDays",
                "Retrieved occurrences",
                /** Map of. */
                mapOf(
                    "taskId" to taskId,
                    "days" to days,
                    "startDate" to startDate.toString(),
                    "endDate" to today.toString(),
                    "count" to entities.size,
                ),
            )

            return entities.map { it.toDomain() }
        }

        override suspend fun getOccurrencesForTasksInLastNDays(
            taskIds: List<String>,
            /** Days. */
            days: Int,
        ): Map<String, List<TaskOccurrence>> {
            /** If. */
            if (taskIds.isEmpty()) return emptyMap()

            /** Today. */
            val today = LocalDate.now()
            /** Start date. */
            val startDate = today.minusDays((days - 1).toLong())

            /** Entities. */
            val entities =
                sessionManager.requireDatabase().taskOccurrenceDao().getOccurrencesForTasksInRange(
                    taskIds = taskIds,
                    startDate = startDate.format(dateFormatter),
                    endDate = today.format(dateFormatter),
                )

            logger.d(
                "TaskOccurrenceRepositoryImpl.getOccurrencesForTasksInLastNDays",
                "Retrieved bulk occurrences",
                /** Map of. */
                mapOf(
                    "taskIdsCount" to taskIds.size,
                    "days" to days,
                    "startDate" to startDate.toString(),
                    "endDate" to today.toString(),
                    "totalOccurrences" to entities.size,
                ),
            )

            return entities.groupBy { it.taskId }.mapValues { (_, taskEntities) ->
                taskEntities.map { it.toDomain() }
            }
        }

        override suspend fun getOccurrenceForDate(
            /** Task id. */
            taskId: String,
            /** Date. */
            date: LocalDate,
        ): TaskOccurrence? {
            /** Entity. */
            val entity =
                sessionManager.requireDatabase().taskOccurrenceDao().getOccurrenceForTaskOnDate(
                    taskId = taskId,
                    date = date.format(dateFormatter),
                )
            return entity?.toDomain()
        }

        override fun getOccurrencesForDate(date: LocalDate): Flow<List<TaskOccurrence>> {
            /** Date str. */
            val dateStr = date.format(dateFormatter)
            return sessionManager.requireDatabase().taskOccurrenceDao().getOccurrencesForDate(dateStr).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        override suspend fun toggleOccurrence(
            /** Task id. */
            taskId: String,
            /** Date. */
            date: LocalDate,
            /** New status. */
            newStatus: String,
            note: String?,
            reason: String?,
            actualCompletedAt: LocalDateTime?,
            actualDurationMinutes: Int?,
        ): TaskOccurrence {
            logger.i(
                "TaskOccurrenceRepositoryImpl.toggleOccurrence",
                "TOGGLE_OCCURRENCE_START",
                /** Map of. */
                mapOf(
                    "taskId" to taskId,
                    "date" to date.toString(),
                    "newStatus" to newStatus,
                    "note" to (note ?: "null"),
                    "reason" to (reason ?: "null"),
                    "actualCompletedAt" to (actualCompletedAt?.toString() ?: "null"),
                    "actualDurationMinutes" to (actualDurationMinutes?.toString() ?: "null"),
                ),
            )

            /** Now. */
            val now = LocalDateTime.now()
            /** Date str. */
            val dateStr = date.format(dateFormatter)

            /** Existing. */
            val existing = sessionManager.requireDatabase().taskOccurrenceDao().getOccurrenceForTaskOnDate(taskId, dateStr)
            logger.d(
                "TaskOccurrenceRepositoryImpl.toggleOccurrence",
                "EXISTING_OCCURRENCE_CHECK",
                /** Map of. */
                mapOf(
                    "taskId" to taskId,
                    "date" to dateStr,
                    "existingFound" to (existing != null).toString(),
                    "existingId" to (existing?.id ?: "null"),
                    "existingStatus" to (existing?.status ?: "null"),
                ),
            )

            return if (existing != null) {
                /** Completed at. */
                val completedAt = if (newStatus == "completed") now.format(dateTimeFormatter) else null
                /** Resolved status reason. */
                val resolvedStatusReason =
                    /** When. */
                    when (newStatus) {
                        "completed" -> reason
                        else -> reason ?: existing.statusReason
                    }
                logger.d(
                    "TaskOccurrenceRepositoryImpl.toggleOccurrence",
                    "UPDATING_EXISTING_OCCURRENCE",
                    /** Map of. */
                    mapOf(
                        "existingId" to existing.id,
                        "newStatus" to newStatus,
                        "resolvedStatusReason" to (resolvedStatusReason ?: "null"),
                        "completedAt" to (completedAt ?: "null"),
                    ),
                )
                sessionManager.requireDatabase().taskOccurrenceDao().updateOccurrence(
                    id = existing.id,
                    status = newStatus,
                    statusReason = resolvedStatusReason,
                    note = note,
                    completedAt = completedAt,
                    actualCompletedAt = actualCompletedAt?.format(dateTimeFormatter),
                    actualDurationMinutes = actualDurationMinutes,
                )

                logger.i(
                    "TaskOccurrenceRepositoryImpl.toggleOccurrence",
                    "EXISTING_OCCURRENCE_UPDATED",
                    /** Map of. */
                    mapOf(
                        "id" to existing.id,
                        "taskId" to taskId,
                        "date" to dateStr,
                        "oldStatus" to existing.status,
                        "newStatus" to newStatus,
                        "hasNote" to (note != null),
                    ),
                )

                /** Task occurrence. */
                TaskOccurrence(
                    id = existing.id,
                    taskId = taskId,
                    occurrenceDate = dateStr,
                    status = newStatus,
                    statusNote = note,
                    statusReason = resolvedStatusReason,
                    completedAt = completedAt,
                    actualCompletedAt = actualCompletedAt,
                    actualDurationMinutes = actualDurationMinutes,
                    skippedAt = if (newStatus == "skipped" || newStatus == "missed") completedAt else null,
                    dueDate = null,
                    createdAt = null,
                    completionRate = existing.completionRate,
                    note = note,
                )
            } else {
                /** Id. */
                val id = UUID.randomUUID().toString()
                logger.d(
                    "TaskOccurrenceRepositoryImpl.toggleOccurrence",
                    "CREATING_NEW_OCCURRENCE",
                    /** Map of. */
                    mapOf(
                        "newId" to id,
                        "taskId" to taskId,
                        "date" to dateStr,
                        "status" to newStatus,
                    ),
                )
                /** Entity. */
                val entity =
                    /** Task occurrence entity. */
                    TaskOccurrenceEntity(
                        id = id,
                        taskId = taskId,
                        dueDate = date.atStartOfDay().format(dateTimeFormatter),
                        completedAt = if (newStatus == "completed") now.format(dateTimeFormatter) else null,
                        actualCompletedAt = actualCompletedAt?.format(dateTimeFormatter),
                        actualDurationMinutes = actualDurationMinutes,
                        status = newStatus,
                        statusReason = reason,
                        createdAt = now.format(dateTimeFormatter),
                        completionRate = null,
                        note = note,
                    )

                sessionManager.requireDatabase().taskOccurrenceDao().insert(entity)

                logger.i(
                    "TaskOccurrenceRepositoryImpl.toggleOccurrence",
                    "NEW_OCCURRENCE_CREATED",
                    /** Map of. */
                    mapOf(
                        "id" to id,
                        "taskId" to taskId,
                        "date" to dateStr,
                        "status" to newStatus,
                        "hasNote" to (note != null),
                    ),
                )

                entity.toDomain()
            }.also { markDirtyForDay(date, "habit_occurrence_toggled") }
        }

        override suspend fun deleteOccurrence(taskId: String, date: LocalDate) {
            /** Date str. */
            val dateStr = date.format(dateFormatter)
            /** Existing. */
            val existing = sessionManager.requireDatabase().taskOccurrenceDao().getOccurrenceForTaskOnDate(taskId, dateStr)

            /** If. */
            if (existing != null) {
                sessionManager.requireDatabase().taskOccurrenceDao().deleteById(existing.id)
                /** Mark dirty for day. */
                markDirtyForDay(date, "habit_occurrence_deleted")

                logger.i(
                    "TaskOccurrenceRepositoryImpl.deleteOccurrence",
                    "Deleted occurrence",
                    /** Map of. */
                    mapOf(
                        "id" to existing.id,
                        "taskId" to taskId,
                        "date" to dateStr,
                        "wasStatus" to existing.status,
                    ),
                )
            } else {
                logger.d(
                    "TaskOccurrenceRepositoryImpl.deleteOccurrence",
                    "No occurrence to delete",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "date" to dateStr,
                    ),
                )
            }
        }

        override suspend fun recordOccurrence(occurrence: TaskOccurrence) {
            /** Entity. */
            val entity =
                /** Task occurrence entity. */
                TaskOccurrenceEntity(
                    id = occurrence.id,
                    taskId = occurrence.taskId,
                    dueDate = occurrence.occurrenceDate,
                    completedAt = occurrence.completedAt,
                    actualCompletedAt = occurrence.actualCompletedAt?.format(dateTimeFormatter),
                    actualDurationMinutes = occurrence.actualDurationMinutes,
                    status = occurrence.status,
                    statusReason = occurrence.statusReason,
                    createdAt = LocalDateTime.now().format(dateTimeFormatter),
                    completionRate = occurrence.completionRate,
                    note = occurrence.statusNote ?: occurrence.note,
                )

            sessionManager.requireDatabase().taskOccurrenceDao().insert(entity)
            runCatching { markDirtyForDay(LocalDate.parse(occurrence.occurrenceDate.take(10)), "habit_occurrence_recorded") }

            logger.i(
                "TaskOccurrenceRepositoryImpl.recordOccurrence",
                "Recorded occurrence",
                /** Map of. */
                mapOf(
                    "id" to occurrence.id,
                    "taskId" to occurrence.taskId,
                    "status" to occurrence.status,
                    "hasNote" to (occurrence.statusNote != null),
                    "hasActualCompletedAt" to (occurrence.actualCompletedAt != null),
                    "actualDurationMinutes" to (occurrence.actualDurationMinutes?.toString() ?: "null"),
                ),
            )
        }

        override suspend fun recordOccurrence(
            /** Task id. */
            taskId: String,
            /** Due date. */
            dueDate: LocalDateTime,
            /** Status. */
            status: String,
            note: String?,
            completionRate: Double?,
        ): TaskOccurrence {
            /** Now. */
            val now = LocalDateTime.now()
            /** Date str. */
            val dateStr = dueDate.toLocalDate().format(dateFormatter)

            // UPSERT: update the existing row for (task, day) if present,
            // otherwise insert. Mirrors toggleOccurrence and the
            // self-governance ledger rule — auto/missed writes must never
            // duplicate a user row (see OCC_CHECK_EXISTING in the DB flow spec).
            /** Existing. */
            val existing = sessionManager.requireDatabase().taskOccurrenceDao().getOccurrenceForTaskOnDate(taskId, dateStr)
            /** If. */
            if (existing != null) {
                logger.d(
                    "TaskOccurrenceRepositoryImpl.recordOccurrence",
                    "UPDATING existing occurrence (UPSERT)",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "date" to dateStr,
                        "existingStatus" to existing.status,
                        "newStatus" to status,
                        "existingId" to existing.id,
                    ),
                )
                sessionManager.requireDatabase().taskOccurrenceDao().updateOccurrence(
                    id = existing.id,
                    status = status,
                    statusReason = existing.statusReason,
                    note = note,
                    completedAt = if (status == "completed") now.format(dateTimeFormatter) else null,
                    actualCompletedAt = existing.actualCompletedAt,
                    actualDurationMinutes = existing.actualDurationMinutes,
                )
                /** Mark dirty for day. */
                markDirtyForDay(dueDate.toLocalDate(), "habit_occurrence_recorded")
                logger.i(
                    "TaskOccurrenceRepositoryImpl.recordOccurrence",
                    "Existing occurrence updated (UPSERT)",
                    /** Map of. */
                    mapOf(
                        "id" to existing.id,
                        "taskId" to taskId,
                        "date" to dateStr,
                        "oldStatus" to existing.status,
                        "newStatus" to status,
                    ),
                )
                return TaskOccurrence(
                    id = existing.id,
                    taskId = taskId,
                    occurrenceDate = dateStr,
                    status = status,
                    statusNote = note,
                    statusReason = existing.statusReason,
                    completedAt = if (status == "completed") now.format(dateTimeFormatter) else null,
                    actualCompletedAt = existing.actualCompletedAt?.let {
                        runCatching { LocalDateTime.parse(it, dateTimeFormatter) }.getOrNull()
                    },
                    actualDurationMinutes = existing.actualDurationMinutes,
                    skippedAt = if (status == "skipped" || status == "missed") now.format(dateTimeFormatter) else null,
                    dueDate = null,
                    createdAt = null,
                    completionRate = existing.completionRate,
                    note = note,
                )
            }

            /** Id. */
            val id = UUID.randomUUID().toString()

            /** Entity. */
            val entity =
                /** Task occurrence entity. */
                TaskOccurrenceEntity(
                    id = id,
                    taskId = taskId,
                    dueDate = dueDate.format(dateTimeFormatter),
                    completedAt = if (status == "completed") now.format(dateTimeFormatter) else null,
                    status = status,
                    statusReason = null,
                    createdAt = now.format(dateTimeFormatter),
                    completionRate = completionRate,
                    note = note,
                )

            sessionManager.requireDatabase().taskOccurrenceDao().insert(entity)
            /** Mark dirty for day. */
            markDirtyForDay(dueDate.toLocalDate(), "habit_occurrence_recorded")

            logger.i(
                "TaskOccurrenceRepositoryImpl.recordOccurrence",
                "Recorded occurrence",
                /** Map of. */
                mapOf(
                    "id" to id,
                    "taskId" to taskId,
                    "status" to status,
                    "hasNote" to (note != null),
                ),
            )

            return entity.toDomain()
        }

        override suspend fun deleteOccurrence(id: String) {
            sessionManager.requireDatabase().taskOccurrenceDao().deleteById(id)
            logger.d("TaskOccurrenceRepositoryImpl.deleteOccurrence", "Deleted occurrence", mapOf("id" to id))
        }

        private fun TaskOccurrenceEntity.toDomain() =
            /** Task occurrence. */
            TaskOccurrence(
                id = id,
                taskId = taskId,
                occurrenceDate = dueDate,
                status = status,
                statusNote = note,
                statusReason = statusReason,
                completedAt = completedAt,
                actualCompletedAt = actualCompletedAt?.let { LocalDateTime.parse(it, dateTimeFormatter) },
                actualDurationMinutes = actualDurationMinutes,
                skippedAt = if (status == "skipped" || status == "missed") completedAt else null,
                dueDate = null,
                createdAt = null,
                completionRate = completionRate,
                note = note,
            )

        private suspend fun markDirtyForDay(
            /** Day. */
            day: LocalDate,
            /** Reason. */
            reason: String,
        ) {
            /** Mark lens day dirty. */
            markLensDayDirty(
                dailyInsightDao = sessionManager.requireDatabase().dailyInsightDao(),
                logger = logger,
                dayKey = day.format(dateFormatter),
                changedModules = setOf("habit"),
                reason = reason,
            )
        }
    }
