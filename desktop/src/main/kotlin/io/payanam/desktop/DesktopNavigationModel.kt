//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.shared.settings.DesktopTopLevelRoute

/**
 * The route the app opens into, plus the visible top-level routes for the
 * navigation rail.
 */
data class DesktopNavigationModel(
    val launchRoute: DesktopTopLevelRoute,
    val primaryRoutes: List<DesktopTopLevelRoute>,
)

/**
 * Builds the navigation model from the SHARED preferences: `tabVisibility` and `launchDestination` are
 * Android's own rows, so the rail shows what the database says rather than a desktop copy of it.
 */
fun desktopNavigationModel(preferences: AppPreferencesState): DesktopNavigationModel =
    DesktopNavigationModel(
        launchRoute = desktopLaunchRoute(preferences),
        primaryRoutes = preferences.visibleRoutes(),
    )

/**
 * Preferred launch route, falling back to the first visible (or SETTINGS) when it is hidden.
 */
fun desktopLaunchRoute(preferences: AppPreferencesState): DesktopTopLevelRoute {
    val preferredRoute = DesktopTopLevelRoute.fromStorageKey(preferences.launchDestination.route)
    return if (preferences.isRouteVisible(preferredRoute)) {
        preferredRoute
    } else {
        preferences.visibleRoutes().firstOrNull() ?: DesktopTopLevelRoute.SETTINGS
    }
}

/**
 * Whether `tab_visible_<route>` says this route is shown. The settings tab's row is always true, because
 * the mapper writes it that way - Android's own rule, not a desktop exception.
 */
fun AppPreferencesState.isRouteVisible(route: DesktopTopLevelRoute): Boolean = tabVisibility[route.storageKey] == true

/** The routes the navigation rail shows, in enum order. */
fun AppPreferencesState.visibleRoutes(): List<DesktopTopLevelRoute> = DesktopTopLevelRoute.entries.filter { route -> isRouteVisible(route) }

/**
 * One-line description of what this route contains (for tooltips/help).
 */
fun DesktopTopLevelRoute.summary(): String =
    when (this) {
        DesktopTopLevelRoute.TASKS -> "Task and habit lists, filters, and edit flows."
        DesktopTopLevelRoute.HABITS -> "Recurring-task views and habit-specific filtering."
        DesktopTopLevelRoute.TIME -> "Tracking, timeline, and time-entry flows."
        DesktopTopLevelRoute.JOURNAL -> "Day view, journaling, and planning layers."
        DesktopTopLevelRoute.NOTES -> "Local notes and supporting capture flows."
        DesktopTopLevelRoute.LENSES -> "Insights, reports, and cross-module analysis."
        DesktopTopLevelRoute.SETTINGS -> "Preferences, import-export, and app control surfaces."
    }
