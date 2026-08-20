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
    /** Selected date. */
    val selectedDate: LocalDate = LocalDate.now(),
    /** Time entries. */
    val timeEntries: List<TimeEntry> = emptyList(),
    /** Active entry. */
    val activeEntry: TimeEntry? = null,
    /** Tasks. */
    val tasks: List<Task> = emptyList(),
    /** Task picker tasks. */
    val taskPickerTasks: List<Task> = emptyList(),
    /** Planned tasks. */
    val plannedTasks: List<Task> = emptyList(),
    /** Past occurrences. */
    val pastOccurrences: List<TaskOccurrence> = emptyList(),
    /** Last entry. */
    val lastEntry: TimeEntry? = null,
    /** Is loading. */
    val isLoading: Boolean = true,
    /** Is date content ready. */
    val isDateContentReady: Boolean = false,
    /** Error. */
    val error: String? = null,
)

internal fun shouldUseTodaysPlannedTasks(selectedDate: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
    selectedDate == today

internal fun isTimeScreenDateContentReady(
    /** Entries loaded. */
    entriesLoaded: Boolean,
    /** Planned tasks loaded. */
    plannedTasksLoaded: Boolean,
    /** Occurrences loaded. */
    occurrencesLoaded: Boolean,
    /** Needs occurrences. */
    needsOccurrences: Boolean,
): Boolean = entriesLoaded && plannedTasksLoaded && (occurrencesLoaded || !needsOccurrences)

private data class SelectedDateLoadState(
    /** Request id. */
    val requestId: Long,
    /** Needs occurrences. */
    val needsOccurrences: Boolean,
    /** Entries loaded. */
    val entriesLoaded: Boolean = false,
    /** Planned tasks loaded. */
    val plannedTasksLoaded: Boolean = false,
    /** Occurrences loaded. */
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
    /** Ui state. */
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
        /** Load data. */
        loadData()
        /** Observe active entry. */
        observeActiveEntry()
        /** Observe last entry. */
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
        /** Load entries for date. */
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
                /** Last completed. */
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
        /** Request id. */
        val requestId = ++selectedDateLoadRequestId
        /** Needs occurrences. */
        val needsOccurrences = !FeatureFlags.minimalModeEnabled
        logger.i(
            "TimeViewModel.loadEntriesForDate",
            "Starting selected-date load",
            /** Map of. */
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
        /** If. */
        if (selectedDateLoadState.requestId != requestId) {
            logger.d(
                "TimeViewModel.markSelectedDateSectionLoaded",
                "Ignoring stale selected-date load update",
                /** Map of. */
                mapOf(
                    "requestId" to requestId.toString(),
                    "activeRequestId" to selectedDateLoadState.requestId.toString(),
                    "section" to section.name,
                ),
            )
            /** Return. */
            return
        }
        selectedDateLoadState = when (section) {
            TimeScreenDateSection.ENTRIES -> selectedDateLoadState.markEntriesLoaded()
            TimeScreenDateSection.PLANNED_TASKS -> selectedDateLoadState.markPlannedTasksLoaded()
            TimeScreenDateSection.OCCURRENCES -> selectedDateLoadState.markOccurrencesLoaded()
        }
        /** If. */
        if (!selectedDateLoadState.isReady()) {
            logger.d(
                "TimeViewModel.markSelectedDateSectionLoaded",
                "Selected-date load still waiting on other sections",
                /** Map of. */
                mapOf(
                    "requestId" to requestId.toString(),
                    "section" to section.name,
                    "entriesLoaded" to selectedDateLoadState.entriesLoaded.toString(),
                    "plannedTasksLoaded" to selectedDateLoadState.plannedTasksLoaded.toString(),
                    "occurrencesLoaded" to selectedDateLoadState.occurrencesLoaded.toString(),
                    "needsOccurrences" to selectedDateLoadState.needsOccurrences.toString(),
                ),
            )
            /** Return. */
            return
        }
        _uiState.update {
            /** If. */
            if (it.isDateContentReady && !it.isLoading) {
                /** It. */
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
            /** Map of. */
            mapOf(
                "requestId" to requestId.toString(),
                "section" to section.name,
                "selectedDate" to _uiState.value.selectedDate.toString(),
            ),
        )
    }

    private enum class TimeScreenDateSection {
        /** Entries. */
        ENTRIES,
        /** Planned tasks. */
        PLANNED_TASKS,
        /** Occurrences. */
        OCCURRENCES,
    }
    /**
     * Navigate to previous day.
     */
    fun navigateToPreviousDay() {
        /** Load entries for date. */
        loadEntriesForDate(_uiState.value.selectedDate.minusDays(1))
    }
    /**
     * Navigate to next day.
     */
    fun navigateToNextDay() {
        /** Load entries for date. */
        loadEntriesForDate(_uiState.value.selectedDate.plusDays(1))
    }
    /**
     * Navigate to today.
     */
    fun navigateToToday() {
        /** Load entries for date. */
        loadEntriesForDate(LocalDate.now())
    }
    private fun buildTaskPickerTasks(dueTasks: List<Task>, allTasks: List<Task>): List<Task> {
        /** Due pending. */
        val duePending = dueTasks.filter { it.status == "pending" }.distinctBy { it.id }
        /** All pending. */
        val allPending = allTasks.filter { it.status == "pending" }.distinctBy { it.id }
        /** Return. */
        return (duePending + allPending).distinctBy { it.id }
    }
    /**
     * Start tracking.
     */
    fun startTracking(
        /** Dimension id. */
        dimensionId: String,
        /** Dimension label. */
        dimensionLabel: String,
        taskId: String? = null,
        startedAt: LocalDateTime = LocalDateTime.now(),
        onSuccess: (() -> Unit)? = null,
    ) {
        /** Normalized task id. */
        val normalizedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() }
        logger.i(
            "TimeViewModel.startTracking",
            "Starting tracking",
            /** Map of. */
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
                /** Input. */
                val input = TimeEntryInput(
                    lifeIntentionCategory = dimensionLabel,
                    dimensionId = dimensionId,
                    taskId = normalizedTaskId,
                    startedAt = startedAt,
                )
                /** Entry. */
                val entry = timeEntryRepository.startTimeEntry(input)
                logger.i(
                    "TimeViewModel.startTracking",
                    "Tracking started successfully",
                    /** Map of. */
                    mapOf(
                        "entryId" to entry.id,
                        "dimensionId" to dimensionId,
                        "startTime" to startedAt.toString(),
                    ),
                )
                /** Task title. */
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
                /** If. */
                if (_uiState.value.selectedDate == startedAt.toLocalDate()) {
                    /** Load entries for date. */
                    loadEntriesForDate(_uiState.value.selectedDate)
                }
                onSuccess?.invoke()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TimeViewModel.startTracking",
                    "Failed to start tracking",
                    /** E. */
                    e,
                    /** Map of. */
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
        /** Stop tracking. */
        stopTracking(focusRating = 0.0, focusNote = null)
    }
    /**
     * Stop tracking.
     */
    fun stopTracking(focusRating: Double, focusNote: String?) {
        /** Safe focus rating. */
        val safeFocusRating = focusRating.coerceIn(0.0, 1.0)
        /** Normalized focus note. */
        val normalizedFocusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() }
        logger.i(
            "TimeViewModel.stopTracking",
            "Stopping tracking",
            /** Map of. */
            mapOf(
                "focusRating" to safeFocusRating.toString(),
                "hasFocusNote" to (normalizedFocusNote != null).toString(),
            ),
        )
        viewModelScope.launch {
            try {
                /** Stopped entry. */
                val stoppedEntry = timeTrackingUseCase.stopTrackingAndCompleteTask(
                    focusRating = safeFocusRating,
                    focusNote = normalizedFocusNote,
                )
                TrackingService.stopTracking(context)
                TimeTrackingWidgetProvider.requestUpdate(context)
                logger.i(
                    "TimeViewModel.stopTracking",
                    "Tracking stopped successfully",
                    /** Map of. */
                    mapOf(
                        "taskCompleted" to (stoppedEntry?.taskId != null).toString(),
                        "focusRating" to safeFocusRating.toString(),
                    ),
                )
                /** Load entries for date. */
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
        /** Entry id. */
        entryId: String,
        /** Dimension id. */
        dimensionId: String,
        /** Dimension label. */
        dimensionLabel: String,
        taskId: String?,
        /** Start date. */
        startDate: LocalDate,
        /** Start time. */
        startTime: LocalTime,
        endDate: LocalDate?,
        endTime: LocalTime?,
        focusRating: Double?,
        focusNote: String?,
        focusRatedAt: LocalDateTime? = null,
    ) {
        viewModelScope.launch {
            try {
                /** Normalized focus rating. */
                val normalizedFocusRating = focusRating?.coerceIn(0.0, 1.0)
                /** Normalized focus note. */
                val normalizedFocusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() }
                /** Resolved focus rated at. */
                val resolvedFocusRatedAt = focusRatedAt
                    ?: if (normalizedFocusRating != null || normalizedFocusNote != null) {
                        LocalDateTime.now()
                    } else {
                        /** Null. */
                        null
                    }
                /** Input. */
                val input = TimeEntryInput(
                    lifeIntentionCategory = dimensionLabel,
                    dimensionId = dimensionId,
                    taskId = taskId,
                    startedAt = LocalDateTime.of(startDate, startTime),
                    endedAt = if (endDate != null && endTime != null) {
                        LocalDateTime.of(endDate, endTime)
                    } else {
                        /** Null. */
                        null
                    },
                    focusRating = normalizedFocusRating,
                    focusNote = normalizedFocusNote,
                    focusRatedAt = resolvedFocusRatedAt,
                )
                timeEntryRepository.updateTimeEntry(entryId, input)
                /** Load entries for date. */
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TimeViewModel.updateTimeEntry",
                    "Error updating time entry",
                    /** E. */
                    e,
                    /** Map of. */
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
                /** Entry to delete. */
                val entryToDelete = _uiState.value.timeEntries.find { it.id == entryId }
                timeEntryRepository.deleteTimeEntry(entryId)
                entryToDelete?.taskId?.let { taskId ->
                    /** Revert task completion from time tracking. */
                    revertTaskCompletionFromTimeTracking(taskId, entryToDelete)
                }
                logger.i(
                    "TimeViewModel.deleteTimeEntry",
                    "Time entry deleted",
                    /** Map of. */
                    mapOf(
                        "entryId" to entryId,
                        "taskReverted" to (entryToDelete?.taskId != null).toString(),
                    ),
                )
                /** Load entries for date. */
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
            /** Map of. */
            mapOf(
                "taskId" to taskId,
                "timeEntryId" to timeEntry.id,
            ),
        )
        try {
            /** Task. */
            val task = taskRepository.getTaskById(taskId)
            /** If. */
            if (task == null) {
                logger.w("TimeViewModel.revertTaskCompletionFromTimeTracking", "Task not found", mapOf("taskId" to taskId))
                /** Return. */
                return
            }
            /** If. */
            if (task.status != "completed") {
                logger.d(
                    "TimeViewModel.revertTaskCompletionFromTimeTracking",
                    "Task not completed, no revert needed",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "status" to task.status,
                    ),
                )
                /** Return. */
                return
            }
            /** If. */
            if (task.recurrenceEnabled) {
                /** Now. */
                val now = LocalDate.now()
                /** Due date. */
                val dueDate = task.dueDate?.toLocalDate() ?: now
                /** If. */
                if (dueDate >= now) {
                    taskRepository.updateTask(
                        /** Task id. */
                        taskId,
                        /** Task input. */
                        TaskInput(
                            title = task.title,
                            status = "pending",
                        ),
                    )
                    logger.i(
                        "TimeViewModel.revertTaskCompletionFromTimeTracking",
                        "Recurring task reverted to pending",
                        /** Map of. */
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
                        /** Map of. */
                        mapOf(
                            "taskId" to taskId,
                            "dueDate" to dueDate.toString(),
                        ),
                    )
                }
            } else {
                taskRepository.updateTask(
                    /** Task id. */
                    taskId,
                    /** Task input. */
                    TaskInput(
                        title = task.title,
                        status = "pending",
                    ),
                )
                logger.i(
                    "TimeViewModel.revertTaskCompletionFromTimeTracking",
                    "One-time task reverted to pending",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "TimeViewModel.revertTaskCompletionFromTimeTracking",
                "Failed to revert task completion",
                /** E. */
                e,
                /** Map of. */
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
        /** Dimension id. */
        dimensionId: String,
        /** Dimension label. */
        dimensionLabel: String,
        taskId: String?,
        /** Start date. */
        startDate: LocalDate,
        /** Start time. */
        startTime: LocalTime,
        /** End date. */
        endDate: LocalDate,
        /** End time. */
        endTime: LocalTime,
        focusRating: Double? = null,
        focusNote: String? = null,
        onCreated: ((TimeEntry) -> Unit)? = null,
    ) {
        logger.i(
            "TimeViewModel.createManualEntry",
            "Creating manual time entry",
            /** Map of. */
            mapOf(
                "dimensionId" to dimensionId,
                "taskId" to (taskId ?: "none"),
                "start" to LocalDateTime.of(startDate, startTime).toString(),
                "end" to LocalDateTime.of(endDate, endTime).toString(),
            ),
        )
        viewModelScope.launch {
            try {
                /** Normalized focus rating. */
                val normalizedFocusRating = focusRating?.coerceIn(0.0, 1.0)
                /** Normalized focus note. */
                val normalizedFocusNote = focusNote?.trim()?.takeIf { it.isNotEmpty() }
                /** Focus rated at. */
                val focusRatedAt = if (normalizedFocusRating != null || normalizedFocusNote != null) {
                    LocalDateTime.now()
                } else {
                    /** Null. */
                    null
                }
                /** Input. */
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
                /** Created. */
                val created = timeEntryRepository.createTimeEntry(input)
                logger.i(
                    "TimeViewModel.createManualEntry",
                    "Manual time entry created",
                    /** Map of. */
                    mapOf("entryId" to created.id, "dimensionId" to dimensionId),
                )
                onCreated?.invoke(created)
                /** Load entries for date. */
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TimeViewModel.createManualEntry",
                    "Error creating manual entry",
                    /** E. */
                    e,
                    /** Map of. */
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
        /** Last entry. */
        val lastEntry = _uiState.value.lastEntry ?: return
        logger.i(
            "TimeViewModel.continueLastSession",
            "Continuing last session",
            /** Map of. */
            mapOf(
                "entryId" to lastEntry.id,
                "dimensionId" to (lastEntry.dimensionId ?: "unknown"),
                "taskId" to (lastEntry.taskId ?: "none"),
            ),
        )
        /** Continue entry. */
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
                /** Entry. */
                val entry = _uiState.value.timeEntries.find { it.id == entryId }
                    ?: _uiState.value.lastEntry?.takeIf { it.id == entryId }
                /** If. */
                if (entry == null) {
                    logger.w("TimeViewModel.continueEntry", "Entry not found", mapOf("entryId" to entryId))
                    return@launch
                }
                /** Input. */
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
                /** Task title. */
                val taskTitle = entry.taskId?.let { id ->
                    _uiState.value.tasks.find { it.id == id }?.title
                }
                /** Dimension label. */
                val dimensionLabel = entry.lifeIntentionCategory
                /** Dimension id. */
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
                    /** Map of. */
                    mapOf(
                        "entryId" to entryId,
                        "dimensionId" to dimensionId,
                        "originalStartTime" to entry.startedAt.toString(),
                    ),
                )
                /** Load entries for date. */
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
        /** Complete task with details. */
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
        /** Task id. */
        taskId: String,
        note: String?,
        actualCompletedAt: LocalDateTime?,
        actualDurationMinutes: Int?,
    ) {
        logger.i(
            "TimeViewModel.completeTaskWithDetails",
            "Completing task with explicit details",
            /** Map of. */
            mapOf(
                "taskId" to taskId,
                "hasNote" to (note != null).toString(),
                "actualCompletedAt" to (actualCompletedAt?.toString() ?: "none"),
                "actualDurationMinutes" to (actualDurationMinutes?.toString() ?: "none"),
            ),
        )
        viewModelScope.launch {
            try {
                /** Task. */
                val task = taskRepository.getTaskById(taskId)
                /** If. */
                if (task == null) {
                    logger.e("TimeViewModel.completeTaskWithDetails", "Task not found", null, mapOf("taskId" to taskId))
                    return@launch
                }
                /** Is frequency habit. */
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
                /** If. */
                if (task.recurrenceEnabled) {
                    /** If. */
                    if (!isFrequencyHabit) {
                        taskRepository.completeTask(task.id, note)
                    }
                    /** Occurrence date. */
                    val occurrenceDate = task.dueDate?.toLocalDate() ?: LocalDate.now()
                    /** Record occurrence. */
                    recordOccurrence(
                        taskId = task.id,
                        dueDate = occurrenceDate,
                        status = "completed",
                        note = note,
                        actualCompletedAt = actualCompletedAt,
                        actualDurationMinutes = actualDurationMinutes,
                    )
                    recurrenceManager.onTaskCompleted(task, note, null)
                    /** Schedule next recurring reminder. */
                    scheduleNextRecurringReminder(task.id, "TimeViewModel.completeTaskWithDetails")
                } else {
                    taskRepository.completeTask(task.id, note)
                    /** Cancel one time reminder. */
                    cancelOneTimeReminder(task.id, "TimeViewModel.completeTaskWithDetails")
                }
                /** Load entries for date. */
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TimeViewModel.completeTaskWithDetails",
                    "Failed to complete task with details",
                    /** E. */
                    e,
                    /** Map of. */
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
            /** Map of. */
            mapOf(
                "taskId" to taskId,
                "hasNote" to (note != null).toString(),
            ),
        )
        viewModelScope.launch {
            try {
                /** Task. */
                val task = taskRepository.getTaskById(taskId)
                /** If. */
                if (task == null) {
                    logger.e("TimeViewModel.skipTask", "Task not found", null, mapOf("taskId" to taskId))
                    return@launch
                }
                /** Is frequency habit. */
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
                /** If. */
                if (!isFrequencyHabit) {
                    taskRepository.skipTask(taskId, note)
                }
                /** If. */
                if (task.recurrenceEnabled) {
                    /** Record occurrence. */
                    recordOccurrence(taskId, task.dueDate?.toLocalDate() ?: LocalDate.now(), "skipped", note)
                    recurrenceManager.onTaskSkipped(task, note, null)
                    /** Schedule next recurring reminder. */
                    scheduleNextRecurringReminder(taskId, "TimeViewModel.skipTask")
                    logger.i(
                        "TimeViewModel.skipTask",
                        "Recurring task skipped, decay applied",
                        /** Map of. */
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                } else {
                    /** Cancel one time reminder. */
                    cancelOneTimeReminder(taskId, "TimeViewModel.skipTask")
                }
                logger.i(
                    "TimeViewModel.skipTask",
                    "Task skipped successfully",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "recurring" to task.recurrenceEnabled.toString(),
                    ),
                )
                /** Load entries for date. */
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TimeViewModel.skipTask",
                    "Failed to skip task",
                    /** E. */
                    e,
                    /** Map of. */
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
            /** Map of. */
            mapOf(
                "taskId" to taskId,
                "hasNote" to (note != null).toString(),
            ),
        )
        viewModelScope.launch {
            try {
                /** Task. */
                val task = taskRepository.getTaskById(taskId)
                /** If. */
                if (task == null) {
                    logger.e("TimeViewModel.missTask", "Task not found", null, mapOf("taskId" to taskId))
                    return@launch
                }
                /** Is frequency habit. */
                val isFrequencyHabit = task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)
                /** If. */
                if (!isFrequencyHabit) {
                    taskRepository.missTask(taskId, note)
                }
                /** If. */
                if (task.recurrenceEnabled) {
                    /** Record occurrence. */
                    recordOccurrence(taskId, task.dueDate?.toLocalDate() ?: LocalDate.now(), "missed", note)
                    recurrenceManager.onTaskMissed(task, note, null)
                    /** Schedule next recurring reminder. */
                    scheduleNextRecurringReminder(taskId, "TimeViewModel.missTask")
                    logger.i(
                        "TimeViewModel.missTask",
                        "Recurring task missed, decay applied",
                        /** Map of. */
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                } else {
                    /** Cancel one time reminder. */
                    cancelOneTimeReminder(taskId, "TimeViewModel.missTask")
                }
                logger.i(
                    "TimeViewModel.missTask",
                    "Task missed successfully",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "recurring" to task.recurrenceEnabled.toString(),
                    ),
                )
                /** Load entries for date. */
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TimeViewModel.missTask",
                    "Failed to miss task",
                    /** E. */
                    e,
                    /** Map of. */
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
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                /** Load entries for date. */
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TimeViewModel.archiveTask",
                    "Failed to archive task",
                    /** E. */
                    e,
                    /** Map of. */
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
                /** Cancel one time reminder. */
                cancelOneTimeReminder(taskId, "TimeViewModel.deleteTask")
                taskRepository.deleteTask(taskId)
                /** Load entries for date. */
                loadEntriesForDate(_uiState.value.selectedDate)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TimeViewModel.deleteTask",
                    "Failed to delete task",
                    /** E. */
                    e,
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    private suspend fun recordOccurrence(
        /** Task id. */
        taskId: String,
        /** Due date. */
        dueDate: LocalDate,
        /** Status. */
        status: String,
        note: String?,
        actualCompletedAt: LocalDateTime? = null,
        actualDurationMinutes: Int? = null,
    ) {
        logger.d(
            "TimeViewModel.recordOccurrence",
            "RECORDING_OCCURRENCE_START",
            /** Map of. */
            mapOf(
                "taskId" to taskId,
                "dueDate" to dueDate.toString(),
                "status" to status,
                "note" to (note ?: "null"),
                "actualCompletedAt" to (actualCompletedAt?.toString() ?: "null"),
                "actualDurationMinutes" to (actualDurationMinutes?.toString() ?: "null"),
            ),
        )
        /** Now. */
        val now = LocalDateTime.now()
        /** Occurrence. */
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
            /** Map of. */
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
            /** Map of. */
            mapOf(
                "occurrenceId" to occurrence.id,
                "taskId" to taskId,
            ),
        )
    }
    private suspend fun scheduleNextRecurringReminder(taskId: String, source: String) {
        /** Updated task. */
        val updatedTask = taskRepository.getTaskById(taskId)
        /** If. */
        if (updatedTask == null || !updatedTask.recurrenceEnabled) {
            /** Return. */
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
            /** Received initial entries. */
            var receivedInitialEntries = false
            try {
                timeEntryRepository.getTimeEntriesForDate(date).collect { entries ->
                    _uiState.update {
                        it.copy(
                            timeEntries = entries.sortedBy { e -> e.startedAt },
                        )
                    }
                    /** If. */
                    if (!receivedInitialEntries) {
                        receivedInitialEntries = true
                        logger.d(
                            "TimeViewModel.loadEntriesForDate",
                            "Initial time entries received",
                            /** Map of. */
                            mapOf(
                                "requestId" to requestId.toString(),
                                "selectedDate" to date.toString(),
                                "entryCount" to entries.size,
                            ),
                        )
                        /** Mark selected date section loaded. */
                        markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.ENTRIES)
                    }
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                /** If. */
                if (e is CancellationException) throw e
                logger.e("TimeViewModel.loadEntriesForDate", "Error loading time entries", e)
                _uiState.update { it.copy(error = e.message) }
                /** Mark selected date section loaded. */
                markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.ENTRIES)
            }
        }

    private fun launchPlannedTasksCollection(requestId: Long, date: LocalDate): Job =
        viewModelScope.launch {
            /** Received initial planned tasks. */
            var receivedInitialPlannedTasks = false
            try {
                /** Use todays tasks. */
                val useTodaysTasks = shouldUseTodaysPlannedTasks(date)
                /** Planned tasks flow. */
                val plannedTasksFlow = if (useTodaysTasks) {
                    taskRepository.getTodaysTasks()
                } else {
                    taskRepository.getTasksDueOn(date)
                }
                plannedTasksFlow.collect { tasks ->
                    /** Filtered. */
                    val filtered = if (FeatureFlags.minimalModeEnabled) {
                        tasks.filter { !it.recurrenceEnabled }
                    } else {
                        /** Tasks. */
                        tasks
                    }
                    _uiState.update { state ->
                        state.copy(
                            plannedTasks = filtered,
                            taskPickerTasks = buildTaskPickerTasks(filtered, state.tasks),
                        )
                    }
                    /** If. */
                    if (!receivedInitialPlannedTasks) {
                        receivedInitialPlannedTasks = true
                        logger.d(
                            "TimeViewModel.loadEntriesForDate",
                            "Initial planned tasks received",
                            /** Map of. */
                            mapOf(
                                "requestId" to requestId.toString(),
                                "selectedDate" to date.toString(),
                                "plannedTasksSource" to if (useTodaysTasks) "today" else "due_on_date",
                                "plannedTaskCount" to filtered.size,
                            ),
                        )
                        /** Mark selected date section loaded. */
                        markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.PLANNED_TASKS)
                    }
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                /** If. */
                if (e is CancellationException) throw e
                logger.e("TimeViewModel.loadEntriesForDate", "Failed to load planned tasks", e)
                _uiState.update { it.copy(error = e.message) }
                /** Mark selected date section loaded. */
                markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.PLANNED_TASKS)
            }
        }

    private fun launchOccurrencesCollection(requestId: Long, date: LocalDate): Job? {
        /** If. */
        if (FeatureFlags.minimalModeEnabled) {
            _uiState.update { it.copy(pastOccurrences = emptyList()) }
            logger.d(
                "TimeViewModel.loadEntriesForDate",
                "Skipped occurrences load in minimal mode",
                /** Map of. */
                mapOf(
                    "requestId" to requestId.toString(),
                    "selectedDate" to date.toString(),
                ),
            )
            /** Mark selected date section loaded. */
            markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.OCCURRENCES)
            return null
        }
        return viewModelScope.launch {
            /** Received initial occurrences. */
            var receivedInitialOccurrences = false
            try {
                taskOccurrenceRepository.getOccurrencesForDate(date).collect { occurrences ->
                    _uiState.update { it.copy(pastOccurrences = occurrences) }
                    /** If. */
                    if (!receivedInitialOccurrences) {
                        receivedInitialOccurrences = true
                        logger.d(
                            "TimeViewModel.loadEntriesForDate",
                            "Initial occurrences received",
                            /** Map of. */
                            mapOf(
                                "requestId" to requestId.toString(),
                                "selectedDate" to date.toString(),
                                "occurrenceCount" to occurrences.size,
                            ),
                        )
                        /** Mark selected date section loaded. */
                        markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.OCCURRENCES)
                    }
                }
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                /** If. */
                if (e is CancellationException) throw e
                logger.e("TimeViewModel.loadEntriesForDate", "Failed to load past occurrences", e)
                _uiState.update { it.copy(error = e.message) }
                /** Mark selected date section loaded. */
                markSelectedDateSectionLoaded(requestId, TimeScreenDateSection.OCCURRENCES)
            }
        }
    }
}
