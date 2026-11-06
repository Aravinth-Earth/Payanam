//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.FeatureFlags
import io.payanam.MainActivity
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.notification.NotificationActionCoordinator
import io.payanam.notification.NotificationActionOutcome
import io.payanam.notification.PendingActionRecordOutcome
import io.payanam.notification.PendingNotificationAction
import io.payanam.notification.PendingNotificationActionStore
import io.payanam.widget.TimeTrackingWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles notification action button clicks.
 *
 * A reminder action tapped while the database session is closed cannot be applied: every repository
 * call fails inside [DatabaseSessionManager]. That case records the action in
 * [PendingNotificationActionStore], opens the app on its unlock screen, and leaves
 * [NotificationActionCoordinator] to apply it once the passphrase opens the session. A tapped action
 * whose session is open runs immediately, on the same coordinator path.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_TRACKING = "io.payanam.action.NOTIFICATION_STOP_TRACKING"
        const val ACTION_COMPLETE_TASK = "io.payanam.action.NOTIFICATION_COMPLETE_TASK"
        const val ACTION_SKIP_TASK = "io.payanam.action.NOTIFICATION_SKIP_TASK"
        const val ACTION_MISS_TASK = "io.payanam.action.NOTIFICATION_MISS_TASK"
        const val ACTION_SNOOZE_TASK = "io.payanam.action.NOTIFICATION_SNOOZE_TASK"

        private const val LOG_SOURCE = "NotificationActionReceiver"
    }

    @Inject
    lateinit var sessionManager: DatabaseSessionManager

    @Inject
    lateinit var coordinator: NotificationActionCoordinator

    @Inject
    lateinit var pendingActions: PendingNotificationActionStore

    private val logger = UnifiedLogger.getInstance()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Dispatches the tapped action: stop-tracking redirect, or a reminder action that either runs now
     * or is recorded for the next unlock.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        if (!FeatureFlags.remindersEnabled && intent.action != ACTION_STOP_TRACKING) {
            logger.i(
                "NotificationActionReceiver.onReceive",
                "Ignoring reminder action because reminders are disabled",
                mapOf("action" to (intent.action ?: "null")),
            )
            pendingResult.finish()
            return
        }
        when (intent.action) {
            ACTION_STOP_TRACKING -> handleStopTracking(context, pendingResult)
            ACTION_COMPLETE_TASK, ACTION_SKIP_TASK, ACTION_MISS_TASK, ACTION_SNOOZE_TASK ->
                handleReminderAction(context, intent, pendingResult)
            else -> pendingResult.finish()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: multi-system-API catch in background component
    private fun handleStopTracking(context: Context, pendingResult: BroadcastReceiver.PendingResult) {
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAV_TARGET_TIME)
                putExtra(MainActivity.EXTRA_OPEN_TIME_QUICK_START, false)
                putExtra(MainActivity.EXTRA_OPEN_TIME_STOP_TRACKING, true)
                putExtra(MainActivity.EXTRA_NAV_SOURCE, "tracking_notification")
            }
            context.startActivity(launchIntent)
            logger.i("NotificationActionReceiver.handleStopTracking", "Redirected stop action to in-app focus dialog")
        } catch (e: Exception) {
            logger.e("NotificationActionReceiver.handleStopTracking", "Failed to open stop-tracking dialog", e)
        } finally {
            TimeTrackingWidgetProvider.requestUpdate(context)
            pendingResult.finish()
        }
    }

    /**
     * Resolves the tapped action and either executes it or records it until the session opens.
     *
     * Each branch owns its own `finish()`; the catch is for a throw before a branch is entered, and a
     * second `finish()` on an already-finished result throws, so it is wrapped.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: a throw out of goAsync() would kill the receiver
    private fun handleReminderAction(
        context: Context,
        intent: Intent,
        pendingResult: BroadcastReceiver.PendingResult,
    ) {
        try {
            val actionType = actionTypeFor(intent.action)
            val taskId = intent.getStringExtra(TaskReminderReceiver.EXTRA_TASK_ID)
            val notificationIdInt = intent.getIntExtra(TaskReminderReceiver.EXTRA_NOTIFICATION_ID_INT, -1)
            if (actionType == null || taskId.isNullOrBlank()) {
                logger.w(
                    LOG_SOURCE,
                    "Missing action type or taskId for reminder action",
                    mapOf(
                        "action" to (intent.action ?: "null"),
                        "actionType" to (actionType ?: "null"),
                        "taskIdPresent" to !taskId.isNullOrBlank(),
                    ),
                )
                pendingResult.finish()
                return
            }
            val action =
                PendingNotificationAction(
                    actionType = actionType,
                    taskId = taskId,
                    notificationId = notificationIdInt,
                    recordedAtMillis = System.currentTimeMillis(),
                )
            val sessionOpen = sessionManager.isDbOpen()
            logger.i(
                LOG_SOURCE,
                "Reminder action received",
                mapOf(
                    "actionType" to actionType,
                    "taskId" to taskId,
                    "notificationId" to notificationIdInt,
                    "sessionOpen" to sessionOpen,
                ),
            )
            if (sessionOpen) {
                executeNow(action, pendingResult)
            } else {
                deferUntilUnlock(context, action, pendingResult)
            }
        } catch (e: Exception) {
            logger.e(
                LOG_SOURCE,
                "Failed to dispatch reminder action",
                e,
                mapOf("action" to (intent.action ?: "null")),
            )
            runCatching { pendingResult.finish() }
        }
    }

    /**
     * Runs the action immediately, on the same coordinator path the unlock drain uses.
     *
     * A stale pending record for the same task is cleared on success, so it cannot be replayed on the
     * next unlock.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: keeps the broadcast alive to its finish()
    private fun executeNow(action: PendingNotificationAction, pendingResult: BroadcastReceiver.PendingResult) {
        scope.launch {
            try {
                when (coordinator.execute(action)) {
                    NotificationActionOutcome.APPLIED -> pendingActions.clear(action.taskId)
                    NotificationActionOutcome.NOT_APPLICABLE, NotificationActionOutcome.FAILED -> Unit
                }
            } catch (e: Exception) {
                logger.e(
                    LOG_SOURCE,
                    "Failed to execute reminder action",
                    e,
                    mapOf("actionType" to action.actionType, "taskId" to action.taskId),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Records the action for the next unlock and opens the app so the user can unlock.
     *
     * The reminder is not cancelled here: its action has not been applied yet, and a reminder that
     * reported a change which never happened would be wrong. It stays until the action is applied.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: keeps the broadcast alive to its finish()
    private fun deferUntilUnlock(
        context: Context,
        action: PendingNotificationAction,
        pendingResult: BroadcastReceiver.PendingResult,
    ) {
        try {
            val outcome = pendingActions.record(action)
            logger.i(
                LOG_SOURCE,
                "Deferred reminder action until unlock",
                mapOf(
                    "actionType" to action.actionType,
                    "taskId" to action.taskId,
                    "notificationId" to action.notificationId,
                    "sessionOpen" to false,
                    "recordOutcome" to outcome.name,
                    "recordedDay" to action.recordedDay().toString(),
                ),
            )
            launchUnlockScreen(context, outcome)
        } catch (e: Exception) {
            logger.e(
                LOG_SOURCE,
                "Failed to defer reminder action",
                e,
                mapOf("actionType" to action.actionType, "taskId" to action.taskId),
            )
        } finally {
            pendingResult.finish()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: startActivity from a background component
    private fun launchUnlockScreen(context: Context, outcome: PendingActionRecordOutcome) {
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launchIntent)
            logger.i(
                LOG_SOURCE,
                "Launched app for unlock",
                mapOf("actionRecorded" to (outcome == PendingActionRecordOutcome.RECORDED)),
            )
        } catch (e: Exception) {
            logger.e(LOG_SOURCE, "Failed to launch app for unlock", e)
        }
    }

    private fun actionTypeFor(intentAction: String?): String? = when (intentAction) {
        ACTION_COMPLETE_TASK -> NotificationActionCoordinator.ACTION_TYPE_COMPLETE
        ACTION_SKIP_TASK -> NotificationActionCoordinator.ACTION_TYPE_SKIP
        ACTION_MISS_TASK -> NotificationActionCoordinator.ACTION_TYPE_MISS
        ACTION_SNOOZE_TASK -> NotificationActionCoordinator.ACTION_TYPE_SNOOZE
        else -> null
    }
}
