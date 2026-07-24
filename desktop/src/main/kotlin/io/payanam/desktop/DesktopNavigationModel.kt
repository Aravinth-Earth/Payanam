//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopTopLevelRoute

data class DesktopNavigationModel(
    val launchRoute: DesktopTopLevelRoute,
    val primaryRoutes: List<DesktopTopLevelRoute>,
)

fun desktopNavigationModel(settings: DesktopSettingsSnapshot): DesktopNavigationModel =
    DesktopNavigationModel(
        launchRoute = desktopLaunchRoute(settings),
        primaryRoutes = settings.visibleRoutes(),
    )

fun desktopLaunchRoute(settings: DesktopSettingsSnapshot): DesktopTopLevelRoute {
    val preferredRoute = settings.launchRoute
    return if (settings.isRouteVisible(preferredRoute)) {
        preferredRoute
    } else {
        settings.visibleRoutes().firstOrNull() ?: DesktopTopLevelRoute.SETTINGS
    }
}

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
