//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.platform.SupportState

/** The log source for this seam, so its lines are attributable in a session log. */
private const val SOURCE = "NotificationPermission"

/** Android's side of the seam: the runtime `POST_NOTIFICATIONS` permission. The platform read is the
 * answer; the launcher's result only says to look again. */
@Composable
actual fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    // The host activity, if any, for `shouldShowRequestPermissionRationale`; null for a preview.
    val activity = remember(context) { context.findActivity() }
    // Keyed on the context so a new Activity re-reads the platform rather than inheriting an answer.
    val stateHolder = remember(context) { mutableStateOf(notificationSupportState(context)) }
    // Saved instance state, not a preference: it covers the one fact Android will not report - "never
    // asked" and "refused for good" both answer false to `shouldShowRequestPermissionRationale`.
    val hasAskedHolder = rememberSaveable(context) { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            // The platform read is the answer: a grant with the app-wide switch off still posts nothing.
            val platformState = notificationSupportState(context)
            stateHolder.value = platformState
            AppLogger.current.d(
                SOURCE,
                "Notification permission answered",
                mapOf("state" to platformState.name),
            )
        }
    // Both permission and app-wide switch can change outside this app, and neither reports back; a
    // resume is the only moment the change is visible, so the platform is re-read there.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context, stateHolder) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
                val resumedState = notificationSupportState(context)
                if (resumedState == stateHolder.value) return@LifecycleEventObserver
                stateHolder.value = resumedState
                AppLogger.current.i(
                    SOURCE,
                    "Notification permission changed while the app was away; re-read on resume",
                    mapOf("state" to resumedState.name),
                )
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return remember(stateHolder, launcher, activity, hasAskedHolder) {
        AndroidNotificationPermission(
            stateHolder = stateHolder,
            requestAction = {
                // Both signals are read at the moment of asking, never at composition time: the rationale
                // flag flips the instant the user answers, and the switch may have moved since.
                val currentState = notificationSupportState(context)
                stateHolder.value = currentState
                val rationale = shouldShowNotificationRationale(activity)
                val decision =
                    notificationPermissionAsk(
                        sdkInt = Build.VERSION.SDK_INT,
                        state = currentState,
                        shouldShowRequestPermissionRationale = rationale,
                        alreadyAskedInThisSession = hasAskedHolder.value,
                    )
                when (decision) {
                    NotificationPermissionAsk.ASK -> {
                        hasAskedHolder.value = true
                        AppLogger.current.d(SOURCE, "Requesting the notification permission")
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    NotificationPermissionAsk.SETTINGS_ONLY -> {
                        // The platform has stopped offering its dialog, so a request would be answered
                        // silently and the button would read as broken; the system switch is the only control.
                        AppLogger.current.i(
                            SOURCE,
                            "The notification permission can no longer be requested; opening the settings screen",
                        )
                        openNotificationSettings(context)
                    }

                    NotificationPermissionAsk.NONE -> {
                        Unit
                    }
                }
            },
            openSettingsAction = {
                openNotificationSettings(context)
            },
        )
    }
}

/** The handle, over a snapshot-backed state so reading [state] subscribes the reader to the next answer. */
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

/** The Activity this context belongs to, if any. A walk rather than a composition local: previews and
 * tooling hosts compose this too, and a walk answers null for them instead of throwing. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** The platform's own "would the permission dialog still appear?" answer. False below Android 13 and
 * for a missing activity, both of which mean "no evidence", which the policy reads as "ask". */
private fun shouldShowNotificationRationale(activity: Activity?): Boolean {
    if (activity == null) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.POST_NOTIFICATIONS,
    )
}

/** Opens the OS screen where this app's notifications are switched on, which is the switch the user
 * must flip; `NEW_TASK` because the context may not be an Activity. */
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

/** What the platform currently permits. `areNotificationsEnabled` is the app-wide switch, which on
 * API 33+ also carries the runtime grant, so a declined prompt reads as blocked. */
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
