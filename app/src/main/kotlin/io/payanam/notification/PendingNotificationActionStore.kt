//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.notification

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.common.util.PersistedDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** One reminder action tapped while the DB session was closed: type, task id, notification id, tap time. */
data class PendingNotificationAction(
    val actionType: String,
    val taskId: String,
    val notificationId: Int,
    val recordedAtMillis: Long,
    /**
     * The local date-time the reminder was due, as carried by the notification intent.
     *
     * Null for a record written before this field existed, or for an action whose intent had no
     * due time; callers fall back to the tap day in that case.
     */
    val dueAt: String? = null,
) {
    /** The device-local calendar day this action was recorded on. */
    fun recordedDay(): LocalDate = Instant.ofEpochMilli(recordedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * The local calendar day the reminder was due, when the notification carried one.
     *
     * The occurrence the action records belongs to this day, not to [recordedDay]: a reminder due
     * just before midnight and tapped just after it still completes the day it was shown for.
     */
    fun dueDay(): LocalDate? = runCatching { PersistedDateTime.parseOrDateStart(dueAt)?.toLocalDate() }.getOrNull()
}

/** What [PendingNotificationActionStore.record] did with the action it was given. */
enum class PendingActionRecordOutcome {
    /** The action is on disk and will be applied on the next unlock. */
    RECORDED,

    /** An action for the same task is already pending; the earlier one stands and this one is dropped. */
    DUPLICATE,

    /** The action for this task and notification was already applied today; this delivery is ignored. */
    ALREADY_HANDLED,

    /** The write failed; nothing was recorded. */
    FAILED,
}

/**
 * Durable side-store for reminder actions tapped while the database is locked. Device-level and
 * plaintext on purpose: `app_settings` lives inside the encrypted database, which is unavailable here.
 *
 * FORMAT: key `action:<taskId>` so the first action per task wins, value
 * `<actionType>|<notificationId>|<recordedAtMillis>`; handled tokens are
 * `handled:<taskId>:<notificationId>` in the same file, written only after the action's writes succeed.
 *
 * EVERY WRITE IS `commit()`: `apply()` returns before the bytes reach disk.
 *
 * EXPIRY: an action is valid only on the day it was recorded, because habit occurrences are day-scoped.
 * Expired entries are dropped on read and reported, never discarded silently.
 */
@Singleton
class PendingNotificationActionStore
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val logger = UnifiedLogger.getInstance()

    /** Records [action] unless already applied today or already pending today; stale entries count as absent. */
    @Synchronized
    fun record(action: PendingNotificationAction): PendingActionRecordOutcome {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pruneHandled(prefs, LocalDate.now())
        if (handledToken(prefs, action.taskId, action.notificationId) != null) {
            logger.i(
                LOG_SOURCE,
                MSG_ALREADY_HANDLED,
                mapOf(
                    "taskId" to action.taskId,
                    "notificationId" to action.notificationId,
                    "actionType" to action.actionType,
                ),
            )
            return PendingActionRecordOutcome.ALREADY_HANDLED
        }
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

    /** Returns every action still valid today, oldest first, removing expired and unreadable entries. */
    @Synchronized
    fun readPending(): List<PendingNotificationAction> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = LocalDate.now()
        pruneHandled(prefs, today)
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

    /** True when [taskId]/[notificationId] was already applied today; a hit means a re-delivery. */
    @Synchronized
    fun isHandled(
        taskId: String,
        notificationId: Int,
    ): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pruneHandled(prefs, LocalDate.now())
        if (handledToken(prefs, taskId, notificationId) == null) return false
        logger.i(
            LOG_SOURCE,
            MSG_ALREADY_HANDLED,
            mapOf("taskId" to taskId, "notificationId" to notificationId),
        )
        return true
    }

    /** Marks [action] applied and drops its pending entry in one commit; token last, or a failure loses it. */
    @Synchronized
    fun markHandled(action: PendingNotificationAction) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = handledKey(action.taskId, action.notificationId)
        runCatching {
            prefs.edit()
                .putString(key, System.currentTimeMillis().toString())
                .remove(KEY_PREFIX + action.taskId)
                .commit()
        }.fold(
            onSuccess = { stored ->
                if (stored) {
                    logger.i(
                        LOG_SOURCE,
                        "Marked reminder action handled",
                        mapOf(
                            "taskId" to action.taskId,
                            "notificationId" to action.notificationId,
                            "actionType" to action.actionType,
                        ),
                    )
                } else {
                    logger.w(
                        LOG_SOURCE,
                        "Handled reminder action token was not written to disk",
                        mapOf("taskId" to action.taskId, "notificationId" to action.notificationId),
                    )
                }
            },
            onFailure = { failure ->
                logger.e(
                    LOG_SOURCE,
                    "Failed to mark reminder action handled",
                    failure,
                    mapOf("taskId" to action.taskId, "notificationId" to action.notificationId),
                )
            },
        )
    }

    /** Removes handled tokens not recorded on [today], plus unreadable ones; every read of the file prunes. */
    private fun pruneHandled(
        prefs: SharedPreferences,
        today: LocalDate,
    ) {
        val expired = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        prefs.all.entries
            .filter { it.key.startsWith(HANDLED_KEY_PREFIX) }
            .forEach { entry ->
                val handledAtMillis = (entry.value as? String)?.toLongOrNull()
                if (handledAtMillis == null) {
                    unreadable.add(entry.key)
                } else if (!isSameDay(handledAtMillis, today)) {
                    expired.add(entry.key)
                }
            }
        if (expired.isEmpty() && unreadable.isEmpty()) return
        val editor = prefs.edit()
        expired.forEach { editor.remove(it) }
        unreadable.forEach { editor.remove(it) }
        editor.commit()
        expired.forEach { key ->
            logger.i(LOG_SOURCE, "Dropped expired handled reminder action", mapOf("key" to key))
        }
        unreadable.forEach { key ->
            logger.w(
                LOG_SOURCE,
                "Dropped unreadable handled reminder action record",
                mapOf("key" to key),
            )
        }
    }

    private fun handledToken(
        prefs: SharedPreferences,
        taskId: String,
        notificationId: Int,
    ): Long? = prefs.getString(handledKey(taskId, notificationId), null)?.toLongOrNull()

    private fun handledKey(
        taskId: String,
        notificationId: Int,
    ): String = HANDLED_KEY_PREFIX + taskId + HANDLED_KEY_SEPARATOR + notificationId

    private fun isSameDay(
        handledAtMillis: Long,
        day: LocalDate,
    ): Boolean = Instant.ofEpochMilli(handledAtMillis).atZone(ZoneId.systemDefault()).toLocalDate() == day

    private fun renderValue(action: PendingNotificationAction): String = listOf(
        action.actionType,
        action.notificationId.toString(),
        action.recordedAtMillis.toString(),
        action.dueAt.orEmpty(),
    ).joinToString(VALUE_SEPARATOR.toString())

    private fun parseValue(raw: String?, taskId: String): PendingNotificationAction? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(VALUE_SEPARATOR, limit = VALUE_FIELD_COUNT)
        // Three fields is the shape written before dueAt existed; it stays readable.
        if (parts.size !in LEGACY_VALUE_FIELD_COUNT..VALUE_FIELD_COUNT) return null
        val notificationId = parts[1].toIntOrNull() ?: return null
        val recordedAtMillis = parts[2].toLongOrNull() ?: return null
        return PendingNotificationAction(
            actionType = parts[0],
            taskId = taskId,
            notificationId = notificationId,
            recordedAtMillis = recordedAtMillis,
            dueAt = parts.getOrNull(DUE_AT_FIELD_INDEX)?.takeIf { it.isNotEmpty() },
        )
    }

    private companion object {
        private const val PREFS_NAME = "payanam_pending_notification_actions"
        private const val KEY_PREFIX = "action:"
        private const val HANDLED_KEY_PREFIX = "handled:"
        private const val HANDLED_KEY_SEPARATOR = ':'
        private const val MSG_ALREADY_HANDLED = "Ignoring reminder action; already handled"
        private const val VALUE_SEPARATOR = '|'
        private const val LEGACY_VALUE_FIELD_COUNT = 3
        private const val VALUE_FIELD_COUNT = 4
        private const val DUE_AT_FIELD_INDEX = 3
        private const val LOG_SOURCE = "PendingNotificationActionStore"
    }
}
