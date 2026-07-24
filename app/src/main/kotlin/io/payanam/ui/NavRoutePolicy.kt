//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui

/**
 * Centralised route-access policy for PayanamNavHost.
 *
 * Extracted from the composable so the logic can be unit-tested without a Compose runtime.
 *
 * Route categories:
 * - [startupGateRoutes]: first-run / recovery flows — always allowed regardless of feature flags.
 * - [secondaryRoutes]: child screens reachable from allowed tabs (Tasks, Settings) — always allowed
 *   because they are part of the normal task/settings flow, not independent feature tabs.
 * - [minimalModeAllowedTabs]: bottom-nav tabs visible in minimal mode.
 */
internal object NavRoutePolicy {

    val startupGateRoutes: Set<String> = setOf(
        "database_init",
        "passphrase_setup",
        "passphrase_unlock",
        "passphrase_change",
        "focus_mode_selection",
    )

    /** Prefix-matched: "task_detail" covers "task_detail/{taskId}" etc. */
    val secondaryRoutes: Set<String> = setOf(
        "add_task",
        "task_detail",
        "edit_task",
        "scoring_config",
        "feedback",
        "my_reports",
    )

    val minimalModeAllowedTabs: Set<String> = setOf(
        "time",
        "tasks",
        "journal",
        "notes",
        "lenses",
        "settings",
    )

    /**
     * Returns true if [route] is allowed to be on the nav stack given [minimalModeEnabled].
     *
     * Startup gate routes and secondary (child) routes are always allowed.
     * In minimal mode, only [minimalModeAllowedTabs] are allowed for the bottom nav tabs;
     * other disabled tabs (currently habits) are blocked.
     */
    fun isAllowed(route: String, minimalModeEnabled: Boolean): Boolean {
        if (route in startupGateRoutes) return true
        if (secondaryRoutes.any { prefix -> route == prefix || route.startsWith("$prefix/") }) return true
        if (minimalModeEnabled && route !in minimalModeAllowedTabs) return false
        return true
    }
}
