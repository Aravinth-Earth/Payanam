//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.TaskEntity
import io.payanam.database.mapper.TaskMapper.toDomain
import io.payanam.database.mapper.TaskMapper.toEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("TooManyFunctions")
/**
 * Provides the task repository impl.
 */
class TaskRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : TaskRepository {
        private val logger = UnifiedLogger.getInstance()
        private val dateFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

        /**
         * Returns the get all tasks.
         */
        override fun getAllTasks(): Flow<List<Task>> {
            logger.d("TaskRepositoryImpl.getAllTasks", "Fetching all tasks")
            return sessionManager.requireDatabase().taskDao().getAllTasks().map { entities ->
                logger.d("TaskRepositoryImpl.getAllTasks", "Tasks retrieved", mapOf("count" to entities.size))
                entities.map { it.toDomain() }
            }
        }

        /**
         * Returns the get tasks by status.
         */
        override fun getTasksByStatus(status: String): Flow<List<Task>> {
            logger.d("TaskRepositoryImpl.getTasksByStatus", "Subscribing tasks by status", mapOf("status" to status))
            return sessionManager.requireDatabase().taskDao().getTasksByStatus(status).map { entities ->
                logger.d(
                    "TaskRepositoryImpl.getTasksByStatus",
                    "Tasks emitted for status",
                    mapOf("status" to status, "count" to entities.size),
                )
                entities.map { it.toDomain() }
            }
        }

        /**
         * Returns the get tasks due on.
         */
        override fun getTasksDueOn(date: LocalDate): Flow<List<Task>> {
            logger.d("TaskRepositoryImpl.getTasksDueOn", "Subscribing tasks due on day", mapOf("date" to date.toString()))
            return sessionManager.requireDatabase().taskDao().getTasksDueOn(date.format(dateFormatter)).map { entities ->
                logger.d(
                    "TaskRepositoryImpl.getTasksDueOn",
                    "Tasks emitted for due date",
                    mapOf("date" to date.toString(), "count" to entities.size),
                )
                entities.map { it.toDomain() }
            }
        }

        /**
         * Returns the get task by id.
         */
        override suspend fun getTaskById(id: String): Task? {
            val task =
                sessionManager
                    .requireDatabase()
                    .taskDao()
                    .getTaskById(id)
                    ?.toDomain()
            logger.d(
                "TaskRepositoryImpl.getTaskById",
                "Fetched task by id",
                mapOf("id" to id, "found" to (task != null)),
            )
            return task
        }

        /**
         * Creates the create task.
         */
        override suspend fun createTask(input: TaskInput): Task {
            logger.i(
                "TaskRepositoryImpl.createTask",
                "Creating task",
                mapOf(
                    "title" to input.title,
                    "status" to (input.status ?: "pending"),
                    "dueDate" to (input.dueDate?.toString() ?: "none"),
                    "recurrenceEnabled" to (input.recurrenceEnabled ?: false),
                    "recurrenceRule" to (input.recurrenceRule ?: "none"),
                ),
            )
            val now = LocalDateTime.now()
            val id = UUID.randomUUID().toString()
            val resolvedDimensionId =
                resolveDimensionId(
                    explicitDimensionId = input.dimensionId,
                    categoryLabel = input.lifeIntentionCategory,
                )
            val resolvedDimensionLabel =
                resolveDimensionLabel(
                    explicitLabel = input.lifeIntentionCategory,
                    resolvedDimensionId = resolvedDimensionId,
                )
            val task =
                Task(
                    id = id,
                    title = input.title,
                    description = input.description,
                    status = input.status ?: "pending",
                    dueDate = input.dueDate,
                    createdAt = now,
                    updatedAt = now,
                    recurrenceEnabled = input.recurrenceEnabled ?: false,
                    recurrenceRule = input.recurrenceRule,
                    durationMinutes = input.durationMinutes ?: 60,
                    impactLevel = input.impactLevel ?: "Moderate Impact",
                    goalAlignment = input.goalAlignment ?: "Moderate Alignment",
                    energyLevel = input.energyLevel ?: "Moderate",
                    controlLevel = input.controlLevel ?: "Office/Colleagues Dependent",
                    dimensionId = resolvedDimensionId,
                    lifeIntentionCategory = resolvedDimensionLabel,
                    explicitUrgency = input.explicitUrgency,
                    focusRequired = input.focusRequired,
                    recurrenceStrategy = input.recurrenceStrategy,
                    blockedReason = input.blockedReason,
                    completionRate = input.completionRate,
                    externalDependency = input.externalDependency,
                    notificationMode = input.notificationMode,
                    customNotificationMinutes = input.customNotificationMinutes,
                    taskScore = null, // Will be calculated by scoring module
                )

            sessionManager.requireDatabase().taskDao().insert(task.toEntity())
            markDirtyForTaskDay(task.dueDate?.toLocalDate(), "task_created")
            logger.i(
                "TaskRepositoryImpl.createTask",
                "Task created successfully",
                mapOf(
                    "id" to id,
                    "title" to task.title,
                ),
            )
            return task
        }

