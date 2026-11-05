//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.presentation.tasks.TaskFilter
import io.payanam.presentation.tasks.displayName
import io.payanam.shared.logging.AppLogger

/**
 * The top-level routes a user can land on, in the order the segmented row draws them.
 *
 * SHARED rather than derived: the route ids are what the preference stores, so a list of them has to be
 * identical on both platforms or a landing written on one is unrecognised on the other. The labels come
 * from [topLevelRouteLabel], which reads the shared copy table.
 */
private val TOP_LEVEL_ROUTES = listOf("tasks", "habits", "time", "journal", "notes", "lenses")

/**
 * A top-level route's button label, as copy.
 *
 * WHY A FUNCTION AND NOT A TABLE OF IDS: the original paired each route with an `R.string` id, which is
 * readable only by a module that has an `R`. These labels are ordinary copy and already live in
 * [UiStrings], so the pairing is spelled once, here, against copy.
 */
private fun UiStrings.topLevelRouteLabel(route: String): String =
    when (route) {
        "tasks" -> settingsDatabaseTasks
        "habits" -> locHabits
        "time" -> locTime
        "journal" -> locJournal
        "notes" -> settingsDatabaseNotes
        "lenses" -> locLenses
        else -> error("No label mapped for top-level route: $route")
    }

/**
 * Default-landing settings section: which top-level route the app opens on, and - when that route is
 * Tasks - which task layer and state it opens with.
 *
 * WHY THE WRITES ARE PARAMETERS: nothing here is platform-bound; it reads the shared
 * [AppPreferencesState] and reports the row the user touched. Binding to storage is the caller's job, so
 * the desktop and Android bind the same section to their own writers.
 *
 * The three writes are separate callbacks rather than one because they are three different STORAGE
 * writes, not three spellings of one: choosing Tasks records a task filter, while choosing any other
 * route clears the filter key, so a single `onRouteChanged(route, filter)` would make every caller
 * re-derive which of those two shapes a given route needs - and would put a `when` over route identity
 * into every future platform that binds it.
 *
 * The auto-switch below is a write from inside a composable, which is why it is a callback too: when the
 * stored landing is a route the user has since hidden, the row has nothing selected, and the section asks
 * its owner to move the landing to the first visible route rather than writing on its own.
 */
@Composable
fun SettingsDefaultLandingSection(
    prefsState: AppPreferencesState,
    onLaunchDestinationRouteChange: (route: String) -> Unit,
    onLaunchDestinationTimeChange: () -> Unit,
    onLaunchDestinationTasksChange: (taskFilter: TaskFilter?) -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    if (prefsState.isLoading) {
        Text(
            text = strings.settingsLoadingPreferences,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val launchDestination = prefsState.launchDestination
    val effectiveTaskFilter = launchDestination.taskFilter ?: prefsState.currentTaskFilter
    val isTasksDestination = launchDestination.route == "tasks"
    val visibleRoutes =
        TOP_LEVEL_ROUTES.filter { route ->
            prefsState.tabVisibility[route] != false
        }
    LaunchedEffect(visibleRoutes) {
        if (visibleRoutes.none { it == launchDestination.route }) {
            visibleRoutes.firstOrNull()?.let { route ->
                logger.i(
                    "SettingsDefaultLanding",
                    "Auto-switching landing: ${launchDestination.route} → $route",
                )
                onLaunchDestinationRouteChange(route)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = strings.settingsDefaultLandingDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = strings.settingsDefaultLandingTopLevelLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            visibleRoutes.forEachIndexed { index, route ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = visibleRoutes.size),
                    onClick = {
                        logger.i(
                            "SettingsDefaultLanding",
                            "User changed default landing",
                            mapOf(
                                "route" to route,
                                "wasHidden" to (prefsState.tabVisibility[route] == false).toString(),
                            ),
                        )
                        when (route) {
                            "time" -> {
                                onLaunchDestinationTimeChange()
                            }

                            "tasks" -> {
                                onLaunchDestinationTasksChange(
                                    if (effectiveTaskFilter == TaskFilter.NOT_ACTIVE) null else effectiveTaskFilter,
                                )
                            }

                            else -> {
                                onLaunchDestinationRouteChange(route)
                            }
                        }
                    },
                    selected = launchDestination.route == route,
                ) {
                    Text(
                        text = strings.topLevelRouteLabel(route),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (isTasksDestination) {
            val selectedTaskState =
                if (effectiveTaskFilter == TaskFilter.NOT_ACTIVE) {
                    TaskFilter.NOT_ACTIVE
                } else {
                    TaskFilter.ACTIVE
                }
            Text(
                text = strings.settingsDefaultLandingTaskLayerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(TaskFilter.NOT_ACTIVE, TaskFilter.ACTIVE).forEachIndexed { index, filter ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        onClick = {
                            logger.i(
                                "SettingsDefaultLanding",
                                "User changed task layer",
                                mapOf(
                                    "filter" to (
                                        when (filter) {
                                            TaskFilter.NOT_ACTIVE -> "not_active"
                                            else -> "active"
                                        }
                                    ),
                                ),
                            )
                            when (filter) {
                                TaskFilter.NOT_ACTIVE -> {
                                    onLaunchDestinationTasksChange(TaskFilter.NOT_ACTIVE)
                                }

                                TaskFilter.ACTIVE -> {
                                    onLaunchDestinationTasksChange(
                                        when (effectiveTaskFilter) {
                                            TaskFilter.OVERDUE, TaskFilter.TODAY, TaskFilter.FUTURE -> effectiveTaskFilter
                                            else -> TaskFilter.TODAY
                                        },
                                    )
                                }

                                else -> {
                                    Unit
                                }
                            }
                        },
                        selected = selectedTaskState == filter,
                    ) {
                        Text(
                            text =
                                if (filter == TaskFilter.NOT_ACTIVE) {
                                    strings.locNotActive
                                } else {
                                    strings.widgetTrackingStatusActive
                                },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (effectiveTaskFilter != TaskFilter.NOT_ACTIVE) {
                Text(
                    text = strings.settingsDefaultLandingTaskStateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(TaskFilter.OVERDUE, TaskFilter.TODAY, TaskFilter.FUTURE).forEachIndexed { index, filter ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            onClick = {
                                logger.i(
                                    "SettingsDefaultLanding",
                                    "User changed task state",
                                    mapOf("filter" to filter.key),
                                )
                                onLaunchDestinationTasksChange(filter)
                            },
                            selected = effectiveTaskFilter == filter,
                        ) {
                            Text(
                                text =
                                    when (filter) {
                                        TaskFilter.OVERDUE -> strings.locPast
                                        TaskFilter.TODAY -> strings.locToday
                                        TaskFilter.FUTURE -> strings.locFuture
                                        else -> filter.displayName
                                    },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
