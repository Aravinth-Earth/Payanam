//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.TaskFilter
import io.payanam.ui.viewmodel.TaskFilterCounts

@Composable
internal fun MinimalModeTaskFilterRow(
    /** Filter counts. */
    filterCounts: TaskFilterCounts,
    /** Current filter. */
    currentFilter: TaskFilter,
    /** Overdue count. */
    overdueCount: Int,
    /** Search query. */
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (TaskFilter) -> Unit,
) {
    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }
    /** All count. */
    val allCount = filterCounts.all
    /** Not active count. */
    val notActiveCount = filterCounts.notActive
    /** Active count. */
    val activeCount = filterCounts.active
    /** Overdue sub count. */
    val overdueSubCount = filterCounts.overdue
    /** Today sub count. */
    val todaySubCount = filterCounts.today
    /** Future sub count. */
    val futureSubCount = filterCounts.future
    /** Completed sub count. */
    val completedSubCount = filterCounts.completed
    /** Archived sub count. */
    val archivedSubCount = filterCounts.archived
    /** Is all layer. */
    val isAllLayer = currentFilter == TaskFilter.ALL
    /** Is active layer. */
    val isActiveLayer = currentFilter == TaskFilter.ACTIVE ||
        currentFilter == TaskFilter.TODAY ||
        currentFilter == TaskFilter.OVERDUE ||
        currentFilter == TaskFilter.FUTURE ||
        !isAllLayer && currentFilter != TaskFilter.NOT_ACTIVE && currentFilter != TaskFilter.COMPLETED && currentFilter != TaskFilter.ARCHIVED
    /** Row. */
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /** Filter chip. */
        FilterChip(
            selected = isAllLayer,
            onClick = {
                logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.ALL.name))
                /** On filter change. */
                onFilterChange(TaskFilter.ALL)
            },
            label = {
                /** Text. */
                Text(
                    /** String resource. */
                    stringResource(
                        id = R.string.loc_task_filter_with_count,
                        /** String resource. */
                        stringResource(id = R.string.loc_all),
                        /** All count. */
                        allCount,
                    ),
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        /** Filter chip. */
        FilterChip(
            selected = isActiveLayer,
            onClick = {
                logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.TODAY.name))
                /** On filter change. */
                onFilterChange(TaskFilter.TODAY)
            },
            label = {
                /** Text. */
                Text(
                    /** String resource. */
                    stringResource(
                        id = R.string.loc_task_filter_with_count,
                        /** String resource. */
                        stringResource(id = R.string.widget_tracking_status_active),
                        /** Active count. */
                        activeCount,
                    ),
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        /** Filter chip. */
        FilterChip(
            selected = !isActiveLayer,
            onClick = {
                logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.NOT_ACTIVE.name))
                /** On filter change. */
                onFilterChange(TaskFilter.NOT_ACTIVE)
            },
            label = {
                /** Text. */
                Text(
                    /** String resource. */
                    stringResource(
                        id = R.string.loc_task_filter_with_count,
                        /** String resource. */
                        stringResource(id = R.string.loc_not_active),
                        /** Not active count. */
                        notActiveCount,
                    ),
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
    /** If. */
    if (isAllLayer) {
        /** Outlined text field. */
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            label = { Text(stringResource(id = R.string.loc_search_tasks)) },
            placeholder = { Text(stringResource(id = R.string.loc_search_tasks_placeholder)) },
        )
    } else {
        /** Row. */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /** If. */
            if (isActiveLayer) {
            /** Filter chip. */
            FilterChip(
                selected = currentFilter == TaskFilter.OVERDUE,
                onClick = {
                    logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.OVERDUE.name))
                    /** On filter change. */
                    onFilterChange(TaskFilter.OVERDUE)
                },
                label = {
                    /** Text. */
                    Text(
                        /** String resource. */
                        stringResource(
                            id = R.string.loc_task_filter_with_count,
                            /** String resource. */
                            stringResource(id = R.string.loc_overdue),
                            /** Overdue sub count. */
                            overdueSubCount,
                        ),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            )
            /** Filter chip. */
            FilterChip(
                selected = currentFilter == TaskFilter.TODAY || currentFilter == TaskFilter.ACTIVE || currentFilter == TaskFilter.ALL,
                onClick = {
                    logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.TODAY.name))
                    /** On filter change. */
                    onFilterChange(TaskFilter.TODAY)
                },
                label = {
                    /** Text. */
                    Text(
                        /** String resource. */
                        stringResource(
                            id = R.string.loc_task_filter_with_count,
                            /** String resource. */
                            stringResource(id = R.string.loc_today),
                            /** Today sub count. */
                            todaySubCount,
                        ),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            /** Filter chip. */
            FilterChip(
                selected = currentFilter == TaskFilter.FUTURE,
                onClick = {
                    logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.FUTURE.name))
                    /** On filter change. */
                    onFilterChange(TaskFilter.FUTURE)
                },
                label = {
                    /** Text. */
                    Text(
                        /** String resource. */
                        stringResource(
                            id = R.string.loc_task_filter_with_count,
                            /** String resource. */
                            stringResource(id = R.string.loc_future),
                            /** Future sub count. */
                            futureSubCount,
                        ),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            } else {
                /** Filter chip. */
                FilterChip(
                    selected = currentFilter == TaskFilter.COMPLETED || currentFilter == TaskFilter.NOT_ACTIVE,
                    onClick = {
                        logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.COMPLETED.name))
                        /** On filter change. */
                        onFilterChange(TaskFilter.COMPLETED)
                    },
                    label = {
                        /** Text. */
                        Text(
                            /** String resource. */
                            stringResource(
                                id = R.string.loc_task_filter_with_count,
                                /** String resource. */
                                stringResource(id = R.string.loc_completed),
                                /** Completed sub count. */
                                completedSubCount,
                            ),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
                /** Filter chip. */
                FilterChip(
                    selected = currentFilter == TaskFilter.ARCHIVED,
                    onClick = {
                        logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.ARCHIVED.name))
                        /** On filter change. */
                        onFilterChange(TaskFilter.ARCHIVED)
                    },
                    label = {
                        /** Text. */
                        Text(
                            /** String resource. */
                            stringResource(
                                id = R.string.loc_task_filter_with_count,
                                /** String resource. */
                                stringResource(id = R.string.loc_archived),
                                /** Archived sub count. */
                                archivedSubCount,
                            ),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
    }
}
