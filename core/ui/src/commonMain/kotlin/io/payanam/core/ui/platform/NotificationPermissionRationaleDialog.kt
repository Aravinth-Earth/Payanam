//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.shared.logging.AppLogger

/**
 * Which consequence the offer is about, so the copy names the loss the user is about to take.
 *
 * WHY THE REASON IS AN ARGUMENT AND NOT A BOOLEAN:
 *
 * "Notifications are off" is the mechanism. What the user wants to know is what they will NOT have: a
 * running timer they cannot see, or a reminder that will not arrive. The two sentences are different,
 * and a screen that used one for both would describe a feature rather than a consequence.
 */
enum class NotificationPermissionReason {
    /** A tracking timer is running; without the permission its notification row never appears. */
    TRACKING_TIMER,

    /** A reminder was saved; without the permission it never arrives. */
    TASK_REMINDER,
}

/**
 * The offer: "notifications are off, here is what you lose, here is how to turn them on".
 *
 * WHY THREE ACTIONS AND NOT ONE:
 *
 * [NotificationPermission.request] and [NotificationPermission.openSettings] fix two DIFFERENT blocked
 * states, and no read can tell them apart - `shouldShowRequestPermissionRationale` answers false for
 * both "never asked" and "permanently denied". A user who switched notifications off in system settings
 * would tap "Allow" and watch nothing happen, because the runtime request is answered by the platform
 * itself, silently. So the settings route is offered unconditionally rather than guessed at.
 *
 * WHY THIS DIALOG NEVER BLOCKS THE ACTION THAT CAUSED IT:
 *
 * The caller raises it AFTER the action has already committed - the timer is running, the task is saved
 * with its reminder armed. Everything the user came for has already happened; this only reports that one
 * thing will be missing. "Not now" and the outside tap are both a plain dismissal, never a refusal that
 * changes what was saved.
 *
 * WHY THE CALLER DECIDES WHETHER TO SHOW IT:
 *
 * The screen asks for the permission only when the platform's own answer is `DECLINES` (see
 * [io.payanam.shared.platform.SupportState]), and it makes that decision at the moment the action
 * commits. This composable therefore renders what it is told to render and does not re-read the state:
 * a state that changes while the dialog is up (the user granting the permission elsewhere) must not
 * make the dialog vanish under the caller's feet, because the caller is often holding a navigation on
 * the other side of its dismissal.
 */
@Composable
fun NotificationPermissionRationaleDialog(
    permission: NotificationPermission,
    reason: NotificationPermissionReason,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    val body =
        when (reason) {
            NotificationPermissionReason.TRACKING_TIMER -> strings.locNotificationPermissionTimerBody
            NotificationPermissionReason.TASK_REMINDER -> strings.locNotificationPermissionReminderBody
        }
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.locNotificationPermissionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                // The settings route lives in the body rather than in the button row: the row already
                // carries two buttons, and a third would not fit a phone-width dialog.
                TextButton(
                    onClick = {
                        logger.i(
                            "NotificationPermissionRationaleDialog",
                            "User opened the system notification settings from the offer",
                            mapOf("reason" to reason.name),
                        )
                        permission.openSettings()
                        onDismiss()
                    },
                ) {
                    Text(strings.locNotificationPermissionOpenSettings)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    logger.i(
                        "NotificationPermissionRationaleDialog",
                        "User asked for the notification permission from the offer",
                        mapOf("reason" to reason.name),
                    )
                    permission.request()
                    onDismiss()
                },
            ) {
                Text(strings.locNotificationPermissionAllow)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.locNotificationPermissionNotNow)
            }
        },
    )
}
