//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.TaskEntity
import io.payanam.database.mapper.TaskMapper.toDomain
import io.payanam.database.mapper.TaskMapper.toEntity
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.repository.TaskRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * JDBC implementation of [TaskRepository] over the encrypted database — the desktop's half of the task
 * store, on the same interface the Android Room implementation satisfies.
 *
 * Every read statement is copied from `TaskDao` rather than re-derived, and the surrounding behaviour
 * mirrors `TaskRepositoryImpl` line for line: the same defaults on create, the same partial-update
 * semantics, the same dimension resolution, and the same lens dirty-day marking after a mutation. The
 * SQL text and the dirty-marking are the parts that would silently diverge if rewritten from intent.
 *
 * Four things worth stating because they are easy to get wrong when porting:
 *
 *  - **The two task write statements are NOT copied from `TaskDao` — deliberately.** Room's
 *    `@Insert(onConflict = REPLACE)` is a DELETE plus an INSERT, and `tasks` is the parent of four
 *    `ON DELETE CASCADE` child tables, so porting it verbatim would port a silent data-loss bug.
 *    [writeEntity] uses `ON CONFLICT(id) DO UPDATE` instead, which reaches the same end state without
 *    deleting the row. See [writeEntity] and `TaskRepositoryJvmCascadeDeleteRegressionTest`.
 *  - **A connected read is a re-query, not an invalidation.** Room re-runs a query when its table changes;
 *    JDBC has no such signal, so [JdbcFlows] re-runs the statement on an interval and emits only when the
 *    result differs. The parameters that a Room query binds once (the `now` of the overdue query, the
 *    `today` of the today query) are bound once here too — binding them per poll would move the window
 *    while the caller is collecting.
 *  - **A full-row update binds every column, including the ones the caller did not change.** That is what
 *    Room's `@Update` does, and the partial-update method above it already merged the incoming input into
 *    the existing row, so the merge must not be repeated here.
 *  - **Every column is bound as text.** SQLite applies column affinity, so text that spells a number is
 *    stored as INTEGER/REAL in a column declared with that type — which is how the Android side's
 *    `taskScore`, `recurrenceEnabled` and `durationMinutes` round-trip identically.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
class TaskRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : TaskRepository {
    private val logger = AppLogger.current
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Emits every task, highest score first then newest, mapped to the domain model. */
    override fun getAllTasks(): Flow<List<Task>> =
        observeTasks(ALL_TASKS_SQL) { entities ->
            logger.d("TaskRepositoryJvm.getAllTasks", "Tasks retrieved", mapOf("count" to entities.size))
        }

    /** Emits tasks whose status equals [status], highest score first. */
    override fun getTasksByStatus(status: String): Flow<List<Task>> =
        observeTasks(STATUS_SQL, arrayOf(status)) { entities ->
            logger.d(
                "TaskRepositoryJvm.getTasksByStatus",
                "Tasks emitted for status",
                mapOf("status" to status, "count" to entities.size),
            )
        }

    /** Emits pending tasks whose `day_key` — or, when null, calendar `dueDate` — falls on [date]. */
    override fun getTasksDueOn(date: LocalDate): Flow<List<Task>> =
        // The date is bound TWICE because the statement names the placeholder twice (`day_key = ?` and
        // `date(dueDate) = date(?)`). Room binds a named parameter to every occurrence; a positional bind
        // needs one value per placeholder, and binding it once leaves the second as SQL NULL — which
        // matches nothing and makes the whole `day_key IS NULL` branch dead.
        observeTasks(DUE_ON_SQL, arrayOf(date.format(dateFormatter), date.format(dateFormatter))) { entities ->
            logger.d(
                "TaskRepositoryJvm.getTasksDueOn",
                "Tasks emitted for due date",
                mapOf("date" to date.toString(), "count" to entities.size),
            )
        }

