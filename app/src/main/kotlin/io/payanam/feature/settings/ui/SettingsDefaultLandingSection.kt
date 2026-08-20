//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.AppPreferencesViewModel
import io.payanam.ui.viewmodel.TaskFilter
import io.payanam.ui.viewmodel.displayName

@Composable
internal fun SettingsDefaultLandingSection(
    /** Prefs state. */
    prefsState: AppPreferencesState,
    /** Prefs view model. */
    prefsViewModel: AppPreferencesViewModel,
) {
    /** If. */
    if (prefsState.isLoading) {
        /** Text. */
        Text(
            text = stringResource(id = R.string.settings_loading_preferences),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Return. */
        return
    }

    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Launch destination. */
    val launchDestination = prefsState.launchDestination
    /** Effective task filter. */
    val effectiveTaskFilter = launchDestination.taskFilter ?: prefsState.currentTaskFilter
    /** Is tasks destination. */
    val isTasksDestination = launchDestination.route == "tasks"

    /** All routes. */
    val allRoutes = listOf(
        "tasks" to R.string.settings_database_tasks,
        "habits" to R.string.loc_habits,
        "time" to R.string.loc_time,
        "journal" to R.string.loc_journal,
        "notes" to R.string.settings_database_notes,
        "lenses" to R.string.loc_lenses,
    )

    /** Visible routes. */
    val visibleRoutes = allRoutes.filter { (route, _) ->
        prefsState.tabVisibility[route] != false
    }

    /** Launched effect. */
    LaunchedEffect(visibleRoutes) {
        /** If. */
        if (visibleRoutes.none { it.first == launchDestination.route }) {
            visibleRoutes.firstOrNull()?.let { (route, _) ->
                logger.i(
                    "SettingsDefaultLanding",
                    "Auto-switching landing: ${launchDestination.route} → $route",
                )
                prefsViewModel.setLaunchDestination(route)
            }
        }
    }

    /** Column. */
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        /** Text. */
        Text(
            text = stringResource(id = R.string.settings_default_landing_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Text. */
        Text(
            text = stringResource(id = R.string.settings_default_landing_top_level_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Single choice segmented button row. */
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            visibleRoutes.forEachIndexed { index, (route, labelRes) ->
                /** Segmented button. */
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = visibleRoutes.size),
                    onClick = {
                        logger.i(
                            "SettingsDefaultLanding",
                            "User changed default landing",
                            /** Map of. */
                            mapOf(
                                "route" to route,
                                "wasHidden" to (prefsState.tabVisibility[route] == false).toString(),
                            ),
                        )
                        /** When. */
                        when (route) {
                            "time" -> prefsViewModel.setLaunchDestinationTime()
                            "tasks" -> prefsViewModel.setLaunchDestinationTasks(
                                /** If. */
                                if (effectiveTaskFilter == TaskFilter.NOT_ACTIVE) null else effectiveTaskFilter,
                            )
                            else -> prefsViewModel.setLaunchDestination(route)
                        }
                    },
                    selected = launchDestination.route == route,
                ) {
                    /** Text. */
                    Text(
                        text = stringResource(id = labelRes),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        /** If. */
        if (isTasksDestination) {
            /** Selected task state. */
            val selectedTaskState = if (effectiveTaskFilter == TaskFilter.NOT_ACTIVE) {
                TaskFilter.NOT_ACTIVE
            } else {
                TaskFilter.ACTIVE
            }
            /** Text. */
            Text(
                text = stringResource(id = R.string.settings_default_landing_task_layer_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            /** Single choice segmented button row. */
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                /** List of. */
                listOf(TaskFilter.NOT_ACTIVE, TaskFilter.ACTIVE).forEachIndexed { index, filter ->
                    /** Segmented button. */
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        onClick = {
                            logger.i(
                                "SettingsDefaultLanding",
                                "User changed task layer",
                                /** Map of. */
                                mapOf("filter" to (when (filter) {
                                    TaskFilter.NOT_ACTIVE -> "not_active"
                                    else -> "active"
                                })),
                            )
                            /** When. */
                            when (filter) {
                                TaskFilter.NOT_ACTIVE -> prefsViewModel.setLaunchDestinationTasks(TaskFilter.NOT_ACTIVE)

                                TaskFilter.ACTIVE -> prefsViewModel.setLaunchDestinationTasks(
                                    /** When. */
                                    when (effectiveTaskFilter) {
                                        TaskFilter.OVERDUE, TaskFilter.TODAY, TaskFilter.FUTURE -> effectiveTaskFilter
                                        else -> TaskFilter.TODAY
                                    },
                                )

                                else -> Unit
                            }
                        },
                        selected = selectedTaskState == filter,
                    ) {
                        /** Text. */
                        Text(
                            text = if (filter == TaskFilter.NOT_ACTIVE) {
                                /** String resource. */
                                stringResource(id = R.string.loc_not_active)
                            } else {
                                /** String resource. */
                                stringResource(id = R.string.widget_tracking_status_active)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            /** If. */
            if (effectiveTaskFilter != TaskFilter.NOT_ACTIVE) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.settings_default_landing_task_state_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                /** Single choice segmented button row. */
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    /** List of. */
                    listOf(TaskFilter.OVERDUE, TaskFilter.TODAY, TaskFilter.FUTURE).forEachIndexed { index, filter ->
                        /** Segmented button. */
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            onClick = {
                                logger.i(
                                    "SettingsDefaultLanding",
                                    "User changed task state",
                                    /** Map of. */
                                    mapOf("filter" to filter.key),
                                )
                                prefsViewModel.setLaunchDestinationTasks(filter)
                            },
                            selected = effectiveTaskFilter == filter,
                        ) {
                            /** Text. */
                            Text(
                                text = when (filter) {
                                    TaskFilter.OVERDUE -> stringResource(id = R.string.loc_past)
                                    TaskFilter.TODAY -> stringResource(id = R.string.loc_today)
                                    TaskFilter.FUTURE -> stringResource(id = R.string.loc_future)
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
