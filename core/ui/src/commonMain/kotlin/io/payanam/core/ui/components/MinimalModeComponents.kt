//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

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
import androidx.compose.ui.unit.dp
import io.payanam.presentation.tasks.TaskFilterCounts
import io.payanam.shared.logging.AppLogger
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.presentation.tasks.TaskFilter

@Composable
fun MinimalModeTaskFilterRow(
    filterCounts: TaskFilterCounts,
    currentFilter: TaskFilter,
    overdueCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (TaskFilter) -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    val allCount = filterCounts.all
    val notActiveCount = filterCounts.notActive
    val activeCount = filterCounts.active
    val overdueSubCount = filterCounts.overdue
    val todaySubCount = filterCounts.today
    val futureSubCount = filterCounts.future
    val completedSubCount = filterCounts.completed
    val archivedSubCount = filterCounts.archived
    val isAllLayer = currentFilter == TaskFilter.ALL
    val isActiveLayer = currentFilter == TaskFilter.ACTIVE ||
        currentFilter == TaskFilter.TODAY ||
        currentFilter == TaskFilter.OVERDUE ||
        currentFilter == TaskFilter.FUTURE ||
        !isAllLayer && currentFilter != TaskFilter.NOT_ACTIVE && currentFilter != TaskFilter.COMPLETED && currentFilter != TaskFilter.ARCHIVED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = isAllLayer,
            onClick = {
                logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.ALL.name))
                onFilterChange(TaskFilter.ALL)
            },
            label = {
                Text(
                    strings.locTaskFilterWithCount.format(strings.locAll,
                        allCount,),
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        FilterChip(
            selected = isActiveLayer,
            onClick = {
                logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.TODAY.name))
                onFilterChange(TaskFilter.TODAY)
            },
            label = {
                Text(
                    strings.locTaskFilterWithCount.format(strings.widgetTrackingStatusActive,
                        activeCount,),
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
        FilterChip(
            selected = !isActiveLayer,
            onClick = {
                logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.NOT_ACTIVE.name))
                onFilterChange(TaskFilter.NOT_ACTIVE)
            },
            label = {
                Text(
                    strings.locTaskFilterWithCount.format(strings.locNotActive,
                        notActiveCount,),
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
    if (isAllLayer) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            label = { Text(strings.locSearchTasks) },
            placeholder = { Text(strings.locSearchTasksPlaceholder) },
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isActiveLayer) {
            FilterChip(
                selected = currentFilter == TaskFilter.OVERDUE,
                onClick = {
                    logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.OVERDUE.name))
                    onFilterChange(TaskFilter.OVERDUE)
                },
                label = {
                    Text(
                        strings.locTaskFilterWithCount.format(strings.locOverdue,
                            overdueSubCount,),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            )
            FilterChip(
                selected = currentFilter == TaskFilter.TODAY || currentFilter == TaskFilter.ACTIVE || currentFilter == TaskFilter.ALL,
                onClick = {
                    logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.TODAY.name))
                    onFilterChange(TaskFilter.TODAY)
                },
                label = {
                    Text(
                        strings.locTaskFilterWithCount.format(strings.locToday,
                            todaySubCount,),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            FilterChip(
                selected = currentFilter == TaskFilter.FUTURE,
                onClick = {
                    logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.FUTURE.name))
                    onFilterChange(TaskFilter.FUTURE)
                },
                label = {
                    Text(
                        strings.locTaskFilterWithCount.format(strings.locFuture,
                            futureSubCount,),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            } else {
                FilterChip(
                    selected = currentFilter == TaskFilter.COMPLETED || currentFilter == TaskFilter.NOT_ACTIVE,
                    onClick = {
                        logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.COMPLETED.name))
                        onFilterChange(TaskFilter.COMPLETED)
                    },
                    label = {
                        Text(
                            strings.locTaskFilterWithCount.format(strings.locCompleted,
                                completedSubCount,),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
                FilterChip(
                    selected = currentFilter == TaskFilter.ARCHIVED,
                    onClick = {
                        logger.d("TasksScreen.filterChipSelected", "Filter chip selected", mapOf("filter" to TaskFilter.ARCHIVED.name))
                        onFilterChange(TaskFilter.ARCHIVED)
                    },
                    label = {
                        Text(
                            strings.locTaskFilterWithCount.format(strings.locArchived,
                                archivedSubCount,),
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
