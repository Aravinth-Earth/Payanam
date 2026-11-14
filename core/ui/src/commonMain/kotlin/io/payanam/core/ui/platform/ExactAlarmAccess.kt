//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import androidx.compose.runtime.Composable

/**
 * Whether this platform may arm exact alarms, and the system screen that grants it.
 *
 * A caller reads [canScheduleExactAlarms] inside composition and offers the system screen only while the
 * answer is false; the answer is re-read when the app returns to the foreground, because the grant happens
 * on a system screen that reports nothing back to this process.
 */
interface ExactAlarmAccess {
    /**
     * True when reminders may be armed exactly. False means every reminder is armed inexactly, which can
     * arrive minutes late.
     */
    fun canScheduleExactAlarms(): Boolean

    /** Opens the system screen where exact alarms are allowed for this app. */
    fun requestExactAlarmAccess()
}

/**
 * The platform's exact-alarm handle, remembered for the life of the composition.
 *
 * Callers hold the handle rather than a snapshot of it, so the answer they read is the current one.
 */
@Composable
expect fun rememberExactAlarmAccess(): ExactAlarmAccess
