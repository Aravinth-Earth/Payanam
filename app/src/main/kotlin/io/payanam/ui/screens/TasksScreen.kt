//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Task
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.ui.components.CheckmarkDialog
import io.payanam.ui.components.CompletionDialog
import io.payanam.ui.components.DayCheckmark
import io.payanam.ui.components.DayHeaderRow
import io.payanam.ui.components.HabitCard
import io.payanam.ui.components.calculateButtonCount
import io.payanam.ui.perf.PerfBaselineTelemetry
import io.payanam.ui.viewmodel.HabitRowUiModel
import io.payanam.ui.viewmodel.HabitSortOption
import io.payanam.ui.viewmodel.TaskFilter
import io.payanam.ui.viewmodel.TaskFilterCounts
import io.payanam.ui.viewmodel.TaskRowUiModel
import io.payanam.ui.viewmodel.TaskSortOption
import io.payanam.ui.viewmodel.TasksChromeUiState
import io.payanam.ui.viewmodel.TasksTabUiState
import io.payanam.ui.viewmodel.matchesTaskSearch
import io.payanam.ui.viewmodel.TasksViewModel
import java.time.LocalDate

enum class TasksScreenMode {
    COMBINED,
    TASKS_ONLY,
    HABITS_ONLY,
}

private data class TaskFilterInteractionTrace(
    val interactionId: String,
    val filter: TaskFilter,
    val tapMs: Long,
)

private data class TaskTabInteractionTrace(
    val interactionId: String,
    val tab: String,
    val tapMs: Long,
)

@Composable
private fun TraceTaskInteractionPhase(
    trace: TaskFilterInteractionTrace?,
    event: String,
    data: Map<String, Any?> = emptyMap(),
) {
    var sent by remember(trace?.interactionId, event) { mutableStateOf(false) }
    SideEffect {
        if (trace != null && !sent) {
            PerfBaselineTelemetry.markEvent(
                screen = "tasks",
                event = event,
                data = buildMap {
                    put("interactionId", trace.interactionId)
                    put("filter", trace.filter.key)
                    put("elapsedSinceTapMs", SystemClock.elapsedRealtime() - trace.tapMs)
                    putAll(data)
                },
            )
            sent = true
        }
    }
}

