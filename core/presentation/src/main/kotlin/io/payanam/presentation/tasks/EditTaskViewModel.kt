//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.port.ScoreCalculator
import io.payanam.domain.port.ScoreRollupTrigger
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.presentation.data.LocalDataLayer
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.shared.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the edit-task screen: the loaded task, its tags + suggestions,
 * loading/saving flags, and the last error.
 */
data class EditTaskUiState(
    val task: Task? = null,
    val taskTags: List<String> = emptyList(),
    val tagSuggestions: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
)

/**
 * Edit-task ViewModel: loads a task with its tags, then persists edits —
 * replacing tags, rebuilding score roll-ups, and rescheduling reminders.
 */
class EditTaskViewModel(
    private val taskRepository: TaskRepository,
    private val tagRepository: TagRepository,
    private val notificationScheduler: TaskReminderScheduler,
    private val scoreRollupTrigger: ScoreRollupTrigger,
    private val scoreCalculator: ScoreCalculator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = AppLogger.current
    private val _uiState = MutableStateFlow(EditTaskUiState())
    val uiState: StateFlow<EditTaskUiState> = _uiState.asStateFlow()

    private var currentTaskId: String? = null

    init {
        observeTagSuggestions()
    }

    private fun observeTagSuggestions() {
        scope.launch {
            tagRepository
                .observeAllTags()
                .map { tags -> tags.map { it.name } }
                .collect { names ->
                    _uiState.update { it.copy(tagSuggestions = names) }
                    logger.d(
                        "EditTaskViewModel.observeTagSuggestions",
                        "Tag suggestion stream updated",
                        mapOf("count" to names.size),
                    )
                }
        }
    }

    /**
     * Fetches [taskId]'s task + tags into state for editing.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun loadTask(taskId: String) {
        currentTaskId = taskId
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val task = taskRepository.getTaskById(taskId)
                val taskTags =
                    tagRepository
                        .observeTagsForTask(taskId)
                        .first()
                        .map { it.name }
                _uiState.update {
                    it.copy(
                        task = task,
                        taskTags = taskTags,
                        isLoading = false,
                        error = null,
                    )
                }
                logger.d(
                    "EditTaskViewModel.loadTask",
                    "Task loaded",
                    mapOf(
                        "taskId" to taskId,
                        "found" to (task != null),
                    ),
                )
            } catch (e: Exception) {
                logger.e("EditTaskViewModel.loadTask", "Error loading task", e)
                _uiState.update {
                    it.copy(isLoading = false, error = e.message)
                }
            }
        }
    }

    /**
     * Persists the edited fields, replaces tags, recomputes scores (roll-up
     * cascade for habits), and re-arms the reminder.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun updateTask(input: EditTaskInput) {
        val taskId = currentTaskId ?: return
        logger.i(
            "EditTaskViewModel.updateTask",
            "Task update requested from UI",
            mapOf(
                "taskId" to taskId,
                "title" to input.title,
                "tagCount" to input.tags.size,
                "recurrenceEnabled" to input.recurrenceEnabled,
            ),
        )
        scope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val taskInput = buildTaskInput(input)
                val updatedTask = taskRepository.updateTask(taskId, taskInput)
                tagRepository.replaceTaskTags(taskId, input.tags)
                logger.i(
                    "EditTaskViewModel.updateTask",
                    "Task tags replaced",
                    mapOf("taskId" to taskId, "tagCount" to input.tags.size),
                )

                // Rebuild score roll-up for rule/dimension changes (Inc 3):
                // stale grid rows are removed and L1/L2/L3 recomputed.
                if (updatedTask.recurrenceEnabled) {
                    scoreRollupTrigger.recalcForRuleChange(taskId)
                    logger.d(
                        "EditTaskViewModel.updateTask",
                        "Score roll-up rebuilt after task update",
                        mapOf("taskId" to taskId),
                    )
                } else if (PlatformFeatureFlags.current.scoringEnabled) {
                    val score = scoreCalculator.calculateScore(updatedTask)
                    taskRepository.updateTaskScore(taskId, score)
                    logger.d(
                        "EditTaskViewModel.updateTask",
                        "Task score recalculated after update",
                        mapOf("taskId" to taskId, "score" to score),
                    )
                }

                // Reschedule reminder based on latest task settings
                if (PlatformFeatureFlags.current.remindersEnabled) {
                    try {
                        notificationScheduler.scheduleForTask(updatedTask)
                    } catch (e: Exception) {
                        logger.e(
                            "EditTaskViewModel.updateTask",
                            "Failed to reschedule reminder",
                            e,
                            mapOf(
                                "taskId" to taskId,
                            ),
                        )
                    }
                }

                logger.d("EditTaskViewModel.updateTask", "Task updated", mapOf("title" to input.title))
                logger.i(
                    "EditTaskViewModel.updateTask",
                    "Task updated",
                    mapOf(
                        "taskId" to taskId,
                        "tagCount" to input.tags.size,
                        "remindersEnabled" to PlatformFeatureFlags.current.remindersEnabled,
                    ),
                )
                _uiState.update { it.copy(isSaving = false) }
            } catch (e: Exception) {
                logger.e(
                    "EditTaskViewModel.updateTask",
                    "Error updating task",
                    e,
                    mapOf(
                        "taskId" to taskId,
                    ),
                )
                _uiState.update {
                    it.copy(isSaving = false, error = e.message)
                }
            }
        }
    }

    private fun buildTaskInput(input: EditTaskInput): TaskInput =
        TaskInput(
            title = input.title,
            description = input.description,
            dueDate = input.dueDate,
            dimensionId = input.dimensionId,
            lifeIntentionCategory = input.dimensionLabel,
            impactLevel = input.impactLevel,
            goalAlignment = input.goalAlignment,
            energyLevel = input.energyLevel,
            controlLevel = input.controlLevel,
            durationMinutes = input.durationMinutes,
            recurrenceEnabled = input.recurrenceEnabled,
            recurrenceRule = input.recurrenceRule,
            notificationMode = input.notificationMode,
            customNotificationMinutes = input.customNotificationMinutes,
            explicitUrgency = input.explicitUrgency,
            focusRequired = input.focusRequired,
            blockedReason = input.blockedReason,
            externalDependency = input.externalDependency,
        )

    /**
     * Releases the coroutine scope this ViewModel created.
     *
     * `onCleared` used to do this: it was the androidx lifecycle hook that ran when the framework dropped the
     * ViewModel, and this class no longer has one. The platform calls this at the same point - Android through
     * the release action it registers when it retains the ViewModel, the desktop from a `DisposableEffect`.
     */
    fun dispose() {
        scope.cancel()
    }
}

/**
 * The one-line entry point a platform's destination body calls to get the EditTaskViewModel.
 *
 * Every collaborator comes from [LocalDataLayer], which the platform installed at startup with its own
 * implementation, so a call site on either platform contains no wiring and cannot supply a different data
 * source by accident.
 */
fun editTaskViewModel(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)): EditTaskViewModel =
    EditTaskViewModel(
        taskRepository = LocalDataLayer.current.tasks,
        tagRepository = LocalDataLayer.current.tags,
        notificationScheduler = LocalDataLayer.current.reminderScheduler,
        scoreRollupTrigger = LocalDataLayer.current.scoreRollupTrigger,
        scoreCalculator = LocalDataLayer.current.scoreCalculator,
        scope = scope,
    )
