//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.navigation

/**
 * A request to navigate that arrives from outside the graph - a home-screen widget tap, a notification
 * action, or a launcher shortcut - passed into [PayanamNavHost] as data.
 *
 * Moved from `:app` unchanged. It is a plain data type with no platform dependency, which is why it can
 * live beside the graph: the platform reads the Intent/CLI argument and constructs one of these, and the
 * graph decides what to do with it.
 */
sealed interface PayanamExternalNavigationCommand {
    /**
     * Asks to open the Time tab, optionally straight into the quick-start or stop-tracking dialog.
     *
     * [requestId] exists so the graph can tell one request from the next: the same command may be replayed
     * on recomposition, and the pending flag must only be cleared for the request that was actually
     * handled.
     */
    data class OpenTimeScreen(
        val openQuickStart: Boolean,
        val openStopTracking: Boolean,
        val source: String,
        val requestId: Long,
    ) : PayanamExternalNavigationCommand
}
