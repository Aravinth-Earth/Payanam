//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooGenericExceptionCaught", "SwallowedException")
package io.payanam.presentation.time
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.scoring.port.TaskRecurrenceHandler
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.port.TimeTrackingController
import io.payanam.domain.port.TimeTrackingWidgetRefresher
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
/**
 * ViewModel for the Time-tracking screen: loads the selected day's entries,
 * planned tasks, and past occurrences, and drives start/stop/edit/continue
 * tracking plus task complete/skip/miss/archive/delete (recurring tasks also
 * record occurrences and reschedule reminders).
 */
class TimeViewModel(
    private val timeEntryRepository: TimeEntryRepository,
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val recurrenceHandler: TaskRecurrenceHandler,
    private val reminderScheduler: TaskReminderScheduler,
    private val timeTrackingController: TimeTrackingController,
    private val widgetRefresher: TimeTrackingWidgetRefresher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current
    private val _uiState = MutableStateFlow(TimeScreenUiState())
    val uiState: StateFlow<TimeScreenUiState> = _uiState.asStateFlow()
    private var entriesJob: Job? = null
    private var plannedTasksJob: Job? = null
    private var occurrencesJob: Job? = null
    private var selectedDateLoadRequestId: Long = 0L
    private var selectedDateLoadState = TimeScreenDateLoadState(
        requestId = selectedDateLoadRequestId,
        needsOccurrences = !PlatformFeatureFlags.current.minimalModeEnabled,
    )
    init {
        logger.i("TimeViewModel.init", "ViewModel initialized")
        loadData()
        observeActiveEntry()
        observeLastEntry()
    }
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    private fun loadData() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                taskRepository.getAllTasks().collect { tasks ->
                    _uiState.update { state ->
                        state.copy(
                            tasks = tasks,
                            taskPickerTasks = buildTaskPickerTasks(state.plannedTasks, tasks),
                        )
                    }
                }
            } catch (e: Exception) {
                logger.e("TimeViewModel.loadData", "Error loading tasks", e)
            }
        }
        loadEntriesForDate(_uiState.value.selectedDate)
    }
    private fun observeActiveEntry() {
        scope.launch {
            timeEntryRepository.observeActiveTimeEntry().collect { active ->
                _uiState.update { it.copy(activeEntry = active) }
            }
        }
    }
    private fun observeLastEntry() {
        scope.launch {
            timeEntryRepository.getAllTimeEntries().collect { entries ->
                val lastCompleted = entries
                    .filter { it.endedAt != null }
                    .maxByOrNull { it.endedAt ?: it.startedAt }
                _uiState.update { it.copy(lastEntry = lastCompleted) }
            }
        }
    }
    /**
     * Loads all data for [date]: cancels any in-flight loads and (re)starts the
     * entries, planned-tasks, and occurrences collections for that day.
     */
    fun loadEntriesForDate(date: LocalDate) {
        val requestId = ++selectedDateLoadRequestId
        val needsOccurrences = !PlatformFeatureFlags.current.minimalModeEnabled
        logger.i(
            "TimeViewModel.loadEntriesForDate",
            "Starting selected-date load",
            mapOf(
                "requestId" to requestId.toString(),
                "selectedDate" to date.toString(),
                "needsOccurrences" to needsOccurrences.toString(),
                "activeEntryPresent" to (_uiState.value.activeEntry != null).toString(),
            ),
        )
        selectedDateLoadState = TimeScreenDateLoadState(
            requestId = requestId,
            needsOccurrences = needsOccurrences,
        )
        _uiState.update {
            it.copy(
                selectedDate = date,
                isLoading = true,
                isDateContentReady = false,
                error = null,
            )
        }
        entriesJob?.cancel()
        entriesJob = launchTimeEntriesCollection(requestId, date)
        plannedTasksJob?.cancel()
        plannedTasksJob = launchPlannedTasksCollection(requestId, date)
        occurrencesJob?.cancel()
        occurrencesJob = launchOccurrencesCollection(requestId, date)
    }

    private fun markSelectedDateSectionLoaded(requestId: Long, section: TimeScreenDateSection) {
        if (selectedDateLoadState.requestId != requestId) {
            logger.d(
                "TimeViewModel.markSelectedDateSectionLoaded",
                "Ignoring stale selected-date load update",
                mapOf(
                    "requestId" to requestId.toString(),
                    "activeRequestId" to selectedDateLoadState.requestId.toString(),
                    "section" to section.name,
                ),
            )
            return
        }
        selectedDateLoadState = selectedDateLoadState.markSectionLoaded(section)
        if (!selectedDateLoadState.isReady()) {
            logger.d(
                "TimeViewModel.markSelectedDateSectionLoaded",
                "Selected-date load still waiting on other sections",
                mapOf(
                    "requestId" to requestId.toString(),
                    "section" to section.name,
                    "entriesLoaded" to selectedDateLoadState.entriesLoaded.toString(),
                    "plannedTasksLoaded" to selectedDateLoadState.plannedTasksLoaded.toString(),
                    "occurrencesLoaded" to selectedDateLoadState.occurrencesLoaded.toString(),
                    "needsOccurrences" to selectedDateLoadState.needsOccurrences.toString(),
                ),
            )
            return
        }
        _uiState.update {
            if (it.isDateContentReady && !it.isLoading) {
                it
            } else {
                it.copy(
                    isLoading = false,
                    isDateContentReady = true,
                )
            }
        }
        logger.d(
            "TimeViewModel.markSelectedDateSectionLoaded",
            "Selected date content is ready for rendering",
            mapOf(
                "requestId" to requestId.toString(),
                "section" to section.name,
                "selectedDate" to _uiState.value.selectedDate.toString(),
            ),
        )
    }

    /**
     * Switches the screen to the previous day and reloads its data.
     */
    fun navigateToPreviousDay() {
        loadEntriesForDate(_uiState.value.selectedDate.minusDays(1))
    }
    /**
     * Switches the screen to the next day and reloads its data.
     */
    fun navigateToNextDay() {
        loadEntriesForDate(_uiState.value.selectedDate.plusDays(1))
    }
    /**
     * Switches the screen back to today and reloads its data.
     */
    fun navigateToToday() {
        loadEntriesForDate(LocalDate.now())
    }
    /**
     * Starts a time entry for [dimensionId]/[dimensionLabel] (optionally tied to
     * [taskId]) from [startedAt], stops any active entry first, and hands off to
     * the foreground tracking service + widget.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun startTracking(
        dimensionId: String,
        dimensionLabel: String,
        taskId: String? = null,
        startedAt: LocalDateTime = LocalDateTime.now(),
        onSuccess: (() -> Unit)? = null,
    ) {
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() }
        logger.i(
            "TimeViewModel.startTracking",
            "Starting tracking",
            mapOf(
                "dimensionId" to dimensionId,
                "taskId" to (normalizedTaskId ?: "none"),
                "startedAt" to startedAt.toString(),
            ),
        )
        scope.launch {
            try {
                timeEntryRepository.stopActiveTimeEntry()
                timeTrackingController.stopTracking()
                val input = TimeEntryInput(
                    lifeIntentionCategory = dimensionLabel,
                    dimensionId = dimensionId,
                    taskId = normalizedTaskId,
                    startedAt = startedAt,
                )
                val entry = timeEntryRepository.startTimeEntry(input)
                logger.i(
                    "TimeViewModel.startTracking",
                    "Tracking started successfully",
                    mapOf(
                        "entryId" to entry.id,
                        "dimensionId" to dimensionId,
                        "startTime" to startedAt.toString(),
                    ),
                )
                val taskTitle = normalizedTaskId?.let { id ->
                    _uiState.value.tasks.find { it.id == id }?.title
                }
                timeTrackingController.startTracking(
                    taskId = normalizedTaskId,
                    taskTitle = taskTitle ?: dimensionLabel,
                    dimension = dimensionLabel,
                    startTime = startedAt,
                )
                if (_uiState.value.selectedDate == startedAt.toLocalDate()) {
                    loadEntriesForDate(_uiState.value.selectedDate)
                }
                onSuccess?.invoke()
            } catch (e: Exception) {
                logger.e(
                    "TimeViewModel.startTracking",
                    "Failed to start tracking",
                    e,
                    mapOf(
                        "dimensionId" to dimensionId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Stops the running time entry without recording focus (delegates with
     * zero rating).
     */
    fun stopTracking() {
        stopTracking(focusRating = 0.0, focusNote = null)
    }
    /**
     * Stops the running time entry and records the supplied [focusRating] (0..1)
     * plus optional [focusNote].
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun stopTracking(focusRating: Double, focusNote: String?) {
        val safeFocusRating = focusRating.coerceIn(0.0, 1.0)
        val normalizedFocusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() }
        logger.i(
            "TimeViewModel.stopTracking",
            "Stopping tracking",
            mapOf(
                "focusRating" to safeFocusRating.toString(),
                "hasFocusNote" to (normalizedFocusNote != null).toString(),
            ),
        )
        scope.launch {
            try {
                val stoppedEntry = timeTrackingController.stopTrackingAndCompleteTask(
                    focusRating = safeFocusRating,
                    focusNote = normalizedFocusNote,
                )
                timeTrackingController.stopTracking()
                widgetRefresher.refreshWidget()
                logger.i(
                    "TimeViewModel.stopTracking",
                    "Tracking stopped successfully",
                    mapOf(
                        "taskCompleted" to (stoppedEntry?.taskId != null).toString(),
                        "focusRating" to safeFocusRating.toString(),
                    ),
                )
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e("TimeViewModel.stopTracking", "Failed to stop tracking", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Edits an existing time entry ([entryId]) with new dimension/task/time window
     * and focus rating/note, then reloads the day.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun updateTimeEntry(
        entryId: String,
        dimensionId: String,
        dimensionLabel: String,
        taskId: String?,
        startDate: LocalDate,
        startTime: LocalTime,
        endDate: LocalDate?,
        endTime: LocalTime?,
        focusRating: Double?,
        focusNote: String?,
        focusRatedAt: LocalDateTime? = null,
    ) {
        scope.launch {
            try {
                val normalizedFocusRating = focusRating?.coerceIn(0.0, 1.0)
                val normalizedFocusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() }
                val resolvedFocusRatedAt = focusRatedAt
                    ?: if (normalizedFocusRating != null || normalizedFocusNote != null) {
                        LocalDateTime.now()
                    } else {
                        null
                    }
                val input = TimeEntryInput(
                    lifeIntentionCategory = dimensionLabel,
                    dimensionId = dimensionId,
                    taskId = taskId,
                    startedAt = LocalDateTime.of(startDate, startTime),
                    endedAt = if (endDate != null && endTime != null) {
                        LocalDateTime.of(endDate, endTime)
                    } else {
                        null
                    },
                    focusRating = normalizedFocusRating,
                    focusNote = normalizedFocusNote,
                    focusRatedAt = resolvedFocusRatedAt,
                )
                timeEntryRepository.updateTimeEntry(entryId, input)
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e(
                    "TimeViewModel.updateTimeEntry",
                    "Error updating time entry",
                    e,
                    mapOf(
                        "entryId" to entryId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Deletes [entryId] and, if it was linked to a task, reverts that task's
     * completion status, then reloads the day.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun deleteTimeEntry(entryId: String) {
        logger.w("TimeViewModel.deleteTimeEntry", "Deleting time entry", mapOf("entryId" to entryId))
        scope.launch {
            try {
                val entryToDelete = _uiState.value.timeEntries.find { it.id == entryId }
                timeEntryRepository.deleteTimeEntry(entryId)
                entryToDelete?.taskId?.let { taskId ->
                    revertTaskCompletionFromTimeTracking(taskId, entryToDelete)
                }
                logger.i(
                    "TimeViewModel.deleteTimeEntry",
                    "Time entry deleted",
                    mapOf(
                        "entryId" to entryId,
                        "taskReverted" to (entryToDelete?.taskId != null).toString(),
                    ),
                )
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e("TimeViewModel.deleteTimeEntry", "Failed to delete time entry", e, mapOf("entryId" to entryId))
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    private suspend fun revertTaskCompletionFromTimeTracking(taskId: String, timeEntry: TimeEntry) {
        logger.i(
            "TimeViewModel.revertTaskCompletionFromTimeTracking",
            "Reverting task completion from deleted time entry",
            mapOf(
                "taskId" to taskId,
                "timeEntryId" to timeEntry.id,
            ),
        )
        try {
            val task = taskRepository.getTaskById(taskId)
            if (task == null) {
                logger.w("TimeViewModel.revertTaskCompletionFromTimeTracking", "Task not found", mapOf("taskId" to taskId))
                return
            }
            if (task.status != "completed") {
                logger.d(
                    "TimeViewModel.revertTaskCompletionFromTimeTracking",
                    "Task not completed, no revert needed",
                    mapOf(
                        "taskId" to taskId,
                        "status" to task.status,
                    ),
                )
                return
            }
            if (task.recurrenceEnabled) {
                val now = LocalDate.now()
                val dueDate = task.dueDate?.toLocalDate() ?: now
                if (dueDate >= now) {
                    taskRepository.updateTask(
                        taskId,
                        TaskInput(
                            title = task.title,
                            status = "pending",
                        ),
                    )
                    logger.i(
                        "TimeViewModel.revertTaskCompletionFromTimeTracking",
                        "Recurring task reverted to pending",
                        mapOf(
                            "taskId" to taskId,
                            "dueDate" to dueDate.toString(),
                        ),
                    )
                } else {
                    taskRepository.missTask(taskId, "Time tracking cancelled")
                    logger.i(
                        "TimeViewModel.revertTaskCompletionFromTimeTracking",
                        "Past recurring task marked as missed",
                        mapOf(
                            "taskId" to taskId,
                            "dueDate" to dueDate.toString(),
                        ),
                    )
                }
            } else {
                taskRepository.updateTask(
                    taskId,
                    TaskInput(
                        title = task.title,
                        status = "pending",
                    ),
                )
                logger.i(
                    "TimeViewModel.revertTaskCompletionFromTimeTracking",
                    "One-time task reverted to pending",
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.e(
                "TimeViewModel.revertTaskCompletionFromTimeTracking",
                "Failed to revert task completion",
                e,
                mapOf(
                    "taskId" to taskId,
                ),
            )
        }
    }
    /**
     * Creates a finished (already-stopped) time entry spanning the given start/end
     * window and dimension/task/focus, then reloads the day.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun createManualEntry(
        dimensionId: String,
        dimensionLabel: String,
        taskId: String?,
        startDate: LocalDate,
        startTime: LocalTime,
        endDate: LocalDate,
        endTime: LocalTime,
        focusRating: Double? = null,
        focusNote: String? = null,
        onCreated: ((TimeEntry) -> Unit)? = null,
    ) {
        logger.i(
            "TimeViewModel.createManualEntry",
            "Creating manual time entry",
            mapOf(
                "dimensionId" to dimensionId,
                "taskId" to (taskId ?: "none"),
                "start" to LocalDateTime.of(startDate, startTime).toString(),
                "end" to LocalDateTime.of(endDate, endTime).toString(),
            ),
        )
        scope.launch {
            try {
                val normalizedFocusRating = focusRating?.coerceIn(0.0, 1.0)
                val normalizedFocusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() }
                val focusRatedAt = if (normalizedFocusRating != null || normalizedFocusNote != null) {
                    LocalDateTime.now()
                } else {
                    null
                }
                val input = TimeEntryInput(
                    lifeIntentionCategory = dimensionLabel,
                    dimensionId = dimensionId,
                    taskId = taskId,
                    startedAt = LocalDateTime.of(startDate, startTime),
                    endedAt = LocalDateTime.of(endDate, endTime),
                    focusRating = normalizedFocusRating,
                    focusNote = normalizedFocusNote,
                    focusRatedAt = focusRatedAt,
                )
                val created = timeEntryRepository.createTimeEntry(input)
                logger.i(
                    "TimeViewModel.createManualEntry",
                    "Manual time entry created",
                    mapOf("entryId" to created.id, "dimensionId" to dimensionId),
                )
                onCreated?.invoke(created)
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e(
                    "TimeViewModel.createManualEntry",
                    "Error creating manual entry",
                    e,
                    mapOf(
                        "dimensionId" to dimensionId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Clears the current error message shown on the screen.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    /**
     * Resumes tracking from the most recent completed entry (re-opening it as the
     * active entry).
     */
    fun continueLastSession() {
        val lastEntry = _uiState.value.lastEntry ?: return
        logger.i(
            "TimeViewModel.continueLastSession",
            "Continuing last session",
            mapOf(
                "entryId" to lastEntry.id,
                "dimensionId" to (lastEntry.dimensionId ?: "unknown"),
                "taskId" to (lastEntry.taskId ?: "none"),
            ),
        )
        continueEntry(lastEntry.id)
    }
    /**
     * Re-opens a specific finished [entryId] as the active tracking entry and
     * hands off to the foreground tracking service.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun continueEntry(entryId: String) {
        logger.i("TimeViewModel.continueEntry", "Continuing specific entry", mapOf("entryId" to entryId))
        scope.launch {
            try {
                timeEntryRepository.stopActiveTimeEntry()
                timeTrackingController.stopTracking()
                val entry = _uiState.value.timeEntries.find { it.id == entryId }
                    ?: _uiState.value.lastEntry?.takeIf { it.id == entryId }
                if (entry == null) {
                    logger.w("TimeViewModel.continueEntry", "Entry not found", mapOf("entryId" to entryId))
                    return@launch
                }
                val input = TimeEntryInput(
                    lifeIntentionCategory = entry.lifeIntentionCategory,
                    dimensionId = entry.dimensionId,
                    taskId = entry.taskId,
                    startedAt = entry.startedAt,
                    endedAt = null,
                    focusRating = entry.focusRating,
                    focusNote = entry.focusNote,
                    focusRatedAt = entry.focusRatedAt,
                )
                timeEntryRepository.updateTimeEntry(entryId, input)
                val taskTitle = entry.taskId?.let { id ->
                    _uiState.value.tasks.find { it.id == id }?.title
                }
                val dimensionLabel = entry.lifeIntentionCategory
                val dimensionId = entry.dimensionId ?: "dim_unassigned"
                timeTrackingController.startTracking(
                    taskId = entry.taskId,
                    taskTitle = taskTitle ?: dimensionLabel,
                    dimension = dimensionLabel,
                    startTime = entry.startedAt,
                )
                logger.i(
                    "TimeViewModel.continueEntry",
                    "Entry continued successfully",
                    mapOf(
                        "entryId" to entryId,
                        "dimensionId" to dimensionId,
                        "originalStartTime" to entry.startedAt.toString(),
                    ),
                )
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e("TimeViewModel.continueEntry", "Failed to continue entry", e, mapOf("entryId" to entryId))
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Marks [taskId] complete (no explicit timing details) and, for recurring
     * tasks, records the occurrence and schedules the next reminder.
     */
    fun completeTask(taskId: String, note: String?) {
        completeTaskWithDetails(
            taskId = taskId,
            note = note,
            actualCompletedAt = null,
            actualDurationMinutes = null,
        )
    }
    /**
     * Completes [taskId] with explicit [actualCompletedAt]/[actualDurationMinutes],
     * records the occurrence (and next reminder for recurring tasks), then reloads.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun completeTaskWithDetails(
        taskId: String,
        note: String?,
        actualCompletedAt: LocalDateTime?,
        actualDurationMinutes: Int?,
    ) {
        logger.i(
            "TimeViewModel.completeTaskWithDetails",
            "Completing task with explicit details",
            mapOf(
                "taskId" to taskId,
                "hasNote" to (note != null).toString(),
                "actualCompletedAt" to (actualCompletedAt?.toString() ?: "none"),
                "actualDurationMinutes" to (actualDurationMinutes?.toString() ?: "none"),
            ),
        )
        scope.launch {
            try {
                val task = taskRepository.getTaskById(taskId)
                if (task == null) {
                    logger.e("TimeViewModel.completeTaskWithDetails", "Task not found", null, mapOf("taskId" to taskId))
                    return@launch
                }
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceHandler.isFrequencyHabit(task)
                if (task.recurrenceEnabled) {
                    if (!isFrequencyHabit) {
                        taskRepository.completeTask(task.id, note)
                    }
                    val occurrenceDate = task.dueDate?.toLocalDate() ?: LocalDate.now()
                    recordOccurrence(
                        taskId = task.id,
                        dueDate = occurrenceDate,
                        status = "completed",
                        note = note,
                        actualCompletedAt = actualCompletedAt,
                        actualDurationMinutes = actualDurationMinutes,
                    )
                    recurrenceHandler.onTaskCompleted(task, note, null, null)
                    scheduleNextRecurringReminder(task.id, "TimeViewModel.completeTaskWithDetails")
                } else {
                    taskRepository.completeTask(task.id, note)
                    cancelOneTimeReminder(task.id, "TimeViewModel.completeTaskWithDetails")
                }
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e(
                    "TimeViewModel.completeTaskWithDetails",
                    "Failed to complete task with details",
                    e,
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Marks [taskId] skipped: records the occurrence and applies decay for
     * recurring tasks, otherwise just cancels its reminder, then reloads.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun skipTask(taskId: String, note: String?) {
        logger.i(
            "TimeViewModel.skipTask",
            "Skipping task",
            mapOf(
                "taskId" to taskId,
                "hasNote" to (note != null).toString(),
            ),
        )
        scope.launch {
            try {
                val task = taskRepository.getTaskById(taskId)
                if (task == null) {
                    logger.e("TimeViewModel.skipTask", "Task not found", null, mapOf("taskId" to taskId))
                    return@launch
                }
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceHandler.isFrequencyHabit(task)
                if (!isFrequencyHabit) {
                    taskRepository.skipTask(taskId, note)
                }
                if (task.recurrenceEnabled) {
                    recordOccurrence(taskId, task.dueDate?.toLocalDate() ?: LocalDate.now(), "skipped", note)
                    recurrenceHandler.onTaskSkipped(task, note, null, null)
                    scheduleNextRecurringReminder(taskId, "TimeViewModel.skipTask")
                    logger.i(
                        "TimeViewModel.skipTask",
                        "Recurring task skipped, decay applied",
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                } else {
                    cancelOneTimeReminder(taskId, "TimeViewModel.skipTask")
                }
                logger.i(
                    "TimeViewModel.skipTask",
                    "Task skipped successfully",
                    mapOf(
                        "taskId" to taskId,
                        "recurring" to task.recurrenceEnabled.toString(),
                    ),
                )
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e(
                    "TimeViewModel.skipTask",
                    "Failed to skip task",
                    e,
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Marks [taskId] missed: records the occurrence and applies decay for
     * recurring tasks, otherwise just cancels its reminder, then reloads.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun missTask(taskId: String, note: String?) {
        logger.i(
            "TimeViewModel.missTask",
            "Missing task",
            mapOf(
                "taskId" to taskId,
                "hasNote" to (note != null).toString(),
            ),
        )
        scope.launch {
            try {
                val task = taskRepository.getTaskById(taskId)
                if (task == null) {
                    logger.e("TimeViewModel.missTask", "Task not found", null, mapOf("taskId" to taskId))
                    return@launch
                }
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceHandler.isFrequencyHabit(task)
                if (!isFrequencyHabit) {
                    taskRepository.missTask(taskId, note)
                }
                if (task.recurrenceEnabled) {
                    recordOccurrence(taskId, task.dueDate?.toLocalDate() ?: LocalDate.now(), "missed", note)
                    recurrenceHandler.onTaskMissed(task, note, null, null)
                    scheduleNextRecurringReminder(taskId, "TimeViewModel.missTask")
                    logger.i(
                        "TimeViewModel.missTask",
                        "Recurring task missed, decay applied",
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                } else {
                    cancelOneTimeReminder(taskId, "TimeViewModel.missTask")
                }
                logger.i(
                    "TimeViewModel.missTask",
                    "Task missed successfully",
                    mapOf(
                        "taskId" to taskId,
                        "recurring" to task.recurrenceEnabled.toString(),
                    ),
                )
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e(
                    "TimeViewModel.missTask",
                    "Failed to miss task",
                    e,
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Archives [taskId] (removes it from active lists) and reloads the day.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun archiveTask(taskId: String) {
        logger.i("TimeViewModel.archiveTask", "Archiving task", mapOf("taskId" to taskId))
        scope.launch {
            try {
                taskRepository.archiveTask(taskId)
                logger.i(
                    "TimeViewModel.archiveTask",
                    "Task archived successfully",
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e(
                    "TimeViewModel.archiveTask",
                    "Failed to archive task",
                    e,
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Permanently deletes [taskId] (canceling its reminder) and reloads the day.
     */
    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    fun deleteTask(taskId: String) {
        logger.w("TimeViewModel.deleteTask", "Deleting task from time screen", mapOf("taskId" to taskId))
        scope.launch {
            try {
                cancelOneTimeReminder(taskId, "TimeViewModel.deleteTask")
                taskRepository.deleteTask(taskId)
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (e: Exception) {
                logger.e(
                    "TimeViewModel.deleteTask",
                    "Failed to delete task",
                    e,
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    private suspend fun recordOccurrence(
        taskId: String,
        dueDate: LocalDate,
        status: String,
        note: String?,
        actualCompletedAt: LocalDateTime? = null,
        actualDurationMinutes: Int? = null,
    ) {
        logger.d(
            "TimeViewModel.recordOccurrence",
            "RECORDING_OCCURRENCE_START",
            mapOf(
                "taskId" to taskId,
                "dueDate" to dueDate.toString(),
                "status" to status,
                "note" to (note ?: "null"),
                "actualCompletedAt" to (actualCompletedAt?.toString() ?: "null"),
                "actualDurationMinutes" to (actualDurationMinutes?.toString() ?: "null"),
            ),
        )
        val now = LocalDateTime.now()
        val occurrence = TaskOccurrence(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            occurrenceDate = dueDate.toString(),
            status = status,
            statusNote = note,
            completedAt = if (status == "completed") now.toString() else null,
            actualCompletedAt = actualCompletedAt,
            actualDurationMinutes = actualDurationMinutes,
            dueDate = dueDate.atStartOfDay(),
            createdAt = now,
        )
        logger.d(
            "TimeViewModel.recordOccurrence",
            "CREATED_OCCURRENCE_OBJECT",
            mapOf(
                "occurrenceId" to occurrence.id,
                "taskId" to taskId,
                "status" to status,
            ),
        )
        taskOccurrenceRepository.recordOccurrence(occurrence)
        logger.d(
            "TimeViewModel.recordOccurrence",
            "OCCURRENCE_RECORDED_SUCCESS",
            mapOf(
                "occurrenceId" to occurrence.id,
                "taskId" to taskId,
            ),
        )
    }
    private suspend fun scheduleNextRecurringReminder(taskId: String, source: String) {
        val updatedTask = taskRepository.getTaskById(taskId)
        if (updatedTask == null || !updatedTask.recurrenceEnabled) {
            return
        }
        try {
            reminderScheduler.scheduleForTask(updatedTask)
        } catch (e: Exception) {
            logger.e(source, "Failed to schedule next recurring reminder", e, mapOf("taskId" to taskId))
        }
    }
    private suspend fun cancelOneTimeReminder(taskId: String, source: String) {
        try {
            reminderScheduler.cancelForTask(taskId)
        } catch (e: Exception) {
            logger.e(source, "Failed to cancel one-time reminder", e, mapOf("taskId" to taskId))
        }
    }

    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    private fun launchTimeEntriesCollection(requestId: Long, date: LocalDate): Job =
        scope.launch {
            var receivedInitialEntries = false
            try {
                timeEntryRepository.getTimeEntriesForDate(date).collect { entries ->
                    _uiState.update {
                        it.copy(
                            timeEntries = entries.sortedBy { e -> e.startedAt },
                        )
                    }
                    if (!receivedInitialEntries) {
                        receivedInitialEntries = true
                        logger.d(
                            "TimeViewModel.loadEntriesForDate",
                            "Initial time entries received",
                            mapOf(
                                "requestId" to requestId.toString(),
                                "selectedDate" to date.toString(),
                                "entryCount" to entries.size,
                            ),
                        )
                        markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.ENTRIES)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.e("TimeViewModel.loadEntriesForDate", "Error loading time entries", e)
                _uiState.update { it.copy(error = e.message) }
                markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.ENTRIES)
            }
        }

    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    private fun launchPlannedTasksCollection(requestId: Long, date: LocalDate): Job =
        scope.launch {
            var receivedInitialPlannedTasks = false
            try {
                val useTodaysTasks = shouldUseTodaysPlannedTasks(date)
                val plannedTasksFlow = if (useTodaysTasks) {
                    taskRepository.getTodaysTasks()
                } else {
                    taskRepository.getTasksDueOn(date)
                }
                plannedTasksFlow.collect { tasks ->
                    val filtered = if (PlatformFeatureFlags.current.minimalModeEnabled) {
                        tasks.filter { !it.recurrenceEnabled }
                    } else {
                        tasks
                    }
                    _uiState.update { state ->
                        state.copy(
                            plannedTasks = filtered,
                            taskPickerTasks = buildTaskPickerTasks(filtered, state.tasks),
                        )
                    }
                    if (!receivedInitialPlannedTasks) {
                        receivedInitialPlannedTasks = true
                        logger.d(
                            "TimeViewModel.loadEntriesForDate",
                            "Initial planned tasks received",
                            mapOf(
                                "requestId" to requestId.toString(),
                                "selectedDate" to date.toString(),
                                "plannedTasksSource" to if (useTodaysTasks) "today" else "due_on_date",
                                "plannedTaskCount" to filtered.size,
                            ),
                        )
                        markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.PLANNED_TASKS)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.e("TimeViewModel.loadEntriesForDate", "Failed to load planned tasks", e)
                _uiState.update { it.copy(error = e.message) }
                markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.PLANNED_TASKS)
            }
        }

    @Suppress("TooGenericExceptionCaught")  // Intentional: multi-operation try block; any repo call can throw
    private fun launchOccurrencesCollection(requestId: Long, date: LocalDate): Job? {
        if (PlatformFeatureFlags.current.minimalModeEnabled) {
            _uiState.update { it.copy(pastOccurrences = emptyList()) }
            logger.d(
                "TimeViewModel.loadEntriesForDate",
                "Skipped occurrences load in minimal mode",
                mapOf(
                    "requestId" to requestId.toString(),
                    "selectedDate" to date.toString(),
                ),
            )
            markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.OCCURRENCES)
            return null
        }
        return scope.launch {
            var receivedInitialOccurrences = false
            try {
                taskOccurrenceRepository.getOccurrencesForDate(date).collect { occurrences ->
                    _uiState.update { it.copy(pastOccurrences = occurrences) }
                    if (!receivedInitialOccurrences) {
                        receivedInitialOccurrences = true
                        logger.d(
                            "TimeViewModel.loadEntriesForDate",
                            "Initial occurrences received",
                            mapOf(
                                "requestId" to requestId.toString(),
                                "selectedDate" to date.toString(),
                                "occurrenceCount" to occurrences.size,
                            ),
                        )
                        markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.OCCURRENCES)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.e("TimeViewModel.loadEntriesForDate", "Failed to load past occurrences", e)
                _uiState.update { it.copy(error = e.message) }
                markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.OCCURRENCES)
            }
        }
    }

    /**
     * Cancels the scope this ViewModel runs on.
     *
     * The ViewModel owns the work it starts on [scope] and nothing else can join it, so a caller that
     * retains it past the screen's life (see `retainSharedViewModel` on Android and the desktop's own
     * holder) has to be able to stop that work. A caller that passed its own [scope] has that scope
     * cancelled here too - it is the same scope the ViewModel runs on, so leaving it alive would strand
     * its collectors.
     */
    fun dispose() {
        scope.cancel()
    }
}

/**
 * Builds the Time screen's ViewModel from [LocalDataLayer], the platform-installed repositories.
 *
 * The same construction path the desktop takes: one factory reading the installed data layer, so neither
 * platform gets a second hand-wiring of the Time screen's collaborators. `timeTrackingController` is
 * substituted here rather than taking the app-only use case, which is what lets the platform decide how a
 * session is started and stopped.
 */
fun timeViewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): TimeViewModel {
    val dataLayer = LocalDataLayer.current
    return TimeViewModel(
        timeEntryRepository = dataLayer.timeEntries,
        taskRepository = dataLayer.tasks,
        taskOccurrenceRepository = dataLayer.taskOccurrences,
        recurrenceHandler = dataLayer.recurrenceHandler,
        reminderScheduler = dataLayer.reminderScheduler,
        timeTrackingController = dataLayer.timeTrackingController,
        widgetRefresher = dataLayer.timeTrackingWidgetRefresher,
        scope = scope,
    )
}
