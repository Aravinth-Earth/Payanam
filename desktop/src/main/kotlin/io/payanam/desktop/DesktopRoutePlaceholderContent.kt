//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.settings.DesktopTopLevelRoute

/**
 * DesktopRoutePlaceholderContent.

 */
data class DesktopRoutePlaceholderContent(
    val title: String,
    val summary: String,
    val readiness: String,
    val details: List<String>,
)
/**
 * Placeholder copy for a not-yet-implemented route (null when the route has
 * real content).
 */
fun desktopRoutePlaceholderContent(route: DesktopTopLevelRoute): DesktopRoutePlaceholderContent? =
    when (route) {
        DesktopTopLevelRoute.SETTINGS -> {
            null
        }

        DesktopTopLevelRoute.TASKS -> {
            DesktopRoutePlaceholderContent(
                title = "Tasks shell",
                summary =
                    "This route is next in the parity sequence after startup and shared contracts. " +
                        "The desktop shell is now ready to host the real task list view.",
                readiness = "Next major parity slice",
                details =
                    listOf(
                        "Will absorb Tasks and Habits list read paths.",
                        "Needs shared task-state contracts and desktop repository wiring.",
                        "Will become the default launch surface when preferred.",
                    ),
            )
        }

        DesktopTopLevelRoute.TIME -> {
            DesktopRoutePlaceholderContent(
                title = "Time shell",
                summary =
                    "The route exists so the desktop app can already navigate the same top-level " +
                        "surface map as Android.",
                readiness = "Blocked on task/habit entity parity",
                details =
                    listOf(
                        "Will host start-stop tracking and timeline views.",
                        "Depends on task selection, entry persistence, and time calculations.",
                        "Will reuse desktop session logging for activity traces.",
                    ),
            )
        }

        DesktopTopLevelRoute.NOTES -> {
            null
        }

        DesktopTopLevelRoute.JOURNAL -> {
            null
        }

        DesktopTopLevelRoute.HABITS -> {
            DesktopRoutePlaceholderContent(
                title = "Habits shell",
                summary =
                    "Habits stays separate in desktop navigation even though the underlying " +
                        "Android list engine overlaps with Tasks.",
                readiness = "Will share the Tasks list foundation",
                details =
                    listOf(
                        "Needs recurring-task desktop presentation.",
                        "Will respect minimal-mode and scoring gates where relevant.",
                    ),
            )
        }

        DesktopTopLevelRoute.LENSES -> {
            DesktopRoutePlaceholderContent(
                title = "Lenses shell",
                summary = "Insights and reports land later in the parity sequence.",
                readiness = "Downstream parity phase",
                details =
                    listOf(
                        "Depends on Tasks, Time, Notes, and Journal history.",
                        "Will likely arrive in multiple chart/report slices.",
                    ),
            )
        }
    }
