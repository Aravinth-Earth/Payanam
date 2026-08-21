//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.backfill.ScoreRollupCascadeService
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.ui.components.CheckmarkStatus
import io.payanam.ui.perf.PerfBaselineTelemetry
import io.payanam.usecase.RecurrenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
@HiltViewModel
/**
 * Provides the tasks view model.
 */
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val notificationScheduler: NotificationScheduler,
    private val appSettingsRepository: AppSettingsRepository,
    private val recurrenceManager: RecurrenceManager,
    private val createTimeEntryForHabitUseCase: io.payanam.usecase.CreateTimeEntryForHabitUseCase,
    private val scoreRollupCascadeService: ScoreRollupCascadeService,
    private val habitMetricRepository: io.payanam.domain.repository.HabitMetricRepository,
) : ViewModel() {
    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()
    val chromeUiState: StateFlow<TasksChromeUiState> = uiState
        .map { state ->
            TasksChromeUiState(
                isLoading = state.isLoading,
                recurringTaskCount = state.recurringTasks.size,
                oneTimeTaskCount = state.oneTimeTasks.size,
                habitSortOption = state.habitSortOption,
                currentSort = state.currentSort,
                showArchivedHabits = state.showArchivedHabits,
                showCompletedHabits = state.showCompletedHabits,
                hideAllMarkedToday = state.hideAllMarkedToday,
                dueTodayOnly = state.dueTodayOnly,
                showCompletionDialog = state.showCompletionDialog,
                completionDialogTask = state.completionDialogTask,
                completionDialogDate = state.completionDialogDate,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TasksChromeUiState(),
        )
    val habitsTabUiState: StateFlow<HabitsTabUiState> = uiState
        .map { state ->
            HabitsTabUiState(
                rows = state.visibleHabitRows,
                totalHabitCount = state.recurringTasks.size,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HabitsTabUiState(),
        )
    val tasksTabUiState: StateFlow<TasksTabUiState> = uiState
        .map { state ->
            TasksTabUiState(
                rows = state.filteredTaskRows,
                currentFilter = state.currentFilter,
                filterCounts = state.taskFilterCounts,
                overdueCount = state.overdueCount,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TasksTabUiState(),
        )
    private var tasksLoadJob: Job? = null
    init {
        // Sequenced: persisted preferences must be in state BEFORE the first
        // list shaping. Parallel launches raced loadTasks' shaping snapshot
        // against the visibility-toggle load, leaving the persisted
        // hide-all-marked filter unapplied on launch (rows were shaped with
        // the default flags and clobbered the flag-only update).
        viewModelScope.launch {
            loadSavedSortOption()
            loadSavedFilterOption()
            loadSavedHabitSortOption()
            loadSavedVisibilityToggles()
            loadTasks()
        }
    }

    /** Persisted visibility toggles: showArchived / showCompleted / hideAllMarked. */
    private suspend fun loadSavedVisibilityToggles() {
        try {
                val showArchived = appSettingsRepository
                    .getSetting(AppPreferencesViewModel.KEY_SHOW_ARCHIVED_HABITS) == "true"
                val showCompleted = appSettingsRepository
                    .getSetting(AppPreferencesViewModel.KEY_SHOW_COMPLETED_HABITS) == "true"
                val hideAllMarked = appSettingsRepository
                    .getSetting(AppPreferencesViewModel.KEY_HIDE_ALL_MARKED_TODAY) == "true"
                val dueTodayOnly = appSettingsRepository
                    .getSetting(AppPreferencesViewModel.KEY_DUE_TODAY_ONLY) == "true"
                logger.d(
                    "TasksViewModel.loadSavedVisibilityToggles",
                    "Loaded persisted visibility toggles",
                    mapOf(
                        "showArchived" to showArchived,
                        "showCompleted" to showCompleted,
                        "hideAllMarked" to hideAllMarked,
                        "dueTodayOnly" to dueTodayOnly,
                    ),
                )
                _uiState.update { state ->
                    state.copy(
                        showArchivedHabits = showArchived,
                        showCompletedHabits = showCompleted,
                        hideAllMarkedToday = hideAllMarked,
                        dueTodayOnly = dueTodayOnly,
                    ).applyVisibilityRows()
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.loadSavedVisibilityToggles", "Failed to load visibility toggles", e)
            }
    }
    private suspend fun loadSavedSortOption() {
        try {
                val savedSortKey = appSettingsRepository.getSetting(AppPreferencesViewModel.KEY_TASK_SORT_OPTION)
                val sortOption = TaskSortOption.fromKey(savedSortKey)
                _uiState.update { state ->
                    val filtered = if (state.tasks.isNotEmpty()) {
                        filterAndSortTasks(state.tasks, state.currentFilter, sortOption)
                    } else {
                        state.filteredTasks
                    }
                    state.copy(
                        currentSort = sortOption,
                        filteredTasks = filtered,
                        filteredOneTimeTasks = filtered.filterNot { it.recurrenceEnabled },
                        filteredTaskRows = TasksRowCacheManager.buildTaskRows(filtered.filterNot { it.recurrenceEnabled }),
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.loadSavedSortOption", "Failed to load sort option", e)
            }
    }
    private suspend fun loadSavedFilterOption() {
        try {
                val savedFilterKey = appSettingsRepository.getSetting(AppPreferencesViewModel.KEY_TASK_FILTER_OPTION)
                val filterOption = TaskFilter.fromKey(savedFilterKey)
                _uiState.update { state ->
                    val filtered = if (state.tasks.isNotEmpty()) {
                        filterAndSortTasks(state.tasks, filterOption, state.currentSort)
                    } else {
                        state.filteredTasks
                    }
                    state.copy(
                        currentFilter = filterOption,
                        filteredTasks = filtered,
                        filteredOneTimeTasks = filtered.filterNot { it.recurrenceEnabled },
                        filteredTaskRows = TasksRowCacheManager.buildTaskRows(filtered.filterNot { it.recurrenceEnabled }),
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.loadSavedFilterOption", "Failed to load filter option", e)
            }
    }
    private suspend fun loadSavedHabitSortOption() {
        try {
                val savedHabitSortKey = appSettingsRepository.getSetting(AppPreferencesViewModel.KEY_HABIT_SORT_OPTION)
                val habitSortOption = HabitSortOption.fromKey(savedHabitSortKey)
                _uiState.update { it.copy(habitSortOption = habitSortOption) }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.loadSavedHabitSortOption", "Failed to load habit sort option", e)
            }
    }
    private fun loadTasks() {
        tasksLoadJob?.cancel()
        val startTime = System.currentTimeMillis()
        PerfBaselineTelemetry.markEvent(screen = "tasks", event = "load_tasks_start")
        logger.d("TasksViewModel.loadTasks", "Loading tasks from repository")
        tasksLoadJob = viewModelScope.launch {
            PerfBaselineTelemetry.incrementQuery(screen = "tasks", source = "getAllTasks")
            taskRepository.getAllTasks()
                .catch { e ->
                    logger.e("TasksViewModel.loadTasks", "Failed to load tasks", e)
                    Timber.e(e, "Error loading tasks")
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
                .collect { tasks ->
                    val now = LocalDateTime.now()
                    val today = LocalDate.now()
                    val state = _uiState.value
                    val activeTasks = tasks.filter { it.status != "archived" }
                    val recurringSource = if (FeatureFlags.minimalModeEnabled) {
                        emptyList()
                    } else if (state.showArchivedHabits) {
                        tasks.filter { it.recurrenceEnabled }
                    } else {
                        activeTasks.filter { it.recurrenceEnabled }
                    }
                    val oneTime = tasks.filter { !it.recurrenceEnabled }
                    val activeOneTime = activeTasks.filter { !it.recurrenceEnabled }
                    val todayCount = activeOneTime.count { task ->
                        task.dueDate?.toLocalDate() == today &&
                            task.status != "completed" && task.status != "archived"
                    }
                    val overdueCount = activeOneTime.count { task ->
                        task.dueDate?.isBefore(now) == true &&
                            task.status != "completed" && task.status != "archived"
                    }
                    val futureCount = activeOneTime.count { it.status != "completed" && it.status != "archived" && (it.dueDate == null || it.dueDate?.toLocalDate()?.isAfter(today) == true) }
                    logger.i(
                        "TasksViewModel.loadTasks",
                        "Tasks loaded successfully",
                        mapOf(
                            "total" to tasks.size,
                            "recurring" to recurringSource.size,
                            "oneTime" to oneTime.size,
                            "today" to todayCount,
                            "overdue" to overdueCount,
                            "future" to futureCount,
                        ),
                    )
                    val checkmarkBuildStart = System.currentTimeMillis()
                    val taskIds = recurringSource.map { it.id }
                    // Inc 4: latest L1 score roll-up state per habit (sort + card ring).
                    // Fetched on the real load path (loadTasks), not the dead
                    // refreshRecurringTasksList path.
                    val latestL1 = runCatching { habitMetricRepository.getLatestPerHabit() }.getOrDefault(emptyMap())
                    val occurrencesMap = if (taskIds.isNotEmpty()) {
                        PerfBaselineTelemetry.incrementQuery(screen = "habits", source = "getOccurrencesForTasksInLastNDays")
                        taskOccurrenceRepository.getOccurrencesForTasksInLastNDays(taskIds, HABIT_OCCURRENCE_LOOKBACK_DAYS)
                    } else {
                        emptyMap()
                    }
                    val checkmarkPayload = buildHabitCheckmarkPayload(
                        tasks = recurringSource,
                        occurrencesMap = occurrencesMap,
                        today = today,
                        days = HABIT_CHECKMARK_HISTORY_DAYS,
                    )
                    // Due-today evaluation for the filter; frequency habits with
                    // a denominator beyond the lookback window fetch full history.
                    val dueTodayByTaskId = buildDueTodayByTaskId(
                        tasks = recurringSource,
                        occurrencesMap = occurrencesMap,
                        today = today,
                        fetchFullHistory = { taskOccurrenceRepository.getOccurrencesByTaskId(it.id) },
                    )
                    PerfBaselineTelemetry.markEvent(
                        screen = "habits",
                        event = "checkmark_payload_prepared",
                        data = mapOf("habitCount" to recurringSource.size, "durationMs" to (System.currentTimeMillis() - checkmarkBuildStart)),
                    )
                    val listShapingStart = System.currentTimeMillis()
                    val preparedState = withContext(Dispatchers.Default) {
                        // Fresh read: the pre-suspension snapshot (`state`) may
                        // predate persisted-preference updates applied while the
                        // DB queries above were in flight.
                        val shapingState = _uiState.value
                        val filteredSorted = filterAndSortTasks(tasks, shapingState.currentFilter, shapingState.currentSort)
                        val sortedRecurring = sortHabits(
                            recurringSource,
                            shapingState.habitSortOption,
                            checkmarkPayload.taskCheckmarks,
                            checkmarkPayload.todayStatusByTaskId,
                            latestL1,
                        )
                        val visibleRecurring = visibleHabitsForDisplay(
                            habits = sortedRecurring,
                            todayStatusByTaskId = checkmarkPayload.todayStatusByTaskId,
                            showCompletedHabits = shapingState.showCompletedHabits,
                            hideAllMarkedToday = shapingState.hideAllMarkedToday,
                            dueTodayOnly = shapingState.dueTodayOnly,
                            dueTodayByTaskId = dueTodayByTaskId,
                        )
                        val filteredOneTime = filteredSorted.filterNot { it.recurrenceEnabled }
                        val visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                            tasks = sortedRecurring,
                            checkmarksByTaskId = checkmarkPayload.taskCheckmarks,
                            todayStatusByTaskId = checkmarkPayload.todayStatusByTaskId,
                            showCompletedHabits = shapingState.showCompletedHabits,
                            hideAllMarkedToday = shapingState.hideAllMarkedToday,
                            dueTodayOnly = shapingState.dueTodayOnly,
                            dueTodayByTaskId = dueTodayByTaskId,
                            latestL1ByHabit = latestL1,
                        )
                        val filteredTaskRows = TasksRowCacheManager.buildTaskRows(filteredOneTime)
                        val filterCounts = buildTaskFilterCounts(
                            oneTimeTasks = oneTime,
                            todayCount = todayCount,
                            overdueCount = overdueCount,
                            futureCount = futureCount,
                        )
                        PreparedTaskState(
                            filteredTasks = filteredSorted,
                            filteredOneTimeTasks = filteredOneTime,
                            recurringTasks = sortedRecurring,
                            visibleRecurringTasks = visibleRecurring,
                            visibleHabitRows = visibleHabitRows,
                            filteredTaskRows = filteredTaskRows,
                            filterCounts = filterCounts,
                        )
                    }
                    PerfBaselineTelemetry.markEvent(
                        screen = "tasks",
                        event = "list_shaping_prepared",
                        data = mapOf(
                            "durationMs" to (System.currentTimeMillis() - listShapingStart),
                            "filteredTaskCount" to preparedState.filteredTaskRows.size,
                            "visibleHabitCount" to preparedState.visibleHabitRows.size,
                        ),
                    )
                    _uiState.update { currentState ->
                        logger.d(
                            "TasksViewModel.loadTasks",
                            "Applying initial filter and sort",
                            mapOf(
                                "currentFilter" to currentState.currentFilter.displayName,
                                "currentSort" to currentState.currentSort.displayName,
                                "totalTasks" to tasks.size,
                            ),
                        )
                        currentState.copy(
                            tasks = tasks,
                            filteredTasks = preparedState.filteredTasks,
                            filteredOneTimeTasks = preparedState.filteredOneTimeTasks,
                            recurringTasks = preparedState.recurringTasks,
                            visibleRecurringTasks = preparedState.visibleRecurringTasks,
                            visibleHabitRows = preparedState.visibleHabitRows,
                            oneTimeTasks = oneTime,
                            filteredTaskRows = preparedState.filteredTaskRows,
                            taskFilterCounts = preparedState.filterCounts,
                            taskCheckmarks = checkmarkPayload.taskCheckmarks,
                            latestL1ByHabit = latestL1,
                            todayHabitStatusByTaskId = checkmarkPayload.todayStatusByTaskId,
                            dueTodayByTaskId = dueTodayByTaskId,
                            isLoading = false,
                            error = null,
                            todayCount = todayCount,
                            overdueCount = overdueCount,
                        ).applyVisibilityRows()
                    }
                    val endTime = System.currentTimeMillis()
                    logger.i(
                        "TasksViewModel.loadTasks",
                        "Complete task loading finished",
                        mapOf(
                            "totalLoadTimeMs" to (endTime - startTime),
                            "taskCount" to tasks.size,
                            "recurringTaskCount" to recurringSource.size,
                        ),
                    )
                    PerfBaselineTelemetry.markEvent(
                        screen = "tasks",
                        event = "load_tasks_complete",
                        data = mapOf(
                            "durationMs" to (endTime - startTime),
                            "taskCount" to tasks.size,
                            "habitCount" to recurringSource.size,
                        ),
                    )
                }
        }
    }
    /**
     * Performs the toggle checkmark.
     */
    fun toggleCheckmark(taskId: String, date: LocalDate) {
        logger.i(
            "TasksViewModel.toggleCheckmark",
            "CHECKMARK_TOGGLE_START",
            mapOf(
                "taskId" to taskId,
                "date" to date.toString(),
            ),
        )
        viewModelScope.launch {
            try {
                val currentCheckmarks = _uiState.value.taskCheckmarks[taskId] ?: return@launch
                val checkmark = currentCheckmarks.find { it.date == date } ?: return@launch
                val newStatus = when (checkmark.status) {
                    CheckmarkStatus.PENDING, CheckmarkStatus.UNKNOWN -> "completed"
                    CheckmarkStatus.COMPLETED -> "missed"
                    CheckmarkStatus.MISSED -> "completed"
                    CheckmarkStatus.SKIPPED -> "completed"
                }
                logger.d(
                    "TasksViewModel.toggleCheckmark",
                    "STATUS_CALCULATED",
                    mapOf(
                        "taskId" to taskId,
                        "date" to date.toString(),
                        "currentStatus" to checkmark.status.toString(),
                        "newStatus" to newStatus,
                    ),
                )
                val task = _uiState.value.tasks.find { it.id == taskId }
                logger.d(
                    "TasksViewModel.toggleCheckmark",
                    "TASK_LOOKUP",
                    mapOf(
                        "taskId" to taskId,
                        "taskFound" to (task != null).toString(),
                        "taskTitle" to (task?.title ?: "null"),
                        "isRecurring" to (task?.recurrenceEnabled?.toString() ?: "null"),
                    ),
                )
                if (task?.recurrenceEnabled == true && newStatus == "completed") {
                    logger.i(
                        "TasksViewModel.toggleCheckmark",
                        "RECURRING_TASK_COMPLETION_DIALOG_SHOWN",
                        mapOf(
                            "taskId" to taskId,
                            "taskTitle" to task.title,
                        ),
                    )
                    _uiState.update {
                        it.copy(
                            showCompletionDialog = true,
                            completionDialogTask = task,
                            completionDialogDate = date,
                        )
                    }
                    return@launch
                }
                logger.d(
                    "TasksViewModel.toggleCheckmark",
                    "PROCEEDING_WITH_DIRECT_TOGGLE",
                    mapOf(
                        "taskId" to taskId,
                        "newStatus" to newStatus,
                    ),
                )
                val occurrence = taskOccurrenceRepository.toggleOccurrence(
                    taskId = taskId,
                    date = date,
                    newStatus = newStatus,
                    note = null,
                    actualCompletedAt = null,
                    actualDurationMinutes = null,
                )
                logger.i(
                    "TasksViewModel.toggleCheckmark",
                    "CHECKMARK_TOGGLED_SUCCESS",
                    mapOf(
                        "taskId" to taskId,
                        "date" to date.toString(),
                        "newStatus" to newStatus,
                    ),
                )
                // Recompute L1/L2/L3 FIRST so refreshed rows carry the
                // just-updated metrics (refresh-before-cascade showed stale).
                scoreRollupCascadeService.recalcForStatusChange(taskId, date)
                refreshCheckmarksForTask(taskId)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TasksViewModel.toggleCheckmark",
                    "CHECKMARK_TOGGLE_FAILED",
                    e,
                    mapOf(
                        "taskId" to taskId,
                        "date" to date.toString(),
                    ),
                )
            }
        }
    }
    /**
     * Performs the dismiss completion dialog.
     */
    fun dismissCompletionDialog() {
        _uiState.update {
            it.copy(
                showCompletionDialog = false,
                completionDialogTask = null,
                completionDialogDate = null,
            )
        }
    }
    /**
     * Performs the confirm completion.
     */
    fun confirmCompletion(actualCompletedAt: LocalDateTime?, actualDurationMinutes: Int?) {
        val task = _uiState.value.completionDialogTask ?: return
        val date = _uiState.value.completionDialogDate ?: return
        viewModelScope.launch {
            try {
                val occurrence = taskOccurrenceRepository.toggleOccurrence(
                    taskId = task.id,
                    date = date,
                    newStatus = "completed",
                    note = null,
                    actualCompletedAt = actualCompletedAt,
                    actualDurationMinutes = actualDurationMinutes,
                )
                logger.i(
                    "TasksViewModel.confirmCompletion",
                    "Completion confirmed",
                    mapOf(
                        "taskId" to task.id,
                        "date" to date.toString(),
                        "actualCompletedAt" to actualCompletedAt?.toString(),
                        "actualDurationMinutes" to actualDurationMinutes?.toString(),
                    ),
                )
                refreshCheckmarksForTask(task.id)
                scoreRollupCascadeService.recalcForStatusChange(task.id, date)
                createTimeEntryForHabitUseCase(task, actualCompletedAt, actualDurationMinutes)
                dismissCompletionDialog()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TasksViewModel.confirmCompletion",
                    "Failed to confirm completion",
                    e,
                    mapOf(
                        "taskId" to task.id,
                        "date" to date.toString(),
                    ),
                )
            }
        }
    }
    /**
     * Updates the update checkmark.
     */
    fun updateCheckmark(
        taskId: String,
        date: LocalDate,
        status: CheckmarkStatus,
        note: String,
    ) {
        viewModelScope.launch {
            try {
                if (status == CheckmarkStatus.PENDING) {
                    taskOccurrenceRepository.deleteOccurrence(taskId, date)
                    logger.i(
                        "TasksViewModel.updateCheckmark",
                        "Checkmark cleared (deleted)",
                        mapOf(
                            "taskId" to taskId,
                            "date" to date.toString(),
                        ),
                    )
                } else {
                    val statusStr = when (status) {
                        CheckmarkStatus.COMPLETED -> "completed"
                        CheckmarkStatus.SKIPPED -> "skipped"
                        CheckmarkStatus.MISSED -> "missed"
                        else -> return@launch
                    }
                    taskOccurrenceRepository.toggleOccurrence(
                        taskId = taskId,
                        date = date,
                        newStatus = statusStr,
                        note = note.takeIf { it.isNotBlank() },
                    )
                    logger.i(
                        "TasksViewModel.updateCheckmark",
                        "Checkmark updated",
                        mapOf(
                            "taskId" to taskId,
                            "date" to date.toString(),
                            "status" to statusStr,
                            "hasNote" to note.isNotBlank(),
                        ),
                    )
                }
                // Recompute L1/L2/L3 FIRST so refreshed rows carry the
                // just-updated metrics (refresh-before-cascade showed stale).
                scoreRollupCascadeService.recalcForStatusChange(taskId, date)
                refreshCheckmarksForTask(taskId)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.updateCheckmark", "Failed to update checkmark", e)
            }
        }
    }
    private suspend fun refreshCheckmarksForTask(taskId: String) {
        try {
            val today = LocalDate.now()
            val occurrences = taskOccurrenceRepository.getOccurrencesForLastNDays(taskId, HABIT_OCCURRENCE_LOOKBACK_DAYS)
            val checkmarks = buildCheckmarksForTask(occurrences = occurrences, today = today, days = HABIT_CHECKMARK_HISTORY_DAYS)
            val todayStatus = checkmarks.firstOrNull()?.status ?: CheckmarkStatus.UNKNOWN
            // Inc 4: refresh the L1 map after a toggle so the ring/sort follow
            // the just-updated metric.
            val fallbackL1 = _uiState.value.latestL1ByHabit
            val latestL1 = runCatching { habitMetricRepository.getLatestPerHabit() }.getOrDefault(fallbackL1)
            // E1: recompute the due-today flag after every mark — a FREQUENCY
            // habit whose quota just became satisfied is no longer due.
            val task = _uiState.value.recurringTasks.find { it.id == taskId }
            val dueToday = if (task != null) {
                computeDueTodayForTask(
                    task = task,
                    occurrences = occurrences,
                    today = today,
                    fetchFullHistory = { taskOccurrenceRepository.getOccurrencesByTaskId(it.id) },
                )
            } else {
                _uiState.value.dueTodayByTaskId[taskId] ?: true
            }
            _uiState.update { state ->
                val updatedCheckmarks = state.taskCheckmarks + (taskId to checkmarks)
                val updatedTodayStatus = state.todayHabitStatusByTaskId + (taskId to todayStatus)
                val updatedDueToday = state.dueTodayByTaskId + (taskId to dueToday)
                val sortedRecurring = sortHabits(
                    state.recurringTasks,
                    state.habitSortOption,
                    updatedCheckmarks,
                    updatedTodayStatus,
                    latestL1,
                )
                state.copy(
                    latestL1ByHabit = latestL1,
                    taskCheckmarks = updatedCheckmarks,
                    todayHabitStatusByTaskId = updatedTodayStatus,
                    dueTodayByTaskId = updatedDueToday,
                    recurringTasks = sortedRecurring,
                    visibleRecurringTasks = visibleHabitsForDisplay(
                        habits = sortedRecurring,
                        todayStatusByTaskId = updatedTodayStatus,
                        showCompletedHabits = state.showCompletedHabits,
                        hideAllMarkedToday = state.hideAllMarkedToday,
                        dueTodayOnly = state.dueTodayOnly,
                        dueTodayByTaskId = updatedDueToday,
                    ),
                    visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                        tasks = sortedRecurring,
                        checkmarksByTaskId = updatedCheckmarks,
                        todayStatusByTaskId = updatedTodayStatus,
                        showCompletedHabits = state.showCompletedHabits,
                        hideAllMarkedToday = state.hideAllMarkedToday,
                        dueTodayOnly = state.dueTodayOnly,
                        dueTodayByTaskId = updatedDueToday,
                        latestL1ByHabit = latestL1,
                    ),
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("TasksViewModel.refreshCheckmarksForTask", "Failed to refresh checkmarks", e)
        }
    }
    private suspend fun refreshRecurringTasksList() {
        try {
            PerfBaselineTelemetry.incrementQuery(screen = "habits", source = "refresh_getAllTasks")
            val allTasks = taskRepository.getAllTasks().first()
            val recurring = if (_uiState.value.showArchivedHabits) {
                allTasks.filter { it.recurrenceEnabled }
            } else {
                allTasks.filter { it.status != "archived" && it.recurrenceEnabled }
            }
            // Inc 4: latest L1 score roll-up state per habit (sort + card ring)
            val latestL1 = runCatching { habitMetricRepository.getLatestPerHabit() }.getOrDefault(emptyMap())
            _uiState.update { state ->
                val sortedRecurring = sortHabits(
                    recurring,
                    state.habitSortOption,
                    state.taskCheckmarks,
                    state.todayHabitStatusByTaskId,
                    latestL1,
                )
                state.copy(
                    latestL1ByHabit = latestL1,
                    recurringTasks = sortedRecurring,
                    visibleRecurringTasks = visibleHabitsForDisplay(
                        habits = sortedRecurring,
                        todayStatusByTaskId = state.todayHabitStatusByTaskId,
                        showCompletedHabits = state.showCompletedHabits,
                        hideAllMarkedToday = state.hideAllMarkedToday,
                        dueTodayOnly = state.dueTodayOnly,
                        dueTodayByTaskId = state.dueTodayByTaskId,
                    ),
                    visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                        tasks = sortedRecurring,
                        checkmarksByTaskId = state.taskCheckmarks,
                        todayStatusByTaskId = state.todayHabitStatusByTaskId,
                        showCompletedHabits = state.showCompletedHabits,
                        hideAllMarkedToday = state.hideAllMarkedToday,
                        dueTodayOnly = state.dueTodayOnly,
                        dueTodayByTaskId = state.dueTodayByTaskId,
                        latestL1ByHabit = latestL1,
                    ),
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("TasksViewModel.refreshRecurringTasksList", "Failed to refresh", e)
        }
    }
    /**
     * Updates the set sort option.
     */
    fun setSortOption(sortOption: TaskSortOption) {
        viewModelScope.launch {
            try {
                logger.d("TasksViewModel.setSortOption", "Saving sort option to DB", mapOf("key" to sortOption.key))
                appSettingsRepository.setSetting(AppPreferencesViewModel.KEY_TASK_SORT_OPTION, sortOption.key)
                logger.i("TasksViewModel.setSortOption", "Sort option saved to DB successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.setSortOption", "Failed to save sort option to DB", e)
            }
        }
        _uiState.update { state ->
            val sorted = filterAndSortTasks(state.tasks, state.currentFilter, sortOption)
            state.copy(
                currentSort = sortOption,
                filteredTasks = sorted,
                filteredOneTimeTasks = sorted.filterNot { it.recurrenceEnabled },
                filteredTaskRows = TasksRowCacheManager.buildTaskRows(sorted.filterNot { it.recurrenceEnabled }),
            )
        }
    }
    /**
     * Updates the set filter.
     */
    fun setFilter(
        filter: TaskFilter,
        interactionId: String? = null,
        interactionStartMs: Long? = null,
    ) {
        val computeStartMs = SystemClock.elapsedRealtime()
        logger.d(
            "TasksViewModel.setFilter",
            "Filter changed",
            mapOf(
                "filter" to filter.displayName,
                "interactionId" to interactionId,
                "interactionStartMs" to interactionStartMs,
            ),
        )
        if (interactionId != null) {
            PerfBaselineTelemetry.markEvent(
                screen = "tasks",
                event = "filter_interaction_compute_start",
                data = mapOf(
                    "interactionId" to interactionId,
                    "filter" to filter.key,
                    "interactionStartMs" to interactionStartMs,
                    "elapsedSinceTapMs" to interactionStartMs?.let { computeStartMs - it },
                ),
            )
        }
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting(AppPreferencesViewModel.KEY_TASK_FILTER_OPTION, filter.key)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.setFilter", "Failed to save filter option", e)
            }
        }
        _uiState.update { state ->
            val filtered = filterAndSortTasks(state.tasks, filter, state.currentSort)
            val computeEndMs = SystemClock.elapsedRealtime()
            val computeDurationMs = computeEndMs - computeStartMs
            logger.d(
                "TasksViewModel.setFilter",
                "Tasks filtered",
                mapOf(
                    "resultCount" to filtered.size,
                    "interactionId" to interactionId,
                    "computeDurationMs" to computeDurationMs,
                ),
            )
            if (interactionId != null) {
                PerfBaselineTelemetry.markEvent(
                    screen = "tasks",
                    event = "filter_interaction_compute_end",
                    data = mapOf(
                        "interactionId" to interactionId,
                        "filter" to filter.key,
                        "resultCount" to filtered.size,
                        "computeDurationMs" to computeDurationMs,
                        "elapsedSinceTapMs" to interactionStartMs?.let { computeEndMs - it },
                    ),
                )
            }
            state.copy(
                currentFilter = filter,
                filteredTasks = filtered,
                filteredOneTimeTasks = filtered.filterNot { it.recurrenceEnabled },
                filteredTaskRows = TasksRowCacheManager.buildTaskRows(filtered.filterNot { it.recurrenceEnabled }),
            )
        }
        if (interactionId != null) {
            val stateAppliedMs = SystemClock.elapsedRealtime()
            PerfBaselineTelemetry.markEvent(
                screen = "tasks",
                event = "filter_interaction_state_applied",
                data = mapOf(
                    "interactionId" to interactionId,
                    "filter" to filter.key,
                    "elapsedSinceTapMs" to interactionStartMs?.let { stateAppliedMs - it },
                ),
            )
        }
    }
    /**
     * Updates the set habit sort option.
     */
    fun setHabitSortOption(option: HabitSortOption) {
        logger.i(
            "TasksViewModel.setHabitSortOption",
            "User changed habit sort option",
            mapOf(
                "newHabitSort" to option.displayName,
                "newHabitSortKey" to option.key,
                "previousHabitSort" to _uiState.value.habitSortOption.displayName,
            ),
        )
        viewModelScope.launch {
            try {
                logger.d("TasksViewModel.setHabitSortOption", "Saving habit sort option to DB", mapOf("key" to option.key))
                appSettingsRepository.setSetting(AppPreferencesViewModel.KEY_HABIT_SORT_OPTION, option.key)
                logger.i("TasksViewModel.setHabitSortOption", "Habit sort option saved to DB successfully")
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.setHabitSortOption", "Failed to save habit sort option to DB", e)
            }
        }
        _uiState.update { state ->
            val sorted = sortHabits(state.recurringTasks, option, state.taskCheckmarks, state.todayHabitStatusByTaskId, state.latestL1ByHabit)
            state.copy(
                habitSortOption = option,
                recurringTasks = sorted,
                visibleRecurringTasks = visibleHabitsForDisplay(
                    habits = sorted,
                    todayStatusByTaskId = state.todayHabitStatusByTaskId,
                    showCompletedHabits = state.showCompletedHabits,
                    hideAllMarkedToday = state.hideAllMarkedToday,
                    dueTodayOnly = state.dueTodayOnly,
                    dueTodayByTaskId = state.dueTodayByTaskId,
                ),
                visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                    tasks = sorted,
                    checkmarksByTaskId = state.taskCheckmarks,
                    todayStatusByTaskId = state.todayHabitStatusByTaskId,
                    showCompletedHabits = state.showCompletedHabits,
                    hideAllMarkedToday = state.hideAllMarkedToday,
                    dueTodayOnly = state.dueTodayOnly,
                    dueTodayByTaskId = state.dueTodayByTaskId,
                    latestL1ByHabit = state.latestL1ByHabit,
                ),
            )
        }
    }
    /**
     * Performs the toggle show archived habits.
     */
    fun toggleShowArchivedHabits() {
        _uiState.update { state ->
            val newValue = !state.showArchivedHabits
            logger.d("TasksViewModel.toggleShowArchivedHabits", "Toggled", mapOf("showArchived" to newValue))
            state.copy(showArchivedHabits = newValue)
        }
        persistVisibilityToggle(AppPreferencesViewModel.KEY_SHOW_ARCHIVED_HABITS) { it.showArchivedHabits }
        loadTasks()
    }
    /**
     * Performs the toggle show completed habits.
     */
    fun toggleShowCompletedHabits() {
        _uiState.update { state ->
            val newValue = !state.showCompletedHabits
            logger.d("TasksViewModel.toggleShowCompletedHabits", "Toggled", mapOf("showCompleted" to newValue))
            state.copy(
                showCompletedHabits = newValue,
                visibleRecurringTasks = visibleHabitsForDisplay(
                    habits = state.recurringTasks,
                    todayStatusByTaskId = state.todayHabitStatusByTaskId,
                    showCompletedHabits = newValue,
                    hideAllMarkedToday = state.hideAllMarkedToday,
                    dueTodayOnly = state.dueTodayOnly,
                    dueTodayByTaskId = state.dueTodayByTaskId,
                ),
                visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                    tasks = state.recurringTasks,
                    checkmarksByTaskId = state.taskCheckmarks,
                    todayStatusByTaskId = state.todayHabitStatusByTaskId,
                    showCompletedHabits = newValue,
                    hideAllMarkedToday = state.hideAllMarkedToday,
                    dueTodayOnly = state.dueTodayOnly,
                    dueTodayByTaskId = state.dueTodayByTaskId,
                    latestL1ByHabit = state.latestL1ByHabit,
                ),
            )
        }
        persistVisibilityToggle(AppPreferencesViewModel.KEY_SHOW_COMPLETED_HABITS) { it.showCompletedHabits }
    }

    /** Persist one visibility toggle value as "true"/"false". */
    private fun persistVisibilityToggle(key: String, valueOf: (TasksUiState) -> Boolean) {
        viewModelScope.launch {
            try {
                appSettingsRepository.setSetting(key, valueOf(_uiState.value).toString())
                logger.d("TasksViewModel.persistVisibilityToggle", "Visibility toggle saved", mapOf("key" to key))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.persistVisibilityToggle", "Failed to save visibility toggle", e, mapOf("key" to key))
            }
        }
    }
    /**
     * Performs the toggle hide all marked today.
     */
    fun toggleHideAllMarkedToday() {
        _uiState.update { state ->
            val newValue = !state.hideAllMarkedToday
            logger.d("TasksViewModel.toggleHideAllMarkedToday", "Toggled", mapOf("hideAllMarked" to newValue))
            state.copy(hideAllMarkedToday = newValue).applyVisibilityRows()
        }
        persistVisibilityToggle(AppPreferencesViewModel.KEY_HIDE_ALL_MARKED_TODAY) { it.hideAllMarkedToday }
    }
    /**
     * Performs the toggle due today only.
     */
    fun toggleDueTodayOnly() {
        _uiState.update { state ->
            val newValue = !state.dueTodayOnly
            logger.d("TasksViewModel.toggleDueTodayOnly", "Toggled", mapOf("dueTodayOnly" to newValue))
            state.copy(dueTodayOnly = newValue).applyVisibilityRows()
        }
        persistVisibilityToggle(AppPreferencesViewModel.KEY_DUE_TODAY_ONLY) { it.dueTodayOnly }
    }

    /**
     * Recomputes the flag-dependent habit visibility rows (visibleRecurringTasks
     * + visibleHabitRows) from the current state. Must only run inside a
     * non-suspending _uiState.update lambda — MutableStateFlow.update retries
     * with the latest value on contention, so the rows always derive from the
     * current flags. Never compute these from a snapshot captured before a
     * suspension (that was the launch race: persisted hide-all-marked loaded
     * after list shaping captured the default flags and got clobbered).
     */
    private fun TasksUiState.applyVisibilityRows(): TasksUiState =
        copy(
            visibleRecurringTasks = visibleHabitsForDisplay(
                habits = recurringTasks,
                todayStatusByTaskId = todayHabitStatusByTaskId,
                showCompletedHabits = showCompletedHabits,
                hideAllMarkedToday = hideAllMarkedToday,
                dueTodayOnly = dueTodayOnly,
                dueTodayByTaskId = dueTodayByTaskId,
            ),
            visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                tasks = recurringTasks,
                checkmarksByTaskId = taskCheckmarks,
                todayStatusByTaskId = todayHabitStatusByTaskId,
                showCompletedHabits = showCompletedHabits,
                hideAllMarkedToday = hideAllMarkedToday,
                dueTodayOnly = dueTodayOnly,
                dueTodayByTaskId = dueTodayByTaskId,
                latestL1ByHabit = latestL1ByHabit,
            ),
        )
    /**
     * Performs the complete task.
     */
    fun completeTask(taskId: String, note: String? = null) {
        logger.i("TasksViewModel.completeTask", "Completing task", mapOf("taskId" to taskId, "hasNote" to (note != null)))
        viewModelScope.launch {
            try {
                val task = taskRepository.getTaskById(taskId)
                if (task == null) {
                    logger.w("TasksViewModel.completeTask", "Task not found", mapOf("taskId" to taskId))
                    return@launch
                }
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
                if (!isFrequencyHabit) {
                    taskRepository.completeTask(taskId, note)
                }
                if (task.recurrenceEnabled) {
                    if (FeatureFlags.minimalModeEnabled) {
                        logger.i("TasksViewModel.completeTask", "Minimal mode: skipping recurrence processing for recurring task", mapOf("taskId" to taskId))
                        notificationScheduler.cancelForTask(taskId)
                        logger.i("TasksViewModel.completeTask", "Task completed successfully", mapOf("taskId" to taskId))
                        return@launch
                    }
                    val occurrence = TaskOccurrence(
                        id = java.util.UUID.randomUUID().toString(),
                        taskId = taskId,
                        occurrenceDate = LocalDate.now().toString(),
                        status = "completed",
                        statusNote = note,
                        statusReason = "tasks_tab_action",
                        completedAt = LocalDateTime.now().toString(),
                    )
                    taskOccurrenceRepository.recordOccurrence(occurrence)
                    recurrenceManager.onTaskCompleted(task, note = note, reason = "tasks_tab_action")
                    val updatedTask = taskRepository.getTaskById(taskId)
                    if (updatedTask != null && updatedTask.recurrenceEnabled) {
                        try {
                            notificationScheduler.scheduleForTask(updatedTask)
                        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                            logger.e(
                                "TasksViewModel.completeTask",
                                "Failed to schedule next recurring reminder",
                                e,
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                    refreshCheckmarksForTask(taskId)
                    scoreRollupCascadeService.recalcForStatusChange(taskId, LocalDate.now())
                } else {
                    notificationScheduler.cancelForTask(taskId)
                }
                logger.i("TasksViewModel.completeTask", "Task completed successfully", mapOf("taskId" to taskId))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.completeTask", "Failed to complete task", e, mapOf("taskId" to taskId))
                Timber.e(e, "Error completing task")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Performs the archive task.
     */
    fun archiveTask(taskId: String) {
        logger.i("TasksViewModel.archiveTask", "Archiving task", mapOf("taskId" to taskId))
        viewModelScope.launch {
            try {
                taskRepository.archiveTask(taskId)
                try {
                    notificationScheduler.cancelForTask(taskId)
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    logger.e(
                        "TasksViewModel.archiveTask",
                        "Failed to cancel reminders",
                        e,
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                }
                logger.i("TasksViewModel.archiveTask", "Task archived successfully", mapOf("taskId" to taskId))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.archiveTask", "Failed to archive task", e, mapOf("taskId" to taskId))
                Timber.e(e, "Error archiving task")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Removes the delete task.
     */
    fun deleteTask(taskId: String) {
        logger.w("TasksViewModel.deleteTask", "Deleting task", mapOf("taskId" to taskId))
        viewModelScope.launch {
            try {
                try {
                    notificationScheduler.cancelForTask(taskId)
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    logger.e(
                        "TasksViewModel.deleteTask",
                        "Failed to cancel reminders",
                        e,
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                }
                taskRepository.deleteTask(taskId)
                logger.i("TasksViewModel.deleteTask", "Task deleted successfully", mapOf("taskId" to taskId))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TasksViewModel.deleteTask", "Failed to delete task", e, mapOf("taskId" to taskId))
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Removes the clear error.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