@Composable
private fun TraceTaskTabPhase(
    trace: TaskTabInteractionTrace?,
    event: String,
    data: Map<String, Any?> = emptyMap(),
) {
    var sent by remember(trace?.interactionId, event) { mutableStateOf(false) }
    SideEffect {
        if (trace != null && !sent) {
            PerfBaselineTelemetry.markEvent(
                screen = "tasks",
                event = event,
                data = buildMap {
                    put("interactionId", trace.interactionId)
                    put("tab", trace.tab)
                    put("elapsedSinceTapMs", SystemClock.elapsedRealtime() - trace.tapMs)
                    putAll(data)
                },
            )
            sent = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    mode: TasksScreenMode = TasksScreenMode.COMBINED,
    viewModel: TasksViewModel = hiltViewModel(),
    onNavigateToAddTask: () -> Unit = {},
    onNavigateToTaskDetail: (String) -> Unit = {},
) {
    val logger = UnifiedLogger.getInstance()
    val chromeState by viewModel.chromeUiState.collectAsState()
    var firstContentLogged by remember { mutableStateOf(false) }
    var selectedTabIndex by remember {
        mutableIntStateOf(
            when (mode) {
                TasksScreenMode.HABITS_ONLY -> 0
                TasksScreenMode.TASKS_ONLY -> 1
                TasksScreenMode.COMBINED -> 0
            },
        )
    }

    // Dialog state for checkmark editing
    var showCheckmarkDialog by remember { mutableStateOf(false) }
    var dialogTaskId by remember { mutableStateOf("") }
    var dialogCheckmark by remember { mutableStateOf<DayCheckmark?>(null) }

    // Menu state for Habits tab
    var showHabitSortMenu by remember { mutableStateOf(false) }
    var showHabitFilterMenu by remember { mutableStateOf(false) }

    // Search state for Habits tab (hidden by default; toggled from top bar icon)
    var habitSearchActive by remember { mutableStateOf(false) }
    var habitSearchQuery by remember { mutableStateOf("") }
    var habitSearchOpenElapsed by remember { mutableStateOf(0L) }
    var habitVisibleCount by remember { mutableStateOf(0) }

    // Menu state for Tasks tab
    var showTaskSortMenu by remember { mutableStateOf(false) }
    var pendingTabTrace by remember { mutableStateOf<TaskTabInteractionTrace?>(null) }

    val effectiveTabIndex = when (mode) {
        TasksScreenMode.HABITS_ONLY -> 0
        TasksScreenMode.TASKS_ONLY -> 1
        TasksScreenMode.COMBINED -> selectedTabIndex
    }
    val showTabRow = mode == TasksScreenMode.COMBINED

    // Hoisted here so tab switches can dismiss the keyboard even after the
    // Habits tab content (which owns the search field) leaves composition.
    val screenKeyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        PerfBaselineTelemetry.markEvent(screen = "tasks", event = "screen_enter")
    }

    LaunchedEffect(chromeState.isLoading) {
        if (!chromeState.isLoading && !firstContentLogged) {
            firstContentLogged = true
            PerfBaselineTelemetry.markEvent(screen = "tasks", event = "first_content")
            PerfBaselineTelemetry.markEvent(screen = "habits", event = "first_content")
        }
    }

    LaunchedEffect(effectiveTabIndex) {
        PerfBaselineTelemetry.markEvent(
            screen = "tasks",
            event = "tab_selected",
            data = mapOf("tab" to if (effectiveTabIndex == 0) "habits" else "tasks"),
        )
        // Reset habit search when leaving the Habits tab so returning users
        // start from a clean list instead of a stale search mode. Keyboard is
        // dismissed here because the search field's content is leaving composition.
        if (effectiveTabIndex != 0 && habitSearchActive) {
            habitSearchActive = false
            habitSearchQuery = ""
            screenKeyboard?.hide()
        }
    }

    LaunchedEffect(effectiveTabIndex, pendingTabTrace?.interactionId) {
        val trace = pendingTabTrace ?: return@LaunchedEffect
        val expectedTab = if (effectiveTabIndex == 0) "habits" else "tasks"
        if (expectedTab != trace.tab) return@LaunchedEffect
        withFrameNanos {
            PerfBaselineTelemetry.markEvent(
                screen = "tasks",
                event = "tab_interaction_first_frame",
                data = mapOf(
                    "interactionId" to trace.interactionId,
                    "tab" to trace.tab,
                    "elapsedSinceTapMs" to (SystemClock.elapsedRealtime() - trace.tapMs),
                ),
            )
        }
        pendingTabTrace = null
    }

    Scaffold(
        topBar = {
            TasksTopBar(
                mode = mode,
                effectiveTabIndex = effectiveTabIndex,
                chromeState = chromeState,
                showHabitSortMenu = showHabitSortMenu,
                onShowHabitSortMenuChange = { showHabitSortMenu = it },
                showHabitFilterMenu = showHabitFilterMenu,
                onShowHabitFilterMenuChange = { showHabitFilterMenu = it },
                habitSearchActive = habitSearchActive,
                onToggleHabitSearch = {
                    val nowMs = SystemClock.elapsedRealtime()
                    logger.d("TasksScreen.searchToggled", "Habit search toggled", mapOf("tab" to "habits", "active" to !habitSearchActive, "elapsedMs" to nowMs))
                    if (habitSearchActive) {
                        habitSearchQuery = ""
                    } else {
                        habitSearchOpenElapsed = nowMs
                    }
                    habitSearchActive = !habitSearchActive
                    PerfBaselineTelemetry.markEvent(
                        screen = "habits",
                        event = if (habitSearchActive) "search_opened" else "search_closed",
                        data = mapOf("elapsedMs" to nowMs),
                    )
                },
                showTaskSortMenu = showTaskSortMenu,
                onShowTaskSortMenuChange = { showTaskSortMenu = it },
                onSetHabitSortOption = viewModel::setHabitSortOption,
                onToggleShowCompletedHabits = viewModel::toggleShowCompletedHabits,
                onToggleShowArchivedHabits = viewModel::toggleShowArchivedHabits,
                onToggleHideAllMarkedToday = viewModel::toggleHideAllMarkedToday,
                onSetTaskSortOption = viewModel::setSortOption,
                habitVisibleCount = habitVisibleCount,
                habitSearchQuery = habitSearchQuery,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    logger.i("TasksScreen", "Add task FAB clicked", mapOf())
                    onNavigateToAddTask()
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Add, contentDescription = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_add_task))
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (showTabRow) {
                // Tab row: Habits vs Tasks
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = {
                            logger.d("TasksScreen.tabSelected", "Tab selected", mapOf("tab" to "habits"))
                            val tapMs = SystemClock.elapsedRealtime()
                            pendingTabTrace = TaskTabInteractionTrace(
                                interactionId = "tasks_tab_habits_$tapMs",
                                tab = "habits",
                                tapMs = tapMs,
                            )
                            PerfBaselineTelemetry.markEvent(
                                screen = "tasks",
                                event = "tab_interaction_start",
                                data = mapOf(
                                    "interactionId" to pendingTabTrace?.interactionId,
                                    "tab" to "habits",
                                    "tapMs" to tapMs,
                                ),
                            )
                            selectedTabIndex = 0
                        },
                        text = {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    id = io.payanam.R.string.loc_habits_count,
                                    chromeState.recurringTaskCount,
                                ),
                            )
                        },
                        icon = { Icon(Icons.Default.Repeat, contentDescription = null) },
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = {
                            logger.d("TasksScreen.tabSelected", "Tab selected", mapOf("tab" to "tasks"))
                            val tapMs = SystemClock.elapsedRealtime()
                            pendingTabTrace = TaskTabInteractionTrace(
                                interactionId = "tasks_tab_tasks_$tapMs",
                                tab = "tasks",
                                tapMs = tapMs,
                            )
                            PerfBaselineTelemetry.markEvent(
                                screen = "tasks",
                                event = "tab_interaction_start",
                                data = mapOf(
                                    "interactionId" to pendingTabTrace?.interactionId,
                                    "tab" to "tasks",
                                    "tapMs" to tapMs,
                                ),
                            )
                            selectedTabIndex = 1
                        },
                        text = {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    id = io.payanam.R.string.loc_tasks_count,
                                    chromeState.oneTimeTaskCount,
                                ),
                            )
                        },
                        icon = { Icon(Icons.Default.Task, contentDescription = null) },
                    )
                }
            }

            when {
                chromeState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                effectiveTabIndex == 0 -> {
                    HabitsTabRoute(
                        viewModel = viewModel,
                        searchActive = habitSearchActive,
                        searchQuery = habitSearchQuery,
                        onSearchQueryChange = { newQuery ->
                            habitSearchQuery = newQuery
                            logger.d(
                                "TasksScreen.searchQueryChanged",
                                "Habit search query changed",
                                mapOf(
                                    "query" to newQuery,
                                    "queryLength" to newQuery.length,
                                    "elapsedSinceOpenMs" to (SystemClock.elapsedRealtime() - habitSearchOpenElapsed),
                                ),
                            )
                        },
                        onCloseSearch = {
                            val closeMs = SystemClock.elapsedRealtime()
                            logger.d(
                                "TasksScreen.searchClosed",
                                "Habit search closed",
                                mapOf(
                                    "query" to habitSearchQuery,
                                    "queryLength" to habitSearchQuery.length,
                                    "elapsedSinceOpenMs" to (closeMs - habitSearchOpenElapsed),
                                ),
                            )
                            PerfBaselineTelemetry.markEvent(
                                screen = "habits",
                                event = "search_closed",
                                data = mapOf("elapsedSinceOpenMs" to (closeMs - habitSearchOpenElapsed)),
                            )
                            habitSearchQuery = ""
                            habitSearchActive = false
                        },
                        onVisibleCountChange = { habitVisibleCount = it },
                        onCardClick = { task ->
                            logger.i("TasksScreen", "Habit card clicked", mapOf("taskId" to task.id, "taskTitle" to task.title))
                            onNavigateToTaskDetail(task.id)
                        },
                        onCheckmarkClick = { taskId, checkmark ->
                            viewModel.toggleCheckmark(taskId, checkmark.date)
                        },
                        onCheckmarkLongClick = { taskId, checkmark ->
                            dialogTaskId = taskId
                            dialogCheckmark = checkmark
                            showCheckmarkDialog = true
                        },
                    )
                }

                else -> {
                    TasksTabRoute(
                        viewModel = viewModel,
                        pendingTabTrace = pendingTabTrace,
                        onTaskClick = {
                            logger.i("TasksScreen", "Task card clicked", mapOf("taskId" to it.id, "taskTitle" to it.title))
                            onNavigateToTaskDetail(it.id)
                        },
                    )
                }
            }
        }
    }

    // Checkmark dialog
    if (showCheckmarkDialog && dialogCheckmark != null) {
        CheckmarkDialog(
            date = dialogCheckmark!!.date,
            currentStatus = dialogCheckmark!!.status,
            currentNote = dialogCheckmark!!.note.orEmpty(),
            onDismiss = {
                logger.d("TasksScreen.dialogDismissed", "Dialog dismissed", mapOf("dialog" to "checkmark"))
                showCheckmarkDialog = false
                dialogCheckmark = null
            },
            onSave = { status, note ->
                logger.d("TasksScreen.dialogConfirmed", "Dialog confirmed", mapOf("dialog" to "checkmark"))
                viewModel.updateCheckmark(
                    taskId = dialogTaskId,
                    date = dialogCheckmark!!.date,
                    status = status,
                    note = note,
                )
                showCheckmarkDialog = false
                dialogCheckmark = null
            },
        )
    }

    // Completion dialog for recurring tasks
    if (chromeState.showCompletionDialog && chromeState.completionDialogTask != null) {
        CompletionDialog(
            taskTitle = chromeState.completionDialogTask!!.title,
            plannedDurationMinutes = chromeState.completionDialogTask!!.durationMinutes,
            plannedCompletedAt = chromeState.completionDialogTask!!.dueDate,
            onDismiss = {
                logger.d("TasksScreen.dialogDismissed", "Dialog dismissed", mapOf("dialog" to "completion"))
                viewModel.dismissCompletionDialog()
            },
            onSave = { actualCompletedAt, actualDurationMinutes ->
                logger.d("TasksScreen.dialogConfirmed", "Dialog confirmed", mapOf("dialog" to "completion"))
                viewModel.confirmCompletion(actualCompletedAt, actualDurationMinutes)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksTopBar(
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
                    val filterActive = !chromeState.showCompletedHabits || chromeState.hideAllMarkedToday || chromeState.showArchivedHabits
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
private fun HabitsTabRoute(
    viewModel: TasksViewModel,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onVisibleCountChange: (Int) -> Unit,
    onCardClick: (Task) -> Unit,
    onCheckmarkClick: (String, DayCheckmark) -> Unit,
    onCheckmarkLongClick: (String, DayCheckmark) -> Unit,
) {
    val habitsTabState by viewModel.habitsTabUiState.collectAsState()
    HabitsTabContent(
        rows = habitsTabState.rows,
        totalHabitCount = habitsTabState.totalHabitCount,
        searchActive = searchActive,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onCloseSearch = onCloseSearch,
        onVisibleCountChange = onVisibleCountChange,
        onCardClick = onCardClick,
        onCheckmarkClick = onCheckmarkClick,
        onCheckmarkLongClick = onCheckmarkLongClick,
    )
}

@Composable
private fun TasksTabRoute(
    viewModel: TasksViewModel,
    pendingTabTrace: TaskTabInteractionTrace?,
    onTaskClick: (Task) -> Unit,
) {
    val tasksTabState by viewModel.tasksTabUiState.collectAsState()
    TraceTaskTabPhase(
        trace = pendingTabTrace?.takeIf { it.tab == "tasks" },
        event = "tab_interaction_tasks_content_composed",
        data = mapOf(
            "rowCount" to tasksTabState.rows.size,
            "currentFilter" to tasksTabState.currentFilter.key,
        ),
    )
    TasksTabContent(
        rows = tasksTabState.rows,
        currentFilter = tasksTabState.currentFilter,
        filterCounts = tasksTabState.filterCounts,
        overdueCount = tasksTabState.overdueCount,
        onFilterChange = { filter, interactionId, interactionStartMs ->
            viewModel.setFilter(
                filter = filter,
                interactionId = interactionId,
                interactionStartMs = interactionStartMs,
            )
        },
        onTaskClick = onTaskClick,
    )
}

/**
 * Habits tab showing recurring tasks in HabitCard style.
 * Includes date header row like uHabits.
 */
@Composable
private fun HabitsTabContent(
    rows: List<HabitRowUiModel>,
    totalHabitCount: Int,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onVisibleCountChange: (Int) -> Unit,
    onCardClick: (Task) -> Unit,
    onCheckmarkClick: (String, DayCheckmark) -> Unit,
    onCheckmarkLongClick: (String, DayCheckmark) -> Unit,
) {
    val buttonCount = calculateButtonCount()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    val logger = remember { UnifiedLogger.getInstance() }

    // Auto-focus + show keyboard when search opens, so the first keystroke is not
    // swallowed by the IME connection handshake (results appear from char 1).
    LaunchedEffect(searchActive) {
        if (searchActive) {
            searchFocusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    // Hide keyboard as soon as the user starts scrolling the results list.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) keyboard?.hide()
            }
    }

    val displayRows = remember(rows, searchQuery) {
        val normalizedQuery = searchQuery.trim().lowercase()
        if (normalizedQuery.isBlank()) {
            rows
        } else {
            rows.filter { row -> row.task.matchesTaskSearch(normalizedQuery) }
        }
    }

    LaunchedEffect(displayRows.size, searchQuery) {
        onVisibleCountChange(displayRows.size)
        if (searchActive) {
            logger.d(
                "TasksScreen.habitSearchFiltered",
                "Habit search filter applied",
                mapOf(
                    "query" to searchQuery,
                    "queryLength" to searchQuery.length,
                    "inputRows" to rows.size,
                    "matchedRows" to displayRows.size,
                ),
            )
            PerfBaselineTelemetry.markEvent(
                screen = "habits",
                event = "search_filtered",
                data = mapOf(
                    "queryLength" to searchQuery.length,
                    "inputRows" to rows.size,
                    "matchedRows" to displayRows.size,
                ),
            )
        }
    }

    if (rows.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (totalHabitCount == 0) {
                        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_habits_yet)
                    } else {
                        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_all_habits_completed_today)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (totalHabitCount == 0) {
                        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_create_recurring_task_for_habits)
                    } else {
                        androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_great_job_habits)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (searchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .focusRequester(searchFocusRequester),
                    singleLine = true,
                    label = { Text(stringResource(id = io.payanam.R.string.loc_search_habits)) },
                    placeholder = { Text(stringResource(id = io.payanam.R.string.loc_search_habits_placeholder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = onCloseSearch) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(id = io.payanam.R.string.loc_clear_search),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            // Live search on every keystroke; Enter just dismisses the keyboard.
                            keyboard?.hide()
                        },
                    ),
                )
            }
            if (displayRows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_habits_match_search),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    overscrollEffect = null,
                ) {
                    // Date header as sticky header (stays pinned on scroll)
                    stickyHeader(key = "day_header") {
                        DayHeaderRow(buttonCount = buttonCount)
                    }
                    items(
                        items = displayRows,
                        key = { it.id },
                        contentType = { "habit_row" },
                    ) { row ->
                        HabitCard(
                            task = row.task,
                            checkmarks = row.checkmarks,
                            onCardClick = { onCardClick(row.task) },
                            onCheckmarkClick = { checkmark ->
                                onCheckmarkClick(row.id, checkmark)
                            },
                            onCheckmarkLongClick = { checkmark ->
                                onCheckmarkLongClick(row.id, checkmark)
                            },
                            buttonCount = buttonCount,
                            latestL1RunningAvg = row.latestL1?.runningAvg,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tasks tab showing one-time tasks with filter chips.
 */
@Composable
private fun TasksTabContent(
    rows: List<TaskRowUiModel>,
    currentFilter: TaskFilter,
    filterCounts: TaskFilterCounts,
    overdueCount: Int = 0,
    onFilterChange: (TaskFilter, String, Long) -> Unit,
    onTaskClick: (Task) -> Unit,
) {
    val logger = UnifiedLogger.getInstance()
    val listState = rememberLazyListState()
    var pendingFilterTrace by remember { mutableStateOf<TaskFilterInteractionTrace?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val displayTasks = remember(rows, currentFilter, searchQuery) {
        if (currentFilter != TaskFilter.ALL) {
            rows
        } else {
            val normalizedQuery = searchQuery.trim().lowercase()
            if (normalizedQuery.isBlank()) {
                rows
            } else {
                rows.filter { row -> row.task.matchesTaskSearch(normalizedQuery) }
            }
        }
    }

    TraceTaskInteractionPhase(
        trace = pendingFilterTrace?.takeIf { currentFilter == it.filter },
        event = "filter_interaction_tasks_tab_content_composed",
        data = mapOf("rowCount" to displayTasks.size),
    )

    fun traceFilterSelection(filter: TaskFilter) {
        val tapMs = SystemClock.elapsedRealtime()
        val interactionId = "tasks_filter_${filter.key}_$tapMs"
        pendingFilterTrace = TaskFilterInteractionTrace(
            interactionId = interactionId,
            filter = filter,
            tapMs = tapMs,
        )
        PerfBaselineTelemetry.markEvent(
            screen = "tasks",
            event = "filter_interaction_start",
            data = mapOf(
                "interactionId" to interactionId,
                "filter" to filter.key,
                "tapMs" to tapMs,
                "visibleRowCount" to displayTasks.size,
            ),
        )
        onFilterChange(filter, interactionId, tapMs)
    }

    LaunchedEffect(currentFilter, displayTasks.size, pendingFilterTrace?.interactionId) {
        val trace = pendingFilterTrace ?: return@LaunchedEffect
        if (currentFilter != trace.filter) return@LaunchedEffect
        val listReadyMs = SystemClock.elapsedRealtime()
        PerfBaselineTelemetry.markEvent(
            screen = "tasks",
            event = "filter_interaction_list_bound",
            data = mapOf(
                "interactionId" to trace.interactionId,
                "filter" to trace.filter.key,
                "rowCount" to displayTasks.size,
                "elapsedSinceTapMs" to (listReadyMs - trace.tapMs),
            ),
        )
        withFrameNanos {
            val firstFrameMs = SystemClock.elapsedRealtime()
            PerfBaselineTelemetry.markEvent(
                screen = "tasks",
                event = "filter_interaction_first_frame",
                data = mapOf(
                    "interactionId" to trace.interactionId,
                    "filter" to trace.filter.key,
                    "rowCount" to displayTasks.size,
                    "elapsedSinceTapMs" to (firstFrameMs - trace.tapMs),
                ),
            )
        }
        withFrameNanos {
            val stableFrameMs = SystemClock.elapsedRealtime()
            PerfBaselineTelemetry.markEvent(
                screen = "tasks",
                event = "filter_interaction_stable_frame",
                data = mapOf(
                    "interactionId" to trace.interactionId,
                    "filter" to trace.filter.key,
                    "rowCount" to displayTasks.size,
                    "elapsedSinceTapMs" to (stableFrameMs - trace.tapMs),
                ),
            )
        }
        logger.d(
            "TasksScreen.filterInteraction",
            "Filter interaction rendered",
            mapOf(
                "interactionId" to trace.interactionId,
                "filter" to trace.filter.key,
                "rowCount" to displayTasks.size,
                "elapsedSinceTapMs" to (SystemClock.elapsedRealtime() - trace.tapMs),
            ),
        )
        pendingFilterTrace = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (FeatureFlags.minimalModeEnabled) {
            TraceTaskInteractionPhase(
                trace = pendingFilterTrace?.takeIf { currentFilter == it.filter },
                event = "filter_interaction_filter_controls_composed",
                data = mapOf("mode" to "minimal", "rowCount" to displayTasks.size),
            )
            MinimalModeTaskFilterRow(
                filterCounts = filterCounts,
                currentFilter = currentFilter,
                overdueCount = overdueCount,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onFilterChange = ::traceFilterSelection,
            )
        } else {
            TraceTaskInteractionPhase(
                trace = pendingFilterTrace?.takeIf { currentFilter == it.filter },
                event = "filter_interaction_filter_controls_composed",
                data = mapOf("mode" to "full", "rowCount" to displayTasks.size),
            )
            MinimalModeTaskFilterRow(
                filterCounts = filterCounts,
                currentFilter = currentFilter,
                overdueCount = overdueCount,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onFilterChange = ::traceFilterSelection,
            )
        }
        if (displayTasks.isEmpty()) {
            TraceTaskInteractionPhase(
                trace = pendingFilterTrace?.takeIf { currentFilter == it.filter },
                event = "filter_interaction_empty_state_composed",
                data = mapOf("rowCount" to 0),
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when (currentFilter) {
                            TaskFilter.ALL -> if (searchQuery.isNotBlank()) {
                                androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_tasks_match_search)
                            } else {
                                androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_tasks_yet)
                            }
                            TaskFilter.ACTIVE -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_active_tasks)
                            TaskFilter.TODAY -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_tasks_due_today)
                            TaskFilter.OVERDUE -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_overdue_tasks)
                            TaskFilter.FUTURE -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_future_tasks)
                            TaskFilter.COMPLETED -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_completed_tasks)
                            TaskFilter.ARCHIVED -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_archived_tasks)
                            TaskFilter.NOT_ACTIVE -> androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_no_not_active_tasks)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_tap_add_create_task),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            TraceTaskInteractionPhase(
                trace = pendingFilterTrace?.takeIf { currentFilter == it.filter },
                event = "filter_interaction_non_empty_branch_composed",
                data = mapOf("rowCount" to displayTasks.size),
            )
            TraceTaskInteractionPhase(
                trace = pendingFilterTrace?.takeIf { currentFilter == it.filter },
                event = "filter_interaction_list_container_composed",
                data = mapOf("rowCount" to displayTasks.size),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(
                    items = displayTasks,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> "task_row" },
                ) { index, row ->
                    val trace = pendingFilterTrace?.takeIf { currentFilter == it.filter && index == 0 }
                    TaskListRow(
                        task = row.task,
                        onClick = { onTaskClick(row.task) },
                        traceInteractionId = trace?.interactionId,
                        traceTapMs = trace?.tapMs,
                        tracePosition = index,
                    )
                }
            }
        }
    }
}

@Composable
private fun taskFilterLabel(filter: TaskFilter): String = when (filter) {
    TaskFilter.ALL -> stringResource(id = io.payanam.R.string.loc_all)
    TaskFilter.ACTIVE -> stringResource(id = io.payanam.R.string.widget_tracking_status_active)
    TaskFilter.TODAY -> stringResource(id = io.payanam.R.string.loc_today)
    TaskFilter.OVERDUE -> stringResource(id = io.payanam.R.string.loc_overdue)
    TaskFilter.FUTURE -> stringResource(id = io.payanam.R.string.loc_future)
    TaskFilter.COMPLETED -> stringResource(id = io.payanam.R.string.loc_completed)
    TaskFilter.ARCHIVED -> stringResource(id = io.payanam.R.string.loc_archived)
    TaskFilter.NOT_ACTIVE -> stringResource(id = io.payanam.R.string.loc_not_active)
}

@Composable
private fun taskSortLabel(option: TaskSortOption): String = when (option) {
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
private fun habitSortLabel(option: HabitSortOption): String = when (option) {
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
