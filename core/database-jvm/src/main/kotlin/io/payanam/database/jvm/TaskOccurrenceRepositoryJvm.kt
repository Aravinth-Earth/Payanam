//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.database.entity.TaskOccurrenceEntity
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * JDBC implementation of [TaskOccurrenceRepository] over the encrypted database — the desktop's half of the
 * per-day occurrence rows that back the habit checkmark grids.
 *
 * Statements are copied from `TaskOccurrenceDao` and the behaviour mirrors
 * `TaskOccurrenceRepositoryImpl`, including the parts that are easy to "improve" into a bug:
 *
 *  - **`toggleOccurrence` and the parameterised `recordOccurrence` are UPSERTs.** Both read the row for
 *    (task, day) first and update it when present. Auto-generated and missed writes must never duplicate a
 *    row the user created by hand, so the read is not an optimisation to be dropped.
 *  - **A row is found by CALENDAR day, not by string equality.** Every lookup compares `date(dueDate)` to
 *    `date(?)`, so an occurrence stored with a time component is still found by its date.
 *  - **`skippedAt` is derived, not stored.** It is `completedAt` for a skipped or missed row and null
 *    otherwise, which is how the domain model exposes that state without a second column.
 *  - **Every mutation marks the day lens-dirty** for module `habit`. Without it the desktop would edit a
 *    day's occurrences and leave the lens showing the previous snapshot.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
class TaskOccurrenceRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : TaskOccurrenceRepository {
    private val logger = AppLogger.current
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Returns every occurrence for [taskId], newest first, as a one-shot list.
     *
     * Implemented as the first emission of the same [Flow] the Android version subscribes to and then
     * abandons: a one-shot read needs the same rows in the same order, and a second statement would be a
     * second thing to keep in sync with the DAO.
     */
    override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> = getOccurrencesForTask(taskId).first()

    /** Emits every occurrence for [taskId], newest-due-first. */
    override fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrence>> =
        JdbcFlows
            .observe(pollIntervalMs) { readOccurrences(OCCURRENCES_FOR_TASK_SQL, arrayOf(taskId)) }
            .map { entities -> entities.map { it.toDomain() } }

