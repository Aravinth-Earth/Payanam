//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
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
import io.payanam.scoring.RecurrenceScoreCalculator
import io.payanam.ui.components.CheckmarkStatus
import io.payanam.ui.components.DayCheckmark
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
        loadSavedSortOption()
        loadSavedFilterOption()
        loadSavedHabitSortOption()
        loadTasks()
    }
    private fun loadSavedSortOption() {
        viewModelScope.launch {
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
            } catch (e: Exception) {
                logger.e("TasksViewModel.loadSavedSortOption", "Failed to load sort option", e)
            }
        }
    }
    private fun loadSavedFilterOption() {
        viewModelScope.launch {
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
            } catch (e: Exception) {
                logger.e("TasksViewModel.loadSavedFilterOption", "Failed to load filter option", e)
            }
        }
    }
    private fun loadSavedHabitSortOption() {
        viewModelScope.launch {
            try {
                val savedHabitSortKey = appSettingsRepository.getSetting(AppPreferencesViewModel.KEY_HABIT_SORT_OPTION)
                val habitSortOption = HabitSortOption.fromKey(savedHabitSortKey)
                _uiState.update { it.copy(habitSortOption = habitSortOption) }
            } catch (e: Exception) {
                logger.e("TasksViewModel.loadSavedHabitSortOption", "Failed to load habit sort option", e)
            }
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
                        taskOccurrenceRepository.getOccurrencesForTasksInLastNDays(taskIds, 14)
                    } else {
                        emptyMap()
                    }
                    val checkmarkPayload = buildHabitCheckmarkPayload(
                        tasks = recurringSource,
                        occurrencesMap = occurrencesMap,
                        today = today,
                        days = 14,
                    )
                    PerfBaselineTelemetry.markEvent(
                        screen = "habits",
                        event = "checkmark_payload_prepared",
                        data = mapOf("habitCount" to recurringSource.size, "durationMs" to (System.currentTimeMillis() - checkmarkBuildStart)),
                    )
                    val listShapingStart = System.currentTimeMillis()
                    val preparedState = withContext(Dispatchers.Default) {
                        val filteredSorted = filterAndSortTasks(tasks, state.currentFilter, state.currentSort)
                        val sortedRecurring = sortHabits(
                            recurringSource,
                            state.habitSortOption,
                            checkmarkPayload.taskCheckmarks,
                            checkmarkPayload.todayStatusByTaskId,
                            latestL1,
                        )
                        val visibleRecurring = visibleHabitsForDisplay(
                            habits = sortedRecurring,
                            todayStatusByTaskId = checkmarkPayload.todayStatusByTaskId,
                            showCompletedHabits = state.showCompletedHabits,
                            hideAllMarkedToday = state.hideAllMarkedToday,
                        )
                        val filteredOneTime = filteredSorted.filterNot { it.recurrenceEnabled }
                        val visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                            tasks = sortedRecurring,
                            checkmarksByTaskId = checkmarkPayload.taskCheckmarks,
                            todayStatusByTaskId = checkmarkPayload.todayStatusByTaskId,
                            showCompletedHabits = state.showCompletedHabits,
                            hideAllMarkedToday = state.hideAllMarkedToday,
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
                            isLoading = false,
                            error = null,
                            todayCount = todayCount,
                            overdueCount = overdueCount,
                        )
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
                refreshCheckmarksForTask(taskId)
                scoreRollupCascadeService.recalcForStatusChange(taskId, date)
            } catch (e: Exception) {
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
    fun dismissCompletionDialog() {
        _uiState.update {
            it.copy(
                showCompletionDialog = false,
                completionDialogTask = null,
                completionDialogDate = null,
            )
        }
    }
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
            } catch (e: Exception) {
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
                refreshCheckmarksForTask(taskId)
                scoreRollupCascadeService.recalcForStatusChange(taskId, date)
            } catch (e: Exception) {
                logger.e("TasksViewModel.updateCheckmark", "Failed to update checkmark", e)
            }
        }
    }
    private suspend fun refreshCheckmarksForTask(taskId: String) {
        try {
            val today = LocalDate.now()
            val occurrences = taskOccurrenceRepository.getOccurrencesForLastNDays(taskId, 14)
            val checkmarks = buildCheckmarksForTask(occurrences = occurrences, today = today, days = 14)
            val todayStatus = checkmarks.firstOrNull()?.status ?: CheckmarkStatus.UNKNOWN
            // Inc 4: refresh the L1 map after a toggle so the ring/sort follow
            // the just-updated metric.
            val fallbackL1 = _uiState.value.latestL1ByHabit
            val latestL1 = runCatching { habitMetricRepository.getLatestPerHabit() }.getOrDefault(fallbackL1)
            _uiState.update { state ->
                val updatedCheckmarks = state.taskCheckmarks + (taskId to checkmarks)
                val updatedTodayStatus = state.todayHabitStatusByTaskId + (taskId to todayStatus)
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
                    recurringTasks = sortedRecurring,
                    visibleRecurringTasks = visibleHabitsForDisplay(
                        habits = sortedRecurring,
                        todayStatusByTaskId = updatedTodayStatus,
                        showCompletedHabits = state.showCompletedHabits,
                        hideAllMarkedToday = state.hideAllMarkedToday,
                    ),
                    visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                        tasks = sortedRecurring,
                        checkmarksByTaskId = updatedCheckmarks,
                        todayStatusByTaskId = updatedTodayStatus,
                        showCompletedHabits = state.showCompletedHabits,
                        hideAllMarkedToday = state.hideAllMarkedToday,
                    ),
                )
            }
        } catch (e: Exception) {
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
                    ),
                    visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                        tasks = sortedRecurring,
                        checkmarksByTaskId = state.taskCheckmarks,
                        todayStatusByTaskId = state.todayHabitStatusByTaskId,
                        showCompletedHabits = state.showCompletedHabits,
                        hideAllMarkedToday = state.hideAllMarkedToday,
                        latestL1ByHabit = latestL1,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.e("TasksViewModel.refreshRecurringTasksList", "Failed to refresh", e)
        }
    }
    fun setSortOption(sortOption: TaskSortOption) {
        viewModelScope.launch {
            try {
                logger.d("TasksViewModel.setSortOption", "Saving sort option to DB", mapOf("key" to sortOption.key))
                appSettingsRepository.setSetting(AppPreferencesViewModel.KEY_TASK_SORT_OPTION, sortOption.key)
                logger.i("TasksViewModel.setSortOption", "Sort option saved to DB successfully")
            } catch (e: Exception) {
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
            } catch (e: Exception) {
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
            } catch (e: Exception) {
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
                ),
                visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                    tasks = sorted,
                    checkmarksByTaskId = state.taskCheckmarks,
                    todayStatusByTaskId = state.todayHabitStatusByTaskId,
                    showCompletedHabits = state.showCompletedHabits,
                    hideAllMarkedToday = state.hideAllMarkedToday,
                ),
            )
        }
    }
    fun toggleShowArchivedHabits() {
        _uiState.update { state ->
            val newValue = !state.showArchivedHabits
            logger.d("TasksViewModel.toggleShowArchivedHabits", "Toggled", mapOf("showArchived" to newValue))
            state.copy(showArchivedHabits = newValue)
        }
        loadTasks()
    }
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
                ),
                visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                    tasks = state.recurringTasks,
                    checkmarksByTaskId = state.taskCheckmarks,
                    todayStatusByTaskId = state.todayHabitStatusByTaskId,
                    showCompletedHabits = newValue,
                    hideAllMarkedToday = state.hideAllMarkedToday,
                ),
            )
        }
    }
    fun toggleHideAllMarkedToday() {
        _uiState.update { state ->
            val newValue = !state.hideAllMarkedToday
            logger.d("TasksViewModel.toggleHideAllMarkedToday", "Toggled", mapOf("hideAllMarked" to newValue))
            state.copy(
                hideAllMarkedToday = newValue,
                visibleRecurringTasks = visibleHabitsForDisplay(
                    habits = state.recurringTasks,
                    todayStatusByTaskId = state.todayHabitStatusByTaskId,
                    showCompletedHabits = state.showCompletedHabits,
                    hideAllMarkedToday = newValue,
                ),
                visibleHabitRows = TasksRowCacheManager.buildHabitRows(
                    tasks = state.recurringTasks,
                    checkmarksByTaskId = state.taskCheckmarks,
                    todayStatusByTaskId = state.todayHabitStatusByTaskId,
                    showCompletedHabits = state.showCompletedHabits,
                    hideAllMarkedToday = newValue,
                ),
            )
        }
    }
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
                        } catch (e: Exception) {
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
            } catch (e: Exception) {
                logger.e("TasksViewModel.completeTask", "Failed to complete task", e, mapOf("taskId" to taskId))
                Timber.e(e, "Error completing task")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    fun archiveTask(taskId: String) {
        logger.i("TasksViewModel.archiveTask", "Archiving task", mapOf("taskId" to taskId))
        viewModelScope.launch {
            try {
                taskRepository.archiveTask(taskId)
                try {
                    notificationScheduler.cancelForTask(taskId)
                } catch (e: Exception) {
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
            } catch (e: Exception) {
                logger.e("TasksViewModel.archiveTask", "Failed to archive task", e, mapOf("taskId" to taskId))
                Timber.e(e, "Error archiving task")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    fun deleteTask(taskId: String) {
        logger.w("TasksViewModel.deleteTask", "Deleting task", mapOf("taskId" to taskId))
        viewModelScope.launch {
            try {
                try {
                    notificationScheduler.cancelForTask(taskId)
                } catch (e: Exception) {
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
            } catch (e: Exception) {
                logger.e("TasksViewModel.deleteTask", "Failed to delete task", e, mapOf("taskId" to taskId))
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
