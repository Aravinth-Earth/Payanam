//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length")

package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.TaskRescheduleEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.TaskReschedule
import io.payanam.domain.repository.TaskRescheduleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRescheduleRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : TaskRescheduleRepository {
        private val logger = UnifiedLogger.getInstance()

        override suspend fun getReschedulesByTaskId(taskId: String): List<TaskReschedule> {
            logger.d("TaskRescheduleRepositoryImpl.getReschedulesByTaskId", "Fetching reschedules", mapOf("taskId" to taskId))
            val result =
                sessionManager
                    .requireDatabase()
                    .taskRescheduleDao()
                    .getReschedulesForTask(taskId)
                    .firstOrNull()
                    ?.map { it.toDomain() }
                    ?: emptyList()
            logger.d(
                "TaskRescheduleRepositoryImpl.getReschedulesByTaskId",
                "Fetched reschedules",
                mapOf(
                    "taskId" to taskId,
                    "count" to result.size,
                ),
            )
            return result
        }

        override fun getReschedulesForTask(taskId: String): Flow<List<TaskReschedule>> {
            logger.d("TaskRescheduleRepositoryImpl.getReschedulesForTask", "Subscribing to reschedules", mapOf("taskId" to taskId))
            return sessionManager.requireDatabase().taskRescheduleDao().getReschedulesForTask(taskId).map { entities ->
                entities.map { it.toDomain() }
            }
        }

        override suspend fun recordReschedule(reschedule: TaskReschedule) {
            val entity =
                TaskRescheduleEntity(
                    id = reschedule.id,
                    taskId = reschedule.taskId,
                    previousDueDate = PersistedDateTime.format(reschedule.previousDueDate),
                    newDueDate = PersistedDateTime.format(reschedule.newDueDate),
                    rescheduledAt = PersistedDateTime.format(reschedule.rescheduledAt),
                    wasOverdue = if (reschedule.wasOverdue) 1 else 0,
                )
            sessionManager.requireDatabase().taskRescheduleDao().insert(entity)
            logger.i(
                "TaskRescheduleRepositoryImpl.recordReschedule",
                "Recorded reschedule",
                mapOf(
                    "id" to reschedule.id,
                    "taskId" to reschedule.taskId,
                    "wasOverdue" to reschedule.wasOverdue,
                ),
            )
        }

        override suspend fun recordReschedule(
            taskId: String,
            previousDueDate: LocalDateTime,
            newDueDate: LocalDateTime,
            wasOverdue: Boolean,
        ): TaskReschedule {
            val now = LocalDateTime.now()
            val id = UUID.randomUUID().toString()
            val entity =
                TaskRescheduleEntity(
                    id = id,
                    taskId = taskId,
                    previousDueDate = PersistedDateTime.format(previousDueDate),
                    newDueDate = PersistedDateTime.format(newDueDate),
                    rescheduledAt = PersistedDateTime.format(now),
                    wasOverdue = if (wasOverdue) 1 else 0,
                )
            sessionManager.requireDatabase().taskRescheduleDao().insert(entity)
            logger.i(
                "TaskRescheduleRepositoryImpl.recordReschedule",
                "Recorded reschedule",
                mapOf(
                    "id" to id,
                    "taskId" to taskId,
                    "wasOverdue" to wasOverdue,
                ),
            )
            return TaskReschedule(
                id = id,
                taskId = taskId,
                previousDueDate = previousDueDate,
                newDueDate = newDueDate,
                rescheduledAt = now,
                wasOverdue = wasOverdue,
            )
        }

        private fun TaskRescheduleEntity.toDomain(): TaskReschedule =
            TaskReschedule(
                id = id,
                taskId = taskId,
                previousDueDate = PersistedDateTime.parse(previousDueDate),
                newDueDate = PersistedDateTime.parse(newDueDate),
                rescheduledAt = PersistedDateTime.parse(rescheduledAt),
                wasOverdue = wasOverdue == 1,
            )
    }