    /** Returns occurrences for [taskId] within the last [days] days, inclusive, newest first. */
    override suspend fun getOccurrencesForLastNDays(
        taskId: String,
        days: Int,
    ): List<TaskOccurrence> {
        val today = LocalDate.now()
        val startDate = today.minusDays((days - 1).toLong())
        val entities =
            JdbcFlows.onIo {
                readOccurrences(
                    OCCURRENCES_FOR_TASK_IN_RANGE_SQL,
                    arrayOf(taskId, startDate.format(dateFormatter), today.format(dateFormatter)),
                )
            }
        logger.d(
            "TaskOccurrenceRepositoryJvm.getOccurrencesForLastNDays",
            "Retrieved occurrences",
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

    /** Returns task id → its occurrences within the last [days] days, for every [taskIds]. */
    override suspend fun getOccurrencesForTasksInLastNDays(
        taskIds: List<String>,
        days: Int,
    ): Map<String, List<TaskOccurrence>> {
        if (taskIds.isEmpty()) return emptyMap()
        val today = LocalDate.now()
        val startDate = today.minusDays((days - 1).toLong())
        val entities =
            JdbcFlows.onIo {
                // One placeholder per id: a positional bind cannot share a single value across an IN list.
                val placeholders = taskIds.joinToString(separator = ",") { "?" }
                readOccurrences(
                    OCCURRENCES_FOR_TASKS_IN_RANGE_SQL.replace(TASK_IDS_TOKEN, placeholders),
                    arrayOf(
                        *taskIds.toTypedArray(),
                        startDate.format(dateFormatter),
                        today.format(dateFormatter),
                    ),
                )
            }
        logger.d(
            "TaskOccurrenceRepositoryJvm.getOccurrencesForTasksInLastNDays",
            "Retrieved bulk occurrences",
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

    /** Returns the occurrence for [taskId] on [date], or null. */
    override suspend fun getOccurrenceForDate(
        taskId: String,
        date: LocalDate,
    ): TaskOccurrence? =
        JdbcFlows.onIo {
            readOccurrence(taskId, date.format(dateFormatter))?.toDomain()
        }

    /** Emits every occurrence on [date] across all tasks. */
    override fun getOccurrencesForDate(date: LocalDate): Flow<List<TaskOccurrence>> {
        val dateStr = date.format(dateFormatter)
        return JdbcFlows
            .observe(pollIntervalMs) { readOccurrences(OCCURRENCES_FOR_DATE_SQL, arrayOf(dateStr)) }
            .map { entities -> entities.map { it.toDomain() } }
    }

    /**
     * Upserts the occurrence for [taskId] on [date] to [newStatus] and marks the day lens-dirty.
     *
     * Updating an existing row preserves `completionRate`, and keeps the stored `statusReason` for any
     * status other than `completed` — clearing a task's reason because it was re-toggled to the same status
     * would lose why it was skipped.
     */
    @Suppress("LongMethod")
    override suspend fun toggleOccurrence(
        taskId: String,
        date: LocalDate,
        newStatus: String,
        note: String?,
        reason: String?,
        actualCompletedAt: LocalDateTime?,
        actualDurationMinutes: Int?,
    ): TaskOccurrence =
        JdbcFlows.onIo {
            logger.i(
                "TaskOccurrenceRepositoryJvm.toggleOccurrence",
                "TOGGLE_OCCURRENCE_START",
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
            val now = LocalDateTime.now()
            val dateStr = date.format(dateFormatter)
            val existing = readOccurrence(taskId, dateStr)
            val completedAt = if (newStatus == COMPLETED) now.format(dateTimeFormatter) else null
            val result =
                if (existing != null) {
                    val resolvedStatusReason =
                        when (newStatus) {
                            COMPLETED -> reason
                            else -> reason ?: existing.statusReason
                        }
                    JdbcQueries.write(
                        session,
                        UPDATE_OCCURRENCE_SQL,
                        arrayOf(
                            newStatus,
                            resolvedStatusReason,
                            note,
                            completedAt,
                            actualCompletedAt?.format(dateTimeFormatter),
                            actualDurationMinutes?.toString(),
                            existing.id,
                        ),
                    )
                    logger.i(
                        "TaskOccurrenceRepositoryJvm.toggleOccurrence",
                        "EXISTING_OCCURRENCE_UPDATED",
                        mapOf(
                            "id" to existing.id,
                            "taskId" to taskId,
                            "oldStatus" to existing.status,
                            "newStatus" to newStatus,
                        ),
                    )
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
                        skippedAt = if (newStatus == SKIPPED || newStatus == MISSED) completedAt else null,
                        dueDate = null,
                        createdAt = null,
                        completionRate = existing.completionRate,
                        note = note,
                    )
                } else {
                    val entity =
                        TaskOccurrenceEntity(
                            id = UUID.randomUUID().toString(),
                            taskId = taskId,
                            dueDate = date.atStartOfDay().format(dateTimeFormatter),
                            completedAt = completedAt,
                            actualCompletedAt = actualCompletedAt?.format(dateTimeFormatter),
                            actualDurationMinutes = actualDurationMinutes,
                            status = newStatus,
                            statusReason = reason,
                            createdAt = now.format(dateTimeFormatter),
                            completionRate = null,
                            note = note,
                        )
                    JdbcQueries.write(session, INSERT_SQL, entity.toBindings())
                    logger.i(
                        "TaskOccurrenceRepositoryJvm.toggleOccurrence",
                        "NEW_OCCURRENCE_CREATED",
                        mapOf("id" to entity.id, "taskId" to taskId, "status" to newStatus),
                    )
                    entity.toDomain()
                }
            markDirtyForDay(date, "habit_occurrence_toggled")
            result
        }

    /** Deletes the occurrence for [taskId] on [date]; no-ops when no row exists. */
    override suspend fun deleteOccurrence(
        taskId: String,
        date: LocalDate,
    ) {
        JdbcFlows.onIo {
            val dateStr = date.format(dateFormatter)
            val existing = readOccurrence(taskId, dateStr)
            if (existing == null) {
                logger.d(
                    "TaskOccurrenceRepositoryJvm.deleteOccurrence",
                    "No occurrence to delete",
                    mapOf("taskId" to taskId, "date" to dateStr),
                )
                return@onIo
            }
            JdbcQueries.write(session, DELETE_BY_ID_SQL, arrayOf(existing.id))
            markDirtyForDay(date, "habit_occurrence_deleted")
            logger.i(
                "TaskOccurrenceRepositoryJvm.deleteOccurrence",
                "Deleted occurrence",
                mapOf("id" to existing.id, "taskId" to taskId, "wasStatus" to existing.status),
            )
        }
    }

    /** Persists a domain [TaskOccurrence] directly (insert-only, caller owns the id). */
    override suspend fun recordOccurrence(occurrence: TaskOccurrence) {
        JdbcFlows.onIo {
            val entity =
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
            JdbcQueries.write(session, INSERT_SQL, entity.toBindings())
            // The occurrence date arrives as a string here and may not be a bare day (it is whatever the
            // caller had). The Android side treats an unparseable date as "no day to mark" rather than a
            // hard failure, and so does this: a malformed date must not lose the occurrence that was just
            // written.
            runCatching {
                markDirtyForDay(LocalDate.parse(occurrence.occurrenceDate.take(DATE_PREFIX_LENGTH)), "habit_occurrence_recorded")
            }.onFailure { error ->
                logger.d(
                    "TaskOccurrenceRepositoryJvm.recordOccurrence",
                    "Skipped dirty-mark: occurrence date is not a plain day",
                    mapOf("occurrenceDate" to occurrence.occurrenceDate, "failure" to error::class.simpleName),
                )
            }
            logger.i(
                "TaskOccurrenceRepositoryJvm.recordOccurrence",
                "Recorded occurrence",
                mapOf("id" to occurrence.id, "taskId" to occurrence.taskId, "status" to occurrence.status),
            )
        }
    }

    /**
     * Upserts an occurrence for [taskId] on [dueDate] with the given [status], [note] and [completionRate].
     *
     * Updating preserves `actualCompletedAt` and `actualDurationMinutes`: the caller is recording a status,
     * not correcting the actuals, and overwriting them would discard what the user actually logged.
     */
    override suspend fun recordOccurrence(
        taskId: String,
        dueDate: LocalDateTime,
        status: String,
        note: String?,
        completionRate: Double?,
    ): TaskOccurrence =
        JdbcFlows.onIo {
            val now = LocalDateTime.now()
            val dateStr = dueDate.toLocalDate().format(dateFormatter)
            val existing = readOccurrence(taskId, dateStr)
            val completedAt = if (status == COMPLETED) now.format(dateTimeFormatter) else null
            if (existing != null) {
                JdbcQueries.write(
                    session,
                    UPDATE_OCCURRENCE_SQL,
                    arrayOf(
                        status,
                        existing.statusReason,
                        note,
                        completedAt,
                        existing.actualCompletedAt,
                        existing.actualDurationMinutes?.toString(),
                        existing.id,
                    ),
                )
                markDirtyForDay(dueDate.toLocalDate(), "habit_occurrence_recorded")
                logger.i(
                    "TaskOccurrenceRepositoryJvm.recordOccurrence",
                    "Existing occurrence updated (UPSERT)",
                    mapOf(
                        "id" to existing.id,
                        "taskId" to taskId,
                        "oldStatus" to existing.status,
                        "newStatus" to status,
                    ),
                )
                return@onIo TaskOccurrence(
                    id = existing.id,
                    taskId = taskId,
                    occurrenceDate = dateStr,
                    status = status,
                    statusNote = note,
                    statusReason = existing.statusReason,
                    completedAt = completedAt,
                    actualCompletedAt =
                        existing.actualCompletedAt?.let {
                            runCatching { LocalDateTime.parse(it, dateTimeFormatter) }.getOrNull()
                        },
                    actualDurationMinutes = existing.actualDurationMinutes,
                    // Populated here even though the row stores a NULL completedAt for these statuses, so
                    // reading the row back yields a NULL skippedAt where this return value does not. It
                    // matches the Android UPDATE branch exactly; "fixing" it would make the two platforms
                    // disagree about the same row.
                    skippedAt = if (status == SKIPPED || status == MISSED) now.format(dateTimeFormatter) else null,
                    dueDate = null,
                    createdAt = null,
                    completionRate = existing.completionRate,
                    note = note,
                )
            }
            val entity =
                TaskOccurrenceEntity(
                    id = UUID.randomUUID().toString(),
                    taskId = taskId,
                    dueDate = dueDate.format(dateTimeFormatter),
                    completedAt = completedAt,
                    status = status,
                    statusReason = null,
                    createdAt = now.format(dateTimeFormatter),
                    completionRate = completionRate,
                    note = note,
                )
            JdbcQueries.write(session, INSERT_SQL, entity.toBindings())
            markDirtyForDay(dueDate.toLocalDate(), "habit_occurrence_recorded")
            logger.i(
                "TaskOccurrenceRepositoryJvm.recordOccurrence",
                "Recorded occurrence",
                mapOf("id" to entity.id, "taskId" to taskId, "status" to status),
            )
            entity.toDomain()
        }

    /** Hard-deletes the occurrence with the given [id]. */
    override suspend fun deleteOccurrence(id: String) {
        JdbcFlows.onIo {
            JdbcQueries.write(session, DELETE_BY_ID_SQL, arrayOf(id))
            logger.d("TaskOccurrenceRepositoryJvm.deleteOccurrence", "Deleted occurrence", mapOf("id" to id))
        }
    }

    private fun readOccurrence(
        taskId: String,
        dateStr: String,
    ): TaskOccurrenceEntity? =
        JdbcQueries.read(session, OCCURRENCE_FOR_TASK_ON_DATE_SQL, arrayOf(taskId, dateStr)) { resultSet ->
            if (resultSet.next()) resultSet.readOccurrence() else null
        }

    private fun readOccurrences(
        sql: String,
        parameters: Array<out String?>,
    ): List<TaskOccurrenceEntity> =
        JdbcQueries.read(session, sql, parameters) { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.readOccurrence())
                }
            }
        }

    private fun ResultSet.readOccurrence(): TaskOccurrenceEntity =
        TaskOccurrenceEntity(
            id = getString("id"),
            taskId = getString("taskId"),
            dueDate = getString("dueDate"),
            completedAt = getString("completedAt"),
            actualCompletedAt = getString("actualCompletedAt"),
            actualDurationMinutes = getNullableInt("actualDurationMinutes"),
            status = getString("status"),
            statusReason = getString("statusReason"),
            createdAt = getString("createdAt"),
            completionRate = getNullableDouble("completionRate"),
            note = getString("note"),
        )

    private fun TaskOccurrenceEntity.toBindings(): Array<String?> =
        arrayOf(
            id,
            taskId,
            dueDate,
            completedAt,
            actualCompletedAt,
            actualDurationMinutes?.toString(),
            status,
            statusReason,
            createdAt,
            completionRate?.toString(),
            note,
        )

    private fun TaskOccurrenceEntity.toDomain(): TaskOccurrence =
        TaskOccurrence(
            id = id,
            taskId = taskId,
            occurrenceDate = dueDate,
            status = status,
            statusNote = note,
            statusReason = statusReason,
            completedAt = completedAt,
            actualCompletedAt =
                actualCompletedAt?.let {
                    runCatching { LocalDateTime.parse(it, dateTimeFormatter) }.getOrNull()
                },
            actualDurationMinutes = actualDurationMinutes,
            skippedAt = if (status == SKIPPED || status == MISSED) completedAt else null,
            dueDate = null,
            createdAt = null,
            completionRate = completionRate,
            note = note,
        )

    /** `getInt`/`getDouble` return 0 for SQL NULL, which would invent a logged duration or a rate. */
    private fun ResultSet.getNullableInt(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    private fun ResultSet.getNullableDouble(column: String): Double? {
        val value = getDouble(column)
        return if (wasNull()) null else value
    }

    private fun markDirtyForDay(
        day: LocalDate,
        reason: String,
    ) {
        // The sync form: this runs inside the caller's onIo block.
        markLensDayDirtySync(
            session = session,
            dayKey = day.format(dateFormatter),
            changedModules = setOf("habit"),
            reason = reason,
            logger = logger,
        )
    }

    private companion object {
        const val COMPLETED = "completed"
        const val SKIPPED = "skipped"
        const val MISSED = "missed"
        const val DATE_PREFIX_LENGTH = 10
        const val TASK_IDS_TOKEN = "{taskIds}"

        /** The 11 occurrence columns, in the order [INSERT_SQL] binds them. */
        const val COLUMNS =
            "(id, taskId, dueDate, completedAt, actualCompletedAt, actualDurationMinutes, " +
                "status, statusReason, createdAt, completionRate, note)"

        val PLACEHOLDERS = "(" + List(11) { "?" }.joinToString(", ") + ")"

        /** Verbatim from `TaskOccurrenceDao.insert`. */
        val INSERT_SQL = "INSERT OR REPLACE INTO task_occurrences $COLUMNS VALUES $PLACEHOLDERS"

        /** Verbatim from `TaskOccurrenceDao.getOccurrencesForTask`. */
        const val OCCURRENCES_FOR_TASK_SQL =
            "SELECT * FROM task_occurrences WHERE taskId = ? ORDER BY dueDate DESC"

        /** Verbatim from `TaskOccurrenceDao.getOccurrencesForDate`. */
        const val OCCURRENCES_FOR_DATE_SQL =
            "SELECT * FROM task_occurrences WHERE date(dueDate) = date(?)"

        /** Verbatim from `TaskOccurrenceDao.getOccurrenceForTaskOnDate`. */
        const val OCCURRENCE_FOR_TASK_ON_DATE_SQL = """
            SELECT * FROM task_occurrences
            WHERE taskId = ?
            AND date(dueDate) = date(?)
            LIMIT 1
        """

        /** Verbatim from `TaskOccurrenceDao.getOccurrencesForTaskInRange`. */
        const val OCCURRENCES_FOR_TASK_IN_RANGE_SQL = """
            SELECT * FROM task_occurrences
            WHERE taskId = ?
            AND date(dueDate) >= date(?)
            AND date(dueDate) <= date(?)
            ORDER BY dueDate DESC
        """

        /** Verbatim from `TaskOccurrenceDao.getOccurrencesForTasksInRange`, IN list expanded by the caller. */
        const val OCCURRENCES_FOR_TASKS_IN_RANGE_SQL = """
            SELECT * FROM task_occurrences
            WHERE taskId IN ({taskIds})
            AND date(dueDate) >= date(?)
            AND date(dueDate) <= date(?)
            ORDER BY taskId, dueDate DESC
        """

        /** Verbatim from `TaskOccurrenceDao.updateOccurrence`; nullable columns write SQL NULL. */
        const val UPDATE_OCCURRENCE_SQL = """
            UPDATE task_occurrences
            SET status = ?, statusReason = ?, note = ?, completedAt = ?,
                actualCompletedAt = ?, actualDurationMinutes = ?
            WHERE id = ?
        """

        /** Verbatim from `TaskOccurrenceDao.deleteById`. */
        const val DELETE_BY_ID_SQL = "DELETE FROM task_occurrences WHERE id = ?"
    }
}
