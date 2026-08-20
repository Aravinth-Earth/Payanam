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
    /** Mode. */
    mode: TasksScreenMode,
    /** Effective tab index. */
    effectiveTabIndex: Int,
    /** Chrome state. */
    chromeState: TasksChromeUiState,
    /** Show habit sort menu. */
    showHabitSortMenu: Boolean,
    onShowHabitSortMenuChange: (Boolean) -> Unit,
    /** Show habit filter menu. */
    showHabitFilterMenu: Boolean,
    onShowHabitFilterMenuChange: (Boolean) -> Unit,
    /** Habit search active. */
    habitSearchActive: Boolean,
    onToggleHabitSearch: () -> Unit,
    /** Habit search query. */
    habitSearchQuery: String,
    /** Habit visible count. */
    habitVisibleCount: Int,
    /** Show task sort menu. */
    showTaskSortMenu: Boolean,
    onShowTaskSortMenuChange: (Boolean) -> Unit,
    onSetHabitSortOption: (HabitSortOption) -> Unit,
    onToggleShowCompletedHabits: () -> Unit,
    onToggleShowArchivedHabits: () -> Unit,
    onToggleHideAllMarkedToday: () -> Unit,
    onToggleDueTodayOnly: () -> Unit,
    onSetTaskSortOption: (TaskSortOption) -> Unit,
) {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Top app bar. */
    TopAppBar(
        title = {
            Column {
                /** Title res. */
                val titleRes = when (mode) {
                    TasksScreenMode.HABITS_ONLY -> io.payanam.R.string.loc_habits
                    TasksScreenMode.TASKS_ONLY -> io.payanam.R.string.settings_database_tasks
                    TasksScreenMode.COMBINED -> io.payanam.R.string.settings_database_tasks
                }
                /** Text. */
                Text(text = stringResource(id = titleRes))
                /** Subtitle. */
                val subtitle = if (effectiveTabIndex == 0) {
                    /** Filter active. */
                    val filterActive = !chromeState.showCompletedHabits || chromeState.hideAllMarkedToday || chromeState.showArchivedHabits || chromeState.dueTodayOnly
                    // Show "x of y" only when search/filter actually narrows the list;
                    // blank search query or no filters -> plain count.
                    /** Search narrows. */
                    val searchNarrows = habitSearchActive && habitSearchQuery.isNotBlank()
                    /** If. */
                    if (searchNarrows || filterActive) {
                        /** String resource. */
                        stringResource(
                            id = io.payanam.R.string.loc_habits_count_filtered,
                            /** Habit visible count. */
                            habitVisibleCount,
                            chromeState.recurringTaskCount,
                        )
                    } else {
                        /** String resource. */
                        stringResource(id = io.payanam.R.string.loc_habits_count, chromeState.recurringTaskCount)
                    }
                } else {
                    /** String resource. */
                    stringResource(id = io.payanam.R.string.loc_tasks_count, chromeState.oneTimeTaskCount)
                }
                /** Text. */
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            /** If. */
            if (effectiveTabIndex == 0) {
                /** If. */
                if (!habitSearchActive) {
                    Box {
                        androidx.compose.material3.IconButton(onClick = {
                            /** On toggle habit search. */
                            onToggleHabitSearch()
                        }) {
                            /** Icon. */
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
                        /** On show habit sort menu change. */
                        onShowHabitSortMenuChange(true)
                    }) {
                        /** Icon. */
                        Icon(Icons.Default.Sort, contentDescription = stringResource(id = io.payanam.R.string.loc_sort_habits))
                    }
                    /** Dropdown menu. */
                    DropdownMenu(
                        expanded = showHabitSortMenu,
                        onDismissRequest = { onShowHabitSortMenuChange(false) },
                    ) {
                        HabitSortOption.entries
                            .filter { FeatureFlags.scoringEnabled || it.legacyCategory() }
                            .forEach { option ->
                                /** Dropdown menu item. */
                                DropdownMenuItem(
                                    text = {
                                        /** Row. */
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            /** If. */
                                            if (chromeState.habitSortOption == option) {
                                                /** Icon. */
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                /** Spacer. */
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            /** Text. */
                                            Text(habitSortLabel(option))
                                        }
                                    },
                                    onClick = {
                                        logger.d("TasksScreen.sortOptionSelected", "Sort option selected", mapOf("tab" to "habits", "option" to option.name))
                                        /** On set habit sort option. */
                                        onSetHabitSortOption(option)
                                        /** On show habit sort menu change. */
                                        onShowHabitSortMenuChange(false)
                                    },
                                )
                            }
                    }
                }

                Box {
                    androidx.compose.material3.IconButton(onClick = {
                        logger.d("TasksScreen.filterMenuOpened", "Filter menu opened", mapOf("tab" to "habits"))
                        /** On show habit filter menu change. */
                        onShowHabitFilterMenuChange(true)
                    }) {
                        /** Icon. */
                        Icon(
                            imageVector = Icons.Default.Archive,
                            contentDescription = stringResource(id = io.payanam.R.string.loc_filter_habits),
                        )
                    }
                    /** Dropdown menu. */
                    DropdownMenu(
                        expanded = showHabitFilterMenu,
                        onDismissRequest = { onShowHabitFilterMenuChange(false) },
                    ) {
                        /** Dropdown menu item. */
                        DropdownMenuItem(
                            text = {
                                /** Row. */
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(
                                        checked = !chromeState.showCompletedHabits,
                                        onCheckedChange = null,
                                    )
                                    /** Spacer. */
                                    Spacer(modifier = Modifier.width(8.dp))
                                    /** Text. */
                                    Text(stringResource(id = io.payanam.R.string.loc_hide_completed_today))
                                }
                            },
                            onClick = {
                                logger.d("TasksScreen.hideCompletedToggled", "Hide completed toggled", mapOf("tab" to "habits", "value" to chromeState.showCompletedHabits))
                                /** On toggle show completed habits. */
                                onToggleShowCompletedHabits()
                            },
                        )
                        /** Dropdown menu item. */
                        DropdownMenuItem(
                            text = {
                                /** Row. */
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(
                                        checked = chromeState.hideAllMarkedToday,
                                        onCheckedChange = null,
                                    )
                                    /** Spacer. */
                                    Spacer(modifier = Modifier.width(8.dp))
                                    /** Text. */
                                    Text(stringResource(id = io.payanam.R.string.loc_hide_all_marked_today))
                                }
                            },
                            onClick = {
                                logger.d("TasksScreen.hideAllMarkedToggled", "Hide all marked toggled", mapOf("tab" to "habits", "value" to chromeState.hideAllMarkedToday))
                                /** On toggle hide all marked today. */
                                onToggleHideAllMarkedToday()
                            },
                        )
                        /** Dropdown menu item. */
                        DropdownMenuItem(
                            text = {
                                /** Row. */
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(
                                        checked = chromeState.dueTodayOnly,
                                        onCheckedChange = null,
                                    )
                                    /** Spacer. */
                                    Spacer(modifier = Modifier.width(8.dp))
                                    /** Text. */
                                    Text(stringResource(id = io.payanam.R.string.loc_due_today_only))
                                }
                            },
                            onClick = {
                                logger.d("TasksScreen.dueTodayToggled", "Due today only toggled", mapOf("tab" to "habits", "value" to chromeState.dueTodayOnly))
                                /** On toggle due today only. */
                                onToggleDueTodayOnly()
                            },
                        )
                        /** Dropdown menu item. */
                        DropdownMenuItem(
                            text = {
                                /** Row. */
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(
                                        checked = chromeState.showArchivedHabits,
                                        onCheckedChange = null,
                                    )
                                    /** Spacer. */
                                    Spacer(modifier = Modifier.width(8.dp))
                                    /** Text. */
                                    Text(stringResource(id = io.payanam.R.string.loc_show_archived))
                                }
                            },
                            onClick = {
                                logger.d("TasksScreen.showArchivedToggled", "Show archived toggled", mapOf("tab" to "habits", "value" to !chromeState.showArchivedHabits))
                                /** On toggle show archived habits. */
                                onToggleShowArchivedHabits()
                            },
                        )
                    }
                }
            }

            /** If. */
            if (effectiveTabIndex == 1) {
                Box {
                    androidx.compose.material3.IconButton(onClick = {
                        logger.d("TasksScreen.sortMenuOpened", "Sort menu opened", mapOf("tab" to "tasks"))
                        /** On show task sort menu change. */
                        onShowTaskSortMenuChange(true)
                    }) {
                        /** Icon. */
                        Icon(Icons.Default.Sort, contentDescription = stringResource(id = io.payanam.R.string.loc_sort_tasks))
                    }
                    /** Dropdown menu. */
                    DropdownMenu(
                        expanded = showTaskSortMenu,
                        onDismissRequest = { onShowTaskSortMenuChange(false) },
                    ) {
                        /** Scoring hidden sorts. */
                        val scoringHiddenSorts = setOf(
                            TaskSortOption.SCORE_DESC,
                            TaskSortOption.SCORE_ASC,
                            TaskSortOption.IMPACT_DESC,
                            TaskSortOption.ENERGY_ASC,
                        )
                        TaskSortOption.entries
                            .filter { FeatureFlags.scoringEnabled || it !in scoringHiddenSorts }
                            .forEach { option ->
                                /** Dropdown menu item. */
                                DropdownMenuItem(
                                    text = {
                                        /** Row. */
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            /** If. */
                                            if (chromeState.currentSort == option) {
                                                /** Icon. */
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                /** Spacer. */
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            /** Text. */
                                            Text(taskSortLabel(option))
                                        }
                                    },
                                    onClick = {
                                        logger.d("TasksScreen.sortOptionSelected", "Sort option selected", mapOf("tab" to "tasks", "option" to option.name))
                                        /** On set task sort option. */
                                        onSetTaskSortOption(option)
                                        /** On show task sort menu change. */
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
    HabitSortOption.BY_NAME -> stringResource(id = io.payanam.R.string.loc_sort_name_a_to_z)
    HabitSortOption.BY_NAME_REVERSE -> stringResource(id = io.payanam.R.string.loc_sort_name_z_to_a)
    HabitSortOption.BY_DUE_TIME -> stringResource(id = io.payanam.R.string.loc_sort_due_time_early)
    HabitSortOption.BY_DUE_TIME_REVERSE -> stringResource(id = io.payanam.R.string.loc_sort_due_time_late)
    HabitSortOption.SCORE_HIGH_LOW -> stringResource(id = io.payanam.R.string.loc_sort_score_high_low)
    HabitSortOption.SCORE_LOW_HIGH -> stringResource(id = io.payanam.R.string.loc_sort_score_low_high)
}

