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
    prefsState: AppPreferencesState,
    prefsViewModel: AppPreferencesViewModel,
) {
    if (prefsState.isLoading) {
        Text(
            text = stringResource(id = R.string.settings_loading_preferences),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val logger = UnifiedLogger.getInstance()
    val launchDestination = prefsState.launchDestination
    val effectiveTaskFilter = launchDestination.taskFilter ?: prefsState.currentTaskFilter
    val isTasksDestination = launchDestination.route == "tasks"
    val allRoutes = listOf(
        "tasks" to R.string.settings_database_tasks,
        "habits" to R.string.loc_habits,
        "time" to R.string.loc_time,
        "journal" to R.string.loc_journal,
        "notes" to R.string.settings_database_notes,
        "lenses" to R.string.loc_lenses,
    )
    val visibleRoutes = allRoutes.filter { (route, _) ->
        prefsState.tabVisibility[route] != false
    }
    LaunchedEffect(visibleRoutes) {
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(id = R.string.settings_default_landing_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(id = R.string.settings_default_landing_top_level_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            visibleRoutes.forEachIndexed { index, (route, labelRes) ->
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
                            "time" -> prefsViewModel.setLaunchDestinationTime()
                            "tasks" -> prefsViewModel.setLaunchDestinationTasks(
                                if (effectiveTaskFilter == TaskFilter.NOT_ACTIVE) null else effectiveTaskFilter,
                            )
                            else -> prefsViewModel.setLaunchDestination(route)
                        }
                    },
                    selected = launchDestination.route == route,
                ) {
                    Text(
                        text = stringResource(id = labelRes),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (isTasksDestination) {
            val selectedTaskState = if (effectiveTaskFilter == TaskFilter.NOT_ACTIVE) {
                TaskFilter.NOT_ACTIVE
            } else {
                TaskFilter.ACTIVE
            }
            Text(
                text = stringResource(id = R.string.settings_default_landing_task_layer_label),
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
                                mapOf("filter" to (when (filter) {
                                    TaskFilter.NOT_ACTIVE -> "not_active"
                                    else -> "active"
                                })),
                            )
                            when (filter) {
                                TaskFilter.NOT_ACTIVE -> prefsViewModel.setLaunchDestinationTasks(TaskFilter.NOT_ACTIVE)

                                TaskFilter.ACTIVE -> prefsViewModel.setLaunchDestinationTasks(
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
                        Text(
                            text = if (filter == TaskFilter.NOT_ACTIVE) {
                                stringResource(id = R.string.loc_not_active)
                            } else {
                                stringResource(id = R.string.widget_tracking_status_active)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            if (effectiveTaskFilter != TaskFilter.NOT_ACTIVE) {
                Text(
                    text = stringResource(id = R.string.settings_default_landing_task_state_label),
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
                                prefsViewModel.setLaunchDestinationTasks(filter)
                            },
                            selected = effectiveTaskFilter == filter,
                        ) {
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
