//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TaskReschedule
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TaskRescheduleRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.scoring.CompletionStats
import io.payanam.usecase.RecurrenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * TaskDetailUiState.
 */
data class TaskDetailUiState(
    /** Task. */
    val task: Task? = null,
    /** Is loading. */
    val isLoading: Boolean = true,
    /** Error. */
    val error: String? = null,
    /** Occurrence history. */
    val occurrenceHistory: List<TaskOccurrence> = emptyList(),
    /** Is loading occurrences. */
    val isLoadingOccurrences: Boolean = false,
    /** Reschedule history. */
    val rescheduleHistory: List<TaskReschedule> = emptyList(),
    /** Is loading reschedules. */
    val isLoadingReschedules: Boolean = false,
    /** Completion stats. */
    val completionStats: CompletionStats? = null,
    /** Latest l1. */
    val latestL1: io.payanam.domain.model.HabitL1Summary? = null,

    // Activity detail window (Part C): range switcher + pagination
    /** Window size days. */
    val windowSizeDays: Int = 7,
    /** Window end. */
    val windowEnd: java.time.LocalDate = java.time.LocalDate.now(),
    /** Window rows. */
    val windowRows: List<io.payanam.domain.model.HabitL1Summary> = emptyList(),
    /** Window occurrences. */
    val windowOccurrences: Map<String, io.payanam.domain.model.TaskOccurrence> = emptyMap(),
    /** Is loading window. */
    val isLoadingWindow: Boolean = false,
    // true = chart view, false = table view
    /** Show chart view. */
    val showChartView: Boolean = true,

    // Dialog states
    /** Show status note dialog. */
    val showStatusNoteDialog: Boolean = false,
    // "complete", "skip", "miss"
    /** Pending status action. */
    val pendingStatusAction: String? = null,
)

@HiltViewModel
/**
 * TaskDetailViewModel.
 */
class TaskDetailViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val taskRescheduleRepository: TaskRescheduleRepository,
    private val notificationScheduler: NotificationScheduler,
    private val recurrenceManager: RecurrenceManager,
    private val habitMetricRepository: io.payanam.domain.repository.HabitMetricRepository,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()

    private val _uiState = MutableStateFlow(TaskDetailUiState())
    /** Ui state. */
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    private var currentTaskId: String? = null

    /**
     * Load task.
     */
    fun loadTask(taskId: String) {
        currentTaskId = taskId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                /** Task. */
                val task = taskRepository.getTaskById(taskId)
                // Inc 4: latest L1 score roll-up state (6 metrics) for the detail card
                /** Latest l1. */
                val latestL1 = if (task?.recurrenceEnabled == true) {
                    runCatching { habitMetricRepository.getLatestForHabit(taskId) }.getOrNull()
                } else {
                    /** Null. */
                    null
                }
                _uiState.update {
                    it.copy(
                        task = task,
                        latestL1 = latestL1,
                        isLoading = false,
                        error = null,
                    )
                }

                // Load occurrence history and stats if task is recurring
                /** If. */
                if (task?.recurrenceEnabled == true) {
                    /** Load occurrence history. */
                    loadOccurrenceHistory(taskId)
                    /** Load completion stats. */
                    loadCompletionStats(task)
                    /** Load activity window. */
                    loadActivityWindow(taskId)
                }

                /** Load reschedule history. */
                loadRescheduleHistory(taskId)

                logger.i(
                    "TaskDetailViewModel.loadTask",
                    "Task loaded",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "found" to (task != null),
                        "recurring" to (task?.recurrenceEnabled ?: false),
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.loadTask", "Error loading task", e)
                _uiState.update {
                    it.copy(isLoading = false, error = e.message)
                }
            }
        }
    }

    private fun loadCompletionStats(task: Task) {
        viewModelScope.launch {
            try {
                /** Stats. */
                val stats = recurrenceManager.getCompletionStats(task)
                _uiState.update { it.copy(completionStats = stats) }
                logger.d(
                    "TaskDetailViewModel.loadCompletionStats",
                    "Stats loaded",
                    /** Map of. */
                    mapOf(
                        "taskId" to task.id,
                        "rate7d" to stats.completionRate7Days,
                        "rate30d" to stats.completionRate30Days,
                        "currentStreak" to stats.currentStreak,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.loadCompletionStats", "Error loading stats", e)
            }
        }
    }

    private fun loadOccurrenceHistory(taskId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingOccurrences = true) }
            try {
                /** Occurrences. */
                val occurrences = taskOccurrenceRepository.getOccurrencesByTaskId(taskId)
                _uiState.update {
                    it.copy(
                        occurrenceHistory = occurrences,
                        isLoadingOccurrences = false,
                    )
                }
                logger.d(
                    "TaskDetailViewModel.loadOccurrenceHistory",
                    "Loaded occurrences",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "count" to occurrences.size,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.loadOccurrenceHistory", "Error loading occurrences", e)
                _uiState.update { it.copy(isLoadingOccurrences = false) }
            }
        }
    }

    // ── Activity detail window (Part C) ──────────────────────────────────

    /** Range options (days): 7d / 30d / 90d / 180d / 365d / all-time. */
    fun setWindowSizeDays(days: Int) {
        _uiState.update { it.copy(windowSizeDays = days, windowEnd = java.time.LocalDate.now()) }
        /** Load activity window. */
        loadActivityWindow(currentTaskId ?: return)
    }

    /**
     * Shift window back.
     */
    fun shiftWindowBack() {
        /** S. */
        val s = _uiState.value
        _uiState.update {
            it.copy(windowEnd = it.windowEnd.minusDays(it.windowSizeDays.toLong()))
        }
        /** Load activity window. */
        loadActivityWindow(currentTaskId ?: return)
    }

    /**
     * Shift window forward.
     */
    fun shiftWindowForward() {
        /** S. */
        val s = _uiState.value
        /** If. */
        if (s.windowEnd >= java.time.LocalDate.now()) return // cannot go past today
        _uiState.update {
            it.copy(windowEnd = it.windowEnd.plusDays(it.windowSizeDays.toLong()).let { end ->
                /** If. */
                if (end > java.time.LocalDate.now()) java.time.LocalDate.now() else end
            })
        }
        /** Load activity window. */
        loadActivityWindow(currentTaskId ?: return)
    }

    /**
     * Jump window to today.
     */
    fun jumpWindowToToday() {
        _uiState.update { it.copy(windowEnd = java.time.LocalDate.now()) }
        /** Load activity window. */
        loadActivityWindow(currentTaskId ?: return)
    }

    /**
     * Set chart view.
     */
    fun setChartView(chart: Boolean) {
        _uiState.update { it.copy(showChartView = chart) }
    }

    private fun loadActivityWindow(taskId: String) {
        viewModelScope.launch {
            /** S. */
            val s = _uiState.value
            _uiState.update { it.copy(isLoadingWindow = true) }
            try {
                /** Val. */
                val (start, end) = windowBounds(s.windowSizeDays, s.windowEnd)
                /** Rows. */
                val rows = runCatching {
                    habitMetricRepository.getForHabitRange(taskId, start.toString(), end.toString())
                }.getOrDefault(emptyList())
                // Occurrences in window keyed by dayKey — raw status for the table.
                /** Occs. */
                val occs = taskOccurrenceRepository.getOccurrencesByTaskId(taskId)
                    .filter { it.occurrenceDate.take(10) in start.toString()..end.toString() }
                    .associateBy { it.occurrenceDate.take(10) }
                _uiState.update {
                    it.copy(
                        windowRows = rows,
                        windowOccurrences = occs,
                        isLoadingWindow = false,
                    )
                }
                logger.d(
                    "TaskDetailViewModel.loadActivityWindow",
                    "Loaded activity window",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "start" to start.toString(),
                        "end" to end.toString(),
                        "sizeDays" to s.windowSizeDays,
                        "metricRows" to rows.size,
                        "occurrences" to occs.size,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.loadActivityWindow", "Error loading activity window", e)
                _uiState.update { it.copy(isLoadingWindow = false) }
            }
        }
    }

    /** Window bounds: [sizeDays] days ending at [end]; sizeDays <= 0 = all-time. */
    internal companion object {
        /**
         * Window bounds.
         */
        fun windowBounds(sizeDays: Int, end: java.time.LocalDate): Pair<java.time.LocalDate, java.time.LocalDate> {
            /** Start. */
            val start = if (sizeDays > 0) end.minusDays((sizeDays - 1).toLong()) else java.time.LocalDate.of(2020, 1, 1)
            return start to end
        }
    }

    private fun loadRescheduleHistory(taskId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingReschedules = true) }
            try {
                /** Reschedules. */
                val reschedules = taskRescheduleRepository.getReschedulesByTaskId(taskId)
                _uiState.update {
                    it.copy(
                        rescheduleHistory = reschedules,
                        isLoadingReschedules = false,
                    )
                }
                logger.d(
                    "TaskDetailViewModel.loadRescheduleHistory",
                    "Loaded reschedules",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "count" to reschedules.size,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.loadRescheduleHistory", "Error loading reschedules", e)
                _uiState.update { it.copy(isLoadingReschedules = false) }
            }
        }
    }

    /**
     * Show status note dialog before completing/skipping
     */
    fun showStatusDialog(action: String) {
        logger.d("TaskDetailViewModel.showStatusDialog", "Showing dialog", mapOf("action" to action))
        _uiState.update {
            it.copy(
                showStatusNoteDialog = true,
                pendingStatusAction = action,
            )
        }
    }

    /**
     * Hide status note dialog
     */
    fun hideStatusDialog() {
        _uiState.update {
            it.copy(
                showStatusNoteDialog = false,
                pendingStatusAction = null,
            )
        }
    }

    /**
     * Complete task with optional note and record occurrence
     */
    fun completeTask(note: String? = null, reason: String? = null, nextDueStrategy: String? = null) {
        /** Task id. */
        val taskId = currentTaskId ?: return
        /** Task. */
        val task = _uiState.value.task ?: return

        viewModelScope.launch {
            try {
                /** If. */
                if (task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)) {
                    /** Record occurrence. */
                    recordOccurrence(taskId, "completed", note, reason)
                    recurrenceManager.onTaskCompleted(task, note, reason, nextDueStrategy)
                    /** Updated task. */
                    val updatedTask = taskRepository.getTaskById(taskId)
                    /** If. */
                    if (updatedTask != null && updatedTask.recurrenceEnabled) {
                        try {
                            notificationScheduler.scheduleForTask(updatedTask)
                        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.completeTask",
                                "Failed to schedule next recurring reminder",
                                /** E. */
                                e,
                                /** Map of. */
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                    // Reload task to get updated score
                    /** Load task. */
                    loadTask(taskId)
                } else {
                    taskRepository.completeTask(taskId, note)

                    // Handle recurring task completion with score update
                    /** If. */
                    if (task.recurrenceEnabled) {
                        /** Record occurrence. */
                        recordOccurrence(taskId, "completed", note, reason)
                        recurrenceManager.onTaskCompleted(task, note, reason, nextDueStrategy)
                        /** Updated task. */
                        val updatedTask = taskRepository.getTaskById(taskId)
                        /** If. */
                        if (updatedTask != null && updatedTask.recurrenceEnabled) {
                            try {
                                notificationScheduler.scheduleForTask(updatedTask)
                            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                                logger.e(
                                    "TaskDetailViewModel.completeTask",
                                    "Failed to schedule next recurring reminder",
                                    /** E. */
                                    e,
                                    /** Map of. */
                                    mapOf(
                                        "taskId" to taskId,
                                    ),
                                )
                            }
                        }
                        // Reload task to get updated score
                        /** Load task. */
                        loadTask(taskId)
                    } else {
                        try {
                            notificationScheduler.cancelForTask(taskId)
                        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.completeTask",
                                "Failed to cancel reminders",
                                /** E. */
                                e,
                                /** Map of. */
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                }

                logger.i(
                    "TaskDetailViewModel.completeTask",
                    "Task completed",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "hasNote" to (note != null),
                        "recurring" to task.recurrenceEnabled,
                    ),
                )

                /** Hide status dialog. */
                hideStatusDialog()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.completeTask", "Error completing task", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Skip task with optional note and record occurrence
     */
    fun skipTask(note: String? = null, reason: String? = null, nextDueStrategy: String? = null) {
        /** Task id. */
        val taskId = currentTaskId ?: return
        /** Task. */
        val task = _uiState.value.task ?: return

        viewModelScope.launch {
            try {
                /** If. */
                if (task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)) {
                    /** Record occurrence. */
                    recordOccurrence(taskId, "skipped", note, reason)
                    recurrenceManager.onTaskSkipped(task, note, reason, nextDueStrategy)
                    /** Updated task. */
                    val updatedTask = taskRepository.getTaskById(taskId)
                    /** If. */
                    if (updatedTask != null && updatedTask.recurrenceEnabled) {
                        try {
                            notificationScheduler.scheduleForTask(updatedTask)
                        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.skipTask",
                                "Failed to schedule next recurring reminder",
                                /** E. */
                                e,
                                /** Map of. */
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                    // Reload task to get updated score
                    /** Load task. */
                    loadTask(taskId)
                } else {
                    taskRepository.skipTask(taskId, note)

                    // Handle recurring task skip with score update (skips apply decay)
                    /** If. */
                    if (task.recurrenceEnabled) {
                        /** Record occurrence. */
                        recordOccurrence(taskId, "skipped", note, reason)
                        recurrenceManager.onTaskSkipped(task, note, reason, nextDueStrategy)
                        /** Updated task. */
                        val updatedTask = taskRepository.getTaskById(taskId)
                        /** If. */
                        if (updatedTask != null && updatedTask.recurrenceEnabled) {
                            try {
                                notificationScheduler.scheduleForTask(updatedTask)
                            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                                logger.e(
                                    "TaskDetailViewModel.skipTask",
                                    "Failed to schedule next recurring reminder",
                                    /** E. */
                                    e,
                                    /** Map of. */
                                    mapOf(
                                        "taskId" to taskId,
                                    ),
                                )
                            }
                        }
                        // Reload task to get updated score
                        /** Load task. */
                        loadTask(taskId)
                    } else {
                        try {
                            notificationScheduler.cancelForTask(taskId)
                        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.skipTask",
                                "Failed to cancel reminders",
                                /** E. */
                                e,
                                /** Map of. */
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                }

                logger.i(
                    "TaskDetailViewModel.skipTask",
                    "Task skipped",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "reason" to (reason ?: "none"),
                        "recurring" to task.recurrenceEnabled,
                    ),
                )

                /** Hide status dialog. */
                hideStatusDialog()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.skipTask", "Error skipping task", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Mark task as missed with optional note and record occurrence
     */
    fun missTask(note: String? = null, reason: String? = null) {
        /** Task id. */
        val taskId = currentTaskId ?: return
        /** Task. */
        val task = _uiState.value.task ?: return

        viewModelScope.launch {
            try {
                /** If. */
                if (task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)) {
                    /** Record occurrence. */
                    recordOccurrence(taskId, "missed", note, reason)
                    recurrenceManager.onTaskMissed(task, note, reason)
                    /** Updated task. */
                    val updatedTask = taskRepository.getTaskById(taskId)
                    /** If. */
                    if (updatedTask != null && updatedTask.recurrenceEnabled) {
                        try {
                            notificationScheduler.scheduleForTask(updatedTask)
                        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.missTask",
                                "Failed to schedule next recurring reminder",
                                /** E. */
                                e,
                                /** Map of. */
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                    /** Load task. */
                    loadTask(taskId)
                } else {
                    taskRepository.missTask(taskId, note)

                    // Record occurrence with missed status for recurring tasks (applies decay)
                    /** If. */
                    if (task.recurrenceEnabled) {
                        /** Record occurrence. */
                        recordOccurrence(taskId, "missed", note, reason)
                        recurrenceManager.onTaskMissed(task, note, reason)
                        /** Updated task. */
                        val updatedTask = taskRepository.getTaskById(taskId)
                        /** If. */
                        if (updatedTask != null && updatedTask.recurrenceEnabled) {
                            try {
                                notificationScheduler.scheduleForTask(updatedTask)
                            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                                logger.e(
                                    "TaskDetailViewModel.missTask",
                                    "Failed to schedule next recurring reminder",
                                    /** E. */
                                    e,
                                    /** Map of. */
                                    mapOf(
                                        "taskId" to taskId,
                                    ),
                                )
                            }
                        }
                        /** Load task. */
                        loadTask(taskId)
                    } else {
                        try {
                            notificationScheduler.cancelForTask(taskId)
                        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.missTask",
                                "Failed to cancel reminders",
                                /** E. */
                                e,
                                /** Map of. */
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                }

                logger.i(
                    "TaskDetailViewModel.missTask",
                    "Task marked as missed",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "reason" to (reason ?: "none"),
                        "recurring" to task.recurrenceEnabled,
                    ),
                )

                /** Hide status dialog. */
                hideStatusDialog()
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.missTask", "Error marking task as missed", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    private suspend fun recordOccurrence(taskId: String, status: String, note: String?, reason: String?) {
        try {
            /** Occurrence. */
            val occurrence = TaskOccurrence(
                id = java.util.UUID.randomUUID().toString(),
                taskId = taskId,
                occurrenceDate = LocalDate.now().toString(),
                status = status,
                statusNote = note,
                statusReason = reason,
                completedAt = if (status == "completed") LocalDateTime.now().toString() else null,
                skippedAt = if (status == "skipped" || status == "missed") LocalDateTime.now().toString() else null,
            )

            taskOccurrenceRepository.recordOccurrence(occurrence)

            logger.d(
                "TaskDetailViewModel.recordOccurrence",
                "Occurrence recorded",
                /** Map of. */
                mapOf(
                    "taskId" to taskId,
                    "status" to status,
                    "occurrenceId" to occurrence.id,
                ),
            )

            // Reload occurrence history
            /** Load occurrence history. */
            loadOccurrenceHistory(taskId)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("TaskDetailViewModel.recordOccurrence", "Error recording occurrence", e)
        }
    }

    /**
     * Archive task.
     */
    fun archiveTask() {
        /** Task id. */
        val taskId = currentTaskId ?: return
        viewModelScope.launch {
            try {
                taskRepository.archiveTask(taskId)
                try {
                    notificationScheduler.cancelForTask(taskId)
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    logger.e(
                        "TaskDetailViewModel.archiveTask",
                        "Failed to cancel reminders",
                        /** E. */
                        e,
                        /** Map of. */
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                }
                logger.i("TaskDetailViewModel.archiveTask", "Task archived", mapOf("taskId" to taskId))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.archiveTask", "Error archiving task", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Reschedule task.
     */
    fun rescheduleTask(newDueDate: LocalDateTime) {
        /** Task id. */
        val taskId = currentTaskId ?: return
        /** Task. */
        val task = _uiState.value.task ?: return
        /** Previous due date. */
        val previousDueDate = task.dueDate
        /** If. */
        if (previousDueDate == null) {
            logger.w(
                "TaskDetailViewModel.rescheduleTask",
                "Task has no due date",
                /** Map of. */
                mapOf(
                    "taskId" to taskId,
                ),
            )
            _uiState.update { it.copy(error = null) }
            /** Return. */
            return
        }

        viewModelScope.launch {
            try {
                /** Updated task. */
                val updatedTask = taskRepository.updateTask(
                    id = taskId,
                    input = io.payanam.domain.model.TaskInput(
                        title = task.title,
                        dueDate = newDueDate,
                    ),
                )
                /** Was overdue. */
                val wasOverdue = previousDueDate.isBefore(LocalDateTime.now())
                taskRescheduleRepository.recordReschedule(
                    taskId = taskId,
                    previousDueDate = previousDueDate,
                    newDueDate = newDueDate,
                    wasOverdue = wasOverdue,
                )
                _uiState.update { it.copy(task = updatedTask) }
                /** Load reschedule history. */
                loadRescheduleHistory(taskId)

                try {
                    notificationScheduler.scheduleForTask(updatedTask)
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    logger.e(
                        "TaskDetailViewModel.rescheduleTask",
                        "Failed to reschedule reminder",
                        /** E. */
                        e,
                        /** Map of. */
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                }

                logger.i(
                    "TaskDetailViewModel.rescheduleTask",
                    "Task rescheduled",
                    /** Map of. */
                    mapOf(
                        "taskId" to taskId,
                        "from" to previousDueDate.toString(),
                        "to" to newDueDate.toString(),
                        "wasOverdue" to wasOverdue,
                    ),
                )
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e(
                    "TaskDetailViewModel.rescheduleTask",
                    "Error rescheduling task",
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
    fun deleteTask() {
        /** Task id. */
        val taskId = currentTaskId ?: return
        viewModelScope.launch {
            try {
                try {
                    notificationScheduler.cancelForTask(taskId)
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    logger.e(
                        "TaskDetailViewModel.deleteTask",
                        "Failed to cancel reminders",
                        /** E. */
                        e,
                        /** Map of. */
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                }
                taskRepository.deleteTask(taskId)
                logger.i("TaskDetailViewModel.deleteTask", "Task deleted", mapOf("taskId" to taskId))
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                logger.e("TaskDetailViewModel.deleteTask", "Error deleting task", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
}
