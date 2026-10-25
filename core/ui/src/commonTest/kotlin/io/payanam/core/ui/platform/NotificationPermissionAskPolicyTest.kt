//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.platform

import io.payanam.shared.platform.SupportState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The notification-permission offer's rule table, checked on the JVM.
 *
 * WHY THIS TEST EXISTS:
 *
 * Device testing found the app declaring `POST_NOTIFICATIONS` and then asking every time a timer
 * started, including after the user had refused the dialog for good - at which point Android answers the
 * request itself, silently, and the button the user just pressed reads as broken. Every branch that
 * decides "ask" or "send them to settings" is therefore pinned here, one case per input, because none of
 * these answers can be observed without a device once they are mixed into a composable.
 *
 * NOT COVERED HERE, ON PURPOSE:
 *
 * The platform reads themselves - `areNotificationsEnabled`, `shouldShowRequestPermissionRationale`, the
 * activity lookup - and the launch of the system dialog. Those are Android API calls with no JVM
 * implementation, and faking them would test the fake. They stay behind [rememberNotificationPermission]
 * and need a device or an instrumented run; what they FEED this function is what is tested here.
 */
class NotificationPermissionAskPolicyTest {
    /** Below Android 13 there is no runtime permission, so a request would be a dialog that does not exist. */
    @Test
    fun none_below_android_13() {
        assertEquals(
            NotificationPermissionAsk.NONE,
            notificationPermissionAsk(
                sdkInt = NOTIFICATION_PERMISSION_API_LEVEL - 1,
                state = SupportState.DECLINES,
                shouldShowRequestPermissionRationale = false,
                alreadyAskedInThisSession = false,
            ),
        )
    }

    /** Android 13 itself is the first release with the permission, so the gate is `>=`, not `>`. */
    @Test
    fun asks_on_android_13_itself() {
        assertEquals(
            NotificationPermissionAsk.ASK,
            notificationPermissionAsk(
                sdkInt = NOTIFICATION_PERMISSION_API_LEVEL,
                state = SupportState.DECLINES,
                shouldShowRequestPermissionRationale = false,
                alreadyAskedInThisSession = false,
            ),
        )
    }

    /** Granted: there is nothing to ask for, and asking anyway would spend the user's patience on nothing. */
    @Test
    fun none_when_notifications_are_granted() {
        assertEquals(
            NotificationPermissionAsk.NONE,
            notificationPermissionAsk(
                sdkInt = NOTIFICATION_PERMISSION_API_LEVEL,
                state = SupportState.SUPPORTED,
                shouldShowRequestPermissionRationale = false,
                alreadyAskedInThisSession = false,
            ),
        )
    }

    /** The desktop's answer: no permission concept, so no request and no settings route to offer. */
    @Test
    fun none_when_the_platform_has_no_permission_concept() {
        assertEquals(
            NotificationPermissionAsk.NONE,
            notificationPermissionAsk(
                sdkInt = NOTIFICATION_PERMISSION_API_LEVEL,
                state = SupportState.NOT_APPLICABLE,
                shouldShowRequestPermissionRationale = false,
                alreadyAskedInThisSession = false,
            ),
        )
    }

    /**
     * A capability state that is neither granted nor blocked must not be read as askable - this seam never
     * produces one, and a future one must fail closed rather than open a permission dialog unexpectedly.
     */
    @Test
    fun none_for_a_state_that_is_neither_granted_nor_blocked() {
        assertEquals(
            NotificationPermissionAsk.NONE,
            notificationPermissionAsk(
                sdkInt = NOTIFICATION_PERMISSION_API_LEVEL,
                state = SupportState.IN_PROCESS_ONLY,
                shouldShowRequestPermissionRationale = false,
                alreadyAskedInThisSession = false,
            ),
        )
    }

    /** Never asked: the platform will show its dialog on the first request, which is the whole point. */
    @Test
    fun asks_the_first_time() {
        assertEquals(
            NotificationPermissionAsk.ASK,
            notificationPermissionAsk(
                sdkInt = NOTIFICATION_PERMISSION_API_LEVEL,
                state = SupportState.DECLINES,
                shouldShowRequestPermissionRationale = false,
                alreadyAskedInThisSession = false,
            ),
        )
    }

    /**
     * Refused once, without "don't ask again": the platform still offers its dialog (`rationale` is true),
     * so a later deliberate action from the user may ask again.
     */
    @Test
    fun asks_again_after_one_refusal_without_dont_ask_again() {
        assertEquals(
            NotificationPermissionAsk.ASK,
            notificationPermissionAsk(
                sdkInt = NOTIFICATION_PERMISSION_API_LEVEL,
                state = SupportState.DECLINES,
                shouldShowRequestPermissionRationale = true,
                alreadyAskedInThisSession = true,
            ),
        )
    }

    /**
     * The platform's rationale signal outranks the seam's own record: if it says a request would still
     * show a dialog, then a request is worth making even on the first tap of a fresh screen.
     */
    @Test
    fun the_platform_rationale_signal_outranks_the_local_record() {
        assertEquals(
            NotificationPermissionAsk.ASK,
            notificationPermissionAsk(
                sdkInt = NOTIFICATION_PERMISSION_API_LEVEL,
                state = SupportState.DECLINES,
                shouldShowRequestPermissionRationale = true,
                alreadyAskedInThisSession = false,
            ),
        )
    }

    /**
     * The defect this slice fixes: asked already, and the platform no longer offers a dialog, so a request
     * would be answered silently. The system switch is the only control left, and this is what routes
     * there instead of leaving a dead button.
     */
    @Test
    fun settings_only_after_a_permanent_refusal() {
        assertEquals(
            NotificationPermissionAsk.SETTINGS_ONLY,
            notificationPermissionAsk(
                sdkInt = NOTIFICATION_PERMISSION_API_LEVEL,
                state = SupportState.DECLINES,
                shouldShowRequestPermissionRationale = false,
                alreadyAskedInThisSession = true,
            ),
        )
    }
}
