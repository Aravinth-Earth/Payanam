//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length")

package io.payanam.database.repository

import io.payanam.common.logging.UnifiedLogger
import io.payanam.common.util.PersistedDateTime
import io.payanam.database.entity.ScheduledNotificationEntity
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.NotificationRepository
import io.payanam.domain.repository.ScheduledNotification
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * Room-backed implementation of [NotificationRepository]. Manages
 * [ScheduledNotificationEntity] rows: one row per scheduled local push
 * notification tied to a task. Pending = not yet delivered; overdue = fire time
 * passed.
 */
class NotificationRepositoryImpl
    @Inject
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : NotificationRepository {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Schedules a local push notification for [taskId] at [scheduledAt] with the
         * given [title]/[body]/[notificationType]. Returns the new notification id.
         */
        override suspend fun scheduleNotification(
            taskId: String,
            scheduledAt: LocalDateTime,
            notificationType: String,
            title: String,
            body: String,
        ): String {
            val id = UUID.randomUUID().toString()
            val now = LocalDateTime.now()
            val entity =
                ScheduledNotificationEntity(
                    id = id,
                    taskId = taskId,
                    scheduledAt = PersistedDateTime.format(scheduledAt),
                    notificationType = notificationType,
                    title = title,
                    body = body,
                    isDelivered = 0,
                    createdAt = PersistedDateTime.format(now),
                )

            sessionManager.requireDatabase().scheduledNotificationDao().insert(entity)

            logger.i(
                "NotificationRepositoryImpl.scheduleNotification",
                "Scheduled notification",
                mapOf(
                    "id" to id,
                    "taskId" to taskId,
                    "type" to notificationType,
                    "scheduledAt" to scheduledAt.toString(),
                ),
            )

            return id
        }

        /**
         * Returns every notification (delivered or pending) for [taskId].
         */
        override suspend fun getNotificationsForTask(taskId: String): List<ScheduledNotification> {
            logger.d("NotificationRepositoryImpl.getNotificationsForTask", "Fetching notifications for task", mapOf("taskId" to taskId))
            val result =
                sessionManager
                    .requireDatabase()
                    .scheduledNotificationDao()
                    .getNotificationsForTask(taskId)
                    .map { it.toDomain() }
            logger.d(
                "NotificationRepositoryImpl.getNotificationsForTask",
                "Fetched notifications",
                mapOf(
                    "taskId" to taskId,
                    "count" to result.size,
                ),
            )
            return result
        }

        /**
         * Returns notifications that are not yet delivered and whose fire time is
         * still in the future.
         */
        override suspend fun getPendingNotifications(): List<ScheduledNotification> {
            logger.d("NotificationRepositoryImpl.getPendingNotifications", "Fetching pending notifications")
            val now = PersistedDateTime.format(LocalDateTime.now())
            val result =
                sessionManager
                    .requireDatabase()
                    .scheduledNotificationDao()
                    .getPendingNotifications(now)
                    .map { it.toDomain() }
            logger.d("NotificationRepositoryImpl.getPendingNotifications", "Fetched pending notifications", mapOf("count" to result.size))
            return result
        }

        /**
         * Marks the notification [id] as delivered (flips the `isDelivered` flag).
         */
        override suspend fun markDelivered(id: String) {
            sessionManager.requireDatabase().scheduledNotificationDao().markDelivered(id)
            logger.d("NotificationRepositoryImpl.markDelivered", "Marked delivered", mapOf("id" to id))
        }

        /**
         * Deletes all notifications for [taskId] (e.g. when the task is rescheduled
         * or deleted).
         */
        override suspend fun cancelNotificationsForTask(taskId: String) {
            sessionManager.requireDatabase().scheduledNotificationDao().deleteForTask(taskId)
            logger.i("NotificationRepositoryImpl.cancelNotificationsForTask", "Cancelled notifications", mapOf("taskId" to taskId))
        }

        /**
         * Deletes the single notification [id].
         */
        override suspend fun cancelNotification(id: String) {
            sessionManager.requireDatabase().scheduledNotificationDao().deleteById(id)
            logger.d("NotificationRepositoryImpl.cancelNotification", "Cancelled notification", mapOf("id" to id))
        }

        // Mapper
        private fun ScheduledNotificationEntity.toDomain() =
            ScheduledNotification(
                id = id,
                taskId = taskId,
                scheduledAt = PersistedDateTime.parse(scheduledAt),
                notificationType = notificationType,
                title = title,
                body = body,
                isDelivered = isDelivered == 1,
            )
    }
