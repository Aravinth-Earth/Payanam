//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.TaskRescheduleEntity
import io.payanam.domain.model.TaskReschedule
import io.payanam.domain.repository.TaskRescheduleRepository
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID

/**
 * JDBC implementation of [TaskRescheduleRepository] over the encrypted database — the audit trail of every
 * time a task's due date was pushed, and whether the task was already overdue when it happened.
 *
 * Small, but its rows are evidence: nothing else records that a due date moved, so the timestamps are
 * written with [PersistedDateTime] rather than a locally-invented format, and `wasOverdue` is stored as the
 * integer flag the schema declares.
 *
 * @param session an already-open session owned by the caller.
 * @param pollIntervalMs how often a subscribed read re-checks; shorten it in tests.
 */
class TaskRescheduleRepositoryJvm(
    private val session: PayanamDatabaseSession,
    private val pollIntervalMs: Long = JdbcFlows.DEFAULT_POLL_INTERVAL_MS,
) : TaskRescheduleRepository {
    private val logger = AppLogger.current

    /**
     * Returns every reschedule record for [taskId], newest first, as a one-shot list.
     *
     * The first emission of the same flow [getReschedulesForTask] exposes, so there is one statement to
     * keep in step with the DAO rather than two.
     */
    override suspend fun getReschedulesByTaskId(taskId: String): List<TaskReschedule> {
        logger.d(
            "TaskRescheduleRepositoryJvm.getReschedulesByTaskId",
            "Fetching reschedules",
            mapOf("taskId" to taskId),
        )
        val result = getReschedulesForTask(taskId).first()
        logger.d(
            "TaskRescheduleRepositoryJvm.getReschedulesByTaskId",
            "Fetched reschedules",
            mapOf("taskId" to taskId, "count" to result.size),
        )
        return result
    }

    /** Emits the reschedule history for [taskId], newest-first. */
    override fun getReschedulesForTask(taskId: String): Flow<List<TaskReschedule>> =
        JdbcFlows
            .observe(pollIntervalMs) { readReschedules(taskId) }
            .map { entities -> entities.map { it.toDomain() } }

    /** Persists a reschedule record from a domain [TaskReschedule]. */
    override suspend fun recordReschedule(reschedule: TaskReschedule) {
        JdbcFlows.onIo {
            JdbcQueries.write(session, INSERT_SQL, reschedule.toBindings())
            logger.i(
                "TaskRescheduleRepositoryJvm.recordReschedule",
                "Recorded reschedule",
                mapOf(
                    "id" to reschedule.id,
                    "taskId" to reschedule.taskId,
                    "wasOverdue" to reschedule.wasOverdue,
                ),
            )
        }
    }

    /** Creates and persists a reschedule record from its fields, generating the id and timestamp. */
    override suspend fun recordReschedule(
        taskId: String,
        previousDueDate: LocalDateTime,
        newDueDate: LocalDateTime,
        wasOverdue: Boolean,
    ): TaskReschedule {
        val reschedule =
            TaskReschedule(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                previousDueDate = previousDueDate,
                newDueDate = newDueDate,
                rescheduledAt = LocalDateTime.now(),
                wasOverdue = wasOverdue,
            )
        recordReschedule(reschedule)
        return reschedule
    }

    private fun readReschedules(taskId: String): List<TaskRescheduleEntity> =
        JdbcQueries.read(session, RESCHEDULES_FOR_TASK_SQL, arrayOf(taskId)) { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(resultSet.readReschedule())
                }
            }
        }

    private fun ResultSet.readReschedule(): TaskRescheduleEntity =
        TaskRescheduleEntity(
            id = getString("id"),
            taskId = getString("taskId"),
            previousDueDate = getString("previousDueDate"),
            newDueDate = getString("newDueDate"),
            rescheduledAt = getString("rescheduledAt"),
            wasOverdue = getInt("wasOverdue"),
        )

    private fun TaskReschedule.toBindings(): Array<String?> =
        arrayOf(
            id,
            taskId,
            PersistedDateTime.format(previousDueDate),
            PersistedDateTime.format(newDueDate),
            PersistedDateTime.format(rescheduledAt),
            if (wasOverdue) "1" else "0",
        )

    private fun TaskRescheduleEntity.toDomain(): TaskReschedule =
        TaskReschedule(
            id = id,
            taskId = taskId,
            previousDueDate = PersistedDateTime.parse(previousDueDate),
            newDueDate = PersistedDateTime.parse(newDueDate),
            rescheduledAt = PersistedDateTime.parse(rescheduledAt),
            wasOverdue = wasOverdue == 1,
        )

    private companion object {
        /** Verbatim from `TaskRescheduleDao.insert`. */
        const val INSERT_SQL = """
            INSERT OR REPLACE INTO task_reschedules
            (id, taskId, previousDueDate, newDueDate, rescheduledAt, wasOverdue)
            VALUES (?, ?, ?, ?, ?, ?)
        """

        /** Verbatim from `TaskRescheduleDao.getReschedulesForTask`. */
        const val RESCHEDULES_FOR_TASK_SQL =
            "SELECT * FROM task_reschedules WHERE taskId = ? ORDER BY rescheduledAt DESC"
    }
}
