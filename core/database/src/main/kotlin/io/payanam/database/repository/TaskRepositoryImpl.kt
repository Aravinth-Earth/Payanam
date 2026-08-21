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
 * Room-backed implementation of [TaskRepository]. Wraps [TaskDao], maps between
 * [TaskEntity] and domain [Task], resolves the life-dimension from an explicit
 * id or category label, and invalidates the daily-insight cache for affected
 * days whenever a task mutates.
 */
class TaskRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : TaskRepository {
        private val logger = UnifiedLogger.getInstance()
        private val dateFormatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE

        /**
         * Emits every task, mapped to the domain model, as a [Flow].
         */
        override fun getAllTasks(): Flow<List<Task>> {
            logger.d("TaskRepositoryImpl.getAllTasks", "Fetching all tasks")
            return sessionManager.requireDatabase().taskDao().getAllTasks().map { entities ->
                logger.d("TaskRepositoryImpl.getAllTasks", "Tasks retrieved", mapOf("count" to entities.size))
                entities.map { it.toDomain() }
            }
        }

        /**
         * Emits tasks whose status equals [status], mapped to the domain model, as
         * a [Flow].
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
         * Emits tasks whose `dueDate` falls on [date], mapped to the domain model,
         * as a [Flow].
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
         * Returns the task with [id] mapped to the domain model, or null when no
         * such task exists.
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
         * Persists a new task from [input]. Generates the id and timestamps,
         * defaults every optional field (status = pending, duration = 60 min,
         * impact/alignment/energy/control to "Moderate"), resolves the life-dimension,
         * and marks the due day's insight dirty. `taskScore` is left null — the
         * scoring module fills it later. Returns the created domain [Task].
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
         * Applies a partial update from [input] to the existing task [id]. Missing
         * fields fall back to the current values; the dimension is re-resolved and
         * both the old and new due days are marked insight-dirty. Throws
         * [IllegalArgumentException] when [id] does not exist. Returns the updated
         * domain [Task].
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
         * Hard-deletes the task [id] and marks its due day insight-dirty. No
         * archival — the row is removed entirely.
         */
        override suspend fun deleteTask(id: String) {
            logger.w("TaskRepositoryImpl.deleteTask", "Deleting task", mapOf("id" to id))
            val existing = sessionManager.requireDatabase().taskDao().getTaskById(id)
            sessionManager.requireDatabase().taskDao().deleteById(id)
            markDirtyForTaskDay(existing?.dayKey?.let(LocalDate::parse), "task_deleted")
            logger.i("TaskRepositoryImpl.deleteTask", "Task deleted", mapOf("id" to id))
        }

        /**
         * Marks the task [id] `completed`, stamps `completedAt`, marks the task's
         * day insight-dirty, and returns the refreshed domain [Task].
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
         * Marks the task [id] `skipped` (no `completedAt`), marks its day
         * insight-dirty, and returns the refreshed domain [Task].
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
         * Marks the task [id] `missed` (no `completedAt`), marks its day
         * insight-dirty, and returns the refreshed domain [Task].
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
         * Marks the task [id] `archived`, stamps `archivedAt`, marks its day
         * insight-dirty, and returns the refreshed domain [Task].
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
         * Writes the scoring module's computed [score] onto the task [id], stamps
         * `updatedAt`, and marks its day insight-dirty. `score` is null until the
         * scoring module runs.
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
         * Emits tasks whose due date is before now and whose status is not a
         * terminal one, mapped to the domain model, as a [Flow].
         */
        override fun getOverdueTasks(): Flow<List<Task>> {
            val now = PersistedDateTime.format(LocalDateTime.now())
            return sessionManager.requireDatabase().taskDao().getOverdueTasks(now).map { entities ->
                logger.d("TaskRepositoryImpl.getOverdueTasks", "Overdue tasks emitted", mapOf("count" to entities.size))
                entities.map { it.toDomain() }
            }
        }

        /**
         * Emits tasks due on the current day, mapped to the domain model, as a
         * [Flow].
         */
        override fun getTodaysTasks(): Flow<List<Task>> {
            val today = LocalDate.now().format(dateFormatter)
            return sessionManager.requireDatabase().taskDao().getTodaysTasks(today).map { entities ->
                logger.d("TaskRepositoryImpl.getTodaysTasks", "Today's tasks emitted", mapOf("count" to entities.size))
                entities.map { it.toDomain() }
            }
        }

        /**
         * Returns every task with recurrence enabled, mapped to the domain model.
         * Used by the scheduler that advances due recurring tasks.
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
         * Advances a recurring task [taskId] to its next due date: sets `dueDate`
         * and `dayKey` from [newDueDate], records [lastOccurrenceDate], stamps
         * `updatedAt`, and marks the new due day insight-dirty.
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
