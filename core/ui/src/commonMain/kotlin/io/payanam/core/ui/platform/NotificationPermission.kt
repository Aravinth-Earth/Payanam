//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import androidx.compose.runtime.Composable
import io.payanam.shared.platform.SupportState

/**
 * The platform's answer to one question: may this app post a notification, and if it may not, may it
 * ask the user for permission to?
 *
 * WHY A SEAM:
 *
 * The answer is an OS fact, and the two platforms do not even have the same question. Android 13 gates
 * every notification - foreground-service ones included - behind the runtime `POST_NOTIFICATIONS`
 * permission, so a fresh install there is DENIED by default and the app must ask. The desktop has no
 * such permission and must never pretend otherwise. A shared screen cannot ask either question itself
 * (the request is an Android activity contract), so the platform answers and the screen reads.
 *
 * WHY A HANDLE AND NOT A BOOLEAN:
 *
 * Because "are notifications allowed" and "ask for them" are two different moments. A screen that only
 * receives a boolean has to guess when to ask; a handle that carries the current state lets the screen
 * ask at the moment a notification would actually have been useful, and stay silent otherwise. The
 * state is re-read after the user answers, so a screen that keeps the handle sees the new answer
 * without re-creating anything.
 *
 * The three states this seam produces, and what each one means for a caller:
 *
 *  - [SupportState.SUPPORTED] - notifications can be posted NOW. The platform will show them. Ask
 *    nothing; the user has already granted (or was never asked, because the platform has no such
 *    permission to grant).
 *  - [SupportState.DECLINES] - the OS concept exists, the app is currently blocked, and asking is
 *    meaningful: the permission dialog is the way out. This is the state a fresh Android 13+ install
 *    starts in, and the one a screen should offer to fix.
 *  - [SupportState.NOT_APPLICABLE] - this platform has no such permission at all (the desktop). There
 *    is nothing to ask for and nothing to explain, so a caller renders nothing.
 *
 * This seam never produces [SupportState.IN_PROCESS_ONLY] or [SupportState.NOT_SURFACED]: both describe
 * a capability that exists but works differently, and a permission is neither - it is either granted,
 * blocked, or absent.
 *
 * TWO WAYS OUT, NOT ONE:
 *
 * A blocked notification can be unblocked in two different places - the permission dialog
 * ([request]) and the system's own switch ([openSettings]) - and a caller needs both, because only one
 * of them works in every blocked case. Which is which, and why the caller cannot tell them apart, is
 * explained on [openSettings].
 */
interface NotificationPermission {
    /**
     * What the platform can do right now, re-read after every answer the user gives.
     *
     * Reading this inside a composition subscribes the reader to the answer: when the user responds to
     * the permission dialog, this changes and the reader recomposes.
     */
    val state: SupportState

    /**
     * Asks the user for the notification permission, if this platform has one to ask for.
     *
     * A no-op where the platform has no such permission (the desktop) and where the permission does not
     * exist yet (Android below 13). On Android the answer arrives asynchronously; the [state] changes
     * when it does.
     *
     * ASK AFTER, NEVER BLOCK:
     *
     * This seam is for asking AFTER the user has already committed the action that would notify - a
     * timer they started, a reminder they saved. It must never gate that action: the tracking service
     * runs correctly without the permission, and the only thing the user loses is the notification's
     * drawer row. A screen that asks before the action, or that waits for an answer before letting
     * the action proceed, has turned a courtesy into a barrier - and a user who dismisses the dialog
     * has lost the thing they came for.
     */
    fun request()

    /**
     * Opens the platform's own screen where notifications are switched on, if it has one.
     *
     * WHY THIS EXISTS ALONGSIDE [request]:
     *
     * The platform's answer - the app-wide "are notifications enabled" read - is false in TWO
     * different situations, and they need two different fixes. One is a denied runtime permission,
     * where [request] shows the dialog and the user can say yes. The other is the user having
     * switched notifications off in system settings: the grant is not what is missing, and [request]
     * shows no dialog at all - the platform answers it itself, silently - so the one control the user
     * can reach changes nothing. [openSettings] is the path that actually reaches the switch.
     *
     * WHY A CALLER CANNOT TELL THE TWO APART:
     *
     * `shouldShowRequestPermissionRationale` does not distinguish "never asked" from "permanently
     * denied" - it answers false for both - so no read can say which case this is. The UI therefore
     * offers settings as the fallback unconditionally, rather than guessing which one it is in.
     *
     * A no-op where the platform has no such screen (the desktop).
     */
    fun openSettings()
}

/**
 * The platform's notification-permission handle, remembered for the life of the composition.
 *
 * Callers hold the handle rather than a snapshot of it, so the answer they read is the current one.
 */
@Composable
expect fun rememberNotificationPermission(): NotificationPermission
