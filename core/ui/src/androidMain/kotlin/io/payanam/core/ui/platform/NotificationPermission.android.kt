//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.platform.SupportState

/** The log source for this seam, so its lines are attributable in a session log. */
private const val SOURCE = "NotificationPermission"

/**
 * Android's side of the seam: the runtime `POST_NOTIFICATIONS` permission.
 *
 * WHY THIS EXISTS:
 *
 * Android 13 (API 33) requires `POST_NOTIFICATIONS` for EVERY notification, foreground-service ones
 * included. The app declares it and targets 35, so on a fresh install the OS has already denied it -
 * the timer's foreground-service notification appears only in the Task Manager, and task reminders are
 * blocked outright. An install upgraded from Android 12 keeps working, which is why this stayed
 * invisible for so long.
 *
 * WHY THE STATE IS RE-READ AFTER THE ANSWER:
 *
 * The launcher reports WHAT THE USER PICKED; the platform reports WHAT THE APP MAY NOW DO. Only the
 * second one is the answer the UI needs, so the callback re-reads the platform rather than leaving the
 * value read at composition time in place. A cached boolean would keep claiming "blocked" after a
 * successful grant, and the next screen to offer the permission would offer it again.
 *
 * WHY THE PLATFORM READ OUTRANKS THE DIALOG'S ANSWER:
 *
 * A granted runtime permission is not the same fact as "this app can post a notification now":
 * the user can grant the permission while the app's notifications are switched off in system
 * settings, and then the grant is real, the dialog said yes, and nothing is posted. The app-wide
 * switch is the wider of the two answers and outranks the dialog, so the state after the request
 * comes from the same platform read the initial state came from. The dialog's answer is the event
 * that says "look again", never the answer itself.
 *
 * WHY BELOW API 33 THE STATE IS SUPPORTED:
 *
 * There is no runtime permission for notifications before Android 13, so there is nothing to ask for.
 * The app-level switch in system settings is a DIFFERENT question, and the one that answers it is the
 * reminders capability probe (which also reads the reminders channel's importance) - not this seam.
 * This seam answers only "is there a permission to request, and is it granted".
 */
@Composable
actual fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    // Keyed on the context so a new Activity re-reads the platform instead of inheriting an answer
    // that belonged to the previous one.
    val stateHolder = remember(context) { mutableStateOf(notificationSupportState(context)) }
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            // The platform read is the answer; the dialog's answer is only what made us look
            // again. A granted permission is not the same question as "can this app post one
            // now": the user can grant it with the app's notifications switched off in system
            // settings, and then every notification is silently dropped. The app-wide switch
            // outranks the dialog, so the state comes from the same read the initial state came
            // from, and the two cannot drift.
            val platformState = notificationSupportState(context)
            stateHolder.value = platformState
            AppLogger.current.d(
                SOURCE,
                "Notification permission answered",
                mapOf("state" to platformState.name),
            )
        }
    return remember(stateHolder, launcher) {
        AndroidNotificationPermission(
            stateHolder = stateHolder,
            requestAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    stateHolder.value == SupportState.DECLINES
                ) {
                    AppLogger.current.d(SOURCE, "Requesting the notification permission")
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            openSettingsAction = {
                openNotificationSettings(context)
            },
        )
    }
}

/**
 * The handle, over a snapshot-backed state so that reading [state] inside a composition subscribes the
 * reader to the next answer.
 */
private class AndroidNotificationPermission(
    private val stateHolder: MutableState<SupportState>,
    private val requestAction: () -> Unit,
    private val openSettingsAction: () -> Unit,
) : NotificationPermission {
    override val state: SupportState
        get() = stateHolder.value

    override fun request() {
        requestAction()
    }

    override fun openSettings() {
        openSettingsAction()
    }
}

/**
 * Opens the OS screen where this app's notifications are switched on.
 *
 * WHY THIS SCREEN AND NOT THE APP'S OWN PAGE:
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` with the package extra lands on the notification switch itself,
 * which is the thing the user actually has to flip. `ACTION_APPLICATION_DETAILS_SETTINGS` would land
 * on the app's info page instead, where the switch is one more tap away and most users never find it.
 *
 * WHY THE NEW_TASK FLAG:
 *
 * The context this is called with is whatever `LocalContext` holds, which is not guaranteed to be an
 * Activity - a wrapper, a preview context - and `startActivity` from a non-Activity context throws
 * without this flag. Adding it unconditionally is harmless from an Activity.
 *
 * WHY THE STATE IS NOT RE-READ HERE:
 *
 * The settings screen reports nothing back - no result, no callback - so the switch the user flips
 * there is only visible to a later read of the platform. The handle's [NotificationPermission.state] is
 * a snapshot taken when the handle was made, refreshed by a platform read whenever the permission
 * dialog is answered; a caller that sends the user to the settings screen and wants the answer that
 * follows has to read the platform again once the app is back in front.
 */
private fun openNotificationSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        AppLogger.current.d(SOURCE, "Opening the notification settings screen")
        context.startActivity(intent)
    }.onFailure { error ->
        AppLogger.current.w(
            SOURCE,
            "Could not open the notification settings screen",
            mapOf("error" to (error.message ?: "unknown")),
        )
    }
}

/**
 * What the platform currently permits, in the shared vocabulary.
 *
 * `areNotificationsEnabled` is the app-wide switch, which on API 33+ also carries the runtime
 * `POST_NOTIFICATIONS` grant - a user who declined the prompt reads as blocked here, which is exactly
 * what happened on every fresh install.
 */
private fun notificationSupportState(context: Context): SupportState {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return SupportState.SUPPORTED
    }
    return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        SupportState.SUPPORTED
    } else {
        SupportState.DECLINES
    }
}