        @Suppress("CyclomaticComplexMethod")
        /**
         * Updates the update task.
         */
        override suspend fun updateTask(
            id: String,
            input: TaskInput,
        ): Task {
            logger.i(
                "TaskRepositoryImpl.updateTask",
                "Updating task",
                mapOf(
                    "id" to id,
                    "title" to input.title,
                    "status" to (input.status ?: "keep_existing"),
                    "hasDueDate" to (input.dueDate != null),
                ),
            )
            val existing =
                sessionManager.requireDatabase().taskDao().getTaskById(id)
                    ?: throw IllegalArgumentException("Task not found: $id")
            val now = LocalDateTime.now()
            val resolvedDimensionId =
                resolveDimensionId(
                    explicitDimensionId = input.dimensionId,
                    categoryLabel = input.lifeIntentionCategory,
                )
            val resolvedDimensionLabel =
                input.lifeIntentionCategory
                    ?: resolvedDimensionId?.let { resolveDimensionLabel(null, it) }
                    ?: existing.lifeIntentionCategory
            val updated =
                existing.copy(
                    title = input.title,
                    description = input.description ?: existing.description,
                    status = input.status ?: existing.status,
                    dueDate = input.dueDate?.let(PersistedDateTime::format) ?: existing.dueDate,
                    dayKey = input.dueDate?.toLocalDate()?.format(dateFormatter) ?: existing.dayKey,
                    updatedAt = PersistedDateTime.format(now),
                    archivedAt = input.archivedAt?.let(PersistedDateTime::format) ?: existing.archivedAt,
                    recurrenceEnabled = input.recurrenceEnabled?.let { if (it) 1 else 0 } ?: existing.recurrenceEnabled,
                    recurrenceRule = input.recurrenceRule ?: existing.recurrenceRule,
                    durationMinutes = input.durationMinutes ?: existing.durationMinutes,
                    impactLevel = input.impactLevel ?: existing.impactLevel,
                    goalAlignment = input.goalAlignment ?: existing.goalAlignment,
                    energyLevel = input.energyLevel ?: existing.energyLevel,
                    controlLevel = input.controlLevel ?: existing.controlLevel,
                    dimensionId = resolvedDimensionId ?: existing.dimensionId,
                    lifeIntentionCategory = resolvedDimensionLabel,
                    explicitUrgency = input.explicitUrgency ?: existing.explicitUrgency,
                    focusRequired = input.focusRequired ?: existing.focusRequired,
                    recurrenceStrategy = input.recurrenceStrategy ?: existing.recurrenceStrategy,
                    blockedReason = input.blockedReason ?: existing.blockedReason,
                    completionRate = input.completionRate ?: existing.completionRate,
                    externalDependency = input.externalDependency ?: existing.externalDependency,
                    notificationMode = input.notificationMode ?: existing.notificationMode,
                    customNotificationMinutes = input.customNotificationMinutes ?: existing.customNotificationMinutes,
                )

            sessionManager.requireDatabase().taskDao().update(updated)
            markDirtyForTaskDay(existing.dayKey?.let(LocalDate::parse), "task_updated_previous_day")
            markDirtyForTaskDay(updated.dayKey?.let(LocalDate::parse), "task_updated_target_day")
            logger.i(
                "TaskRepositoryImpl.updateTask",
                "Task updated successfully",
                mapOf("id" to id, "dimensionId" to (updated.dimensionId ?: "none")),
            )
            return updated.toDomain()
        }

