//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.service.TrackingService
import io.payanam.usecase.RecurrenceManager
import io.payanam.usecase.TimeTrackingUseCase
import io.payanam.widget.TimeTrackingWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
/**
 * TimeScreenUiState.
 */
data class TimeScreenUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val timeEntries: List<TimeEntry> = emptyList(),
    val activeEntry: TimeEntry? = null,
    val tasks: List<Task> = emptyList(),
    val taskPickerTasks: List<Task> = emptyList(),
    val plannedTasks: List<Task> = emptyList(),
    val pastOccurrences: List<TaskOccurrence> = emptyList(),
    val lastEntry: TimeEntry? = null,
    val isLoading: Boolean = true,
    val isDateContentReady: Boolean = false,
    val error: String? = null,
)

internal fun shouldUseTodaysPlannedTasks(selectedDate: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
    selectedDate == today

internal fun isTimeScreenDateContentReady(
    entriesLoaded: Boolean,
    plannedTasksLoaded: Boolean,
    occurrencesLoaded: Boolean,
    needsOccurrences: Boolean,
): Boolean = entriesLoaded && plannedTasksLoaded && (occurrencesLoaded || !needsOccurrences)

private data class SelectedDateLoadState(
    val requestId: Long,
    val needsOccurrences: Boolean,
    val entriesLoaded: Boolean = false,
    val plannedTasksLoaded: Boolean = false,
    val occurrencesLoaded: Boolean = false,
) {
    /**
     * Mark entries loaded.
     */
    fun markEntriesLoaded(): SelectedDateLoadState = copy(entriesLoaded = true)
    /**
     * Mark planned tasks loaded.
     */
    fun markPlannedTasksLoaded(): SelectedDateLoadState = copy(plannedTasksLoaded = true)
    /**
     * Mark occurrences loaded.
     */
    fun markOccurrencesLoaded(): SelectedDateLoadState = copy(occurrencesLoaded = true)

    /**
     * Is ready.
     */
    fun isReady(): Boolean = isTimeScreenDateContentReady(
        entriesLoaded = entriesLoaded,
        plannedTasksLoaded = plannedTasksLoaded,
        occurrencesLoaded = occurrencesLoaded,
        needsOccurrences = needsOccurrences,
    )
}

@HiltViewModel
/**
 * TimeViewModel.
 */
class TimeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeEntryRepository: TimeEntryRepository,
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val recurrenceManager: RecurrenceManager,
    private val notificationScheduler: NotificationScheduler,
    private val timeTrackingUseCase: TimeTrackingUseCase,
) : ViewModel() {
    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(TimeScreenUiState())
    val uiState: StateFlow<TimeScreenUiState> = _uiState.asStateFlow()
    private var entriesJob: Job? = null
    private var plannedTasksJob: Job? = null
    private var occurrencesJob: Job? = null
    private var selectedDateLoadRequestId: Long = 0L
    private var selectedDateLoadState = SelectedDateLoadState(
        requestId = selectedDateLoadRequestId,
        needsOccurrences = !FeatureFlags.minimalModeEnabled,
    )
    init {
        logger.i("TimeViewModel.init", "ViewModel initialized")
        loadData()
        observeActiveEntry()
        observeLastEntry()
    }
    private fun loadData() {
        viewModelScope.launch {
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TimeViewModel.loadData", "Error loading tasks", e)
            }
        }
        loadEntriesForDate(_uiState.value.selectedDate)
    }
    private fun observeActiveEntry() {
        viewModelScope.launch {
            timeEntryRepository.observeActiveTimeEntry().collect { active ->
                _uiState.update { it.copy(activeEntry = active) }
            }
        }
    }
    private fun observeLastEntry() {
        viewModelScope.launch {
            timeEntryRepository.getAllTimeEntries().collect { entries ->
                val lastCompleted = entries
                    .filter { it.endedAt != null }
                    .maxByOrNull { it.endedAt ?: it.startedAt }
                _uiState.update { it.copy(lastEntry = lastCompleted) }
            }
        }
    }
    /**
     * Load entries for date.
     */
    fun loadEntriesForDate(date: LocalDate) {
        val requestId = ++selectedDateLoadRequestId
        val needsOccurrences = !FeatureFlags.minimalModeEnabled
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
        selectedDateLoadState = SelectedDateLoadState(
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
        selectedDateLoadState = when (section) {
            TimeScreenDateSection.ENTRIES -> selectedDateLoadState.markEntriesLoaded()
            TimeScreenDateSection.PLANNED_TASKS -> selectedDateLoadState.markPlannedTasksLoaded()
            TimeScreenDateSection.OCCURRENCES -> selectedDateLoadState.markOccurrencesLoaded()
        }
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

    private enum class TimeScreenDateSection {
        ENTRIES,
        PLANNED_TASKS,
        OCCURRENCES,
    }
    /**
     * Navigate to previous day.
     */
    fun navigateToPreviousDay() {
        loadEntriesForDate(_uiState.value.selectedDate.minusDays(1))
    }
    /**
     * Navigate to next day.
     */
    fun navigateToNextDay() {
        loadEntriesForDate(_uiState.value.selectedDate.plusDays(1))
    }
    /**
     * Navigate to today.
     */
    fun navigateToToday() {
        loadEntriesForDate(LocalDate.now())
    }
    private fun buildTaskPickerTasks(dueTasks: List<Task>, allTasks: List<Task>): List<Task> {
        val duePending = dueTasks.filter { it.status == "pending" }.distinctBy { it.id }
        val allPending = allTasks.filter { it.status == "pending" }.distinctBy { it.id }
        return (duePending + allPending).distinctBy { it.id }
    }
    /**
     * Start tracking.
     */
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
        viewModelScope.launch {
            try {
                timeEntryRepository.stopActiveTimeEntry()
                TrackingService.stopTracking(context)
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
                TrackingService.startTracking(
                    context = context,
                    taskId = normalizedTaskId,
                    taskTitle = taskTitle ?: dimensionLabel,
                    dimension = dimensionLabel,
                    startTime = startedAt.toString(),
                )
                TimeTrackingWidgetProvider.requestUpdate(context)
                if (_uiState.value.selectedDate == startedAt.toLocalDate()) {
                    loadEntriesForDate(_uiState.value.selectedDate)
                }
                onSuccess?.invoke()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
     * Stop tracking.
     */
    fun stopTracking() {
        stopTracking(focusRating = 0.0, focusNote = null)
    }
    /**
     * Stop tracking.
     */
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
        viewModelScope.launch {
            try {
                val stoppedEntry = timeTrackingUseCase.stopTrackingAndCompleteTask(
                    focusRating = safeFocusRating,
                    focusNote = normalizedFocusNote,
                )
                TrackingService.stopTracking(context)
                TimeTrackingWidgetProvider.requestUpdate(context)
                logger.i(
                    "TimeViewModel.stopTracking",
                    "Tracking stopped successfully",
                    mapOf(
                        "taskCompleted" to (stoppedEntry?.taskId != null).toString(),
                        "focusRating" to safeFocusRating.toString(),
                    ),
                )
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TimeViewModel.stopTracking", "Failed to stop tracking", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Update time entry.
     */
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
        viewModelScope.launch {
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
     * Delete time entry.
     */
    fun deleteTimeEntry(entryId: String) {
        logger.w("TimeViewModel.deleteTimeEntry", "Deleting time entry", mapOf("entryId" to entryId))
        viewModelScope.launch {
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
     * Create manual entry.
     */
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
        viewModelScope.launch {
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
     * Clear error.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    /**
     * Continue last session.
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
     * Continue entry.
     */
    fun continueEntry(entryId: String) {
        logger.i("TimeViewModel.continueEntry", "Continuing specific entry", mapOf("entryId" to entryId))
        viewModelScope.launch {
            try {
                timeEntryRepository.stopActiveTimeEntry()
                TrackingService.stopTracking(context)
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
                TrackingService.startTracking(
                    context = context,
                    taskId = entry.taskId,
                    taskTitle = taskTitle ?: dimensionLabel,
                    dimension = dimensionLabel,
                    startTime = entry.startedAt.toString(),
                )
                TimeTrackingWidgetProvider.requestUpdate(context)
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TimeViewModel.continueEntry", "Failed to continue entry", e, mapOf("entryId" to entryId))
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    /**
     * Complete task.
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
     * Complete task with details.
     */
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
        viewModelScope.launch {
            try {
                val task = taskRepository.getTaskById(taskId)
                if (task == null) {
                    logger.e("TimeViewModel.completeTaskWithDetails", "Task not found", null, mapOf("taskId" to taskId))
                    return@launch
                }
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
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
                    recurrenceManager.onTaskCompleted(task, note, null)
                    scheduleNextRecurringReminder(task.id, "TimeViewModel.completeTaskWithDetails")
                } else {
                    taskRepository.completeTask(task.id, note)
                    cancelOneTimeReminder(task.id, "TimeViewModel.completeTaskWithDetails")
                }
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
     * Skip task.
     */
    fun skipTask(taskId: String, note: String?) {
        logger.i(
            "TimeViewModel.skipTask",
            "Skipping task",
            mapOf(
                "taskId" to taskId,
                "hasNote" to (note != null).toString(),
            ),
        )
        viewModelScope.launch {
            try {
                val task = taskRepository.getTaskById(taskId)
                if (task == null) {
                    logger.e("TimeViewModel.skipTask", "Task not found", null, mapOf("taskId" to taskId))
                    return@launch
                }
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
                if (!isFrequencyHabit) {
                    taskRepository.skipTask(taskId, note)
                }
                if (task.recurrenceEnabled) {
                    recordOccurrence(taskId, task.dueDate?.toLocalDate() ?: LocalDate.now(), "skipped", note)
                    recurrenceManager.onTaskSkipped(task, note, null)
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
     * Miss task.
     */
    fun missTask(taskId: String, note: String?) {
        logger.i(
            "TimeViewModel.missTask",
            "Missing task",
            mapOf(
                "taskId" to taskId,
                "hasNote" to (note != null).toString(),
            ),
        )
        viewModelScope.launch {
            try {
                val task = taskRepository.getTaskById(taskId)
                if (task == null) {
                    logger.e("TimeViewModel.missTask", "Task not found", null, mapOf("taskId" to taskId))
                    return@launch
                }
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
                if (!isFrequencyHabit) {
                    taskRepository.missTask(taskId, note)
                }
                if (task.recurrenceEnabled) {
                    recordOccurrence(taskId, task.dueDate?.toLocalDate() ?: LocalDate.now(), "missed", note)
                    recurrenceManager.onTaskMissed(task, note, null)
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
     * Archive task.
     */
    fun archiveTask(taskId: String) {
        logger.i("TimeViewModel.archiveTask", "Archiving task", mapOf("taskId" to taskId))
        viewModelScope.launch {
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
     * Delete task.
     */
    fun deleteTask(taskId: String) {
        logger.w("TimeViewModel.deleteTask", "Deleting task from time screen", mapOf("taskId" to taskId))
        viewModelScope.launch {
            try {
                cancelOneTimeReminder(taskId, "TimeViewModel.deleteTask")
                taskRepository.deleteTask(taskId)
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
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
            notificationScheduler.scheduleForTask(updatedTask)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(source, "Failed to schedule next recurring reminder", e, mapOf("taskId" to taskId))
        }
    }
    private suspend fun cancelOneTimeReminder(taskId: String, source: String) {
        try {
            notificationScheduler.cancelForTask(taskId)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(source, "Failed to cancel one-time reminder", e, mapOf("taskId" to taskId))
        }
    }

    private fun launchTimeEntriesCollection(requestId: Long, date: LocalDate): Job =
        viewModelScope.launch {
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                if (e is CancellationException) throw e
                logger.e("TimeViewModel.loadEntriesForDate", "Error loading time entries", e)
                _uiState.update { it.copy(error = e.message) }
                markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.ENTRIES)
            }
        }

    private fun launchPlannedTasksCollection(requestId: Long, date: LocalDate): Job =
        viewModelScope.launch {
            var receivedInitialPlannedTasks = false
            try {
                val useTodaysTasks = shouldUseTodaysPlannedTasks(date)
                val plannedTasksFlow = if (useTodaysTasks) {
                    taskRepository.getTodaysTasks()
                } else {
                    taskRepository.getTasksDueOn(date)
                }
                plannedTasksFlow.collect { tasks ->
                    val filtered = if (FeatureFlags.minimalModeEnabled) {
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                if (e is CancellationException) throw e
                logger.e("TimeViewModel.loadEntriesForDate", "Failed to load planned tasks", e)
                _uiState.update { it.copy(error = e.message) }
                markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.PLANNED_TASKS)
            }
        }

    private fun launchOccurrencesCollection(requestId: Long, date: LocalDate): Job? {
        if (FeatureFlags.minimalModeEnabled) {
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
        return viewModelScope.launch {
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
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                if (e is CancellationException) throw e
                logger.e("TimeViewModel.loadEntriesForDate", "Failed to load past occurrences", e)
                _uiState.update { it.copy(error = e.message) }
                markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.OCCURRENCES)
            }
        }
    }
}
