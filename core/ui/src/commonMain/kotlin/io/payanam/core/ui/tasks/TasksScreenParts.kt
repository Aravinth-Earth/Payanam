//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.tasks

import io.payanam.core.ui.text.formatArgs
import androidx.compose.runtime.remember
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.shared.logging.AppLogger
import io.payanam.core.ui.flags.LocalFeatureFlags
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.presentation.tasks.HabitSortOption
import io.payanam.presentation.tasks.TaskSortOption
import io.payanam.presentation.tasks.TasksChromeUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TasksTopBar(
    mode: TasksScreenMode,
    effectiveTabIndex: Int,
    chromeState: TasksChromeUiState,
    showHabitSortMenu: Boolean,
    onShowHabitSortMenuChange: (Boolean) -> Unit,
    showHabitFilterMenu: Boolean,
    onShowHabitFilterMenuChange: (Boolean) -> Unit,
    habitSearchActive: Boolean,
    onToggleHabitSearch: () -> Unit,
    habitSearchQuery: String,
    habitVisibleCount: Int,
    showTaskSortMenu: Boolean,
    onShowTaskSortMenuChange: (Boolean) -> Unit,
    onSetHabitSortOption: (HabitSortOption) -> Unit,
    onToggleShowCompletedHabits: () -> Unit,
    onToggleShowArchivedHabits: () -> Unit,
    onToggleHideAllMarkedToday: () -> Unit,
    onToggleDueTodayOnly: () -> Unit,
    onSetTaskSortOption: (TaskSortOption) -> Unit,
) {
    val strings = LocalUiStrings.current
    val logger = AppLogger.current
    TopAppBar(
        title = {
            Column {
                val title = when (mode) {
                    TasksScreenMode.HABITS_ONLY -> strings.locHabits
                    TasksScreenMode.TASKS_ONLY -> strings.settingsDatabaseTasks
                    TasksScreenMode.COMBINED -> strings.settingsDatabaseTasks
                }
                Text(text = title)
                val subtitle = if (effectiveTabIndex == 0) {
                    val filterActive = !chromeState.showCompletedHabits || chromeState.hideAllMarkedToday || chromeState.showArchivedHabits || chromeState.dueTodayOnly
                    // Show "x of y" only when search/filter actually narrows the list;
                    // blank search query or no filters -> plain count.
                    val searchNarrows = habitSearchActive && habitSearchQuery.isNotBlank()
                    if (searchNarrows || filterActive) {
                        strings.locHabitsCountFiltered.formatArgs(habitVisibleCount,
                            chromeState.recurringTaskCount,
                        )
                    } else {
                        strings.locHabitsCount.formatArgs(chromeState.recurringTaskCount)
                    }
                } else {
                    strings.locTasksCount.formatArgs(chromeState.oneTimeTaskCount)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            if (effectiveTabIndex == 0) {
                if (!habitSearchActive) {
                    Box {
                        androidx.compose.material3.IconButton(onClick = {
                            onToggleHabitSearch()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = strings.locSearchHabits,
                            )
                        }
                    }
                }

                Box {
                    androidx.compose.material3.IconButton(onClick = {
                        logger.d("TasksScreen.sortMenuOpened", "Sort menu opened", mapOf("tab" to "habits"))
                        onShowHabitSortMenuChange(true)
                    }) {
                        Icon(Icons.Default.Sort, contentDescription = strings.locSortHabits)
                    }
                    DropdownMenu(
                        expanded = showHabitSortMenu,
                        onDismissRequest = { onShowHabitSortMenuChange(false) },
                    ) {
                        HabitSortOption.entries
                            .filter { LocalFeatureFlags.current.scoringEnabled || it.legacyCategory() }
                            .forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (chromeState.habitSortOption == option) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            Text(habitSortLabel(option))
                                        }
                                    },
                                    onClick = {
                                        logger.d("TasksScreen.sortOptionSelected", "Sort option selected", mapOf("tab" to "habits", "option" to option.name))
                                        onSetHabitSortOption(option)
                                        onShowHabitSortMenuChange(false)
                                    },
                                )
                            }
                    }
                }

                Box {
                    androidx.compose.material3.IconButton(onClick = {
                        logger.d("TasksScreen.filterMenuOpened", "Filter menu opened", mapOf("tab" to "habits"))
                        onShowHabitFilterMenuChange(true)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = strings.locFilterHabits,
                        )
                    }
                    DropdownMenu(
                        expanded = showHabitFilterMenu,
                        onDismissRequest = { onShowHabitFilterMenuChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(
                                        checked = !chromeState.showCompletedHabits,
                                        onCheckedChange = null,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(strings.locHideCompletedToday)
                                }
                            },
                            onClick = {
                                logger.d("TasksScreen.hideCompletedToggled", "Hide completed toggled", mapOf("tab" to "habits", "value" to chromeState.showCompletedHabits))
                                onToggleShowCompletedHabits()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(
                                        checked = chromeState.hideAllMarkedToday,
                                        onCheckedChange = null,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(strings.locHideAllMarkedToday)
                                }
                            },
                            onClick = {
                                logger.d("TasksScreen.hideAllMarkedToggled", "Hide all marked toggled", mapOf("tab" to "habits", "value" to chromeState.hideAllMarkedToday))
                                onToggleHideAllMarkedToday()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(
                                        checked = chromeState.dueTodayOnly,
                                        onCheckedChange = null,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(strings.locDueTodayOnly)
                                }
                            },
                            onClick = {
                                logger.d("TasksScreen.dueTodayToggled", "Due today only toggled", mapOf("tab" to "habits", "value" to chromeState.dueTodayOnly))
                                onToggleDueTodayOnly()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(
                                        checked = chromeState.showArchivedHabits,
                                        onCheckedChange = null,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(strings.locShowArchived)
                                }
                            },
                            onClick = {
                                logger.d("TasksScreen.showArchivedToggled", "Show archived toggled", mapOf("tab" to "habits", "value" to !chromeState.showArchivedHabits))
                                onToggleShowArchivedHabits()
                            },
                        )
                    }
                }
            }
            if (effectiveTabIndex == 1) {
                Box {
                    androidx.compose.material3.IconButton(onClick = {
                        logger.d("TasksScreen.sortMenuOpened", "Sort menu opened", mapOf("tab" to "tasks"))
                        onShowTaskSortMenuChange(true)
                    }) {
                        Icon(Icons.Default.Sort, contentDescription = strings.locSortTasks)
                    }
                    DropdownMenu(
                        expanded = showTaskSortMenu,
                        onDismissRequest = { onShowTaskSortMenuChange(false) },
                    ) {
                        val scoringHiddenSorts = setOf(
                            TaskSortOption.SCORE_DESC,
                            TaskSortOption.SCORE_ASC,
                            TaskSortOption.IMPACT_DESC,
                            TaskSortOption.ENERGY_ASC,
                        )
                        TaskSortOption.entries
                            .filter { LocalFeatureFlags.current.scoringEnabled || it !in scoringHiddenSorts }
                            .forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (chromeState.currentSort == option) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            Text(taskSortLabel(option))
                                        }
                                    },
                                    onClick = {
                                        logger.d("TasksScreen.sortOptionSelected", "Sort option selected", mapOf("tab" to "tasks", "option" to option.name))
                                        onSetTaskSortOption(option)
                                        onShowTaskSortMenuChange(false)
                                    },
                                )
                            }
                    }
                }
            }
        },
    )
}


