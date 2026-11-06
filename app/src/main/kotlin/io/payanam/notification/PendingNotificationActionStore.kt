//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One reminder action tapped while the encrypted database session was closed.
 *
 * Carries only the four facts needed to replay it: action type, task id, notification id and the
 * moment it was tapped. No task title and no user text, because this record is written to plain
 * `SharedPreferences` while the database is locked and must be readable without the passphrase.
 */
data class PendingNotificationAction(
    val actionType: String,
    val taskId: String,
    val notificationId: Int,
    val recordedAtMillis: Long,
) {
    /** The device-local calendar day this action was recorded on. */
    fun recordedDay(): LocalDate = Instant.ofEpochMilli(recordedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

/** What [PendingNotificationActionStore.record] did with the action it was given. */
enum class PendingActionRecordOutcome {
    /** The action is on disk and will be applied on the next unlock. */
    RECORDED,

    /** An action for the same task is already pending; the earlier one stands and this one is dropped. */
    DUPLICATE,

    /** The write failed; nothing was recorded. */
    FAILED,
}

/**
 * Durable side-store for reminder actions tapped while the database is locked.
 *
 * DEVICE-LEVEL, and deliberately not a database row: `app_settings` lives inside the encrypted
 * database, which is exactly what is unavailable at the moment this record is written. The same
 * precedent as the desktop's `DesktopSecurityStore` / `DesktopOnboardingStore` — plaintext, owned by
 * the installation rather than by the user's data, and holding nothing that needs the passphrase.
 *
 * FORMAT. Preference file `payanam_pending_notification_actions`, one entry per pending action:
 * key `action:<taskId>`, value `<actionType>|<notificationId>|<recordedAtMillis>`. The task id is the
 * key rather than a value field, so "first action wins per task" is the storage's own behaviour and
 * a second tap cannot displace the first.
 *
 * EVERY WRITE IS `commit()`. `apply()` returns before the bytes reach disk, and the process this
 * record exists to survive is the one the system may kill the instant the receiver returns.
 *
 * EXPIRY. An action is valid only on the day it was recorded: habit occurrences are day-scoped, so a
 * stale "complete" applied the next morning would mark the wrong occurrence. Expired entries are
 * dropped on read and reported, never discarded silently.
 */
@Singleton
class PendingNotificationActionStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val logger = UnifiedLogger.getInstance()

        /**
         * Records [action] unless an action for the same task is already pending for today.
         *
         * An entry left over from an earlier day is treated as absent: it can never be applied, so it
         * must not block the tap that is happening now.
         */
        @Synchronized
        fun record(action: PendingNotificationAction): PendingActionRecordOutcome {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = KEY_PREFIX + action.taskId
            val existing = parseValue(prefs.getString(key, null), action.taskId)
            if (existing != null && existing.recordedDay() == LocalDate.now()) {
                logger.i(
                    LOG_SOURCE,
                    "Ignored reminder action; an earlier action is already pending for this task",
                    mapOf(
                        "taskId" to action.taskId,
                        "pendingActionType" to existing.actionType,
                        "ignoredActionType" to action.actionType,
                        "recordedDay" to existing.recordedDay().toString(),
                    ),
                )
                return PendingActionRecordOutcome.DUPLICATE
            }
            return runCatching {
                prefs.edit().putString(key, renderValue(action)).commit()
            }.fold(
                onSuccess = { stored ->
                    if (!stored) {
                        logger.w(
                            LOG_SOURCE,
                            "Reminder action record was not written to disk",
                            mapOf("taskId" to action.taskId, "actionType" to action.actionType),
                        )
                        PendingActionRecordOutcome.FAILED
                    } else {
                        logger.i(
                            LOG_SOURCE,
                            "Recorded reminder action",
                            mapOf(
                                "taskId" to action.taskId,
                                "actionType" to action.actionType,
                                "notificationId" to action.notificationId,
                                "recordedDay" to action.recordedDay().toString(),
                            ),
                        )
                        PendingActionRecordOutcome.RECORDED
                    }
                },
                onFailure = { failure ->
                    logger.e(
                        LOG_SOURCE,
                        "Failed to record reminder action",
                        failure,
                        mapOf("taskId" to action.taskId, "actionType" to action.actionType),
                    )
                    PendingActionRecordOutcome.FAILED
                },
            )
        }

        /**
         * Returns every pending action that is still valid today, oldest first, and removes the ones
         * that are not.
         *
         * Expired and unreadable entries are removed here rather than by a separate sweep, so a read
         * is the single place a stale record can survive past.
         */
        @Synchronized
        fun readPending(): List<PendingNotificationAction> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val today = LocalDate.now()
            val live = mutableListOf<PendingNotificationAction>()
            val expired = mutableListOf<PendingNotificationAction>()
            val unreadable = mutableListOf<String>()
            prefs.all.entries
                .filter { it.key.startsWith(KEY_PREFIX) }
                .forEach { entry ->
                    val taskId = entry.key.removePrefix(KEY_PREFIX)
                    val action = parseValue(entry.value as? String, taskId)
                    if (action == null) {
                        unreadable.add(entry.key)
                    } else if (action.recordedDay() != today) {
                        expired.add(action)
                    } else {
                        live.add(action)
                    }
                }
            if (expired.isNotEmpty() || unreadable.isNotEmpty()) {
                val editor = prefs.edit()
                expired.forEach { editor.remove(KEY_PREFIX + it.taskId) }
                unreadable.forEach { editor.remove(it) }
                editor.commit()
                expired.forEach { action ->
                    logger.i(
                        LOG_SOURCE,
                        "Dropped expired reminder action",
                        mapOf(
                            "actionType" to action.actionType,
                            "taskId" to action.taskId,
                            "recordedDay" to action.recordedDay().toString(),
                        ),
                    )
                }
                unreadable.forEach { key ->
                    logger.w(
                        LOG_SOURCE,
                        "Dropped unreadable reminder action record",
                        mapOf("key" to key),
                    )
                }
            }
            return live.sortedBy { it.recordedAtMillis }
        }

        /** Removes the pending action for [taskId], called once it has been applied. */
        @Synchronized
        fun clear(taskId: String) {
            runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_PREFIX + taskId).commit()
            }.onFailure { failure ->
                logger.e(
                    LOG_SOURCE,
                    "Failed to clear reminder action",
                    failure,
                    mapOf("taskId" to taskId),
                )
            }
        }

        private fun renderValue(action: PendingNotificationAction): String =
            listOf(action.actionType, action.notificationId.toString(), action.recordedAtMillis.toString())
                .joinToString(VALUE_SEPARATOR.toString())

        private fun parseValue(raw: String?, taskId: String): PendingNotificationAction? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(VALUE_SEPARATOR, limit = VALUE_FIELD_COUNT)
            if (parts.size != VALUE_FIELD_COUNT) return null
            val notificationId = parts[1].toIntOrNull() ?: return null
            val recordedAtMillis = parts[2].toLongOrNull() ?: return null
            return PendingNotificationAction(
                actionType = parts[0],
                taskId = taskId,
                notificationId = notificationId,
                recordedAtMillis = recordedAtMillis,
            )
        }

        private companion object {
            private const val PREFS_NAME = "payanam_pending_notification_actions"
            private const val KEY_PREFIX = "action:"
            private const val VALUE_SEPARATOR = '|'
            private const val VALUE_FIELD_COUNT = 3
            private const val LOG_SOURCE = "PendingNotificationActionStore"
        }
    }
