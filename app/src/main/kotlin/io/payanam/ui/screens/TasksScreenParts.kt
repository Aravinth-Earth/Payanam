//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.HabitSortOption
import io.payanam.ui.viewmodel.TaskSortOption
import io.payanam.ui.viewmodel.TasksChromeUiState

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
    val logger = UnifiedLogger.getInstance()
    TopAppBar(
        title = {
            Column {
                val titleRes = when (mode) {
                    TasksScreenMode.HABITS_ONLY -> io.payanam.R.string.loc_habits
                    TasksScreenMode.TASKS_ONLY -> io.payanam.R.string.settings_database_tasks
                    TasksScreenMode.COMBINED -> io.payanam.R.string.settings_database_tasks
                }
                Text(text = stringResource(id = titleRes))
                val subtitle = if (effectiveTabIndex == 0) {
                    val filterActive = !chromeState.showCompletedHabits || chromeState.hideAllMarkedToday || chromeState.showArchivedHabits || chromeState.dueTodayOnly
                    // Show "x of y" only when search/filter actually narrows the list;
                    // blank search query or no filters -> plain count.
                    val searchNarrows = habitSearchActive && habitSearchQuery.isNotBlank()
                    if (searchNarrows || filterActive) {
                        stringResource(
                            id = io.payanam.R.string.loc_habits_count_filtered,
                            habitVisibleCount,
                            chromeState.recurringTaskCount,
                        )
                    } else {
                        stringResource(id = io.payanam.R.string.loc_habits_count, chromeState.recurringTaskCount)
                    }
                } else {
                    stringResource(id = io.payanam.R.string.loc_tasks_count, chromeState.oneTimeTaskCount)
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
                                contentDescription = stringResource(id = io.payanam.R.string.loc_search_habits),
                            )
                        }
                    }
                }

                Box {
                    androidx.compose.material3.IconButton(onClick = {
                        logger.d("TasksScreen.sortMenuOpened", "Sort menu opened", mapOf("tab" to "habits"))
                        onShowHabitSortMenuChange(true)
                    }) {
                        Icon(Icons.Default.Sort, contentDescription = stringResource(id = io.payanam.R.string.loc_sort_habits))
                    }
                    DropdownMenu(
                        expanded = showHabitSortMenu,
                        onDismissRequest = { onShowHabitSortMenuChange(false) },
                    ) {
                        HabitSortOption.entries
                            .filter { FeatureFlags.scoringEnabled || it.legacyCategory() }
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
                            contentDescription = stringResource(id = io.payanam.R.string.loc_filter_habits),
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
                                    Text(stringResource(id = io.payanam.R.string.loc_hide_completed_today))
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
                                    Text(stringResource(id = io.payanam.R.string.loc_hide_all_marked_today))
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
                                    Text(stringResource(id = io.payanam.R.string.loc_due_today_only))
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
                                    Text(stringResource(id = io.payanam.R.string.loc_show_archived))
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
                        Icon(Icons.Default.Sort, contentDescription = stringResource(id = io.payanam.R.string.loc_sort_tasks))
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
                            .filter { FeatureFlags.scoringEnabled || it !in scoringHiddenSorts }
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
internal fun taskSortLabel(option: TaskSortOption): String = when (option) {
    TaskSortOption.SCORE_DESC -> stringResource(id = io.payanam.R.string.loc_score_high_to_low)
    TaskSortOption.SCORE_ASC -> stringResource(id = io.payanam.R.string.loc_score_low_to_high)
    TaskSortOption.DUE_DATE_ASC -> stringResource(id = io.payanam.R.string.loc_due_date_earliest)
    TaskSortOption.DUE_DATE_DESC -> stringResource(id = io.payanam.R.string.loc_due_date_latest)
    TaskSortOption.TITLE_ASC -> stringResource(id = io.payanam.R.string.loc_title_a_to_z)
    TaskSortOption.TITLE_DESC -> stringResource(id = io.payanam.R.string.loc_title_z_to_a)
    TaskSortOption.CREATED_DESC -> stringResource(id = io.payanam.R.string.loc_created_newest)
    TaskSortOption.CREATED_ASC -> stringResource(id = io.payanam.R.string.loc_created_oldest)
    TaskSortOption.IMPACT_DESC -> stringResource(id = io.payanam.R.string.loc_impact_high_to_low)
    TaskSortOption.ENERGY_ASC -> stringResource(id = io.payanam.R.string.loc_energy_low_to_high)
    TaskSortOption.DIMENSION -> stringResource(id = io.payanam.R.string.loc_life_dimension)
}


@Composable
internal fun habitSortLabel(option: HabitSortOption): String = when (option) {
    HabitSortOption.BY_SCORE, HabitSortOption.RUNNING_AVG_DESC -> stringResource(id = io.payanam.R.string.loc_habit_sort_running_avg_desc)
    HabitSortOption.RUNNING_AVG_ASC -> stringResource(id = io.payanam.R.string.loc_habit_sort_running_avg_asc)
    HabitSortOption.SCORE_DESC -> stringResource(id = io.payanam.R.string.loc_habit_sort_score_desc)
    HabitSortOption.SCORE_ASC -> stringResource(id = io.payanam.R.string.loc_habit_sort_score_asc)
    HabitSortOption.PROGRESS_DESC -> stringResource(id = io.payanam.R.string.loc_habit_sort_progress_desc)
    HabitSortOption.PROGRESS_ASC -> stringResource(id = io.payanam.R.string.loc_habit_sort_progress_asc)
    HabitSortOption.STREAK_POS_DESC -> stringResource(id = io.payanam.R.string.loc_habit_sort_streak_pos_desc)
    HabitSortOption.STREAK_POS_ASC -> stringResource(id = io.payanam.R.string.loc_habit_sort_streak_pos_asc)
    HabitSortOption.STREAK_NET_DESC -> stringResource(id = io.payanam.R.string.loc_habit_sort_streak_net_desc)
    HabitSortOption.STREAK_NET_ASC -> stringResource(id = io.payanam.R.string.loc_habit_sort_streak_net_asc)
    HabitSortOption.POS_CONTINUE_DESC -> stringResource(id = io.payanam.R.string.loc_habit_sort_pos_continue_desc)
    HabitSortOption.POS_CONTINUE_ASC -> stringResource(id = io.payanam.R.string.loc_habit_sort_pos_continue_asc)
    HabitSortOption.BY_NAME -> stringResource(id = io.payanam.R.string.loc_name)
    HabitSortOption.BY_STATUS -> stringResource(id = io.payanam.R.string.loc_status)
    HabitSortOption.BY_DUE_TIME -> stringResource(id = io.payanam.R.string.loc_due_time)
    HabitSortOption.BY_LIFE_DIMENSION -> stringResource(id = io.payanam.R.string.loc_life_dimension)
    HabitSortOption.BY_POSITION -> stringResource(id = io.payanam.R.string.loc_manual)
}

