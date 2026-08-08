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

data class TaskDetailUiState(
    val task: Task? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val occurrenceHistory: List<TaskOccurrence> = emptyList(),
    val isLoadingOccurrences: Boolean = false,
    val rescheduleHistory: List<TaskReschedule> = emptyList(),
    val isLoadingReschedules: Boolean = false,
    val completionStats: CompletionStats? = null,
    val latestL1: io.payanam.domain.model.HabitL1Summary? = null,

    // Dialog states
    val showStatusNoteDialog: Boolean = false,
    // "complete", "skip", "miss"
    val pendingStatusAction: String? = null,
)

@HiltViewModel
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
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    private var currentTaskId: String? = null

    fun loadTask(taskId: String) {
        currentTaskId = taskId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val task = taskRepository.getTaskById(taskId)
                // Inc 4: latest L1 score roll-up state (6 metrics) for the detail card
                val latestL1 = if (task?.recurrenceEnabled == true) {
                    runCatching { habitMetricRepository.getLatestForHabit(taskId) }.getOrNull()
                } else {
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
                if (task?.recurrenceEnabled == true) {
                    loadOccurrenceHistory(taskId)
                    loadCompletionStats(task)
                }

                loadRescheduleHistory(taskId)

                logger.i(
                    "TaskDetailViewModel.loadTask",
                    "Task loaded",
                    mapOf(
                        "taskId" to taskId,
                        "found" to (task != null),
                        "recurring" to (task?.recurrenceEnabled ?: false),
                    ),
                )
            } catch (e: Exception) {
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
                val stats = recurrenceManager.getCompletionStats(task)
                _uiState.update { it.copy(completionStats = stats) }
                logger.d(
                    "TaskDetailViewModel.loadCompletionStats",
                    "Stats loaded",
                    mapOf(
                        "taskId" to task.id,
                        "rate7d" to stats.completionRate7Days,
                        "rate30d" to stats.completionRate30Days,
                        "currentStreak" to stats.currentStreak,
                    ),
                )
            } catch (e: Exception) {
                logger.e("TaskDetailViewModel.loadCompletionStats", "Error loading stats", e)
            }
        }
    }

    private fun loadOccurrenceHistory(taskId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingOccurrences = true) }
            try {
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
                    mapOf(
                        "taskId" to taskId,
                        "count" to occurrences.size,
                    ),
                )
            } catch (e: Exception) {
                logger.e("TaskDetailViewModel.loadOccurrenceHistory", "Error loading occurrences", e)
                _uiState.update { it.copy(isLoadingOccurrences = false) }
            }
        }
    }

    private fun loadRescheduleHistory(taskId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingReschedules = true) }
            try {
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
                    mapOf(
                        "taskId" to taskId,
                        "count" to reschedules.size,
                    ),
                )
            } catch (e: Exception) {
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
        val taskId = currentTaskId ?: return
        val task = _uiState.value.task ?: return

        viewModelScope.launch {
            try {
                if (task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)) {
                    recordOccurrence(taskId, "completed", note, reason)
                    recurrenceManager.onTaskCompleted(task, note, reason, nextDueStrategy)
                    val updatedTask = taskRepository.getTaskById(taskId)
                    if (updatedTask != null && updatedTask.recurrenceEnabled) {
                        try {
                            notificationScheduler.scheduleForTask(updatedTask)
                        } catch (e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.completeTask",
                                "Failed to schedule next recurring reminder",
                                e,
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                    // Reload task to get updated score
                    loadTask(taskId)
                } else {
                    taskRepository.completeTask(taskId, note)

                    // Handle recurring task completion with score update
                    if (task.recurrenceEnabled) {
                        recordOccurrence(taskId, "completed", note, reason)
                        recurrenceManager.onTaskCompleted(task, note, reason, nextDueStrategy)
                        val updatedTask = taskRepository.getTaskById(taskId)
                        if (updatedTask != null && updatedTask.recurrenceEnabled) {
                            try {
                                notificationScheduler.scheduleForTask(updatedTask)
                            } catch (e: Exception) {
                                logger.e(
                                    "TaskDetailViewModel.completeTask",
                                    "Failed to schedule next recurring reminder",
                                    e,
                                    mapOf(
                                        "taskId" to taskId,
                                    ),
                                )
                            }
                        }
                        // Reload task to get updated score
                        loadTask(taskId)
                    } else {
                        try {
                            notificationScheduler.cancelForTask(taskId)
                        } catch (e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.completeTask",
                                "Failed to cancel reminders",
                                e,
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
                    mapOf(
                        "taskId" to taskId,
                        "hasNote" to (note != null),
                        "recurring" to task.recurrenceEnabled,
                    ),
                )

                hideStatusDialog()
            } catch (e: Exception) {
                logger.e("TaskDetailViewModel.completeTask", "Error completing task", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Skip task with optional note and record occurrence
     */
    fun skipTask(note: String? = null, reason: String? = null, nextDueStrategy: String? = null) {
        val taskId = currentTaskId ?: return
        val task = _uiState.value.task ?: return

        viewModelScope.launch {
            try {
                if (task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)) {
                    recordOccurrence(taskId, "skipped", note, reason)
                    recurrenceManager.onTaskSkipped(task, note, reason, nextDueStrategy)
                    val updatedTask = taskRepository.getTaskById(taskId)
                    if (updatedTask != null && updatedTask.recurrenceEnabled) {
                        try {
                            notificationScheduler.scheduleForTask(updatedTask)
                        } catch (e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.skipTask",
                                "Failed to schedule next recurring reminder",
                                e,
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                    // Reload task to get updated score
                    loadTask(taskId)
                } else {
                    taskRepository.skipTask(taskId, note)

                    // Handle recurring task skip with score update (skips apply decay)
                    if (task.recurrenceEnabled) {
                        recordOccurrence(taskId, "skipped", note, reason)
                        recurrenceManager.onTaskSkipped(task, note, reason, nextDueStrategy)
                        val updatedTask = taskRepository.getTaskById(taskId)
                        if (updatedTask != null && updatedTask.recurrenceEnabled) {
                            try {
                                notificationScheduler.scheduleForTask(updatedTask)
                            } catch (e: Exception) {
                                logger.e(
                                    "TaskDetailViewModel.skipTask",
                                    "Failed to schedule next recurring reminder",
                                    e,
                                    mapOf(
                                        "taskId" to taskId,
                                    ),
                                )
                            }
                        }
                        // Reload task to get updated score
                        loadTask(taskId)
                    } else {
                        try {
                            notificationScheduler.cancelForTask(taskId)
                        } catch (e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.skipTask",
                                "Failed to cancel reminders",
                                e,
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
                    mapOf(
                        "taskId" to taskId,
                        "reason" to (reason ?: "none"),
                        "recurring" to task.recurrenceEnabled,
                    ),
                )

                hideStatusDialog()
            } catch (e: Exception) {
                logger.e("TaskDetailViewModel.skipTask", "Error skipping task", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Mark task as missed with optional note and record occurrence
     */
    fun missTask(note: String? = null, reason: String? = null) {
        val taskId = currentTaskId ?: return
        val task = _uiState.value.task ?: return

        viewModelScope.launch {
            try {
                if (task.recurrenceEnabled && recurrenceManager.isFrequencyHabit(task)) {
                    recordOccurrence(taskId, "missed", note, reason)
                    recurrenceManager.onTaskMissed(task, note, reason)
                    val updatedTask = taskRepository.getTaskById(taskId)
                    if (updatedTask != null && updatedTask.recurrenceEnabled) {
                        try {
                            notificationScheduler.scheduleForTask(updatedTask)
                        } catch (e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.missTask",
                                "Failed to schedule next recurring reminder",
                                e,
                                mapOf(
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    }
                    loadTask(taskId)
                } else {
                    taskRepository.missTask(taskId, note)

                    // Record occurrence with missed status for recurring tasks (applies decay)
                    if (task.recurrenceEnabled) {
                        recordOccurrence(taskId, "missed", note, reason)
                        recurrenceManager.onTaskMissed(task, note, reason)
                        val updatedTask = taskRepository.getTaskById(taskId)
                        if (updatedTask != null && updatedTask.recurrenceEnabled) {
                            try {
                                notificationScheduler.scheduleForTask(updatedTask)
                            } catch (e: Exception) {
                                logger.e(
                                    "TaskDetailViewModel.missTask",
                                    "Failed to schedule next recurring reminder",
                                    e,
                                    mapOf(
                                        "taskId" to taskId,
                                    ),
                                )
                            }
                        }
                        loadTask(taskId)
                    } else {
                        try {
                            notificationScheduler.cancelForTask(taskId)
                        } catch (e: Exception) {
                            logger.e(
                                "TaskDetailViewModel.missTask",
                                "Failed to cancel reminders",
                                e,
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
                    mapOf(
                        "taskId" to taskId,
                        "reason" to (reason ?: "none"),
                        "recurring" to task.recurrenceEnabled,
                    ),
                )

                hideStatusDialog()
            } catch (e: Exception) {
                logger.e("TaskDetailViewModel.missTask", "Error marking task as missed", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    private suspend fun recordOccurrence(taskId: String, status: String, note: String?, reason: String?) {
        try {
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
                mapOf(
                    "taskId" to taskId,
                    "status" to status,
                    "occurrenceId" to occurrence.id,
                ),
            )

            // Reload occurrence history
            loadOccurrenceHistory(taskId)
        } catch (e: Exception) {
            logger.e("TaskDetailViewModel.recordOccurrence", "Error recording occurrence", e)
        }
    }

    fun archiveTask() {
        val taskId = currentTaskId ?: return
        viewModelScope.launch {
            try {
                taskRepository.archiveTask(taskId)
                try {
                    notificationScheduler.cancelForTask(taskId)
                } catch (e: Exception) {
                    logger.e(
                        "TaskDetailViewModel.archiveTask",
                        "Failed to cancel reminders",
                        e,
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                }
                logger.i("TaskDetailViewModel.archiveTask", "Task archived", mapOf("taskId" to taskId))
            } catch (e: Exception) {
                logger.e("TaskDetailViewModel.archiveTask", "Error archiving task", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun rescheduleTask(newDueDate: LocalDateTime) {
        val taskId = currentTaskId ?: return
        val task = _uiState.value.task ?: return
        val previousDueDate = task.dueDate
        if (previousDueDate == null) {
            logger.w(
                "TaskDetailViewModel.rescheduleTask",
                "Task has no due date",
                mapOf(
                    "taskId" to taskId,
                ),
            )
            _uiState.update { it.copy(error = null) }
            return
        }

        viewModelScope.launch {
            try {
                val updatedTask = taskRepository.updateTask(
                    id = taskId,
                    input = io.payanam.domain.model.TaskInput(
                        title = task.title,
                        dueDate = newDueDate,
                    ),
                )
                val wasOverdue = previousDueDate.isBefore(LocalDateTime.now())
                taskRescheduleRepository.recordReschedule(
                    taskId = taskId,
                    previousDueDate = previousDueDate,
                    newDueDate = newDueDate,
                    wasOverdue = wasOverdue,
                )
                _uiState.update { it.copy(task = updatedTask) }
                loadRescheduleHistory(taskId)

                try {
                    notificationScheduler.scheduleForTask(updatedTask)
                } catch (e: Exception) {
                    logger.e(
                        "TaskDetailViewModel.rescheduleTask",
                        "Failed to reschedule reminder",
                        e,
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                }

                logger.i(
                    "TaskDetailViewModel.rescheduleTask",
                    "Task rescheduled",
                    mapOf(
                        "taskId" to taskId,
                        "from" to previousDueDate.toString(),
                        "to" to newDueDate.toString(),
                        "wasOverdue" to wasOverdue,
                    ),
                )
            } catch (e: Exception) {
                logger.e(
                    "TaskDetailViewModel.rescheduleTask",
                    "Error rescheduling task",
                    e,
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteTask() {
        val taskId = currentTaskId ?: return
        viewModelScope.launch {
            try {
                try {
                    notificationScheduler.cancelForTask(taskId)
                } catch (e: Exception) {
                    logger.e(
                        "TaskDetailViewModel.deleteTask",
                        "Failed to cancel reminders",
                        e,
                        mapOf(
                            "taskId" to taskId,
                        ),
                    )
                }
                taskRepository.deleteTask(taskId)
                logger.i("TaskDetailViewModel.deleteTask", "Task deleted", mapOf("taskId" to taskId))
            } catch (e: Exception) {
                logger.e("TaskDetailViewModel.deleteTask", "Error deleting task", e)
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
}