        /**
         * Removes the delete task.
         */
        override suspend fun deleteTask(id: String) {
            logger.w("TaskRepositoryImpl.deleteTask", "Deleting task", mapOf("id" to id))
            val existing = sessionManager.requireDatabase().taskDao().getTaskById(id)
            sessionManager.requireDatabase().taskDao().deleteById(id)
            markDirtyForTaskDay(existing?.dayKey?.let(LocalDate::parse), "task_deleted")
            logger.i("TaskRepositoryImpl.deleteTask", "Task deleted", mapOf("id" to id))
        }

        /**
         * Performs the complete task.
         */
        override suspend fun completeTask(
            id: String,
            note: String?,
        ): Task {
            logger.i(
                "TaskRepositoryImpl.completeTask",
                "Completing task",
                mapOf(
                    "id" to id,
                    "hasNote" to (note != null),
                ),
            )
            val now = LocalDateTime.now()
            sessionManager.requireDatabase().taskDao().updateStatus(
                id = id,
                status = "completed",
                completedAt = PersistedDateTime.format(now),
                updatedAt = PersistedDateTime.format(now),
            )
            markDirtyForTaskId(id, "task_completed")
            logger.i("TaskRepositoryImpl.completeTask", "Task marked as completed", mapOf("id" to id))
            return getTaskById(id)!!
        }

        /**
         * Performs the skip task.
         */
        override suspend fun skipTask(
            id: String,
            note: String?,
        ): Task {
            logger.i("TaskRepositoryImpl.skipTask", "Skipping task", mapOf("id" to id, "hasNote" to (note != null)))
            val now = LocalDateTime.now()
            sessionManager.requireDatabase().taskDao().updateStatus(
                id = id,
                status = "skipped",
                completedAt = null,
                updatedAt = PersistedDateTime.format(now),
            )
            markDirtyForTaskId(id, "task_skipped")
            logger.i("TaskRepositoryImpl.skipTask", "Task marked as skipped", mapOf("id" to id))
            return getTaskById(id)!!
        }

        /**
         * Performs the miss task.
         */
        override suspend fun missTask(
            id: String,
            note: String?,
        ): Task {
            logger.i("TaskRepositoryImpl.missTask", "Marking task as missed", mapOf("id" to id, "hasNote" to (note != null)))
            val now = LocalDateTime.now()
            sessionManager.requireDatabase().taskDao().updateStatus(
                id = id,
                status = "missed",
                completedAt = null,
                updatedAt = PersistedDateTime.format(now),
            )
            markDirtyForTaskId(id, "task_missed")
            logger.i("TaskRepositoryImpl.missTask", "Task marked as missed", mapOf("id" to id))
            return getTaskById(id)!!
        }

        /**
         * Performs the archive task.
         */
        override suspend fun archiveTask(id: String): Task {
            logger.i("TaskRepositoryImpl.archiveTask", "Archiving task", mapOf("id" to id))
            val now = LocalDateTime.now()
            sessionManager.requireDatabase().taskDao().updateStatusWithArchive(
                id = id,
                status = "archived",
                archivedAt = PersistedDateTime.format(now),
                updatedAt = PersistedDateTime.format(now),
            )
            markDirtyForTaskId(id, "task_archived")
            logger.i("TaskRepositoryImpl.archiveTask", "Task archived", mapOf("id" to id))
            return getTaskById(id)!!
        }

        /**
         * Updates the update task score.
         */
        override suspend fun updateTaskScore(
            id: String,
            score: Double,
        ) {
            logger.d(
                "TaskRepositoryImpl.updateTaskScore",
                "Updating task score",
                mapOf("id" to id, "score" to String.format(Locale.getDefault(), "%.3f", score)),
            )
            val now = LocalDateTime.now()
            sessionManager.requireDatabase().taskDao().updateTaskScore(
                id = id,
                score = score,
                updatedAt = PersistedDateTime.format(now),
            )
            markDirtyForTaskId(id, "task_score_updated")
        }