@Composable
internal fun taskSortLabel(option: TaskSortOption): String {
    val strings = LocalUiStrings.current
    return when (option) {
        TaskSortOption.SCORE_DESC -> strings.locScoreHighToLow
        TaskSortOption.SCORE_ASC -> strings.locScoreLowToHigh
        TaskSortOption.DUE_DATE_ASC -> strings.locDueDateEarliest
        TaskSortOption.DUE_DATE_DESC -> strings.locDueDateLatest
        TaskSortOption.TITLE_ASC -> strings.locTitleAToZ
        TaskSortOption.TITLE_DESC -> strings.locTitleZToA
        TaskSortOption.CREATED_DESC -> strings.locCreatedNewest
        TaskSortOption.CREATED_ASC -> strings.locCreatedOldest
        TaskSortOption.IMPACT_DESC -> strings.locImpactHighToLow
        TaskSortOption.ENERGY_ASC -> strings.locEnergyLowToHigh
        TaskSortOption.DIMENSION -> strings.locLifeDimension
    }
}


@Composable
internal fun habitSortLabel(option: HabitSortOption): String {
    val strings = LocalUiStrings.current
    return when (option) {
        HabitSortOption.BY_NAME -> strings.locSortNameAToZ
        HabitSortOption.BY_NAME_REVERSE -> strings.locSortNameZToA
        HabitSortOption.BY_DUE_TIME -> strings.locSortDueTimeEarly
        HabitSortOption.BY_DUE_TIME_REVERSE -> strings.locSortDueTimeLate
        HabitSortOption.SCORE_HIGH_LOW -> strings.locSortScoreHighLow
        HabitSortOption.SCORE_LOW_HIGH -> strings.locSortScoreLowHigh
    }
}