    /** Returns the task with [id], or null when no row matches. */
    override suspend fun getTaskById(id: String): Task? =
        JdbcFlows.onIo {
            readTaskEntity(id)?.toDomain().also { task ->
                logger.d(
                    "TaskRepositoryJvm.getTaskById",
                    "Fetched task by id",
                    mapOf("id" to id, "found" to (task != null)),
                )
            }
        }

    /**
     * Persists a new task from [input], generating the id and timestamps and defaulting every optional
     * field. `taskScore` stays null — the scoring module fills it later.
     */
    override suspend fun createTask(input: TaskInput): Task =
        JdbcFlows.onIo {
            logger.i(
                "TaskRepositoryJvm.createTask",
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
                    lifeIntentionCategory =
                        resolveDimensionLabel(
                            explicitLabel = input.lifeIntentionCategory,
                            resolvedDimensionId = resolvedDimensionId,
                        ),
                    explicitUrgency = input.explicitUrgency,
                    focusRequired = input.focusRequired,
                    recurrenceStrategy = input.recurrenceStrategy,
                    blockedReason = input.blockedReason,
                    completionRate = input.completionRate,
                    externalDependency = input.externalDependency,
                    notificationMode = input.notificationMode,
                    customNotificationMinutes = input.customNotificationMinutes,
                    taskScore = null,
                )
            writeEntity(task.toEntity())
            markDirtyForTaskDay(task.dueDate?.toLocalDate(), "task_created")
            logger.i(
                "TaskRepositoryJvm.createTask",
                "Task created successfully",
                mapOf("id" to id, "title" to task.title),
            )
            task
        }

