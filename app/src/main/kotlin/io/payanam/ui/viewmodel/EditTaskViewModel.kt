//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.backfill.ScoreRollupCascadeService
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.scoring.ElegantTaskScoring
import io.payanam.ui.screens.EditTaskInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class EditTaskUiState(
    val task: Task? = null,
    val taskTags: List<String> = emptyList(),
    val tagSuggestions: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class EditTaskViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val tagRepository: TagRepository,
    private val notificationScheduler: NotificationScheduler,
    private val scoreRollupCascadeService: ScoreRollupCascadeService,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(EditTaskUiState())
    val uiState: StateFlow<EditTaskUiState> = _uiState.asStateFlow()

    private var currentTaskId: String? = null

    init {
        observeTagSuggestions()
    }

    private fun observeTagSuggestions() {
        viewModelScope.launch {
            tagRepository.observeAllTags()
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

    fun loadTask(taskId: String) {
        currentTaskId = taskId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val task = taskRepository.getTaskById(taskId)
                val taskTags = tagRepository.observeTagsForTask(taskId)
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
                Timber.e(e, "Error loading task")
                _uiState.update {
                    it.copy(isLoading = false, error = e.message)
                }
            }
        }
    }

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
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val taskInput = TaskInput(
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
                    scoreRollupCascadeService.recalcForRuleChange(taskId)
                    logger.d(
                        "EditTaskViewModel.updateTask",
                        "Score roll-up rebuilt after task update",
                        mapOf("taskId" to taskId),
                    )
                } else if (FeatureFlags.scoringEnabled) {
                    val score = ElegantTaskScoring.calculateScore(updatedTask)
                    taskRepository.updateTaskScore(taskId, score)
                    logger.d(
                        "EditTaskViewModel.updateTask",
                        "Task score recalculated after update",
                        mapOf("taskId" to taskId, "score" to score),
                    )
                }

                // Reschedule reminder based on latest task settings
                if (FeatureFlags.remindersEnabled) {
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

                Timber.d("Task updated: ${input.title}")
                logger.i(
                    "EditTaskViewModel.updateTask",
                    "Task updated",
                    mapOf(
                        "taskId" to taskId,
                        "tagCount" to input.tags.size,
                        "remindersEnabled" to FeatureFlags.remindersEnabled,
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
                Timber.e(e, "Error updating task")
                _uiState.update {
                    it.copy(isSaving = false, error = e.message)
                }
            }
        }
    }
}
