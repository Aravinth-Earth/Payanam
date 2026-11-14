//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.payanam.shared.logging.AppLogger

/** The log source for this seam, so its lines are attributable in a session log. */
private const val SOURCE = "ExactAlarmAccess"

/**
 * The desktop's side of the seam: there is no exact-alarm permission here, and alarms are not what
 * delivers a desktop reminder, so the capability is reported as granted and no shared screen offers the
 * row.
 */
@Composable
actual fun rememberExactAlarmAccess(): ExactAlarmAccess =
    remember {
        DesktopExactAlarmAccess()
    }

/**
 * A fixed "granted" answer and an inert request.
 *
 * The request is logged rather than silently dropped because a caller reaching for it on this platform is
 * a bug in the shared screen - it should have read `true` and offered nothing.
 */
private class DesktopExactAlarmAccess : ExactAlarmAccess {
    override fun canScheduleExactAlarms(): Boolean = true

    override fun requestExactAlarmAccess() {
        AppLogger.current.d(
            SOURCE,
            "Exact-alarm request ignored: the desktop has no such permission",
        )
    }
}
