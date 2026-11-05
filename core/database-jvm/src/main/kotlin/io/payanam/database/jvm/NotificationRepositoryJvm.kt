//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.common.util.PersistedDateTime
import io.payanam.domain.repository.NotificationRepository
import io.payanam.domain.repository.ScheduledNotification
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID

/**
 * JDBC implementation of [NotificationRepository] over the encrypted database.
 *
 * The FIRST write-path repository in this layer, which raises a concern the read-only ones did not
 * have: durability of the write. Measured on this driver, `autoCommit` is `true` on open, so each
 * single-statement write here commits as it executes and no explicit commit is needed — but that is a
 * property of the driver's default, not of SQL, so it is asserted by a test rather than assumed: the
 * suite writes, then reads the row back through a SECOND connection, so a write that stayed in an
 * uncommitted transaction would not be found.
 *
 * No change-bus publication is needed for this repository: its DAO declares no `Flow` observer for
 * `scheduled_notifications`, so there is no live reader to notify. Publication is wired when the Flow
 * tier lands and a table has an observer.
 *
 * @param session an already-open session owned by the caller.
 */
class NotificationRepositoryJvm(
    private val session: PayanamDatabaseSession,
) : NotificationRepository {
    /**
     * Inserts a notification and returns its generated id.
     *
     * `INSERT OR REPLACE`, matching the Android DAO's `@Insert(onConflict = REPLACE)`.
     */
    override suspend fun scheduleNotification(
        taskId: String,
        scheduledAt: LocalDateTime,
        notificationType: String,
        title: String,
        body: String,
    ): String =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val now = PersistedDateTime.format(LocalDateTime.now())
            // Inserted directly rather than through the text-only helper: isDelivered is an Int column,
            // and going through setString would rely on the driver's implicit text-to-int coercion.
            session.withSerializedAccess { connection ->
                connection.prepareStatement(INSERT_SQL).use { statement ->
                    statement.setString(COL_ID, id)
                    statement.setString(COL_TASK_ID, taskId)
                    statement.setString(COL_SCHEDULED_AT, PersistedDateTime.format(scheduledAt))
                    statement.setString(COL_NOTIFICATION_TYPE, notificationType)
                    statement.setString(COL_TITLE, title)
                    statement.setString(COL_BODY, body)
                    statement.setInt(COL_IS_DELIVERED, NOT_DELIVERED)
                    statement.setString(COL_CREATED_AT, now)
                    statement.executeUpdate()
                }
            }
            AppLogger.current.i(
                SOURCE,
                "Scheduled a notification",
                mapOf("id" to id, "type" to notificationType),
            )
            id
        }

    /** Every notification for one task, delivered or not. */
    override suspend fun getNotificationsForTask(taskId: String): List<ScheduledNotification> =
        withContext(Dispatchers.IO) {
            session.read(FOR_TASK_SQL, taskId) { it.readNotifications() }
        }

    /**
     * Undelivered notifications scheduled strictly after now, ascending.
     *
     * The comparison is on the persisted text, matching the Android DAO exactly. `PersistedDateTime`
     * writes a fixed-width ISO form, which is why lexicographic ordering is chronological here.
     */
    override suspend fun getPendingNotifications(): List<ScheduledNotification> =
        withContext(Dispatchers.IO) {
            val now = PersistedDateTime.format(LocalDateTime.now())
            session.read(PENDING_SQL, now) { it.readNotifications() }
        }

    /** Marks one notification delivered. Idempotent. */
    override suspend fun markDelivered(id: String) {
        withContext(Dispatchers.IO) {
            session.write(MARK_DELIVERED_SQL, id)
            AppLogger.current.d(SOURCE, "Marked a notification delivered")
        }
    }

    /** Removes every notification for a task. */
    override suspend fun cancelNotificationsForTask(taskId: String) {
        withContext(Dispatchers.IO) {
            session.write(DELETE_FOR_TASK_SQL, taskId)
            AppLogger.current.i(SOURCE, "Cancelled a task's notifications")
        }
    }

    /** Removes one notification. */
    override suspend fun cancelNotification(id: String) {
        withContext(Dispatchers.IO) {
            session.write(DELETE_BY_ID_SQL, id)
            AppLogger.current.d(SOURCE, "Cancelled a notification")
        }
    }

    private fun ResultSet.readNotifications(): List<ScheduledNotification> =
        buildList {
            while (next()) {
                add(
                    ScheduledNotification(
                        id = getString("id"),
                        taskId = getString("taskId"),
                        scheduledAt = PersistedDateTime.parse(getString("scheduledAt")),
                        notificationType = getString("notificationType"),
                        title = getString("title"),
                        body = getString("body"),
                        // Stored as Int (0/1); the domain exposes Boolean.
                        isDelivered = getInt("isDelivered") == 1,
                    ),
                )
            }
        }

    /** Executes a single-statement write whose parameters are all text. */
    private fun PayanamDatabaseSession.write(
        sql: String,
        vararg parameters: String,
    ): Int = JdbcQueries.write(this, sql, parameters)

    /** Runs one query and maps its rows. */
    private fun <T> PayanamDatabaseSession.read(
        sql: String,
        vararg parameters: String,
        map: (ResultSet) -> T,
    ): T = JdbcQueries.read(this, sql, parameters, map)

    private companion object {
        const val SOURCE = "NotificationRepositoryJvm"

        /** `scheduled_notifications` column order, as bound by [INSERT_SQL]. */
        const val COL_ID = 1
        const val COL_TASK_ID = 2
        const val COL_SCHEDULED_AT = 3
        const val COL_NOTIFICATION_TYPE = 4
        const val COL_TITLE = 5
        const val COL_BODY = 6
        const val COL_IS_DELIVERED = 7
        const val COL_CREATED_AT = 8

        /** `isDelivered` is stored as an Int, matching the Android entity. */
        const val NOT_DELIVERED = 0

        /** Verbatim from `ScheduledNotificationDao.getNotificationsForTask`. */
        const val FOR_TASK_SQL = "SELECT * FROM scheduled_notifications WHERE taskId = ?"

        /** Verbatim from `ScheduledNotificationDao.getPendingNotifications`. */
        const val PENDING_SQL =
            "SELECT * FROM scheduled_notifications WHERE isDelivered = 0 AND scheduledAt > ? ORDER BY scheduledAt ASC"

        /** Mirrors `@Insert(onConflict = OnConflictStrategy.REPLACE)`. */
        const val INSERT_SQL =
            "INSERT OR REPLACE INTO scheduled_notifications " +
                "(id, taskId, scheduledAt, notificationType, title, body, isDelivered, createdAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"

        /** Verbatim from `ScheduledNotificationDao.markDelivered`. */
        const val MARK_DELIVERED_SQL =
            "UPDATE scheduled_notifications SET isDelivered = 1 WHERE id = ?"

        /** Verbatim from `ScheduledNotificationDao.deleteForTask`. */
        const val DELETE_FOR_TASK_SQL = "DELETE FROM scheduled_notifications WHERE taskId = ?"

        /** Verbatim from `ScheduledNotificationDao.deleteById`. */
        const val DELETE_BY_ID_SQL = "DELETE FROM scheduled_notifications WHERE id = ?"
    }
}
