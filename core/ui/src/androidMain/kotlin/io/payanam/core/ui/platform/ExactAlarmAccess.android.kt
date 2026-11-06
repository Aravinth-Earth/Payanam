//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.payanam.shared.logging.AppLogger

/** The log source for this seam, so its lines are attributable in a session log. */
private const val SOURCE = "ExactAlarmAccess"

/**
 * Android's side of the seam: the `SCHEDULE_EXACT_ALARM` special access, which a fresh install does not
 * hold and which the user grants on a system screen.
 *
 * The state is a snapshot-backed value so a caller reading it inside composition recomposes when it
 * changes, and it is re-read on every resume because the system screen reports nothing back.
 */
@Composable
actual fun rememberExactAlarmAccess(): ExactAlarmAccess {
    val context = LocalContext.current
    // Keyed on the context so a new Activity re-reads the platform instead of inheriting the previous
    // answer.
    val stateHolder = remember(context) { mutableStateOf(canScheduleExactAlarms(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context, stateHolder) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
                val resumed = canScheduleExactAlarms(context)
                if (resumed == stateHolder.value) return@LifecycleEventObserver
                stateHolder.value = resumed
                AppLogger.current.i(
                    SOURCE,
                    "Exact-alarm access changed while the app was away; re-read on resume",
                    mapOf("granted" to resumed.toString()),
                )
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return remember(stateHolder, context) {
        AndroidExactAlarmAccess(stateHolder) { openExactAlarmSettings(context) }
    }
}

private class AndroidExactAlarmAccess(
    private val stateHolder: MutableState<Boolean>,
    private val openSettingsAction: () -> Unit,
) : ExactAlarmAccess {
    override fun canScheduleExactAlarms(): Boolean = stateHolder.value

    override fun requestExactAlarmAccess() {
        openSettingsAction()
    }
}

/**
 * The platform's answer. Below Android 12 the permission does not exist and exact alarms are always
 * allowed, so the row is never offered there.
 */
private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
    return alarmManager.canScheduleExactAlarms()
}

/**
 * Opens the system screen where exact alarms are allowed for this app.
 *
 * `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` requires the app's package URI, and the NEW_TASK flag covers a
 * non-Activity context such as a preview host. An OEM build without the screen answers the intent with
 * nothing, so the launch is guarded.
 */
private fun openExactAlarmSettings(context: Context) {
    // The action and the permission both arrive in Android 12, so below it there is no screen to open.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        AppLogger.current.d(SOURCE, "Opening the exact-alarm settings screen")
        context.startActivity(intent)
    }.onFailure { error ->
        AppLogger.current.w(
            SOURCE,
            "Could not open the exact-alarm settings screen",
            mapOf("error" to (error.message ?: "unknown")),
        )
    }
}