    /**
     * Applies a partial update from [input] to task [id]; missing fields fall back to the current values,
     * the dimension is re-resolved, and both the old and new due days are marked insight-dirty.
     */
    @Suppress("CyclomaticComplexMethod")
    override suspend fun updateTask(
        id: String,
        input: TaskInput,
    ): Task =
        JdbcFlows.onIo {
            logger.i(
                "TaskRepositoryJvm.updateTask",
                "Updating task",
                mapOf(
                    "id" to id,
                    "title" to input.title,
                    "status" to (input.status ?: "keep_existing"),
                    "hasDueDate" to (input.dueDate != null),
                ),
            )
            val existing =
                readTaskEntity(id)
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
                    recurrenceEnabled =
                        input.recurrenceEnabled?.let { if (it) 1 else 0 } ?: existing.recurrenceEnabled,
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
                    customNotificationMinutes =
                        input.customNotificationMinutes ?: existing.customNotificationMinutes,
                )
            updateEntity(updated)
            markDirtyForTaskDay(existing.dayKey?.let(LocalDate::parse), "task_updated_previous_day")
            markDirtyForTaskDay(updated.dayKey?.let(LocalDate::parse), "task_updated_target_day")
            logger.i(
                "TaskRepositoryJvm.updateTask",
                "Task updated successfully",
                mapOf("id" to id, "dimensionId" to (updated.dimensionId ?: "none")),
            )
            updated.toDomain()
        }

    /** Hard-deletes task [id] and marks its due day insight-dirty. */
    override suspend fun deleteTask(id: String) {
        JdbcFlows.onIo {
            logger.w("TaskRepositoryJvm.deleteTask", "Deleting task", mapOf("id" to id))
            val existing = readTaskEntity(id)
            JdbcQueries.write(session, DELETE_BY_ID_SQL, arrayOf(id))
            markDirtyForTaskDay(existing?.dayKey?.let(LocalDate::parse), "task_deleted")
            logger.i("TaskRepositoryJvm.deleteTask", "Task deleted", mapOf("id" to id))
            Unit
        }
    }

    /** Marks task [id] completed, stamps `completedAt`, and returns the refreshed task. */
    override suspend fun completeTask(
        id: String,
        note: String?,
    ): Task = setStatus(id, "completed", PersistedDateTime.format(LocalDateTime.now()), note, "completeTask")

    /** Marks task [id] skipped (no `completedAt`) and returns the refreshed task. */
    override suspend fun skipTask(
        id: String,
        note: String?,
    ): Task = setStatus(id, "skipped", null, note, "skipTask")

    /** Marks task [id] missed (no `completedAt`) and returns the refreshed task. */
    override suspend fun missTask(
        id: String,
        note: String?,
    ): Task = setStatus(id, "missed", null, note, "missTask")

    /** Marks task [id] archived, stamps `archivedAt`, and returns the refreshed task. */
    override suspend fun archiveTask(id: String): Task =
        JdbcFlows.onIo {
            logger.i("TaskRepositoryJvm.archiveTask", "Archiving task", mapOf("id" to id))
            val now = PersistedDateTime.format(LocalDateTime.now())
            // Order follows the statement: status, archivedAt, updatedAt, then the id in the WHERE.
            JdbcQueries.write(session, ARCHIVE_SQL, arrayOf("archived", now, now, id))
            markDirtyForTaskId(id, "task_archived")
            logger.i("TaskRepositoryJvm.archiveTask", "Task archived", mapOf("id" to id))
            requireTask(id)
        }

    /** Writes the scoring module's computed [score] onto task [id]. */
    override suspend fun updateTaskScore(
        id: String,
        score: Double,
    ) {
        JdbcFlows.onIo {
            logger.d(
                "TaskRepositoryJvm.updateTaskScore",
                "Updating task score",
                mapOf("id" to id, "score" to score.toString()),
            )
            JdbcQueries.write(
                session,
                UPDATE_SCORE_SQL,
                arrayOf(score.toString(), PersistedDateTime.format(LocalDateTime.now()), id),
            )
            markDirtyForTaskId(id, "task_score_updated")
            Unit
        }
    }

    /** Emits pending tasks whose due date is already in the past, oldest first. */
    override fun getOverdueTasks(): Flow<List<Task>> {
        // Bound once, at subscription time, exactly as the Room query binds it: re-binding per poll would
        // walk the overdue boundary forward while the caller is collecting.
        val now = PersistedDateTime.format(LocalDateTime.now())
        return observeTasks(OVERDUE_SQL, arrayOf(now)) { entities ->
            logger.d(
                "TaskRepositoryJvm.getOverdueTasks",
                "Overdue tasks emitted",
                mapOf("count" to entities.size),
            )
        }
    }

    /** Emits today's actionable tasks: pending on that day, plus every pending recurring task. */
    override fun getTodaysTasks(): Flow<List<Task>> {
        // Bound once for the same reason as the overdue query.
        val today = LocalDate.now().format(dateFormatter)
        // Bound twice for the same reason as the due-on query: `day_key = ?` and `date(dueDate) = date(?)`.
        return observeTasks(TODAY_SQL, arrayOf(today, today)) { entities ->
            logger.d(
                "TaskRepositoryJvm.getTodaysTasks",
                "Today's tasks emitted",
                mapOf("count" to entities.size),
            )
        }
    }

    /** Returns every task with recurrence enabled, mapped to the domain model. */
    override suspend fun getRecurringTasks(): List<Task> =
        JdbcFlows.onIo {
            val tasks =
                JdbcQueries
                    .read(session, RECURRING_SQL, emptyArray()) { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(resultSet.readTask())
                            }
                        }
                    }.map { it.toDomain() }
            logger.d(
                "TaskRepositoryJvm.getRecurringTasks",
                "Fetched recurring tasks",
                mapOf("count" to tasks.size),
            )
            tasks
        }

    /** Advances recurring task [taskId] to its next occurrence and marks the new day dirty. */
    override suspend fun updateRecurrenceState(
        taskId: String,
        newDueDate: LocalDateTime,
        lastOccurrenceDate: LocalDateTime,
    ) {
        JdbcFlows.onIo {
            JdbcQueries.write(
                session,
                RECURRENCE_STATE_SQL,
                arrayOf(
                    PersistedDateTime.format(newDueDate),
                    newDueDate.toLocalDate().format(dateFormatter),
                    PersistedDateTime.format(lastOccurrenceDate),
                    PersistedDateTime.format(LocalDateTime.now()),
                    taskId,
                ),
            )
            markDirtyForTaskDay(newDueDate.toLocalDate(), "task_recurrence_updated")
            logger.i(
                "TaskRepositoryJvm.updateRecurrenceState",
                "Recurrence state updated",
                mapOf("taskId" to taskId, "newDueDate" to newDueDate.toString()),
            )
            Unit
        }
    }

    /**
     * Shared body of complete / skip / miss: one status write, then a dirty mark, then a re-read.
     *
     * [completedAt] is null for skip and miss — they move a task out of `pending` without claiming it was
     * done, and stamping a completion time would make a skipped task look completed to any reader that
     * only looks at `completedAt`.
     */
    private suspend fun setStatus(
        id: String,
        status: String,
        completedAt: String?,
        note: String?,
        operation: String,
    ): Task =
        JdbcFlows.onIo {
            logger.i(
                "TaskRepositoryJvm.$operation",
                "Setting task status",
                mapOf("id" to id, "status" to status, "hasNote" to (note != null)),
            )
            // Order follows the statement: status, completedAt, updatedAt, then the id in the WHERE.
            JdbcQueries.write(
                session,
                STATUS_UPDATE_SQL,
                arrayOf(status, completedAt, PersistedDateTime.format(LocalDateTime.now()), id),
            )
            markDirtyForTaskId(id, "task_$status")
            logger.i("TaskRepositoryJvm.$operation", "Task status written", mapOf("id" to id))
            requireTask(id)
        }

    private fun requireTask(id: String): Task =
        readTaskEntity(id)?.toDomain()
            ?: throw IllegalStateException("Task disappeared after write: $id")

    /**
     * Turns a statement into a mapped, de-duplicated [Flow].
     *
     * [parameters] is an array rather than a single value on purpose: several statements in this file name
     * the same placeholder more than once, and a positional bind needs one value per occurrence.
     *
     * The emission log sits inside the `map`, not inside the read, so a poll that returns the same rows
     * produces no log line — the caller only ever sees an emission.
     */
    private fun observeTasks(
        sql: String,
        parameters: Array<out String?> = emptyArray(),
        logEmission: (List<TaskEntity>) -> Unit = {},
    ): Flow<List<Task>> =
        JdbcFlows
            .observe(pollIntervalMs) { readTasks(sql, parameters) }
            .map { entities ->
                logEmission(entities)
                entities.map { entity -> entity.toDomain() }
            }

    private fun readTasks(
        sql: String,
        parameters: Array<out String?>,
    ): List<TaskEntity> =
        JdbcQueries.read(session, sql, parameters) { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.readTask())
                }
            }
        }

    /** Reads one task row, or null. The full-row update cannot be built without it. */
    private fun readTaskEntity(id: String): TaskEntity? =
        JdbcQueries.read(session, BY_ID_SQL, arrayOf(id)) { resultSet ->
            if (resultSet.next()) resultSet.readTask() else null
        }

    /**
     * Inserts a new task, or updates the row in place when the id is already present.
     *
     * `ON CONFLICT(id) DO UPDATE`, NOT `INSERT OR REPLACE`: REPLACE is a DELETE plus an INSERT, and
     * `tasks` is the parent of `task_occurrences`, `task_reschedules`, `scheduled_notifications` and
     * `task_tags`, all `ON DELETE CASCADE`. Re-inserting an existing id through REPLACE therefore
     * discarded the task's entire habit history, notification queue and tag set — silently, with no
     * exception and no log, because SQLite defaults `PRAGMA foreign_keys` to OFF. The upsert reaches the
     * same end state (the row holds the incoming values) without deleting the row, so no delete trigger
     * fires and the children survive. Proven in `TaskRepositoryJvmCascadeDeleteRegressionTest`.
     */
    private fun writeEntity(entity: TaskEntity) {
        JdbcQueries.write(session, UPSERT_SQL, entity.toBindings())
    }

    /**
     * Updates the existing row for [entity], every column, keyed on the row's own id.
     *
     * A real `UPDATE` rather than the upsert [writeEntity] uses, for the same reason that upsert is not
     * a REPLACE: this path merges into the existing row, so a DELETE-then-INSERT would be pure loss.
     * Room's `@Update` is an UPDATE too, and the desktop must not be the platform that loses habit
     * history the day foreign keys are switched on (SQLite defaults them OFF, so this is silent today
     * and would not stay silent).
     */
    private fun updateEntity(entity: TaskEntity) {
        JdbcQueries.write(session, UPDATE_FULL_ROW_SQL, entity.toBindings() + entity.id)
    }

    /**
     * The 34 task columns, in the order the write statements bind them.
     *
     * Numeric columns are bound as text — SQLite applies the column's declared affinity on the way in. The
     * nullable ones use `?.toString()` so a SQL NULL stays NULL rather than becoming the string "null".
     */
    private fun TaskEntity.toBindings(): Array<String?> =
        arrayOf(
            id,
            title,
            description,
            status,
            dueDate,
            createdAt,
            updatedAt,
            completedAt,
            archivedAt,
            recurrenceEnabled.toString(),
            recurrenceRule,
            durationMinutes.toString(),
            impactLevel,
            goalAlignment,
            energyLevel,
            controlLevel,
            lifeIntentionCategory,
            dimensionId,
            dayKey,
            explicitUrgency?.toString(),
            focusRequired?.toString(),
            recurrenceStrategy,
            blockedReason,
            completionRate?.toString(),
            externalDependency,
            notificationMode,
            customNotificationMinutes?.toString(),
            taskScore?.toString(),
            lastOccurrenceDate,
            dayBoundaryHour.toString(),
            importSource,
            importId,
            importedAt,
            importBatchId,
        )

    private fun ResultSet.readTask(): TaskEntity =
        TaskEntity(
            id = getString("id"),
            title = getString("title"),
            description = getString("description"),
            status = getString("status"),
            dueDate = getString("dueDate"),
            createdAt = getString("createdAt"),
            updatedAt = getString("updatedAt"),
            completedAt = getString("completedAt"),
            archivedAt = getString("archivedAt"),
            recurrenceEnabled = getInt("recurrenceEnabled"),
            recurrenceRule = getString("recurrenceRule"),
            durationMinutes = getInt("durationMinutes"),
            impactLevel = getString("impactLevel"),
            goalAlignment = getString("goalAlignment"),
            energyLevel = getString("energyLevel"),
            controlLevel = getString("controlLevel"),
            lifeIntentionCategory = getString("lifeIntentionCategory"),
            dimensionId = getString("dimension_id"),
            dayKey = getString("day_key"),
            explicitUrgency = getNullableDouble("explicitUrgency"),
            focusRequired = getNullableDouble("focusRequired"),
            recurrenceStrategy = getString("recurrenceStrategy"),
            blockedReason = getString("blockedReason"),
            completionRate = getNullableDouble("completionRate"),
            externalDependency = getString("externalDependency"),
            notificationMode = getString("notificationMode"),
            customNotificationMinutes = getNullableInt("customNotificationMinutes"),
            taskScore = getNullableDouble("taskScore"),
            lastOccurrenceDate = getString("lastOccurrenceDate"),
            dayBoundaryHour = getInt("dayBoundaryHour"),
            importSource = getString("import_source"),
            importId = getString("import_id"),
            importedAt = getString("imported_at"),
            importBatchId = getString("import_batch_id"),
        )

    private fun ResultSet.getNullableInt(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    /** `getDouble` returns 0.0 for SQL NULL, which would invent a score or a completion rate. */
    private fun ResultSet.getNullableDouble(column: String): Double? {
        val value = getDouble(column)
        return if (wasNull()) null else value
    }

    private fun markDirtyForTaskId(
        taskId: String,
        reason: String,
    ) {
        val day = readTaskEntity(taskId)?.dayKey?.let(LocalDate::parse)
        markDirtyForTaskDay(day, reason)
    }

    private fun markDirtyForTaskDay(
        day: LocalDate?,
        reason: String,
    ) {
        if (day == null) {
            logger.d(
                "TaskRepositoryJvm.markDirtyForTaskDay",
                "Dirty-mark skipped: task has no due day",
                mapOf("reason" to reason),
            )
            return
        }
        logger.d(
            "TaskRepositoryJvm.markDirtyForTaskDay",
            "Marking daily insight dirty due to task mutation",
            mapOf("day" to day.format(dateFormatter), "reason" to reason),
        )
        // The sync form: this runs inside the caller's onIo block, and the suspend version would be a
        // suspend-inside-non-suspend-lambda compile error there.
        markLensDayDirtySync(
            session = session,
            dayKey = day.format(dateFormatter),
            changedModules = setOf("task"),
            reason = reason,
            logger = logger,
        )
    }

    /**
     * Resolves the canonical dimension id from an explicit id or a category label.
     *
     * A label is NOT reverse-mapped: the Android implementation logs and ignores it, because a label is
     * locale-dependent display text and two different dimensions can carry the same one. Guessing here
     * would write a dimension the user never chose.
     */
    private fun resolveDimensionId(
        explicitDimensionId: String?,
        categoryLabel: String?,
    ): String? =
        explicitDimensionId?.trim()?.takeIf { it.isNotEmpty() }?.let { requestedId ->
            DimensionTaxonomyCatalog.fromCanonicalId(requestedId)?.id ?: requestedId
        } ?: categoryLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { label ->
            logger.w(
                "TaskRepositoryJvm.resolveDimensionId",
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

    private companion object {
        /**
         * The 34 task columns, in the order the write helpers bind them — the ONE place a task column
         * name is spelled.
         *
         * Every statement below is DERIVED from this list rather than written out, because the failure
         * mode being designed out is a second, hand-maintained copy of these names drifting from the
         * first: a `DO UPDATE SET` that silently omits a column looks correct and leaves the omitted
         * field holding the value the incoming row no longer carries. Adding a column here must be
         * enough to reach both statements.
         */
        val TASK_COLUMNS =
            listOf(
                "id",
                "title",
                "description",
                "status",
                "dueDate",
                "createdAt",
                "updatedAt",
                "completedAt",
                "archivedAt",
                "recurrenceEnabled",
                "recurrenceRule",
                "durationMinutes",
                "impactLevel",
                "goalAlignment",
                "energyLevel",
                "controlLevel",
                "lifeIntentionCategory",
                "dimension_id",
                "day_key",
                "explicitUrgency",
                "focusRequired",
                "recurrenceStrategy",
                "blockedReason",
                "completionRate",
                "externalDependency",
                "notificationMode",
                "customNotificationMinutes",
                "taskScore",
                "lastOccurrenceDate",
                "dayBoundaryHour",
                "import_source",
                "import_id",
                "imported_at",
                "import_batch_id",
            )

        /** The task primary key, and the one column an upsert must not rewrite. */
        private const val KEY_COLUMN = "id"

        /** `(col, col, …)` — the column list every write statement names. */
        val COLUMNS = TASK_COLUMNS.joinToString(", ", prefix = "(", postfix = ")")

        /** Counted from the list rather than hardcoded, so it cannot fall behind it. */
        val PLACEHOLDERS = "(" + List(TASK_COLUMNS.size) { "?" }.joinToString(", ") + ")"

        /** `col = excluded.col` for every non-key column, derived from the same list. */
        val UPDATE_CLAUSE =
            TASK_COLUMNS
                .filterNot { it == KEY_COLUMN }
                .joinToString(", ") { "$it = excluded.$it" }

        /**
         * The insert path, matching `TaskDao.insert`'s REPLACE semantics WITHOUT the REPLACE.
         *
         * `INSERT OR REPLACE` is a DELETE followed by an INSERT, and `tasks` is the parent of four
         * cascading child tables — `task_occurrences`, `task_reschedules`, `scheduled_notifications`
         * and `task_tags` — so writing a colliding id deleted that task's whole habit history,
         * notification queue and tag set, with no exception and no log. `ON CONFLICT(id) DO UPDATE`
         * updates the row in place and fires no trigger, so the children survive. See [writeEntity].
         *
         * Requires SQLite 3.24+; the SQLCipher 4.x engine this module links is far newer.
         */
        val UPSERT_SQL =
            "INSERT INTO tasks $COLUMNS VALUES $PLACEHOLDERS " +
                "ON CONFLICT($KEY_COLUMN) DO UPDATE SET $UPDATE_CLAUSE"

        /**
         * Room's `@Update` writes every column of the row; this is that statement made explicit, and
         * its SET list is the same [TASK_COLUMNS] the insert binds in the same order, with the
         * trailing `id` bound again as the WHERE key.
         */
        val UPDATE_FULL_ROW_SQL =
            "UPDATE OR ABORT tasks SET " +
                TASK_COLUMNS.joinToString(", ") { "$it = ?" } +
                " WHERE $KEY_COLUMN = ?"

        /** Verbatim from `TaskDao.getAllTasks`. */
        const val ALL_TASKS_SQL = "SELECT * FROM tasks ORDER BY taskScore DESC, createdAt DESC"

        /** Verbatim from `TaskDao.getTasksByStatus`. */
        const val STATUS_SQL = "SELECT * FROM tasks WHERE status = ? ORDER BY taskScore DESC"

        /** Verbatim from `TaskDao.getTasksDueOn`. */
        const val DUE_ON_SQL = """
            SELECT * FROM tasks
            WHERE status = 'pending'
            AND (
                day_key = ?
                OR (day_key IS NULL AND date(dueDate) = date(?))
            )
            ORDER BY taskScore DESC
        """

        /** Verbatim from `TaskDao.getTaskById`. */
        const val BY_ID_SQL = "SELECT * FROM tasks WHERE id = ?"

        /** Verbatim from `TaskDao.getOverdueTasks`. */
        const val OVERDUE_SQL = """
            SELECT * FROM tasks
            WHERE status = 'pending'
            AND dueDate IS NOT NULL
            AND datetime(dueDate) < datetime(?)
            ORDER BY dueDate ASC
        """

        /** Verbatim from `TaskDao.getTodaysTasks`. */
        const val TODAY_SQL = """
            SELECT * FROM tasks
            WHERE (
                status = 'pending'
                AND (
                    day_key = ?
                    OR (day_key IS NULL AND date(dueDate) = date(?))
                )
            )
            OR (recurrenceEnabled = 1 AND status = 'pending')
            ORDER BY taskScore DESC
        """

        /** Verbatim from `TaskDao.getRecurringTasks`. */
        const val RECURRING_SQL = "SELECT * FROM tasks WHERE recurrenceEnabled = 1"

        /** Verbatim from `TaskDao.deleteById`. */
        const val DELETE_BY_ID_SQL = "DELETE FROM tasks WHERE id = ?"

        /** Verbatim from `TaskDao.updateStatus`: `completedAt` may be null, which writes SQL NULL. */
        const val STATUS_UPDATE_SQL =
            "UPDATE tasks SET status = ?, completedAt = ?, updatedAt = ? WHERE id = ?"

        /** Verbatim from `TaskDao.updateStatusWithArchive`. */
        const val ARCHIVE_SQL =
            "UPDATE tasks SET status = ?, archivedAt = ?, updatedAt = ? WHERE id = ?"

        /** Verbatim from `TaskDao.updateTaskScore`. */
        const val UPDATE_SCORE_SQL = "UPDATE tasks SET taskScore = ?, updatedAt = ? WHERE id = ?"

        /** Verbatim from `TaskDao.updateRecurrenceState`. */
        const val RECURRENCE_STATE_SQL = """
            UPDATE tasks SET
                dueDate = ?,
                day_key = ?,
                status = 'pending',
                lastOccurrenceDate = ?,
                updatedAt = ?
            WHERE id = ?
        """
    }
}
