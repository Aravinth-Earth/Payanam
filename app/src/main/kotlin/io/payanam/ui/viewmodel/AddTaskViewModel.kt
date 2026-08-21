//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.TaskInput
import io.payanam.domain.repository.TagRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.scoring.ElegantTaskScoring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
/**
 * Returns the task view model.
 */
class AddTaskViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val tagRepository: TagRepository,
    private val notificationScheduler: NotificationScheduler,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val _tagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val tagSuggestions: StateFlow<List<String>> = _tagSuggestions.asStateFlow()

    init {
        logger.d("AddTaskViewModel.init", "ViewModel initialized")
        observeTagSuggestions()
    }

    private fun observeTagSuggestions() {
        viewModelScope.launch {
            tagRepository.observeAllTags()
                .map { tags -> tags.map { it.name } }
                .collect { names ->
                    _tagSuggestions.value = names
                    logger.d(
                        "AddTaskViewModel.observeTagSuggestions",
                        "Tag suggestion stream updated",
                        mapOf("count" to names.size),
                    )
                }
        }
    }
    /**
     * Creates the create task.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun createTask(
        title: String,
        description: String? = null,
        dimensionId: String,
        dimensionLabel: String,
        dueDate: LocalDateTime? = null,
        impactLevel: String = "Moderate Impact",
        goalAlignment: String = "Moderate Alignment",
        energyLevel: String = "Moderate",
        controlLevel: String = "Office/Colleagues Dependent",
        durationMinutes: Int = 60,
        recurrenceEnabled: Boolean = false,
        recurrenceRule: String? = null,
        notificationMode: String? = null,
        customNotificationMinutes: Int? = null,
        // POC Priority Fields
        explicitUrgency: Double? = null,
        focusRequired: Double? = null,
        blockedReason: String? = null,
        externalDependency: String? = null,
        tags: List<String> = emptyList(),
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        // For recurring tasks, set a default due date if none provided
        val effectiveDueDate = if (recurrenceEnabled && dueDate == null) {
            // Set default due date to today at the specified time, or 9 AM if no time
            val defaultTime = LocalTime.of(9, 0) // Default 9 AM
            LocalDateTime.of(LocalDate.now(), defaultTime)
        } else {
            dueDate
        }

        logger.i(
            "AddTaskViewModel.createTask",
            "Creating new task",
            mapOf(
                "title" to title,
                "dimensionId" to dimensionId,
                "hasDueDate" to (dueDate != null),
                "dueDate" to (dueDate?.toString() ?: "none"),
                "effectiveDueDate" to (effectiveDueDate?.toString() ?: "none"),
                "recurring" to recurrenceEnabled,
                "recurrenceRule" to (recurrenceRule ?: "none"),
            ),
        )

        viewModelScope.launch {
            try {
                val input = TaskInput(
                    title = title,
                    description = description,
                    dueDate = effectiveDueDate,
                    dimensionId = dimensionId,
                    lifeIntentionCategory = dimensionLabel,
                    impactLevel = impactLevel,
                    goalAlignment = goalAlignment,
                    energyLevel = energyLevel,
                    controlLevel = controlLevel,
                    durationMinutes = durationMinutes,
                    recurrenceEnabled = recurrenceEnabled,
                    recurrenceRule = recurrenceRule,
                    status = "pending",
                    notificationMode = notificationMode,
                    customNotificationMinutes = customNotificationMinutes,
                    explicitUrgency = explicitUrgency,
                    focusRequired = focusRequired,
                    blockedReason = blockedReason,
                    externalDependency = externalDependency,
                )
                val task = taskRepository.createTask(input)
                if (tags.isNotEmpty()) {
                    tagRepository.replaceTaskTags(task.id, tags)
                    logger.i(
                        "AddTaskViewModel.createTask",
                        "Task tags replaced",
                        mapOf("taskId" to task.id, "tagCount" to tags.size),
                    )
                }

                // Calculate and update score
                if (FeatureFlags.scoringEnabled) {
                    val score = ElegantTaskScoring.calculateScore(task)
                    taskRepository.updateTaskScore(task.id, score)
                    logger.d(
                        "AddTaskViewModel.createTask",
                        "Task score updated after create",
                        mapOf("taskId" to task.id, "score" to score),
                    )
                }

                // Schedule reminder if applicable
                if (FeatureFlags.remindersEnabled) {
                    try {
                        notificationScheduler.scheduleForTask(task)
                    } catch (e: Exception) {
                        logger.e(
                            "AddTaskViewModel.createTask",
                            "Failed to schedule reminder",
                            e,
                            mapOf(
                                "taskId" to task.id,
                            ),
                        )
                    }
                }

                logger.i(
                    "AddTaskViewModel.createTask",
                    "Task created successfully",
                    mapOf(
                        "id" to task.id,
                        "title" to title,
                        "tagCount" to tags.size,
                        "remindersEnabled" to FeatureFlags.remindersEnabled,
                    ),
                )
                Timber.d("Task created: $title")
                launch(Dispatchers.Main.immediate) { onResult(Result.success(Unit)) }
            } catch (e: Exception) {
                logger.e("AddTaskViewModel.createTask", "Failed to create task", e)
                Timber.e(e, "Error creating task")
                launch(Dispatchers.Main.immediate) { onResult(Result.failure(e)) }
            }
        }
    }
}
