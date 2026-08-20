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
 * NotificationRepositoryImpl.
 */
class NotificationRepositoryImpl
    @Inject
    /** Constructor. */
    constructor(
        private val sessionManager: DatabaseSessionManager,
    ) : NotificationRepository {
        private val logger = UnifiedLogger.getInstance()

        override suspend fun scheduleNotification(
            /** Task id. */
            taskId: String,
            /** Scheduled at. */
            scheduledAt: LocalDateTime,
            /** Notification type. */
            notificationType: String,
            /** Title. */
            title: String,
            /** Body. */
            body: String,
        ): String {
            /** Id. */
            val id = UUID.randomUUID().toString()
            /** Now. */
            val now = LocalDateTime.now()

            /** Entity. */
            val entity =
                /** Scheduled notification entity. */
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
                /** Map of. */
                mapOf(
                    "id" to id,
                    "taskId" to taskId,
                    "type" to notificationType,
                    "scheduledAt" to scheduledAt.toString(),
                ),
            )

            return id
        }

        override suspend fun getNotificationsForTask(taskId: String): List<ScheduledNotification> {
            logger.d("NotificationRepositoryImpl.getNotificationsForTask", "Fetching notifications for task", mapOf("taskId" to taskId))
            /** Result. */
            val result =
                /** Session manager. */
                sessionManager
                    .requireDatabase()
                    .scheduledNotificationDao()
                    .getNotificationsForTask(taskId)
                    .map { it.toDomain() }
            logger.d(
                "NotificationRepositoryImpl.getNotificationsForTask",
                "Fetched notifications",
                /** Map of. */
                mapOf(
                    "taskId" to taskId,
                    "count" to result.size,
                ),
            )
            return result
        }

        override suspend fun getPendingNotifications(): List<ScheduledNotification> {
            logger.d("NotificationRepositoryImpl.getPendingNotifications", "Fetching pending notifications")
            /** Now. */
            val now = PersistedDateTime.format(LocalDateTime.now())
            /** Result. */
            val result =
                /** Session manager. */
                sessionManager
                    .requireDatabase()
                    .scheduledNotificationDao()
                    .getPendingNotifications(now)
                    .map { it.toDomain() }
            logger.d("NotificationRepositoryImpl.getPendingNotifications", "Fetched pending notifications", mapOf("count" to result.size))
            return result
        }

        override suspend fun markDelivered(id: String) {
            sessionManager.requireDatabase().scheduledNotificationDao().markDelivered(id)
            logger.d("NotificationRepositoryImpl.markDelivered", "Marked delivered", mapOf("id" to id))
        }

        override suspend fun cancelNotificationsForTask(taskId: String) {
            sessionManager.requireDatabase().scheduledNotificationDao().deleteForTask(taskId)
            logger.i("NotificationRepositoryImpl.cancelNotificationsForTask", "Cancelled notifications", mapOf("taskId" to taskId))
        }

        override suspend fun cancelNotification(id: String) {
            sessionManager.requireDatabase().scheduledNotificationDao().deleteById(id)
            logger.d("NotificationRepositoryImpl.cancelNotification", "Cancelled notification", mapOf("id" to id))
        }

        // Mapper
        private fun ScheduledNotificationEntity.toDomain() =
            /** Scheduled notification. */
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
