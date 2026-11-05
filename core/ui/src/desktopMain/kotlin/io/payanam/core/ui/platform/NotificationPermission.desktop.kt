//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.platform.SupportState

/** The log source for this seam, so its lines are attributable in a session log. */
private const val SOURCE = "NotificationPermission"

/**
 * The desktop's side of the seam: there is no notification permission here, and pretending otherwise
 * would be a lie the shared UI would then render.
 *
 * The desktop does post notifications (it has a system tray), but it has no PERMISSION concept: there is
 * nothing for the user to grant, no runtime permission dialog, and nothing that can be declined. So the
 * honest answer is [SupportState.NOT_APPLICABLE] - the platform has no such concept - rather than
 * [SupportState.SUPPORTED], which would claim the app asked the user and was allowed.
 *
 * A shared screen that offers to request the permission checks this state and renders nothing, so the
 * desktop never shows a control it cannot honour.
 */
@Composable
actual fun rememberNotificationPermission(): NotificationPermission =
    remember {
        DesktopNotificationPermission()
    }

/**
 * The desktop's handle: a fixed state, and a request that logs and does nothing.
 *
 * The request is logged rather than silently dropped because a caller reaching for it on this platform
 * is a bug in the shared screen - it should have read [SupportState.NOT_APPLICABLE] and offered
 * nothing. The log line is how that bug becomes visible instead of disappearing.
 *
 * The same holds for [openSettings]: the desktop has no notification settings screen to open, so the
 * call is logged at the same level as the request and does nothing.
 */
private class DesktopNotificationPermission : NotificationPermission {
    override val state: SupportState = SupportState.NOT_APPLICABLE

    override fun request() {
        AppLogger.current.d(
            SOURCE,
            "Notification permission request ignored: the desktop has no such permission",
        )
    }

    override fun openSettings() {
        AppLogger.current.d(
            SOURCE,
            "Notification settings ignored: the desktop has no such settings screen",
        )
    }
}
