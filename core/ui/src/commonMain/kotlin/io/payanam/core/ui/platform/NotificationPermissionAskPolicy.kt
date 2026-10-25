//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import io.payanam.shared.platform.SupportState

/**
 * The Android release that introduced the runtime `POST_NOTIFICATIONS` permission.
 *
 * Below this release there is no permission to request at all - every notification the app posts is
 * allowed by the install - so asking would show a dialog the platform does not have.
 */
const val NOTIFICATION_PERMISSION_API_LEVEL: Int = 33

/**
 * What asking for the notification permission right now would actually achieve.
 *
 * Three answers rather than a boolean, because "may we ask" and "would asking do anything" are two
 * different questions, and the second one has a case that looks identical to the first: on Android a
 * request the user has already refused twice is answered by the platform itself, silently, with no
 * dialog. Firing that request again is harmless but useless - the user taps a button and nothing at all
 * happens - so a caller that only knows "not granted, therefore ask" leaves a dead control in the
 * middle of its screen.
 */
enum class NotificationPermissionAsk {
    /**
     * Nothing to ask for: below Android 13, already granted, or no permission concept on this platform.
     *
     * A caller in this state shows no offer and launches nothing.
     */
    NONE,

    /**
     * The platform will show its permission dialog if asked.
     *
     * This covers both "never asked" and "asked once and refused" - the second one matters, because
     * Android still shows the dialog after a single refusal and a later deliberate action by the user is
     * exactly the moment to use it.
     */
    ASK,

    /**
     * The permission is blocked and the platform will no longer show its dialog.
     *
     * The one control that can still change the answer is the system's own notification switch, so a
     * caller in this state must send the user there instead of requesting anything.
     */
    SETTINGS_ONLY,
}

/**
 * Decides what a notification-permission offer should do, from the platform's own facts.
 *
 * WHY THIS IS A PURE FUNCTION IN THE SHARED LAYER:
 *
 * The decision is the part that is easy to get wrong and impossible to check on the JVM once it is
 * tangled up with an `Activity`, a launcher and `Build.VERSION`. Everything the answer depends on
 * arrives as an argument, so the whole rule table is unit-testable without a device; the Android side
 * keeps only the reads and the launch.
 *
 * THE INPUTS, AND WHY EACH ONE IS AN OS FACT RATHER THAN A REMEMBERED GUESS:
 *
 *  - [sdkInt] - before Android 13 there is no runtime permission to request ([NOTIFICATION_PERMISSION_API_LEVEL]).
 *  - [state] - the platform's own "can this app post a notification now" answer, taken from the same
 *    read the screen renders. Only [SupportState.DECLINES] is actionable: a granted permission has
 *    nothing to ask for, and a platform without the concept has nothing to ask with.
 *  - [shouldShowRequestPermissionRationale] - the platform's own answer to "er, should I explain
 *    first?". It is `true` after ONE refusal, which is the platform saying a second request would still
 *    show the dialog, and `false` both before the very first request and after a permanent refusal.
 *  - [alreadyAskedInThisSession] - the only fact the platform will not tell us: the two `false` cases
 *    above are indistinguishable from the outside, and no read separates "never asked" from "asked and
 *    refused for good". This seam knows it launched the request itself, so it passes that on rather than
 *    guessing.
 *
 * WHY [alreadyAskedInThisSession] IS NOT A STORED PREFERENCE:
 *
 * A saved flag would outlive the reason it exists. In-memory only, it can be wrong in exactly one
 * direction after a restart - it says "not asked yet" while the platform already refuses for good - and
 * the cost of that is a request the platform answers instantly and silently, after which the flag is set
 * and the next tap routes to settings. A persisted flag would instead have to be versioned, cleared and
 * migrated, for a decision that only matters inside one screen.
 *
 * It IS held in saved instance state, though, so an Activity recreation (a rotation) keeps it while the
 * screen is merely rebuilt. Without that, rotating after a permanent refusal would pass `false` again and
 * turn the settings redirect back into a request the platform answers silently.
 */
fun notificationPermissionAsk(
    sdkInt: Int,
    state: SupportState,
    shouldShowRequestPermissionRationale: Boolean,
    alreadyAskedInThisSession: Boolean,
): NotificationPermissionAsk {
    // No runtime permission exists before Android 13: nothing to request, and a request would be a
    // no-op dialog the user never sees.
    if (sdkInt < NOTIFICATION_PERMISSION_API_LEVEL) return NotificationPermissionAsk.NONE
    // Granted already, or a platform with no such concept: every branch below is about the blocked case.
    if (state != SupportState.DECLINES) return NotificationPermissionAsk.NONE
    // The platform says a request would still show its dialog. This is the "asked once, refused once"
    // case, and a later deliberate action from the user is the right moment to spend it.
    if (shouldShowRequestPermissionRationale) return NotificationPermissionAsk.ASK
    // Not asked yet: the platform will show the dialog on the first request.
    if (!alreadyAskedInThisSession) return NotificationPermissionAsk.ASK
    // Asked, and the platform no longer offers a dialog. Only the system switch can change the answer.
    return NotificationPermissionAsk.SETTINGS_ONLY
}
