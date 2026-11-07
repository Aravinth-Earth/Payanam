//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.components.TestTags
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.shared.logging.AppLogger

/** Which consequence the offer is about, so the copy names the loss the user is about to take. */
enum class NotificationPermissionReason {
    /** A tracking timer is running; without the permission its notification row never appears. */
    TRACKING_TIMER,

    /** A reminder was saved; without the permission it never arrives. */
    TASK_REMINDER,
}

/** The offer: "notifications are off, here is what you lose, here is how to turn them on". Three actions
 * because request and openSettings fix two blocked states no platform read can tell apart; it never blocks
 * the action that caused it, and the caller decides whether to show it. */
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
                // In the body, not the button row: a third button would not fit a phone-width dialog.
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
                    modifier = Modifier.testTag(TestTags.NotificationPermission.SETTINGS_BUTTON),
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
                modifier = Modifier.testTag(TestTags.NotificationPermission.ALLOW_BUTTON),
            ) {
                Text(strings.locNotificationPermissionAllow)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TestTags.NotificationPermission.DISMISS_BUTTON),
            ) {
                Text(strings.locNotificationPermissionNotNow)
            }
        },
    )
}