        /**
         * Returns the get overdue tasks.
         */
        override fun getOverdueTasks(): Flow<List<Task>> {
            val now = PersistedDateTime.format(LocalDateTime.now())
            return sessionManager.requireDatabase().taskDao().getOverdueTasks(now).map { entities ->
                logger.d("TaskRepositoryImpl.getOverdueTasks", "Overdue tasks emitted", mapOf("count" to entities.size))
                entities.map { it.toDomain() }
            }
        }

        /**
         * Returns the get todays tasks.
         */
        override fun getTodaysTasks(): Flow<List<Task>> {
            val today = LocalDate.now().format(dateFormatter)
            return sessionManager.requireDatabase().taskDao().getTodaysTasks(today).map { entities ->
                logger.d("TaskRepositoryImpl.getTodaysTasks", "Today's tasks emitted", mapOf("count" to entities.size))
                entities.map { it.toDomain() }
            }
        }

        /**
         * Returns the get recurring tasks.
         */
        override suspend fun getRecurringTasks(): List<Task> {
            val tasks =
                sessionManager
                    .requireDatabase()
                    .taskDao()
                    .getRecurringTasks()
                    .map { it.toDomain() }
            logger.d("TaskRepositoryImpl.getRecurringTasks", "Fetched recurring tasks", mapOf("count" to tasks.size))
            return tasks
        }

        /**
         * Updates the update recurrence state.
         */
        override suspend fun updateRecurrenceState(
            taskId: String,
            newDueDate: LocalDateTime,
            lastOccurrenceDate: LocalDateTime,
        ) {
            val now = LocalDateTime.now()
            sessionManager.requireDatabase().taskDao().updateRecurrenceState(
                id = taskId,
                newDueDate = PersistedDateTime.format(newDueDate),
                dayKey = newDueDate.toLocalDate().format(dateFormatter),
                lastOccurrenceDate = PersistedDateTime.format(lastOccurrenceDate),
                updatedAt = PersistedDateTime.format(now),
            )
            markDirtyForTaskDay(newDueDate.toLocalDate(), "task_recurrence_updated")
            logger.i(
                "TaskRepositoryImpl.updateRecurrenceState",
                "Recurrence state updated",
                mapOf(
                    "taskId" to taskId,
                    "newDueDate" to newDueDate.toString(),
                ),
            )
        }

        private suspend fun markDirtyForTaskId(
            taskId: String,
            reason: String,
        ) {
            val day =
                sessionManager
                    .requireDatabase()
                    .taskDao()
                    .getTaskById(taskId)
                    ?.dayKey
                    ?.let(LocalDate::parse)
            markDirtyForTaskDay(day, reason)
        }

        private suspend fun markDirtyForTaskDay(
            day: LocalDate?,
            reason: String,
        ) {
            if (day == null) {
                logger.d(
                    "TaskRepositoryImpl.markDirtyForTaskDay",
                    "Dirty-mark skipped: task has no due day",
                    mapOf("reason" to reason),
                )
                return
            }
            logger.d(
                "TaskRepositoryImpl.markDirtyForTaskDay",
                "Marking daily insight dirty due to task mutation",
                mapOf("day" to day.format(dateFormatter), "reason" to reason),
            )
            markLensDayDirty(
                dailyInsightDao = sessionManager.requireDatabase().dailyInsightDao(),
                logger = logger,
                dayKey = day.format(dateFormatter),
                changedModules = setOf("task"),
                reason = reason,
            )
        }

        private fun resolveDimensionId(
            explicitDimensionId: String?,
            categoryLabel: String?,
        ): String? =
            explicitDimensionId?.trim()?.takeIf { it.isNotEmpty() }?.let { requestedId ->
                DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id ?: requestedId
            } ?: categoryLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { label ->
                logger.w(
                    "TaskRepositoryImpl.resolveDimensionId",
                    "Ignoring non-canonical task category label during dimension resolution",
                    mapOf("categoryLabel" to label),
                )
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
